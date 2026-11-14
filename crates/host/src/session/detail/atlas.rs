//! Pack sampled pixels, not the native desktop-shaped empty allocations.
use crate::core::MonitorRect;
use crate::platform::x11::monitor_pool::{stream_size, STREAM_MAX_HEIGHT, STREAM_MAX_WIDTH};

#[derive(Clone, Copy, Debug)]
pub(super) struct Tile {
    pub source: MonitorRect,
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}
#[derive(Debug)]
pub(super) struct Plan {
    pub width: u16,
    pub height: u16,
    pub tiles: Vec<Tile>,
}
fn size(desktop: MonitorRect) -> (f64, f64, f64) {
    let scale = 1f64
        .min(8192.0 / desktop.width as f64)
        .min(8192.0 / desktop.height as f64)
        .min((16_777_216.0 / (desktop.width as f64 * desktop.height as f64)).sqrt());
    let x = ((desktop.width as f64 * scale) as u32 / 2 * 2).max(2) as f64 / desktop.width as f64;
    let y = ((desktop.height as f64 * scale) as u32 / 2 * 2).max(2) as f64 / desktop.height as f64;
    let (bw, bh) = stream_size(desktop);
    (
        x,
        y,
        (bw as f64 / desktop.width as f64)
            .min(bh as f64 / desktop.height as f64)
            .min(x)
            .min(y),
    )
}
fn pixels(n: u32, scale: f64) -> u16 {
    ((n as f64 * scale - 1e-7).ceil() as u16).max(1)
}
fn extent(w: u16, h: u16, bound: Option<(u16, u16)>) -> Option<(u16, u16)> {
    let even = |n: u16| (u32::from(n).div_ceil(2) * 2).max(2) as u16;
    let w = even(w);
    let h = even(h);
    let valid = |w: u16, h: u16| {
        w <= 8192
            && h <= 8192
            && u32::from(w) * u32::from(h) <= 16_777_216
            && bound.is_none_or(|(bw, bh)| w <= bw && h <= bh)
    };
    if !valid(w, h) {
        return None;
    }
    // Leave a little growth room, without forcing a larger atlas when an existing one fits.
    let bw = (u32::from(w).div_ceil(256) * 256).min(8192) as u16;
    let bh = (u32::from(h).div_ceil(256) * 256).min(8192) as u16;
    Some(if valid(bw, bh) { (bw, bh) } else { (w, h) })
}
/// Three constant-size zones: dense image, the two side strips, and the two cap strips.
/// Their dimensions do not depend on gaze position, so gaze never rebuilds the codec.
pub(super) fn plan(
    desktop: MonitorRect,
    outer: MonitorRect,
    inner: MonitorRect,
    bound: Option<(u16, u16)>,
) -> Option<Plan> {
    let (sx, sy, baseline) = size(desktop);
    let regions = super::packed_regions(outer, inner);
    let native_w = pixels(outer.width, sx);
    let native_h = pixels(outer.height, sy);
    let mut best = extent(native_w, native_h, bound).map(|(width, height)| Plan {
        width,
        height,
        tiles: regions
            .iter()
            .enumerate()
            .map(|(i, &r)| {
                let x = ((r.x - outer.x) as f64 * sx).floor() as u16;
                let y = ((r.y - outer.y) as f64 * sy).floor() as u16;
                let right = (((r.x - outer.x) as f64 + r.width as f64) * sx).floor() as u16;
                let bottom = (((r.y - outer.y) as f64 + r.height as f64) * sy).floor() as u16;
                Tile {
                    source: r,
                    x,
                    y,
                    width: if i == 0 {
                        right - x
                    } else {
                        pixels(r.width, baseline).min(right - x)
                    }
                    .max(1),
                    height: if i == 0 {
                        bottom - y
                    } else {
                        pixels(r.height, baseline).min(bottom - y)
                    }
                    .max(1),
                }
            })
            .collect(),
    });
    if regions.len() == 1 {
        return best;
    }
    let zones = [
        (pixels(inner.width, sx), pixels(inner.height, sy)),
        (
            pixels(outer.width - inner.width, baseline).saturating_add(2),
            pixels(outer.height, baseline),
        ),
        (
            pixels(inner.width, baseline),
            pixels(outer.height - inner.height, baseline).saturating_add(2),
        ),
    ];
    for permutation in [
        [0, 1, 2],
        [0, 2, 1],
        [1, 0, 2],
        [1, 2, 0],
        [2, 0, 1],
        [2, 1, 0],
    ] {
        for breaks in 0..4 {
            for vertical in [false, true] {
                let mut origins = [(0u16, 0u16); 3];
                let (mut cross, mut along, mut shelf, mut maximum) = (0u16, 0u16, 0u16, 0u16);
                for (i, &index) in permutation.iter().enumerate() {
                    if i > 0 && breaks & (1 << (i - 1)) != 0 {
                        cross += shelf;
                        along = 0;
                        shelf = 0;
                    }
                    let (w, h) = zones[index];
                    origins[index] = if vertical {
                        (cross, along)
                    } else {
                        (along, cross)
                    };
                    along += if vertical { h } else { w };
                    shelf = shelf.max(if vertical { w } else { h });
                    maximum = maximum.max(along);
                }
                cross += shelf;
                let (w, h) = if vertical {
                    (cross, maximum)
                } else {
                    (maximum, cross)
                };
                let Some((width, height)) = extent(w, h, bound) else {
                    continue;
                };
                if best.as_ref().is_some_and(|p| {
                    u32::from(p.width) * u32::from(p.height) <= u32::from(width) * u32::from(height)
                }) {
                    continue;
                }
                let mut side_x = origins[1].0;
                let mut cap_y = origins[2].1;
                let mut tiles = vec![Tile {
                    source: inner,
                    x: origins[0].0,
                    y: origins[0].1,
                    width: zones[0].0,
                    height: zones[0].1,
                }];
                for &r in regions.iter().skip(1) {
                    let (tw, th) = (pixels(r.width, baseline), pixels(r.height, baseline));
                    let side = r.x < inner.x || r.x >= inner.x + inner.width as i32;
                    let (x, y) = if side {
                        let x = side_x;
                        side_x += tw;
                        (x, origins[1].1)
                    } else {
                        let y = cap_y;
                        cap_y += th;
                        (origins[2].0, y)
                    };
                    tiles.push(Tile {
                        source: r,
                        x,
                        y,
                        width: tw,
                        height: th,
                    });
                }
                best = Some(Plan {
                    width,
                    height,
                    tiles,
                });
            }
        }
    }
    best
}
/// Use the ordinary AVC frame budget. Wide views stay on the normal stream;
/// zoomed views spend that same budget on source-native samples instead.
pub(super) fn efficient(
    desktop: MonitorRect,
    outer: MonitorRect,
    inner: MonitorRect,
    entering: bool,
) -> Option<Plan> {
    let (width, height) = stream_size(desktop);
    if width as u32 >= desktop.width && height as u32 >= desktop.height {
        return None;
    }
    let p = plan(
        desktop,
        outer,
        inner,
        Some((STREAM_MAX_WIDTH, STREAM_MAX_HEIGHT)),
    )?;
    let headroom = if entering { 0.9 } else { 1.0 };
    ((u32::from(p.width) * u32::from(p.height)) as f64
        <= (u32::from(width) * u32::from(height)) as f64 * headroom)
        .then_some(p)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn zoomed_out_uses_baseline_and_zoomed_in_fits_the_avc_frame_budget() {
        let desktop = MonitorRect {
            index: 0,
            x: 0,
            y: 0,
            width: 5120,
            height: 3200,
        };
        for f in [0.2, 1.0] {
            let inner = MonitorRect {
                width: (desktop.width as f64 * f) as u32,
                height: (desktop.height as f64 * f) as u32,
                ..desktop
            };
            assert!(efficient(desktop, desktop, inner, true).is_none());
        }
        let outer = MonitorRect {
            index: 0,
            x: 2000,
            y: 1000,
            width: 1536,
            height: 960,
        };
        let p = efficient(desktop, outer, outer, true).unwrap();
        assert!(
            p.width <= 4096
                && p.height <= 2160
                && u32::from(p.width) * u32::from(p.height) <= 3456 * 2160
        );
        assert_eq!((p.tiles[0].width, p.tiles[0].height), (1536, 960));
    }
    #[test]
    fn large_desktops_keep_encoder_and_tile_bounds() {
        for (width, height) in [(32000, 18000), (1280, 16384), (1920, 1200)] {
            let desktop = MonitorRect {
                index: 0,
                x: -200,
                y: 50,
                width,
                height,
            };
            for f in [0.2, 0.99, 1.0] {
                let inner = MonitorRect {
                    index: 0,
                    x: desktop.x,
                    y: desktop.y,
                    width: (width as f64 * f).round() as u32,
                    height: (height as f64 * f).round() as u32,
                };
                let p = plan(desktop, desktop, inner, None).unwrap();
                assert!(
                    p.width <= 8192
                        && p.height <= 8192
                        && u32::from(p.width) * u32::from(p.height) <= 16_777_216
                );
                for t in &p.tiles {
                    assert!(
                        t.width > 0
                            && t.height > 0
                            && t.x + t.width <= p.width
                            && t.y + t.height <= p.height
                    )
                }
            }
        }
    }
    #[test]
    fn dense_and_periphery_keep_density_with_smaller_input() {
        let desktop = MonitorRect {
            index: 0,
            x: 0,
            y: 0,
            width: 5120,
            height: 3200,
        };
        let outer = MonitorRect {
            index: 0,
            x: 512,
            y: 320,
            width: 4096,
            height: 2560,
        };
        for fraction in [0.2, 0.4, 0.7, 0.99, 1.0] {
            let iw = (outer.width as f64 * fraction).round() as u32;
            let ih = (outer.height as f64 * fraction).round() as u32;
            let mut dimensions = None;
            for u in [0.0, 0.2, 0.5, 0.8, 1.0] {
                let inner = MonitorRect {
                    index: 0,
                    x: outer.x + ((outer.width - iw) as f64 * u) as i32,
                    y: outer.y + ((outer.height - ih) as f64 * u) as i32,
                    width: iw,
                    height: ih,
                };
                let p = plan(desktop, outer, inner, None).unwrap();
                if fraction == 0.2 {
                    assert!(u32::from(p.width) * u32::from(p.height) < 4096 * 2560 * 2 / 3)
                }
                if let Some(d) = dimensions {
                    assert_eq!((p.width, p.height), d)
                } else {
                    dimensions = Some((p.width, p.height))
                }
                for (i, t) in p.tiles.iter().enumerate() {
                    assert!(t.x + t.width <= p.width && t.y + t.height <= p.height);
                    if i == 0 {
                        assert_eq!((t.width, t.height), (iw as u16, ih as u16))
                    }
                    for b in p.tiles.iter().skip(i + 1) {
                        assert!(
                            t.x + t.width <= b.x
                                || b.x + b.width <= t.x
                                || t.y + t.height <= b.y
                                || b.y + b.height <= t.y
                        )
                    }
                }
            }
        }
    }
}
