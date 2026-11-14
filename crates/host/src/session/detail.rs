//! Experimental native-density HEVC tiles, with a legacy H.264 atlas fallback.
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
                let mut packed: Option<PackedView> = None;
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
                        if packed.as_ref().is_none_or(|p| !p.fits(desktop, outer)) {
                            // Release the old SHM/encoder before allocating the next bucket.
                            packed.take();
                            packed = Some(PackedView::new(desktop, outer, &request, fps, bitrate)?)
                        }
                        sequence += 1;
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

struct PackedView {
    source: (u32, u32),
    density: (f64, f64),
    width: u16,
    height: u16,
    capture: X11Capture,
    encoder: GstEncoder,
    pixels: Vec<u8>,
}
impl PackedView {
    fn new(
        desktop: MonitorRect,
        outer: MonitorRect,
        request: &serde_json::Value,
        fps: u32,
        bitrate: u32,
    ) -> Result<Self> {
        let scale = 1f64
            .min(8192.0 / desktop.width as f64)
            .min(8192.0 / desktop.height as f64)
            .min((16_777_216.0 / (desktop.width as f64 * desktop.height as f64)).sqrt());
        let width = ((desktop.width as f64 * scale) as u16 / 2 * 2).max(2);
        let height = ((desktop.height as f64 * scale) as u16 / 2 * 2).max(2);
        let density = (
            width as f64 / desktop.width as f64,
            height as f64 / desktop.height as f64,
        );
        // Stable 256-pixel buckets avoid codec recreation on small FOV/crop changes.
        let bucket = |value: f64, limit: u16| {
            ((((value - 1e-7).ceil() as u32).div_ceil(256) * 256)
                .max(64)
                .min(limit as u32)) as u16
        };
        let width = bucket(outer.width as f64 * density.0, width);
        let height = bucket(outer.height as f64 * density.1, height);
        let rate = request["bitrateMbps"]
            .as_f64()
            .filter(|v| v.is_finite() && *v > 0.0)
            .map(|v| (v.clamp(1.0, 200.0) * 1e6) as u32)
            .unwrap_or(bitrate);
        let quality = request["quality"].as_u64().unwrap_or(0).min(2) as u32;
        Ok(Self {
            source: (desktop.width, desktop.height),
            density,
            width,
            height,
            capture: X11Capture::workspace(desktop, width, height)?,
            encoder: GstEncoder::open_hevc(
                crate::core::EncoderConfig {
                    width: width.into(),
                    height: height.into(),
                    fps: fps.clamp(1, 60),
                    bitrate_bps: rate,
                },
                quality,
            )?,
            pixels: vec![0; width as usize * height as usize * 4],
        })
    }
    fn fits(&self, desktop: MonitorRect, outer: MonitorRect) -> bool {
        let w = (outer.width as f64 * self.density.0 - 1e-7).ceil();
        let h = (outer.height as f64 * self.density.1 - 1e-7).ceil();
        // Shrink only after a substantial zoom, retaining allocations through normal head motion.
        w <= self.width as f64
            && h <= self.height as f64
            && !(w * 2.0 < self.width as f64 && h * 2.0 < self.height as f64)
            && self.source == (desktop.width, desktop.height)
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
        self.pixels.fill(0);
        let fraction = request["fraction"]
            .as_f64()
            .filter(|v| v.is_finite())
            .unwrap_or(0.35)
            .clamp(0.2, 1.0);
        let iw = ((outer.width as f64 * fraction).round() as u32).clamp(1, outer.width);
        let ih = ((outer.height as f64 * fraction).round() as u32).clamp(1, outer.height);
        let center = request["center"].as_array();
        let axis = |n: usize, lo: f32, hi: f32| {
            center
                .and_then(|a| a.get(n))
                .and_then(|v| v.as_f64())
                .filter(|v| v.is_finite())
                .unwrap_or((lo + hi) as f64 * 0.5)
                .clamp(lo as f64, hi as f64)
        };
        let ix = (desktop.x as f64 + axis(0, outer_uv[0], outer_uv[2]) * desktop.width as f64
            - iw as f64 * 0.5)
            .round() as i32;
        let iy = (desktop.y as f64 + axis(1, outer_uv[1], outer_uv[3]) * desktop.height as f64
            - ih as f64 * 0.5)
            .round() as i32;
        let inner = MonitorRect {
            index: 0,
            x: ix.clamp(outer.x, outer.x + (outer.width - iw) as i32),
            y: iy.clamp(outer.y, outer.y + (outer.height - ih) as i32),
            width: iw,
            height: ih,
        };
        let (sx, sy) = self.density;
        let baseline = 1f64
            .min(4096.0 / desktop.width as f64)
            .min(2160.0 / desktop.height as f64)
            .min(sx)
            .min(sy);
        let mut metadata = Vec::new();
        for (i, r) in packed_regions(outer, inner).into_iter().enumerate() {
            let x = (((r.x - outer.x) as f64 * sx).floor() as u16).min(self.width - 1);
            let y = (((r.y - outer.y) as f64 * sy).floor() as u16).min(self.height - 1);
            let right =
                (((r.x - outer.x + r.width as i32) as f64 * sx).floor() as u16).min(self.width);
            let bottom =
                (((r.y - outer.y + r.height as i32) as f64 * sy).floor() as u16).min(self.height);
            let w = if i == 0 {
                right - x
            } else {
                ((r.width as f64 * baseline).round() as u16).min(right - x)
            }
            .max(1);
            let h = if i == 0 {
                bottom - y
            } else {
                ((r.height as f64 * baseline).round() as u16).min(bottom - y)
            }
            .max(1);
            self.capture.retarget_resampled(r, w, h)?;
            let mut image = self
                .capture
                .poll(Duration::ZERO, true)?
                .ok_or_else(|| Error::Msg("Atlas capture unavailable".into()))?;
            crate::media::desktop_mask::DesktopMask::new(r, w.into(), h.into(), screens)
                .apply(&mut image);
            if let Ok(cursor) = shared.cursor.lock() {
                paint_cursor_scaled(&mut image, r, &cursor)
            }
            for row in 0..h as usize {
                let from = row * image.stride as usize;
                let to = ((y as usize + row) * self.width as usize + x as usize) * 4;
                self.pixels[to..to + w as usize * 4]
                    .copy_from_slice(&image.bgra[from..from + w as usize * 4]);
            }
            let source = [
                (r.x - desktop.x) as f32 / desktop.width as f32,
                (r.y - desktop.y) as f32 / desktop.height as f32,
                (r.x - desktop.x + r.width as i32) as f32 / desktop.width as f32,
                (r.y - desktop.y + r.height as i32) as f32 / desktop.height as f32,
            ];
            let dest = [
                x as f32 / self.width as f32,
                y as f32 / self.height as f32,
                (x + w) as f32 / self.width as f32,
                (y + h) as f32 / self.height as f32,
            ];
            metadata.push([source, dest]);
        }
        let capture_ns = start.elapsed().as_nanos() as u64;
        let encode = Instant::now();
        // Move ownership into the submission briefly; restore and reuse the same bounded allocation.
        let image = crate::core::CapturedFrame {
            width: self.width.into(),
            height: self.height.into(),
            stride: self.width as u32 * 4,
            bgra: std::mem::take(&mut self.pixels),
            monotonic_ns: monotonic_ns(),
            damage_generation: sequence,
        };
        let submitted = self.encoder.submit(&image);
        self.pixels = image.bgra;
        submitted?;
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
        let header=serde_json::json!({"type":"view-tiles-v2","codec":"video/hevc","frame":sequence,"rect":outer_uv,"inner":metadata[0][0],"tiles":metadata,"width":self.width,"height":self.height,"bytes":frame.bytes.len(),"desktop":[desktop.x,desktop.y,desktop.width,desktop.height]}).to_string();
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
