//! Opt-in, bounded viewed-region detail. Baseline H.264 is independent and remains the fallback.
use super::*;
use std::io::{Read, Write};
use std::net::TcpListener;

const WIDTH: u16 = 1536;
const HEIGHT: u16 = 1024;
const MAX_JPEG: usize = 4 * 1024 * 1024;

pub(super) fn start(shared: Arc<Shared>, address: SocketAddr) -> Option<thread::JoinHandle<()>> {
    let listener = match TcpListener::bind(address) {
        Ok(v) => v,
        Err(error) => {
            tracing::warn!(%error,"Experimental detail unavailable");
            return None;
        }
    };
    if listener.set_nonblocking(true).is_err() {
        return None;
    }
    Some(thread::spawn(move || {
        let mut capture: Option<X11Capture> = None;
        let mut encoder: Option<crate::media::gst::Pipeline> = None;
        let mut last = Instant::now() - Duration::from_secs(1);
        while !shared.stop.load(Ordering::Relaxed) {
            let (mut socket, peer) = match listener.accept() {
                Ok(v) => v,
                Err(_) => {
                    thread::sleep(Duration::from_millis(20));
                    continue;
                }
            };
            let allowed = shared
                .client
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
                .is_some_and(|c| c.control.ip() == peer.ip());
            if !allowed {
                continue;
            }
            let _ = socket.set_read_timeout(Some(Duration::from_millis(500)));
            let _ = socket.set_write_timeout(Some(Duration::from_millis(500)));
            let result = (|| -> Result<()> {
                let mut size = [0u8; 4];
                socket.read_exact(&mut size)?;
                let size = u32::from_be_bytes(size) as usize;
                if size > 512 {
                    return Err(Error::Msg("Detail request too large".into()));
                }
                let mut bytes = vec![0; size];
                socket.read_exact(&mut bytes)?;
                let request: serde_json::Value =
                    serde_json::from_slice(&bytes).map_err(|e| Error::Msg(e.to_string()))?;
                if request["sessionId"].as_u64() != Some(shared.session_id) {
                    return Err(Error::Msg("Expired detail session".into()));
                }
                if last.elapsed() < Duration::from_millis(100) {
                    return Err(Error::Msg("Detail rate limited".into()));
                }
                last = Instant::now();
                let screens = shared
                    .screens
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .clone();
                let sources = screens
                    .iter()
                    .map(|r| (String::new(), *r))
                    .collect::<Vec<_>>();
                let desktop = desktop_rect(&sources)?;
                let Some((region, uv)) = region(desktop, &request) else {
                    return Err(Error::Msg("Invalid detail region".into()));
                };
                if capture.is_none() {
                    capture = Some(X11Capture::open_detail(region, WIDTH, HEIGHT)?)
                }
                let capture = capture.as_mut().unwrap();
                capture.retarget(region)?;
                let mut frame = capture
                    .poll(Duration::ZERO, true)?
                    .ok_or_else(|| Error::Msg("Detail capture unavailable".into()))?;
                let (w, h) = capture.pixel_size();
                crate::media::desktop_mask::DesktopMask::new(region, w, h, &screens)
                    .apply(&mut frame);
                if encoder.is_none() {
                    let pipeline=crate::media::gst::Pipeline::launch("appsrc name=src is-live=true format=time do-timestamp=true block=false max-buffers=1 leaky-type=downstream ! videoconvert ! jpegenc name=enc quality=92 ! appsink name=sink sync=false max-buffers=1 drop=true")?;
                    pipeline.set_arg(
                        crate::media::gst::Element::Source,
                        "caps",
                        &format!("video/x-raw,format=BGRx,width={w},height={h},framerate=10/1"),
                    )?;
                    pipeline.play()?;
                    encoder = Some(pipeline);
                }
                let encoder = encoder.as_ref().unwrap();
                encoder.push(&frame.bgra)?;
                let jpeg = encoder
                    .pull(150_000_000)?
                    .ok_or_else(|| Error::Msg("Detail encode timeout".into()))?;
                if jpeg.len() > MAX_JPEG {
                    return Err(Error::Msg("Detail frame too large".into()));
                }
                let header=serde_json::json!({"rect":uv,"width":w,"height":h,"bytes":jpeg.len(),"desktop":[desktop.x,desktop.y,desktop.width,desktop.height]}).to_string();
                socket.write_all(&(header.len() as u32).to_be_bytes())?;
                socket.write_all(header.as_bytes())?;
                socket.write_all(&jpeg)?;
                Ok(())
            })();
            if let Err(error) = result {
                encoder = None;
                capture = None;
                tracing::debug!(%error,"Experimental detail request failed");
            }
        }
    }))
}

fn region(desktop: MonitorRect, request: &serde_json::Value) -> Option<(MonitorRect, [f32; 4])> {
    let a = request["rect"].as_array()?;
    if a.len() != 4 {
        return None;
    }
    let mut uv = [0f32; 4];
    for (i, v) in a.iter().enumerate() {
        let v = v.as_f64()?;
        if !v.is_finite() || !(0.0..=1.0).contains(&v) {
            return None;
        }
        uv[i] = v as f32;
    }
    if uv[2] <= uv[0] || uv[3] <= uv[1] {
        return None;
    }
    let x = (uv[0] * desktop.width as f32).floor() as u32;
    let y = (uv[1] * desktop.height as f32).floor() as u32;
    let right = ((uv[2] * desktop.width as f32).ceil() as u32).min(desktop.width);
    let bottom = ((uv[3] * desktop.height as f32).ceil() as u32).min(desktop.height);
    if right <= x || bottom <= y {
        return None;
    }
    Some((
        MonitorRect {
            index: 0,
            x: desktop.x + x as i32,
            y: desktop.y + y as i32,
            width: right - x,
            height: bottom - y,
        },
        [
            x as f32 / desktop.width as f32,
            y as f32 / desktop.height as f32,
            right as f32 / desktop.width as f32,
            bottom as f32 / desktop.height as f32,
        ],
    ))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn crops_are_bounded_and_match_exact_pixels() {
        let desktop = MonitorRect {
            index: 0,
            x: -100,
            y: 30,
            width: 1000,
            height: 600,
        };
        let (r, uv) = region(
            desktop,
            &serde_json::json!({"rect":[0.1001,0.2,0.4001,0.8]}),
        )
        .unwrap();
        assert_eq!((r.x, r.y, r.width, r.height), (0, 150, 301, 360));
        assert_eq!(uv, [0.1, 0.2, 0.401, 0.8]);
        for rect in [
            serde_json::json!([-0.1, 0, 1, 1]),
            serde_json::json!([0, 0, 0, 1]),
            serde_json::json!([0, 0, 2, 1]),
        ] {
            assert!(region(desktop, &serde_json::json!({"rect":rect})).is_none());
        }
    }
}
