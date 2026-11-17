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
        let active = Arc::new(std::sync::atomic::AtomicUsize::new(0));
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
            if active.fetch_add(1, Ordering::Relaxed) >= 8 {
                active.fetch_sub(1, Ordering::Relaxed);
                continue;
            }
            let active = Arc::clone(&active);
            let shared = Arc::clone(&shared);
            let encoder_name = encoder_name.clone();
            thread::spawn(move || {
                let result = (|| -> Result<()> {
                    let position_link = crate::platform::x11::X11Link::open()?;
                    let mut root_mode = false;
                    let mut popup_captures = std::collections::HashMap::new();
                    let mut popup_background = None;
                    let mut popups = Vec::new();
                    let mut popups_at = Instant::now() - Duration::from_secs(1);
                    let mut mask_at = Instant::now() - Duration::from_secs(1);
                    let mut mask = Vec::<[i32; 4]>::new();
                    let mut capture = None;
                    let mut encoder = None;
                    let mut native = (0, 0);
                    let mut current_id = 0;
                    let mut client_size = (0, 0);
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
                        let system_windows =
                            crate::platform::x11::windows::observed_system_catalogue();
                        let _window = windows
                            .iter()
                            .chain(system_windows.iter())
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
                        if !root_capture
                            && (current_id != id
                                || popups_at.elapsed() >= Duration::from_millis(80))
                        {
                            popups =
                                crate::platform::x11::windows::window_popups(&position_link, id)
                                    .unwrap_or_default();
                            popups_at = Instant::now();
                        }
                        if root_capture {
                            popups.clear();
                        }
                        let (source_x, source_y, source_width, source_height) =
                            crate::platform::x11::windows::capture_bounds(
                                (source_x, source_y, source_width, source_height),
                                &popups,
                            );
                        let target = request["fps"]
                            .as_u64()
                            .unwrap_or(30)
                            .clamp(5, fps.clamp(5, 60) as u64)
                            as u32;
                        if let Some(wait) =
                            Duration::from_secs_f64(1. / target as f64).checked_sub(last.elapsed())
                        {
                            thread::sleep(wait)
                        }
                        last = Instant::now();
                        let scale = 1f64
                            .min(2048. / source_width as f64)
                            .min(2048. / source_height as f64)
                            .min(
                                (2_097_152. / (source_width as f64 * source_height as f64)).sqrt(),
                            );
                        let w = ((source_width as f64 * scale) as u32 / 2 * 2).max(32) as u16;
                        let h = ((source_height as f64 * scale) as u32 / 2 * 2).max(32) as u16;
                        if capture.is_none()
                            || native != (source_width, source_height)
                            || root_mode != root_capture
                            || current_id != id
                            || client_size != (geometry.width, geometry.height)
                        {
                            encoder.take();
                            capture.take();
                            popup_captures.clear();
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
                            client_size = (geometry.width, geometry.height);
                        }
                        let pixel_rect = |x: i32, y: i32, width: u32, height: u32| {
                            let x0 = (((x - source_x) as f64 * w as f64 / source_width as f64)
                                .round() as u16)
                                .min(w - 1);
                            let y0 = (((y - source_y) as f64 * h as f64 / source_height as f64)
                                .round() as u16)
                                .min(h - 1);
                            let x1 = ((((x - source_x) as f64 + width as f64) * w as f64
                                / source_width as f64)
                                .round() as u16)
                                .min(w);
                            let y1 = ((((y - source_y) as f64 + height as f64) * h as f64
                                / source_height as f64)
                                .round() as u16)
                                .min(h);
                            (x0, y0, (x1 - x0).max(1), (y1 - y0).max(1))
                        };
                        let base = capture.as_ref().unwrap();
                        let mut image = if root_capture {
                            base.atlas_padded(
                                &[(
                                    MonitorRect {
                                        index: 0,
                                        x: source_x,
                                        y: source_y,
                                        width: source_width,
                                        height: source_height,
                                    },
                                    0,
                                    0,
                                    w,
                                    h,
                                )],
                                0,
                            )?
                        } else {
                            popup_captures.retain(|key: &(u32, u32, u32), _| {
                                popups.iter().any(|p| (p.id, p.width, p.height) == *key)
                            });
                            for popup in &popups {
                                let key = (popup.id, popup.width, popup.height);
                                if let std::collections::hash_map::Entry::Vacant(slot) =
                                    popup_captures.entry(key)
                                {
                                    if let Ok(c) = X11Capture::window(popup.id, 32, 32) {
                                        slot.insert(c);
                                    }
                                }
                            }
                            let (x, y, pw, ph) = pixel_rect(
                                position.dst_x.into(),
                                position.dst_y.into(),
                                geometry.width.into(),
                                geometry.height.into(),
                            );
                            // Fill only the extra popup canvas with the current desktop,
                            // then overlay unobscured owner/menu pixmaps. This prevents
                            // black margins from erasing the shared in-place desktop patch.
                            if !popups.is_empty() && popup_background.is_none() {
                                popup_background = Some(X11Capture::workspace(
                                    MonitorRect {
                                        index: 0,
                                        x: 0,
                                        y: 0,
                                        width: 32,
                                        height: 32,
                                    },
                                    32,
                                    32,
                                )?);
                            }
                            let mut layers = Vec::new();
                            if !popups.is_empty() {
                                if let Some(background) = popup_background.as_ref() {
                                    layers.push((
                                        background,
                                        MonitorRect {
                                            index: 0,
                                            x: source_x,
                                            y: source_y,
                                            width: source_width,
                                            height: source_height,
                                        },
                                        0,
                                        0,
                                        w,
                                        h,
                                    ));
                                }
                            }
                            layers.push((
                                base,
                                MonitorRect {
                                    index: 0,
                                    x: 0,
                                    y: 0,
                                    width: geometry.width.into(),
                                    height: geometry.height.into(),
                                },
                                x,
                                y,
                                pw,
                                ph,
                            ));
                            for popup in &popups {
                                if let Some(c) =
                                    popup_captures.get(&(popup.id, popup.width, popup.height))
                                {
                                    let (x, y, pw, ph) =
                                        pixel_rect(popup.x, popup.y, popup.width, popup.height);
                                    layers.push((
                                        c,
                                        MonitorRect {
                                            index: 0,
                                            x: 0,
                                            y: 0,
                                            width: popup.width,
                                            height: popup.height,
                                        },
                                        x,
                                        y,
                                        pw,
                                        ph,
                                    ));
                                }
                            }
                            base.atlas_layers(&layers, 0)?
                        };
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
                        if mask_at.elapsed() >= Duration::from_millis(100) {
                            mask = crate::platform::x11::windows::occluders(&position_link, id)
                                .unwrap_or_default();
                            mask_at = Instant::now();
                        }
                        frame += 1;
                        let header=serde_json::json!({"type":"window-h264-v1","windowId":id,"frame":frame,"width":w,"height":h,"occluders":mask,"sourceX":source_x,"sourceY":source_y,"sourceWidth":source_width,"sourceHeight":source_height,"bytes":encoded.bytes.len()}).to_string();
                        socket.write_all(&(header.len() as u32).to_be_bytes())?;
                        socket.write_all(header.as_bytes())?;
                        socket.write_all(&encoded.bytes)?;
                    }
                    Ok(())
                })();
                if let Err(error) = result {
                    tracing::debug!(%error,"Window magnifier stream closed")
                }
                active.fetch_sub(1, Ordering::Relaxed);
            });
        }
    }))
}
