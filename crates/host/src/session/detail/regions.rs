//! Layout-independent density; pack only visible screen pixels, once each.
use super::atlas::{Plan, Tile};
use crate::core::MonitorRect;
pub(super) const MAX_TILES: usize = 32;
pub(super) const PERIPHERAL_SCALE: f64 = 2160. / 3200.;
fn intersect(a: MonitorRect, b: MonitorRect) -> Option<MonitorRect> {
    let x = a.x.max(b.x);
    let y = a.y.max(b.y);
    let r = (a.x as i64 + a.width as i64).min(b.x as i64 + b.width as i64);
    let bottom = (a.y as i64 + a.height as i64).min(b.y as i64 + b.height as i64);
    (r > x as i64 && bottom > y as i64).then_some(MonitorRect {
        index: 0,
        x,
        y,
        width: (r - x as i64).max(0) as u32,
        height: (bottom - y as i64).max(0) as u32,
    })
}
fn pieces(
    outer: MonitorRect,
    inner: MonitorRect,
    screens: &[MonitorRect],
    scale: f64,
    chunk: (u16, u16),
) -> Vec<Tile> {
    let mut out = Vec::new();
    for high in [true, false] {
        let regions = if high {
            vec![inner]
        } else {
            super::packed_regions(outer, inner)
                .into_iter()
                .skip(1)
                .collect()
        };
        for r in regions {
            for screen in screens {
                if let Some(r) = intersect(r, *screen) {
                    let density = if high { 1. } else { scale };
                    // Bounded chunks allow wide/tall source regions to fit an ordinary AVC atlas.
                    let step_x = (f64::from(chunk.0) / density).floor().max(1.) as u32;
                    let step_y = (f64::from(chunk.1) / density).floor().max(1.) as u32;
                    for dy in (0..r.height).step_by(step_y as usize) {
                        for dx in (0..r.width).step_by(step_x as usize) {
                            let source = MonitorRect {
                                index: 0,
                                x: r.x + dx as i32,
                                y: r.y + dy as i32,
                                width: step_x.min(r.width - dx),
                                height: step_y.min(r.height - dy),
                            };
                            out.push(Tile {
                                source,
                                x: 0,
                                y: 0,
                                width: (source.width as f64 * density).ceil().max(1.) as u16,
                                height: (source.height as f64 * density).ceil().max(1.) as u16,
                            });
                        }
                    }
                }
            }
        }
    }
    out
}
fn pack(tiles: &[Tile], width: u16, height: u16) -> Option<Plan> {
    #[derive(Clone, Copy, PartialEq)]
    struct Space {
        x: u16,
        y: u16,
        w: u16,
        h: u16,
    }
    let contains = |a: Space, b: Space| {
        a.x <= b.x && a.y <= b.y && a.x + a.w >= b.x + b.w && a.y + a.h >= b.y + b.h
    };
    let mut order = (0..tiles.len()).collect::<Vec<_>>();
    order.sort_by_key(|&i| {
        std::cmp::Reverse((
            tiles[i].width.max(tiles[i].height),
            u32::from(tiles[i].width) * u32::from(tiles[i].height),
        ))
    });
    let mut free = vec![Space {
        x: 0,
        y: 0,
        w: width,
        h: height,
    }];
    let mut placed = tiles.to_vec();
    let mut used_w = 0;
    let mut used_h = 0;
    for i in order {
        let t = &mut placed[i];
        let s = *free
            .iter()
            .filter(|s| s.w >= t.width && s.h >= t.height)
            .min_by_key(|s| {
                (
                    (s.w - t.width).min(s.h - t.height),
                    (s.w - t.width).max(s.h - t.height),
                )
            })?;
        t.x = s.x;
        t.y = s.y;
        used_w = used_w.max(t.x + t.width);
        used_h = used_h.max(t.y + t.height);
        let used = Space {
            x: t.x,
            y: t.y,
            w: t.width,
            h: t.height,
        };
        let mut next = Vec::new();
        for s in free {
            if used.x >= s.x + s.w
                || used.x + used.w <= s.x
                || used.y >= s.y + s.h
                || used.y + used.h <= s.y
            {
                next.push(s);
                continue;
            }
            if used.x > s.x {
                next.push(Space {
                    w: used.x - s.x,
                    ..s
                })
            }
            if used.x + used.w < s.x + s.w {
                next.push(Space {
                    x: used.x + used.w,
                    w: s.x + s.w - used.x - used.w,
                    ..s
                })
            }
            if used.y > s.y {
                next.push(Space {
                    h: used.y - s.y,
                    ..s
                })
            }
            if used.y + used.h < s.y + s.h {
                next.push(Space {
                    y: used.y + used.h,
                    h: s.y + s.h - used.y - used.h,
                    ..s
                })
            }
        }
        free = next
            .iter()
            .enumerate()
            .filter(|(i, a)| {
                !next
                    .iter()
                    .enumerate()
                    .any(|(j, b)| i != &j && contains(*b, **a) && (*a != b || j < *i))
            })
            .map(|(_, s)| *s)
            .collect();
    }
    let bucket = |n: u16| ((u32::from(n).div_ceil(128) * 128).max(2)) as u16;
    Some(Plan {
        width: bucket(used_w).min(width),
        height: bucket(used_h).min(height),
        tiles: placed,
    })
}

fn chunked_plan(
    outer: MonitorRect,
    inner: MonitorRect,
    screens: &[MonitorRect],
    scale: f64,
    bound: Option<(u16, u16)>,
    chunk: (u16, u16),
) -> Option<Plan> {
    let tiles = pieces(outer, inner, screens, scale, chunk);
    if tiles.is_empty() {
        return None;
    }
    if tiles.len() > MAX_TILES {
        return None;
    }
    if let Some((w, h)) = bound {
        return pack(&tiles, w, h)
            .filter(|p| u32::from(p.width) * u32::from(p.height) <= 16_777_216);
    }
    let mut candidates = Vec::new();
    for (widths, avc) in [
        (
            &[
                1024u16, 1536, 2048, 2560, 3072, 3328, 3456, 3584, 3712, 3840, 3968, 4096,
            ][..],
            true,
        ),
        (&[2048u16, 3072, 4096, 5120, 6144, 8192][..], false),
    ] {
        for &w in widths {
            let h = if avc {
                2160
            } else {
                (16_777_216u32 / u32::from(w)).min(8192) as u16 / 2 * 2
            };
            if let Some(p) = pack(&tiles, w, h) {
                candidates.push(p)
            }
        }
    }
    candidates.into_iter().min_by_key(|p| {
        (
            (p.width > 4096 || p.height > 2160) as u8,
            u32::from(p.width) * u32::from(p.height),
        )
    })
}
fn preferred_plan(
    outer: MonitorRect,
    inner: MonitorRect,
    screens: &[MonitorRect],
    scale: f64,
    bound: Option<(u16, u16)>,
) -> Option<Plan> {
    [(1024, 1024), (512, 1024), (1024, 512), (768, 768)]
        .into_iter()
        .filter_map(|chunk| chunked_plan(outer, inner, screens, scale, bound, chunk))
        .min_by_key(|p| {
            (
                (p.width > 4096 || p.height > 2160) as u8,
                u32::from(p.width) * u32::from(p.height),
            )
        })
}
/// Only an actual codec-capacity overflow changes sampling; unrelated desktop bounds never do.
pub(super) fn plan(
    outer: MonitorRect,
    inner: MonitorRect,
    screens: &[MonitorRect],
    scale: f64,
    bound: Option<(u16, u16)>,
) -> Option<Plan> {
    let mut native = inner;
    for _ in 0..12 {
        let mut density = scale;
        for _ in 0..10 {
            if let Some(p) = preferred_plan(outer, native, screens, density, bound) {
                return Some(p);
            }
            // An existing allocation that no longer fits must grow, not silently lose quality.
            if bound.is_some() {
                return None;
            }
            density *= 0.85;
        }
        let w = (native.width as f64 * 0.85).floor().max(1.) as u32;
        let h = (native.height as f64 * 0.85).floor().max(1.) as u32;
        native = MonitorRect {
            index: 0,
            x: native.x + (native.width - w) as i32 / 2,
            y: native.y + (native.height - h) as i32 / 2,
            width: w,
            height: h,
        };
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    fn rect(x: i32, y: i32, w: u32, h: u32) -> MonitorRect {
        MonitorRect {
            index: 0,
            x,
            y,
            width: w,
            height: h,
        }
    }
    #[test]
    fn representative_full_view_stays_on_avc() {
        let outer = rect(0, 0, 5120, 3200);
        let inner = rect(3072, 1088, 1024, 1024);
        let p = plan(outer, inner, &[outer], PERIPHERAL_SCALE, None).unwrap();
        println!(
            "full-view atlas {}x{}, {} tiles",
            p.width,
            p.height,
            p.tiles.len()
        );
        assert!(p.width <= 4096 && p.height <= 2160);
    }
    #[test]
    fn unrelated_desktop_height_does_not_change_samples() {
        let outer = rect(0, 0, 2560, 1600);
        let inner = rect(500, 300, 1024, 1024);
        let a = plan(
            outer,
            inner,
            &[outer, rect(0, 1600, 2560, 1600)],
            PERIPHERAL_SCALE,
            None,
        )
        .unwrap();
        let b = plan(
            outer,
            inner,
            &[outer, rect(0, 1600, 2560, 1600), rect(0, 3200, 2560, 1600)],
            PERIPHERAL_SCALE,
            None,
        )
        .unwrap();
        assert_eq!((a.width, a.height), (b.width, b.height));
        for (a, b) in a.tiles.iter().zip(&b.tiles) {
            assert_eq!(a.source, b.source);
            assert_eq!((a.width, a.height), (b.width, b.height))
        }
    }
    #[test]
    fn skips_holes_and_duplicate_detail_with_exact_coverage() {
        let screens = [rect(-100, 50, 1600, 1000), rect(1500, 550, 1600, 1000)];
        let outer = rect(-100, 50, 3200, 1500);
        let inner = rect(1000, 300, 1000, 900);
        let p = plan(outer, inner, &screens, PERIPHERAL_SCALE, None).unwrap();
        assert!(p.tiles.len() <= MAX_TILES);
        let mut total = 0u64;
        for (i, t) in p.tiles.iter().enumerate() {
            total += u64::from(t.source.width) * u64::from(t.source.height);
            assert!(screens
                .iter()
                .any(|s| intersect(t.source, *s) == Some(t.source)));
            let high = intersect(t.source, inner) == Some(t.source);
            let scale = if high { 1. } else { PERIPHERAL_SCALE };
            assert_eq!(t.width, (t.source.width as f64 * scale).ceil() as u16);
            assert_eq!(t.height, (t.source.height as f64 * scale).ceil() as u16);
            for b in p.tiles.iter().skip(i + 1) {
                assert!(intersect(t.source, b.source).is_none());
                assert!(
                    t.x + t.width <= b.x
                        || b.x + b.width <= t.x
                        || t.y + t.height <= b.y
                        || b.y + b.height <= t.y
                );
            }
        }
        assert_eq!(total, 3_200_000);
        assert!(plan(
            outer,
            inner,
            &screens,
            PERIPHERAL_SCALE,
            Some((p.width, p.height))
        )
        .is_some());
    }
}
