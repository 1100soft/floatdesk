//! Cursor capture and compositing shared by streaming modes.
use super::*;

pub(super) fn cursor_loop(shared: Arc<Shared>, socket: UdpSocket) {
    let mut cursor = match X11Cursor::open() {
        Ok(cursor) => cursor,
        Err(err) => {
            tracing::error!("cursor: {err}");
            return;
        }
    };
    let mut router = cursor_map::Router::default();
    let mut last_root = None;
    let mut last_mirror = None;
    let mut client_generation = None;
    let mut owner_sent = Instant::now() - Duration::from_secs(1);
    while !shared.stop.load(Ordering::Relaxed) {
        match cursor.poll() {
            Ok(sample) => {
                let motion = cursor.take_motion();
                let generation = shared
                    .client
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .as_ref()
                    .map(|client| client.generation);
                if generation != client_generation {
                    router.reset();
                    last_mirror = None;
                    client_generation = generation;
                }
                if let Ok(map) = shared.cursor_map.lock() {
                    if map.2.elapsed() < Duration::from_secs(2) && !map.0.is_empty() {
                        let root = [f64::from(sample.root_x), f64::from(sample.root_y)];
                        let driving = shared
                            .cursor_hand_at
                            .lock()
                            .unwrap_or_else(|e| e.into_inner());
                        let hand_driven =
                            driving.is_some_and(|at| at.elapsed() < Duration::from_millis(200));
                        let claim = shared
                            .cursor_claim
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .take();
                        let claimed = claim.is_some_and(|(key, point)| {
                            if hand_driven {
                                router.claim_exact(&map.0, &key, point)
                            } else {
                                router.claim(&map.0, &key, point)
                            }
                        });
                        // Root coordinates can be clamped or moved by the window manager.
                        // While a pane owns the cursor, those coordinates are only its mirror.
                        if !hand_driven
                            && !claimed
                            && motion == [0.0, 0.0]
                            && last_root.is_some_and(|old| old != root)
                        {
                            router.observe_root_warp();
                        }
                        if !hand_driven && (motion != [0.0, 0.0] || router.has_point()) {
                            if let Some(target) =
                                router.advance(&map.0, last_root.unwrap_or(root), motion, map.1)
                            {
                                let x = target[0].round() as i32;
                                let y = target[1].round() as i32;
                                let mirror = (x, y);
                                let changed = last_mirror != Some(mirror);
                                if (changed
                                    || claimed
                                    || motion != [0.0, 0.0]
                                    || last_root != Some(root))
                                    && (x, y) != (sample.root_x, sample.root_y)
                                {
                                    let _ = cursor.move_to(x, y);
                                }
                                last_mirror = Some(mirror);
                            }
                        }
                        drop(driving);
                        if owner_sent.elapsed() >= Duration::from_millis(33) {
                            let value = if let Some((key, uv, root)) = router.location(&map.0) {
                                {
                                    let point = router.virtual_position().unwrap_or(root);
                                    serde_json::json!({"type":"cursorOwner","key":key,"u":uv[0],"v":uv[1],"x":point[0],"y":point[1],"sourceX":root[0],"sourceY":root[1]})
                                }
                            } else {
                                serde_json::json!({"type":"cursorOwner","key":""})
                            };
                            if let Ok(client) = shared.client.read() {
                                if let Some(client) = client.as_ref() {
                                    let packet = ControlPacket::Workspace {
                                        json: value.to_string(),
                                    }
                                    .encode();
                                    let _ = socket.send_to(&packet, client.control);
                                }
                            }
                            owner_sent = Instant::now();
                        }
                    } else {
                        last_mirror = None;
                        shared
                            .cursor_claim
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .take();
                        if last_root.is_some_and(|old| {
                            old != [f64::from(sample.root_x), f64::from(sample.root_y)]
                        }) {
                            router.reset();
                        } else {
                            router.inactive(motion);
                        }
                    }
                }
                last_root = Some([f64::from(sample.root_x), f64::from(sample.root_y)]);

                let changed = if let Ok(mut slot) = shared.cursor.lock() {
                    let moved = slot.root_x != sample.root_x || slot.root_y != sample.root_y;
                    let reshaped = !sample.argb.is_empty();
                    slot.root_x = sample.root_x;
                    slot.root_y = sample.root_y;
                    if reshaped {
                        slot.hotspot_x = sample.hotspot_x;
                        slot.hotspot_y = sample.hotspot_y;
                        slot.width = sample.width;
                        slot.height = sample.height;
                        slot.pixels = argb_to_bgra(&sample.argb);
                    }
                    moved || reshaped
                } else {
                    false
                };
                if changed {
                    shared.cursor_generation.fetch_add(1, Ordering::Relaxed);
                }
            }
            Err(err) => tracing::debug!("cursor poll: {err}"),
        }
        thread::sleep(Duration::from_millis(2));
    }
}

pub(super) fn paint_cursor_scaled(
    frame: &mut crate::core::CapturedFrame,
    rect: MonitorRect,
    cursor: &CursorSprite,
) {
    let destination = (0, 0, frame.width, frame.height);
    paint_cursor_scaled_at(frame, rect, cursor, destination);
}
pub(super) fn paint_cursor_scaled_at(
    frame: &mut crate::core::CapturedFrame,
    rect: MonitorRect,
    cursor: &CursorSprite,
    destination: (u32, u32, u32, u32),
) {
    let (x, y, width_px, height_px) = destination;
    let clip = (
        x as i32,
        y as i32,
        (x + width_px) as i32,
        (y + height_px) as i32,
    );
    if (width_px, height_px) == (rect.width, rect.height) {
        paint_cursor_clipped(frame, rect.x - x as i32, rect.y - y as i32, cursor, clip);
        return;
    }
    if cursor.width == 0
        || cursor.height == 0
        || cursor.pixels.len() != usize::from(cursor.width) * usize::from(cursor.height) * 4
    {
        return;
    }
    let sx = f64::from(width_px) / f64::from(rect.width);
    let sy = f64::from(height_px) / f64::from(rect.height);
    let width = (f64::from(cursor.width) * sx).ceil().max(1.0) as u16;
    let height = (f64::from(cursor.height) * sy).ceil().max(1.0) as u16;
    let mut pixels = vec![0; usize::from(width) * usize::from(height) * 4];
    for y in 0..height {
        for x in 0..width {
            let source_x = (f64::from(x) / sx).floor().min(f64::from(cursor.width - 1)) as usize;
            let source_y = (f64::from(y) / sy)
                .floor()
                .min(f64::from(cursor.height - 1)) as usize;
            let src = (source_y * usize::from(cursor.width) + source_x) * 4;
            let dst = (usize::from(y) * usize::from(width) + usize::from(x)) * 4;
            pixels[dst..dst + 4].copy_from_slice(&cursor.pixels[src..src + 4]);
        }
    }
    let scaled = CursorSprite {
        root_x: x as i32 + (f64::from(cursor.root_x - rect.x) * sx).round() as i32,
        root_y: y as i32 + (f64::from(cursor.root_y - rect.y) * sy).round() as i32,
        hotspot_x: (f64::from(cursor.hotspot_x) * sx).round() as i16,
        hotspot_y: (f64::from(cursor.hotspot_y) * sy).round() as i16,
        width,
        height,
        pixels,
    };
    paint_cursor_clipped(frame, 0, 0, &scaled, clip);
}

pub(super) fn paint_cursor_clipped(
    frame: &mut crate::core::CapturedFrame,
    origin_x: i32,
    origin_y: i32,
    cursor: &CursorSprite,
    clip: (i32, i32, i32, i32),
) {
    let width = cursor.width as i32;
    let height = cursor.height as i32;
    if width <= 0 || height <= 0 {
        return;
    }
    let expected = cursor.width as usize * cursor.height as usize * 4;
    if cursor.pixels.len() < expected {
        return;
    }
    let left = cursor.root_x - i32::from(cursor.hotspot_x) - origin_x;
    let top = cursor.root_y - i32::from(cursor.hotspot_y) - origin_y;
    let frame_w = frame.width as i32;
    let frame_h = frame.height as i32;
    let stride = frame.stride as usize;
    for row in 0..height {
        let dst_y = top + row;
        if dst_y < clip.1.max(0) || dst_y >= clip.3.min(frame_h) {
            continue;
        }
        for col in 0..width {
            let dst_x = left + col;
            if dst_x < clip.0.max(0) || dst_x >= clip.2.min(frame_w) {
                continue;
            }
            let src = ((row as usize * cursor.width as usize) + col as usize) * 4;
            let alpha = cursor.pixels[src + 3] as u32;
            if alpha == 0 {
                continue;
            }
            let dst = dst_y as usize * stride + dst_x as usize * 4;
            if dst + 2 >= frame.bgra.len() {
                continue;
            }
            if alpha == 255 {
                frame.bgra[dst] = cursor.pixels[src];
                frame.bgra[dst + 1] = cursor.pixels[src + 1];
                frame.bgra[dst + 2] = cursor.pixels[src + 2];
            } else {
                for channel in 0..3 {
                    let blended = cursor.pixels[src + channel] as u32 * alpha
                        + frame.bgra[dst + channel] as u32 * (255 - alpha);
                    frame.bgra[dst + channel] = (blended / 255) as u8;
                }
            }
        }
    }
}

pub(super) fn argb_to_bgra(pixels: &[u32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pixels.len() * 4);
    for pixel in pixels {
        out.push(*pixel as u8);
        out.push((pixel >> 8) as u8);
        out.push((pixel >> 16) as u8);
        out.push((pixel >> 24) as u8);
    }
    out
}
