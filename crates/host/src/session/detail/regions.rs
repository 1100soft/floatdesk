//! One contiguous visible rectangle plus at most one source-native gaze patch.
use super::atlas::{Plan, Tile};
use crate::core::MonitorRect;
pub(super) const GUTTER: u16 = 4;
pub(super) const PERIPHERAL_SCALE: f64 = 2160. / 3200.;
fn intersection(a: MonitorRect, b: MonitorRect) -> Option<MonitorRect> {
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
fn tile(source: MonitorRect, scale: f64) -> Option<Tile> {
    let w = (source.width as f64 * scale).ceil().max(1.) as u32;
    let h = (source.height as f64 * scale).ceil().max(1.) as u32;
    (w + 2 * u32::from(GUTTER) <= 8192 && h + 2 * u32::from(GUTTER) <= 8192).then_some(Tile {
        source,
        x: GUTTER,
        y: GUTTER,
        width: w as u16,
        height: h as u16,
    })
}
fn allocation(w: u16, h: u16, bound: Option<(u16, u16)>) -> Option<(u16, u16)> {
    let align = |n: u16| ((u32::from(n).div_ceil(64) * 64).max(2)) as u16;
    let valid = |w: u16, h: u16| {
        w <= 8192
            && h <= 8192
            && u32::from(w) * u32::from(h) <= 16_777_216
            && bound.is_none_or(|(bw, bh)| w <= bw && h <= bh)
    };
    if !valid(w, h) {
        return None;
    }
    let (aw, ah) = (align(w), align(h));
    Some(if valid(aw, ah) {
        (aw, ah)
    } else {
        (w.div_ceil(2) * 2, h.div_ceil(2) * 2)
    })
}
fn candidates(
    outer: MonitorRect,
    inner: Option<MonitorRect>,
    scale: f64,
    bound: Option<(u16, u16)>,
) -> Vec<Plan> {
    let reserve = |t: &Tile| {
        (
            t.width.div_ceil(2) * 2 + 2 * GUTTER,
            t.height.div_ceil(2) * 2 + 2 * GUTTER,
        )
    };
    let mut out = Vec::new();
    if let Some(full) = tile(outer, 1.) {
        let (w, h) = reserve(&full);
        if let Some((width, height)) = allocation(w, h, bound) {
            out.push(Plan {
                width,
                height,
                tiles: vec![full],
            })
        }
    }
    let Some(low) = tile(outer, scale) else {
        return out;
    };
    if let Some(high) = inner.and_then(|r| tile(r, 1.)) {
        let (lw, lh) = reserve(&low);
        let (hw, hh) = reserve(&high);
        for vertical in [false, true] {
            let mut low = low;
            let (w, h) = if vertical {
                low.y += hh;
                (lw.max(hw), lh.saturating_add(hh))
            } else {
                low.x += hw;
                (lw.saturating_add(hw), lh.max(hh))
            };
            if let Some((width, height)) = allocation(w, h, bound) {
                out.push(Plan {
                    width,
                    height,
                    tiles: vec![high, low],
                })
            }
        }
    } else {
        let (w, h) = reserve(&low);
        if let Some((width, height)) = allocation(w, h, bound) {
            out.push(Plan {
                width,
                height,
                tiles: vec![low],
            })
        }
    }
    out
}
fn cost(p: &Plan) -> u64 {
    u64::from(p.width) * u64::from(p.height)
}
pub(super) fn plan_with_avc(
    outer: MonitorRect,
    inner: MonitorRect,
    screens: &[MonitorRect],
    scale: f64,
    bound: Option<(u16, u16)>,
    avc_height: u16,
) -> Option<Plan> {
    let visible = screens
        .iter()
        .filter_map(|s| intersection(outer, *s))
        .collect::<Vec<_>>();
    if visible.is_empty() {
        return None;
    }
    let x = visible.iter().map(|r| r.x).min()?;
    let y = visible.iter().map(|r| r.y).min()?;
    let right = visible.iter().map(|r| r.x + r.width as i32).max()?;
    let bottom = visible.iter().map(|r| r.y + r.height as i32).max()?;
    let crop = MonitorRect {
        index: 0,
        x,
        y,
        width: (right - x) as u32,
        height: (bottom - y) as u32,
    };
    let inner = intersection(crop, inner);
    let mut density = scale;
    for _ in 0..12 {
        let plans = candidates(crop, inner, density, bound);
        if let Some(best) = plans.into_iter().min_by_key(|p| {
            (
                cost(p),
                (p.width > 4096 || p.height > avc_height) as u8,
                p.tiles.len(),
            )
        }) {
            return Some(best);
        }
        if bound.is_some() {
            return None;
        }
        density *= 0.85;
    }
    None
}
/// Pixel sampling facts from the frame actually encoded, not requested settings.
pub(super) fn sampling(plan: &Plan) -> (Option<f64>, f64) {
    let native =
        |t: &&Tile| u32::from(t.width) == t.source.width && u32::from(t.height) == t.source.height;
    let background = plan.tiles.iter().find(|t| !native(t));
    let area = |r: MonitorRect| u64::from(r.width) * u64::from(r.height);
    let crop = background
        .map(|t| area(t.source))
        .unwrap_or_else(|| plan.tiles.iter().map(|t| area(t.source)).max().unwrap_or(1));
    let high = plan
        .tiles
        .iter()
        .filter(native)
        .map(|t| area(t.source))
        .sum::<u64>();
    (
        background.map(|t| 100. * f64::from(t.width) * f64::from(t.height) / area(t.source) as f64),
        (100. * high as f64 / crop as f64).clamp(0., 100.),
    )
}
#[cfg(test)]
fn plan(
    outer: MonitorRect,
    inner: MonitorRect,
    screens: &[MonitorRect],
    scale: f64,
    bound: Option<(u16, u16)>,
) -> Option<Plan> {
    plan_with_avc(outer, inner, screens, scale, bound, 2160)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn r(x: i32, y: i32, width: u32, height: u32) -> MonitorRect {
        MonitorRect {
            index: 0,
            x,
            y,
            width,
            height,
        }
    }
    #[test]
    fn reported_background_ratio_and_native_coverage_use_actual_samples() {
        let outer = r(0, 0, 4000, 2000);
        let inner = r(1000, 500, 1000, 1000);
        let p = plan(outer, inner, &[outer], 0.5, None).unwrap();
        let (bg, coverage) = sampling(&p);
        assert_eq!(bg, Some(25.));
        assert_eq!(coverage, 12.5);
        let small = r(0, 0, 1200, 900);
        let p = plan(
            small,
            r(100, 50, 1024, 800),
            &[small],
            PERIPHERAL_SCALE,
            None,
        )
        .unwrap();
        assert_eq!(sampling(&p), (None, 100.));
    }
    #[test]
    fn taller_negotiated_avc_avoids_hevc_for_contiguous_pair() {
        let outer = r(0, 0, 2400, 3600);
        let inner = r(1000, 1000, 1024, 1024);
        let p = plan_with_avc(outer, inner, &[outer], PERIPHERAL_SCALE, None, 4096).unwrap();
        assert!(p.width <= 4096 && p.height <= 4096);
        assert_eq!(p.tiles.len(), 2);
    }
    #[test]
    fn full_native_wins_when_it_costs_less_than_two_layers() {
        let outer = r(0, 0, 1200, 900);
        let inner = r(100, 50, 1024, 800);
        let p = plan(outer, inner, &[outer], PERIPHERAL_SCALE, None).unwrap();
        assert_eq!(p.tiles.len(), 1);
        assert_eq!(p.tiles[0].source, outer);
        assert_eq!(p.tiles[0].width, 1200);
    }
    #[test]
    fn wide_view_uses_only_one_contiguous_periphery_and_one_native_patch() {
        let outer = r(0, 0, 5120, 3200);
        let inner = r(3000, 1000, 1024, 1024);
        let p = plan(outer, inner, &[outer], PERIPHERAL_SCALE, None).unwrap();
        assert_eq!(p.tiles.len(), 2);
        assert_eq!(p.tiles[0].source, inner);
        assert_eq!(p.tiles[1].source, outer);
        assert_eq!((p.tiles[1].width, p.tiles[1].height), (3456, 2160));
        assert_eq!((p.tiles[0].width, p.tiles[0].height), (1024, 1024));
    }
    #[test]
    fn gaze_motion_does_not_rearrange_the_peripheral_image() {
        let outer = r(-100, 50, 5120, 3200);
        let a = plan(
            outer,
            r(500, 500, 1024, 1024),
            &[outer],
            PERIPHERAL_SCALE,
            None,
        )
        .unwrap();
        let b = plan(
            outer,
            r(3000, 1800, 1024, 1024),
            &[outer],
            PERIPHERAL_SCALE,
            None,
        )
        .unwrap();
        assert_eq!((a.width, a.height), (b.width, b.height));
        assert_eq!(a.tiles[1].source, b.tiles[1].source);
        assert_eq!(
            (
                a.tiles[1].x,
                a.tiles[1].y,
                a.tiles[1].width,
                a.tiles[1].height
            ),
            (
                b.tiles[1].x,
                b.tiles[1].y,
                b.tiles[1].width,
                b.tiles[1].height
            )
        );
    }
    #[test]
    fn off_view_screen_changes_do_not_affect_density() {
        let crop = r(0, 0, 2560, 1600);
        let inner = r(500, 300, 1024, 1024);
        let a = plan(
            crop,
            inner,
            &[crop, r(0, 1600, 2560, 1600)],
            PERIPHERAL_SCALE,
            None,
        )
        .unwrap();
        let b = plan(
            crop,
            inner,
            &[crop, r(0, 1600, 2560, 1600), r(0, 3200, 2560, 1600)],
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
}
