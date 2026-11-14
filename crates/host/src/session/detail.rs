//! Experimental native-density HEVC tiles, with a legacy H.264 atlas fallback.
use super::*;
mod area;
mod atlas;
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
                let mut packed: Option<PackedView> = None;
                let mut using_baseline = true;
                let mut governor = area::Governor::new();
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
                    let mut request: serde_json::Value =
                        serde_json::from_slice(&bytes).map_err(|e| Error::Msg(e.to_string()))?;
                    let allowed = shared
                        .client
                        .read()
                        .unwrap_or_else(|e| e.into_inner())
                        .as_ref()
                        .is_some_and(|c| c.control.ip() == peer.ip());
                    if !allowed
                        || request["sessionId"].as_u64() != Some(shared.session_id)
                        || !matches!(
                            request["mode"].as_str(),
                            Some("view-h264-v1" | "view-tiles-v2")
                        )
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
                    if request["mode"] == "view-tiles-v2" {
                        let area_mode = request["areaBudget"].as_bool().unwrap_or(false);
                        if area_mode {
                            let (w, h) = stream_size(desktop);
                            let normal = u64::from(w) * u64::from(h);
                            let requested = request["nativeAreaPixels"]
                                .as_u64()
                                .unwrap_or(0)
                                .min(16_777_216);
                            let target = request["targetFps"]
                                .as_u64()
                                .unwrap_or(30)
                                .clamp(5, fps.clamp(5, 120) as u64)
                                as u32;
                            governor.configure(requested, target, normal);
                            request["resolvedAreaPixels"] = governor.area.into();
                            request["peripheryPixels"] = governor.periphery(normal).into();
                            request["autoArea"] = governor.automatic().into();
                            request["targetFps"] = target.into();
                        }
                        let budget = request["frameBudget"].as_bool().unwrap_or(false);
                        if budget
                            && !area_mode
                            && atlas::efficient(
                                desktop,
                                outer,
                                inner_region(desktop, outer, &request),
                                using_baseline,
                            )
                            .is_none()
                        {
                            packed.take();
                            if !using_baseline
                                || shared.view_until_ns.swap(0, Ordering::Relaxed) > 0
                            {
                                shared.keys[0].store(true, Ordering::Relaxed);
                            }
                            shared.view_until_ns.store(0, Ordering::Relaxed);
                            using_baseline = true;
                            sequence += 1;
                            let (bw, bh) = stream_size(desktop);
                            let reason =
                                if bw as u32 >= desktop.width && bh as u32 >= desktop.height {
                                    "Normal density: source already native"
                                } else {
                                    "Normal density: zoom in for native detail"
                                };
                            let header=serde_json::json!({"type":"view-baseline-v1","frame":sequence,"bytes":0,"reason":reason}).to_string();
                            socket.write_all(&(header.len() as u32).to_be_bytes())?;
                            socket.write_all(header.as_bytes())?;
                            continue;
                        }
                        using_baseline = false;
                        if packed
                            .as_ref()
                            .is_none_or(|p| !p.fits(desktop, outer, &request))
                        {
                            // Release the old SHM/encoder before allocating the next bucket.
                            packed.take();
                            if area_mode {
                                governor.rebuilt()
                            }
                            packed = Some(PackedView::new(
                                desktop,
                                outer,
                                &request,
                                fps,
                                bitrate,
                                &encoder_name,
                            )?)
                        }
                        sequence += 1;
                        let began = Instant::now();
                        packed.as_mut().unwrap().send(
                            &shared,
                            &mut socket,
                            desktop,
                            &screens,
                            outer,
                            outer_uv,
                            &request,
                            sequence,
                        )?;
                        if area_mode {
                            let p = packed.as_ref().unwrap();
                            governor.observe(
                                began.elapsed().as_secs_f64() * 1000.,
                                request["feedbackMs"].as_f64().unwrap_or(0.),
                                monotonic_ns() / 1_000_000,
                                p.whole_native,
                            );
                        }
                        continue;
                    }
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
    fn packed_tiles_partition_without_overlap_even_at_edges() {
        let outer = MonitorRect {
            index: 0,
            x: -100,
            y: 50,
            width: 1000,
            height: 600,
        };
        for inner in [
            outer,
            MonitorRect {
                index: 0,
                x: -100,
                y: 50,
                width: 400,
                height: 240,
            },
            MonitorRect {
                index: 0,
                x: 200,
                y: 230,
                width: 400,
                height: 240,
            },
            MonitorRect {
                index: 0,
                x: 500,
                y: 410,
                width: 400,
                height: 240,
            },
        ] {
            let regions = packed_regions(outer, inner);
            assert_eq!(regions[0], inner);
            assert_eq!(
                regions
                    .iter()
                    .map(|r| r.width as u64 * r.height as u64)
                    .sum::<u64>(),
                outer.width as u64 * outer.height as u64
            );
            for (i, a) in regions.iter().enumerate() {
                for b in regions.iter().skip(i + 1) {
                    assert!(
                        a.x + a.width as i32 <= b.x
                            || b.x + b.width as i32 <= a.x
                            || a.y + a.height as i32 <= b.y
                            || b.y + b.height as i32 <= a.y
                    );
                }
            }
        }
    }
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

fn view_plan(
    desktop: MonitorRect,
    outer: MonitorRect,
    request: &serde_json::Value,
    bound: Option<(u16, u16)>,
) -> Option<atlas::Plan> {
    let inner = inner_region(desktop, outer, request);
    if request["areaBudget"].as_bool().unwrap_or(false) {
        let p = atlas::area_plan(
            desktop,
            outer,
            inner,
            request["peripheryPixels"].as_u64().unwrap_or(2_000_000),
            request["autoArea"].as_bool().unwrap_or(true),
        )?;
        if bound.is_some_and(|(w, h)| p.width > w || p.height > h) {
            None
        } else {
            Some(p)
        }
    } else if bound.is_some() {
        atlas::plan(desktop, outer, inner, bound)
    } else if request["frameBudget"].as_bool().unwrap_or(false) {
        atlas::efficient(desktop, outer, inner, false)
    } else {
        atlas::plan(desktop, outer, inner, None)
    }
}
struct PackedView {
    source: (u32, u32),
    fraction: f64,
    budget: bool,
    area_mode: bool,
    avc: bool,
    resolved_area: u64,
    periphery: u64,
    whole_native: bool,
    width: u16,
    height: u16,
    capture: X11Capture,
    encoder: GstEncoder,
}
impl PackedView {
    fn new(
        desktop: MonitorRect,
        outer: MonitorRect,
        request: &serde_json::Value,
        fps: u32,
        bitrate: u32,
        encoder_name: &str,
    ) -> Result<Self> {
        let budget = request["frameBudget"].as_bool().unwrap_or(false);
        let area_mode = request["areaBudget"].as_bool().unwrap_or(false);
        let plan = view_plan(desktop, outer, request, None)
            .ok_or_else(|| Error::Msg("Native area cannot fit encoder".into()))?;
        let (width, height) = (plan.width, plan.height);
        let rate = request["bitrateMbps"]
            .as_f64()
            .filter(|v| v.is_finite() && *v > 0.0)
            .map(|v| (v.clamp(1.0, 200.0) * 1e6) as u32)
            .unwrap_or(bitrate);
        let quality = request["quality"].as_u64().unwrap_or(0).min(2) as u32;
        Ok(Self {
            source: (desktop.width, desktop.height),
            budget,
            area_mode,
            avc: if area_mode {
                width <= 4096 && height <= 2160
            } else {
                budget
            },
            resolved_area: request["resolvedAreaPixels"].as_u64().unwrap_or(0),
            periphery: request["peripheryPixels"].as_u64().unwrap_or(0),
            whole_native: false,
            fraction: dense_fraction(request),
            width,
            height,
            capture: X11Capture::workspace(desktop, width, height)?,
            encoder: {
                let config = crate::core::EncoderConfig {
                    width: width.into(),
                    height: height.into(),
                    fps: fps.clamp(1, 60),
                    bitrate_bps: rate,
                };
                if (area_mode && width <= 4096 && height <= 2160) || (!area_mode && budget) {
                    GstEncoder::open_quality(encoder_name, config, quality)?
                } else {
                    GstEncoder::open_hevc(config, quality)?
                }
            },
        })
    }
    fn fits(&self, desktop: MonitorRect, outer: MonitorRect, request: &serde_json::Value) -> bool {
        if self.source != (desktop.width, desktop.height)
            || self.budget != request["frameBudget"].as_bool().unwrap_or(false)
            || self.area_mode != request["areaBudget"].as_bool().unwrap_or(false)
        {
            return false;
        }
        if view_plan(desktop, outer, request, Some((self.width, self.height))).is_none() {
            return false;
        }
        let Some(best) = view_plan(desktop, outer, request, None) else {
            return false;
        };
        if self.area_mode && self.avc != (best.width <= 4096 && best.height <= 2160) {
            return false;
        }
        let area = u32::from(self.width) * u32::from(self.height);
        let best_area = u32::from(best.width) * u32::from(best.height);
        if self.area_mode
            && (self.resolved_area != request["resolvedAreaPixels"].as_u64().unwrap_or(0)
                || self.periphery != request["peripheryPixels"].as_u64().unwrap_or(0))
            && best_area * 10 < area * 9
        {
            return false;
        }
        // Lowering coverage must actually lower encoding work, including after 100%.
        if dense_fraction(request) != self.fraction && best_area < area {
            return false;
        }
        best_area * 2 >= area
    }
    fn send(
        &mut self,
        shared: &Shared,
        socket: &mut std::net::TcpStream,
        desktop: MonitorRect,
        screens: &[MonitorRect],
        outer: MonitorRect,
        outer_uv: [f32; 4],
        request: &serde_json::Value,
        sequence: u64,
    ) -> Result<()> {
        let start = Instant::now();
        let plan = view_plan(desktop, outer, request, Some((self.width, self.height)))
            .ok_or_else(|| Error::Msg("Area plan exceeds encoder".into()))?;
        self.resolved_area = request["resolvedAreaPixels"].as_u64().unwrap_or(0);
        self.periphery = request["peripheryPixels"].as_u64().unwrap_or(0);
        self.whole_native = plan.tiles[0].source == outer;
        self.fraction = dense_fraction(request);
        let regions = plan
            .tiles
            .iter()
            .map(|t| (t.source, t.x, t.y, t.width, t.height))
            .collect::<Vec<_>>();
        let mut image = self.capture.atlas(&regions)?;
        image.damage_generation = sequence;
        let mut metadata = Vec::new();
        for tile in &plan.tiles {
            let r = tile.source;
            crate::media::desktop_mask::DesktopMask::new(
                r,
                tile.width.into(),
                tile.height.into(),
                screens,
            )
            .apply_at(&mut image, tile.x.into(), tile.y.into());
            if let Ok(cursor) = shared.cursor.lock() {
                paint_cursor_scaled_at(
                    &mut image,
                    r,
                    &cursor,
                    (
                        tile.x.into(),
                        tile.y.into(),
                        tile.width.into(),
                        tile.height.into(),
                    ),
                )
            }
            let source = [
                (r.x - desktop.x) as f32 / desktop.width as f32,
                (r.y - desktop.y) as f32 / desktop.height as f32,
                (r.x - desktop.x + r.width as i32) as f32 / desktop.width as f32,
                (r.y - desktop.y + r.height as i32) as f32 / desktop.height as f32,
            ];
            let dest = [
                tile.x as f32 / self.width as f32,
                tile.y as f32 / self.height as f32,
                (tile.x + tile.width) as f32 / self.width as f32,
                (tile.y + tile.height) as f32 / self.height as f32,
            ];
            metadata.push([source, dest]);
        }
        let capture_ns = start.elapsed().as_nanos() as u64;
        let encode = Instant::now();
        self.encoder.submit(&image)?;
        let frame = self
            .encoder
            .pull_wait(500_000_000)?
            .ok_or_else(|| Error::Msg("HEVC view encode timeout".into()))?;
        if frame.bytes.len() > MAX_FRAME {
            return Err(Error::Msg("Packed view frame exceeds bound".into()));
        }
        shared
            .view_capture_ns
            .fetch_add(capture_ns, Ordering::Relaxed);
        shared
            .view_encode_ns
            .fetch_add(encode.elapsed().as_nanos() as u64, Ordering::Relaxed);
        shared.view_samples.fetch_add(1, Ordering::Relaxed);
        shared
            .view_until_ns
            .store(monotonic_ns() + 1_000_000_000, Ordering::Relaxed);
        let header=serde_json::json!({"type":"view-tiles-v2","codec":if self.avc{"video/avc"}else{"video/hevc"},"frame":sequence,"rect":outer_uv,"inner":metadata[0][0],"tiles":metadata,"nativeAreaPixels":u64::from(plan.tiles[0].source.width)*u64::from(plan.tiles[0].source.height),"autoArea":request["autoArea"],"targetFps":request["targetFps"],"width":self.width,"height":self.height,"bytes":frame.bytes.len(),"desktop":[desktop.x,desktop.y,desktop.width,desktop.height]}).to_string();
        socket.write_all(&(header.len() as u32).to_be_bytes())?;
        socket.write_all(header.as_bytes())?;
        socket.write_all(&frame.bytes)?;
        shared.encoded_frames.fetch_add(1, Ordering::Relaxed);
        shared
            .bytes
            .fetch_add(frame.bytes.len() as u64, Ordering::Relaxed);
        Ok(())
    }
}
fn dense_fraction(request: &serde_json::Value) -> f64 {
    request["fraction"]
        .as_f64()
        .filter(|v| v.is_finite())
        .unwrap_or(0.35)
        .clamp(0.2, 1.0)
}
fn inner_region(
    desktop: MonitorRect,
    outer: MonitorRect,
    request: &serde_json::Value,
) -> MonitorRect {
    let fraction = dense_fraction(request);
    let side = request["resolvedAreaPixels"]
        .as_u64()
        .filter(|v| *v > 0)
        .map(|n| (n as f64).sqrt().floor().clamp(1., 4096.) as u32);
    let iw = ((outer.width as f64 * fraction).round() as u32).clamp(1, outer.width);
    let iw = side.map_or(iw, |side| side.min(outer.width));
    let ih = side.map_or(
        ((outer.height as f64 * fraction).round() as u32).clamp(1, outer.height),
        |side| side.min(outer.height),
    );
    let center = request["center"].as_array();
    let axis =
        |n: usize, origin: i32, extent: u32, total_origin: i32, total_extent: u32, size: u32| {
            let low = (origin as f64 - total_origin as f64) / total_extent as f64;
            let high = (origin as f64 - total_origin as f64 + extent as f64) / total_extent as f64;
            let normalized = center
                .and_then(|a| a.get(n))
                .and_then(|v| v.as_f64())
                .filter(|v| v.is_finite())
                .unwrap_or((low + high) * 0.5)
                .clamp(low, high);
            ((total_origin as f64 + normalized * total_extent as f64 - size as f64 * 0.5).round()
                as i32)
                .clamp(origin, origin + (extent - size) as i32)
        };
    MonitorRect {
        index: 0,
        x: axis(0, outer.x, outer.width, desktop.x, desktop.width, iw),
        y: axis(1, outer.y, outer.height, desktop.y, desktop.height, ih),
        width: iw,
        height: ih,
    }
}
fn packed_regions(outer: MonitorRect, inner: MonitorRect) -> Vec<MonitorRect> {
    let r = |x, y, width, height| MonitorRect {
        index: 0,
        x,
        y,
        width,
        height,
    };
    [
        inner,
        r(outer.x, outer.y, (inner.x - outer.x) as u32, outer.height),
        r(
            inner.x + inner.width as i32,
            outer.y,
            (outer.x + outer.width as i32 - inner.x - inner.width as i32) as u32,
            outer.height,
        ),
        r(inner.x, outer.y, inner.width, (inner.y - outer.y) as u32),
        r(
            inner.x,
            inner.y + inner.height as i32,
            inner.width,
            (outer.y + outer.height as i32 - inner.y - inner.height as i32) as u32,
        ),
    ]
    .into_iter()
    .filter(|r| r.width > 0 && r.height > 0)
    .collect()
}
