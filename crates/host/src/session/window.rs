//! A bounded, authenticated focused-window stream. No window is raised or resized.
use super::*;
use std::io::{Read, Write};
use std::net::TcpListener;
use x11rb::protocol::xproto::ConnectionExt as _;
pub(super) fn start(
    shared: Arc<Shared>,
    address: SocketAddr,
    encoder_name: String,
    fps: u32,
    bitrate: u32,
) -> Option<thread::JoinHandle<()>> {
    let listener = match TcpListener::bind(address) {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!(%e,"Window magnifier unavailable");
            return None;
        }
    };
    listener.set_nonblocking(true).ok()?;
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
            let _ = socket.set_read_timeout(Some(Duration::from_millis(700)));
            let _ = socket.set_write_timeout(Some(Duration::from_millis(700)));
            let _ = socket.set_nodelay(true);
            let result = (|| -> Result<()> {
                let position_link = crate::platform::x11::X11Link::open()?;
                let mut root_mode = false;
                let mut capture = None;
                let mut encoder = None;
                let mut native = (0, 0);
                let mut current_id = 0;
                let mut frame = 0u64;
                let mut last = Instant::now() - Duration::from_secs(1);
                while !shared.stop.load(Ordering::Relaxed) {
                    let mut size = [0u8; 4];
                    socket.read_exact(&mut size)?;
                    let n = u32::from_be_bytes(size) as usize;
                    if n > 1024 {
                        return Err(Error::Msg("Window request too large".into()));
                    }
                    let mut data = vec![0; n];
                    socket.read_exact(&mut data)?;
                    let request: serde_json::Value =
                        serde_json::from_slice(&data).map_err(|e| Error::Msg(e.to_string()))?;
                    if request["sessionId"].as_u64() != Some(shared.session_id)
                        || !shared
                            .client
                            .read()
                            .unwrap_or_else(|e| e.into_inner())
                            .as_ref()
                            .is_some_and(|c| c.control.ip() == peer.ip())
                    {
                        return Err(Error::Msg("Window stream session expired".into()));
                    }
                    let id = request["windowId"]
                        .as_u64()
                        .and_then(|v| u32::try_from(v).ok())
                        .ok_or_else(|| Error::Msg("Missing window".into()))?;
                    let (_, windows) = crate::platform::x11::windows::observed_catalogue();
                    let _window = windows
                        .iter()
                        .find(|w| w.id == id)
                        .ok_or_else(|| Error::Msg("Window is no longer visible".into()))?;
                    let position = position_link
                        .conn
                        .translate_coordinates(id, position_link.root, 0, 0)
                        .map_err(Error::x11)?
                        .reply()
                        .map_err(Error::x11)?;
                    let geometry = position_link
                        .conn
                        .get_geometry(id)
                        .map_err(Error::x11)?
                        .reply()
                        .map_err(Error::x11)?;
                    let (source_x, source_y) =
                        (i32::from(position.dst_x), i32::from(position.dst_y));
                    let (source_width, source_height) =
                        (u32::from(geometry.width), u32::from(geometry.height));
                    let root_capture = request["rootCapture"].as_bool().unwrap_or(false);
                    let target = request["fps"]
                        .as_u64()
                        .unwrap_or(30)
                        .clamp(5, fps.clamp(5, 60) as u64) as u32;
                    if let Some(wait) =
                        Duration::from_secs_f64(1. / target as f64).checked_sub(last.elapsed())
                    {
                        thread::sleep(wait)
                    }
                    last = Instant::now();
                    let scale = 1f64
                        .min(2048. / source_width as f64)
                        .min(2048. / source_height as f64)
                        .min((2_097_152. / (source_width as f64 * source_height as f64)).sqrt());
                    let w = ((source_width as f64 * scale) as u32 / 2 * 2).max(32) as u16;
                    let h = ((source_height as f64 * scale) as u32 / 2 * 2).max(32) as u16;
                    if capture.is_none()
                        || native != (source_width, source_height)
                        || root_mode != root_capture
                        || current_id != id
                    {
                        encoder.take();
                        capture.take();
                        capture = Some(if root_capture {
                            X11Capture::workspace(
                                MonitorRect {
                                    index: 0,
                                    x: source_x,
                                    y: source_y,
                                    width: source_width,
                                    height: source_height,
                                },
                                w,
                                h,
                            )?
                        } else {
                            X11Capture::window(id, w, h)?
                        });
                        root_mode = root_capture;
                        let config = crate::core::EncoderConfig {
                            width: w.into(),
                            height: h.into(),
                            fps: target,
                            bitrate_bps: bitrate.clamp(4_000_000, 20_000_000),
                        };
                        encoder = Some(GstEncoder::open_quality(
                            if encoder_name.contains("265") {
                                "vah264enc"
                            } else {
                                &encoder_name
                            },
                            config,
                            shared.encoder_quality.load(Ordering::Relaxed),
                        )?);
                        native = (source_width, source_height);
                        current_id = id;
                    }
                    let source = MonitorRect {
                        index: 0,
                        x: if root_capture { source_x } else { 0 },
                        y: if root_capture { source_y } else { 0 },
                        width: source_width,
                        height: source_height,
                    };
                    let mut image = capture
                        .as_ref()
                        .unwrap()
                        .atlas_padded(&[(source, 0, 0, w, h)], 0)?;
                    if let Ok(cursor) = shared.cursor.lock() {
                        paint_cursor_scaled_at(
                            &mut image,
                            MonitorRect {
                                index: 0,
                                x: source_x,
                                y: source_y,
                                width: source_width,
                                height: source_height,
                            },
                            &cursor,
                            (0, 0, w.into(), h.into()),
                        )
                    }
                    encoder.as_mut().unwrap().submit(&image)?;
                    let encoded = encoder
                        .as_mut()
                        .unwrap()
                        .pull_wait(500_000_000)?
                        .ok_or_else(|| Error::Msg("Window encode timeout".into()))?;
                    if encoded.bytes.len() > 8 * 1024 * 1024 {
                        return Err(Error::Msg("Window frame too large".into()));
                    }
                    frame += 1;
                    let header=serde_json::json!({"type":"window-h264-v1","windowId":id,"frame":frame,"width":w,"height":h,"sourceX":source_x,"sourceY":source_y,"sourceWidth":source_width,"sourceHeight":source_height,"bytes":encoded.bytes.len()}).to_string();
                    socket.write_all(&(header.len() as u32).to_be_bytes())?;
                    socket.write_all(header.as_bytes())?;
                    socket.write_all(&encoded.bytes)?;
                }
                Ok(())
            })();
            if let Err(error) = result {
                tracing::debug!(%error,"Window magnifier stream closed")
            }
        }
    }))
}
