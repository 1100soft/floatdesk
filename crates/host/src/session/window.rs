//! A bounded, authenticated focused-window stream. No window is raised or resized.
use super::*;
use std::io::{Read, Write};
use std::net::TcpListener;
use x11rb::connection::Connection as _;
use x11rb::protocol::xproto::{ChangeWindowAttributesAux, ConnectionExt as _, EventMask};
use x11rb::protocol::Event;
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
                    let mut last_sent = None;
                    let mut decoder_priming = 0u8;
                    let mut pending_tail = false;
                    let mut watched_preview = 0;
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
                        if request["paused"].as_bool() == Some(true) {
                            last_sent = None;
                            decoder_priming = 3;
                            let header=serde_json::json!({"type":"window-paused-v1","windowId":id,"bytes":0}).to_string();
                            socket.write_all(&(header.len() as u32).to_be_bytes())?;
                            socket.write_all(header.as_bytes())?;
                            continue;
                        }
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
                        let owner_x = if _window.panel {
                            _window.x
                        } else {
                            position.dst_x.into()
                        };
                        let owner_y = if _window.panel {
                            _window.y
                        } else {
                            position.dst_y.into()
                        };
                        let owner_width = if _window.panel {
                            _window.width
                        } else {
                            geometry.width.into()
                        };
                        let owner_height = if _window.panel {
                            _window.height
                        } else {
                            geometry.height.into()
                        };
                        let (source_x, source_y, source_width, source_height) =
                            (owner_x, owner_y, owner_width, owner_height);
                        let shell_preview = system_windows
                            .iter()
                            .any(|window| window.id == id && !window.panel);
                        let mut preview_remapped = false;
                        if shell_preview {
                            if watched_preview != id {
                                position_link
                                    .conn
                                    .change_window_attributes(
                                        id,
                                        &ChangeWindowAttributesAux::new()
                                            .event_mask(EventMask::STRUCTURE_NOTIFY),
                                    )
                                    .map_err(Error::x11)?
                                    .check()
                                    .map_err(Error::x11)?;
                                watched_preview = id;
                            }
                            while let Some(event) =
                                position_link.conn.poll_for_event().map_err(Error::x11)?
                            {
                                preview_remapped |= match event {
                                    Event::MapNotify(event) => event.window == id,
                                    Event::UnmapNotify(event) => event.window == id,
                                    _ => false,
                                };
                            }
                            if position_link
                                .conn
                                .get_window_attributes(id)
                                .map_err(Error::x11)?
                                .reply()
                                .map_err(Error::x11)?
                                .map_state
                                != x11rb::protocol::xproto::MapState::VIEWABLE
                            {
                                return Err(Error::Msg("Shell preview closed".into()));
                            }
                        }
                        let root_capture = request["rootCapture"].as_bool().unwrap_or(false);
                        if !root_capture
                            && !shell_preview
                            && (current_id != id
                                || popups_at.elapsed() >= Duration::from_millis(80))
                        {
                            popups =
                                crate::platform::x11::windows::window_popups(&position_link, id)
                                    .unwrap_or_default();
                            popups_at = Instant::now();
                        }
                        if root_capture || shell_preview {
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
                        let dimension = request["maxDimension"]
                            .as_u64()
                            .unwrap_or(2048)
                            .clamp(128, 2048) as f64;
                        let pixels = request["maxPixels"]
                            .as_u64()
                            .unwrap_or(2_097_152)
                            .clamp(16_384, 2_097_152) as f64;
                        let scale = 1f64
                            .min(dimension / source_width as f64)
                            .min(dimension / source_height as f64)
                            .min((pixels / (source_width as f64 * source_height as f64)).sqrt());
                        let w = ((source_width as f64 * scale) as u32 / 2 * 2).max(128) as u16;
                        let h = ((source_height as f64 * scale) as u32 / 2 * 2).max(128) as u16;
                        if capture.is_none()
                            || preview_remapped
                            || native != (source_width, source_height)
                            || root_mode != root_capture
                            || current_id != id
                            || client_size != (geometry.width, geometry.height)
                        {
                            last_sent = None;
                            decoder_priming = 3;
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
                            let (x, y, pw, ph) =
                                pixel_rect(owner_x, owner_y, owner_width, owner_height);
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
                                    x: owner_x - i32::from(position.dst_x),
                                    y: owner_y - i32::from(position.dst_y),
                                    width: owner_width,
                                    height: owner_height,
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
                            let contains = |x: i32, y: i32, width: u32, height: u32| {
                                cursor.root_x >= x
                                    && cursor.root_y >= y
                                    && i64::from(cursor.root_x - x) < i64::from(width)
                                    && i64::from(cursor.root_y - y) < i64::from(height)
                            };
                            let region = if root_capture {
                                Some((source_x, source_y, source_width, source_height))
                            } else {
                                popups
                                    .iter()
                                    .rev()
                                    .find(|p| contains(p.x, p.y, p.width, p.height))
                                    .map(|p| (p.x, p.y, p.width, p.height))
                                    .or_else(|| {
                                        contains(owner_x, owner_y, owner_width, owner_height)
                                            .then_some((
                                                owner_x,
                                                owner_y,
                                                owner_width,
                                                owner_height,
                                            ))
                                    })
                            };
                            if let Some((x, y, width, height)) = region {
                                let (px, py, pw, ph) = pixel_rect(x, y, width, height);
                                paint_cursor_scaled_at(
                                    &mut image,
                                    MonitorRect {
                                        index: 0,
                                        x,
                                        y,
                                        width,
                                        height,
                                    },
                                    &cursor,
                                    (px.into(), py.into(), pw.into(), ph.into()),
                                );
                            }
                        }
                        if mask_at.elapsed() >= Duration::from_millis(100) {
                            mask = crate::platform::x11::windows::occluders(&position_link, id)
                                .unwrap_or_default();
                            mask_at = Instant::now();
                        }
                        let mut header = serde_json::json!({"type":"window-h264-v1","windowId":id,"width":w,"height":h,"occluders":mask,"ownerX":owner_x,"ownerY":owner_y,"ownerWidth":owner_width,"ownerHeight":owner_height,"popups":popups,"sourceX":source_x,"sourceY":source_y,"sourceWidth":source_width,"sourceHeight":source_height});
                        // Compare the exact capture and mapping. A static surface can keep its
                        // last buffer, including at full resolution and with all overlays.
                        let identical = last_sent.as_ref().is_some_and(
                            |(pixels, metadata): &(Vec<u8>, serde_json::Value)| {
                                pixels == &image.bgra && metadata == &header
                            },
                        );
                        if decoder_priming == 0
                            && request["skipIdentical"].as_bool() == Some(true)
                            && identical
                            && !pending_tail
                        {
                            let response=serde_json::json!({"type":"window-unchanged-v1","windowId":id,"bytes":0}).to_string();
                            socket.write_all(&(response.len() as u32).to_be_bytes())?;
                            socket.write_all(response.as_bytes())?;
                            continue;
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
                        if request["skipIdentical"].as_bool() == Some(true) {
                            // A single changed frame can remain queued in the receiver's decoder.
                            // Follow it once even if the image has already gone static.
                            pending_tail = !identical;
                            last_sent = Some((image.bgra, header.clone()));
                        } else {
                            last_sent = None;
                        }
                        decoder_priming = decoder_priming.saturating_sub(1);
                        frame += 1;
                        header["frame"] = frame.into();
                        header["bytes"] = encoded.bytes.len().into();
                        let header = header.to_string();
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
