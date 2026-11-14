//! Experimental view-only H.264 atlas: dense center above lower-density visible periphery.
use super::*;
use std::io::{Read, Write};
use std::net::TcpListener;
const WIDTH: u16 = 2048;
const HIGH: u16 = 1024;
const LOW: u16 = 512;
const MAX_FRAME: usize = 8 * 1024 * 1024;

pub(super) fn start(
    shared: Arc<Shared>,
    address: SocketAddr,
    encoder_name: String,
    fps: u32,
    bitrate: u32,
) -> Option<thread::JoinHandle<()>> {
    let listener = match TcpListener::bind(address) {
        Ok(v) => v,
        Err(error) => {
            tracing::warn!(%error,"View streaming unavailable");
            return None;
        }
    };
    if listener.set_nonblocking(true).is_err() {
        return None;
    }
    Some(thread::spawn(move || {
        while !shared.stop.load(Ordering::Relaxed) {
            let (mut socket, peer) = match listener.accept() {
                Ok(v) => v,
                Err(_) => {
                    thread::sleep(Duration::from_millis(20));
                    continue;
                }
            };
            if !shared
                .client
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
                .is_some_and(|c| c.control.ip() == peer.ip())
            {
                continue;
            }
            let _ = socket.set_read_timeout(Some(Duration::from_millis(600)));
            let _ = socket.set_write_timeout(Some(Duration::from_millis(600)));
            let _ = socket.set_nodelay(true);
            let result = (|| -> Result<()> {
                let mut high: Option<X11Capture> = None;
                let mut low: Option<X11Capture> = None;
                let mut encoder: Option<GstEncoder> = None;
                let mut sequence = 0u64;
                let mut last = Instant::now() - Duration::from_secs(1);
                let interval = Duration::from_secs_f64(1.0 / fps.clamp(1, 60) as f64);
                while !shared.stop.load(Ordering::Relaxed) {
                    let mut size = [0u8; 4];
                    socket.read_exact(&mut size)?;
                    let size = u32::from_be_bytes(size) as usize;
                    if size > 1024 {
                        return Err(Error::Msg("View request too large".into()));
                    }
                    let mut bytes = vec![0; size];
                    socket.read_exact(&mut bytes)?;
                    let request: serde_json::Value =
                        serde_json::from_slice(&bytes).map_err(|e| Error::Msg(e.to_string()))?;
                    let allowed = shared
                        .client
                        .read()
                        .unwrap_or_else(|e| e.into_inner())
                        .as_ref()
                        .is_some_and(|c| c.control.ip() == peer.ip());
                    if !allowed
                        || request["sessionId"].as_u64() != Some(shared.session_id)
                        || request["mode"] != "view-h264-v1"
                    {
                        return Err(Error::Msg("Expired or unsupported view session".into()));
                    }
                    if let Some(wait) = interval.checked_sub(last.elapsed()) {
                        thread::sleep(wait)
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
                    let (outer, outer_uv) = region(desktop, &request)
                        .ok_or_else(|| Error::Msg("Invalid view crop".into()))?;
                    let center = request["center"]
                        .as_array()
                        .filter(|a| a.len() == 2)
                        .and_then(|a| Some((a[0].as_f64()?, a[1].as_f64()?)));
                    let (cx, cy) = center
                        .filter(|(x, y)| x.is_finite() && y.is_finite())
                        .unwrap_or((
                            (outer_uv[0] + outer_uv[2]) as f64 / 2.0,
                            (outer_uv[1] + outer_uv[3]) as f64 / 2.0,
                        ));
                    // Preserve an off-desktop gaze target, but clip capture safely to the visible crop.
                    let cx = cx.clamp(outer_uv[0] as f64, outer_uv[2] as f64);
                    let cy = cy.clamp(outer_uv[1] as f64, outer_uv[3] as f64);
                    let fraction = request["fraction"]
                        .as_f64()
                        .filter(|v| v.is_finite())
                        .unwrap_or(0.35)
                        .clamp(0.2, 1.0);
                    let capture_start = Instant::now();
                    let iw = ((outer.width as f64 * fraction).round() as u32).clamp(1, outer.width);
                    let ih =
                        ((outer.height as f64 * fraction).round() as u32).clamp(1, outer.height);
                    let ix = ((desktop.x as f64 + cx * desktop.width as f64).round() as i32
                        - iw as i32 / 2)
                        .clamp(outer.x, outer.x + (outer.width - iw) as i32);
                    let iy = ((desktop.y as f64 + cy * desktop.height as f64).round() as i32
                        - ih as i32 / 2)
                        .clamp(outer.y, outer.y + (outer.height - ih) as i32);
                    let inner = MonitorRect {
                        index: 0,
                        x: ix,
                        y: iy,
                        width: iw,
                        height: ih,
                    };
                    let inner_uv = [
                        (ix - desktop.x) as f32 / desktop.width as f32,
                        (iy - desktop.y) as f32 / desktop.height as f32,
                        (ix - desktop.x + iw as i32) as f32 / desktop.width as f32,
                        (iy - desktop.y + ih as i32) as f32 / desktop.height as f32,
                    ];
                    if high.is_none() {
                        high = Some(X11Capture::open_detail(inner, WIDTH, HIGH)?);
                        low = Some(X11Capture::open_detail(outer, WIDTH, LOW)?)
                    }
                    let mut atlas = crate::core::CapturedFrame {
                        width: WIDTH.into(),
                        height: (HIGH + LOW).into(),
                        stride: u32::from(WIDTH) * 4,
                        bgra: vec![0; WIDTH as usize * (HIGH + LOW) as usize * 4],
                        monotonic_ns: monotonic_ns(),
                        damage_generation: sequence,
                    };
                    for (capture, rect, height, offset) in [
                        (high.as_mut().unwrap(), inner, HIGH, 0usize),
                        (low.as_mut().unwrap(), outer, LOW, HIGH as usize),
                    ] {
                        capture.retarget(rect)?;
                        let mut frame = capture
                            .poll(Duration::ZERO, true)?
                            .ok_or_else(|| Error::Msg("View capture unavailable".into()))?;
                        crate::media::desktop_mask::DesktopMask::new(
                            rect,
                            WIDTH.into(),
                            height.into(),
                            &screens,
                        )
                        .apply(&mut frame);
                        if let Ok(cursor) = shared.cursor.lock() {
                            paint_cursor_scaled(&mut frame, rect, &cursor)
                        }
                        for row in 0..height as usize {
                            let from = row * frame.stride as usize;
                            let to = (offset + row) * WIDTH as usize * 4;
                            atlas.bgra[to..to + WIDTH as usize * 4]
                                .copy_from_slice(&frame.bgra[from..from + WIDTH as usize * 4]);
                        }
                    }
                    let capture_ns = capture_start.elapsed().as_nanos() as u64;
                    if encoder.is_none() {
                        let quality = request["quality"]
                            .as_u64()
                            .unwrap_or(shared.encoder_quality.load(Ordering::Relaxed) as u64)
                            .min(2) as u32;
                        let rate = request["bitrateMbps"]
                            .as_f64()
                            .filter(|v| v.is_finite() && *v > 0.0)
                            .map(|v| (v.clamp(1.0, 200.0) * 1_000_000.0) as u32)
                            .unwrap_or(bitrate);
                        encoder = Some(GstEncoder::open_quality(
                            &encoder_name,
                            crate::core::EncoderConfig {
                                width: WIDTH.into(),
                                height: (HIGH + LOW).into(),
                                fps: fps.clamp(1, 60),
                                bitrate_bps: rate.max(400_000),
                            },
                            quality,
                        )?)
                    }
                    let encoder = encoder.as_mut().unwrap();
                    let encode_start = Instant::now();
                    encoder.submit(&atlas)?;
                    // Exactly one in-flight submission: no positional metadata can overtake its frame.
                    let frame = encoder
                        .pull_wait(250_000_000)?
                        .ok_or_else(|| Error::Msg("View encoder timeout".into()))?;
                    if frame.bytes.len() > MAX_FRAME {
                        return Err(Error::Msg("View frame exceeds bound".into()));
                    }
                    shared
                        .view_capture_ns
                        .fetch_add(capture_ns, Ordering::Relaxed);
                    shared
                        .view_encode_ns
                        .fetch_add(encode_start.elapsed().as_nanos() as u64, Ordering::Relaxed);
                    shared.view_samples.fetch_add(1, Ordering::Relaxed);
                    sequence += 1;
                    shared
                        .view_until_ns
                        .store(monotonic_ns() + 500_000_000, Ordering::Relaxed);
                    let header=serde_json::json!({"type":"view-h264-v1","frame":sequence,"rect":outer_uv,"inner":inner_uv,"width":WIDTH,"height":HIGH+LOW,"bytes":frame.bytes.len(),"desktop":[desktop.x,desktop.y,desktop.width,desktop.height]}).to_string();
                    socket.write_all(&(header.len() as u32).to_be_bytes())?;
                    socket.write_all(header.as_bytes())?;
                    socket.write_all(&frame.bytes)?;
                    shared.encoded_frames.fetch_add(1, Ordering::Relaxed);
                    shared
                        .bytes
                        .fetch_add(frame.bytes.len() as u64, Ordering::Relaxed);
                }
                Ok(())
            })();
            shared.view_until_ns.store(0, Ordering::Relaxed);
            shared.keys[0].store(true, Ordering::Relaxed);
            if let Err(error) = result {
                tracing::debug!(%error,"View streaming ended")
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
