//! Clip a rectangular capture to the union of real output rectangles.
use crate::core::{CapturedFrame, MonitorRect};

pub struct DesktopMask {
    /// Output row and pixel interval to clear. Precomputed once per arrangement.
    gaps: Vec<(usize, usize, usize)>,
}
impl DesktopMask {
    pub fn new(view: MonitorRect, width: u32, height: u32, screens: &[MonitorRect]) -> Self {
        let mut gaps = Vec::new();
        let ceil = |n: i64, d: i64| -(-n).div_euclid(d);
        let edge = |position: i64, origin: i32, pixels: u32, extent: u32| {
            // Half-open rectangle coverage at pixel centres avoids introducing
            // black seams where adjacent screens meet at a fractional pixel.
            ceil(
                2 * (position - i64::from(origin)) * i64::from(pixels) - i64::from(extent),
                2 * i64::from(extent),
            )
            .clamp(0, i64::from(pixels)) as usize
        };
        let projected: Vec<_> = screens
            .iter()
            .filter_map(|r| {
                let left = edge(i64::from(r.x), view.x, width, view.width);
                let right = edge(
                    i64::from(r.x) + i64::from(r.width),
                    view.x,
                    width,
                    view.width,
                );
                let top = edge(i64::from(r.y), view.y, height, view.height);
                let bottom = edge(
                    i64::from(r.y) + i64::from(r.height),
                    view.y,
                    height,
                    view.height,
                );
                (left < right && top < bottom).then_some((left, right, top, bottom))
            })
            .collect();
        for y in 0..height as usize {
            let mut spans: Vec<_> = projected
                .iter()
                .filter(|r| y >= r.2 && y < r.3)
                .map(|r| (r.0, r.1))
                .collect();
            spans.sort_unstable();
            let mut end = 0;
            for (left, right) in spans {
                if left > end {
                    gaps.push((y, end, left));
                }
                end = end.max(right);
            }
            if end < width as usize {
                gaps.push((y, end, width as usize));
            }
        }
        Self { gaps }
    }
    pub fn apply(&self, frame: &mut CapturedFrame) {
        for &(y, left, right) in &self.gaps {
            let start = y * frame.stride as usize + left * 4;
            let end = y * frame.stride as usize + right * 4;
            frame.bgra[start..end].fill(0);
        }
    }
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
    fn frame(w: u32, h: u32) -> CapturedFrame {
        CapturedFrame {
            width: w,
            height: h,
            stride: w * 4 + 8,
            bgra: vec![127; ((w * 4 + 8) * h) as usize],
            monotonic_ns: 0,
            damage_generation: 0,
        }
    }
    #[test]
    fn clips_l_shape_and_keeps_padding_and_outputs() {
        let mut f = frame(8, 6);
        DesktopMask::new(
            rect(100, 200, 8, 6),
            8,
            6,
            &[rect(100, 200, 4, 6), rect(104, 200, 4, 3)],
        )
        .apply(&mut f);
        for y in 0..6 {
            for x in 0..8 {
                let p = y * f.stride as usize + x * 4;
                assert_eq!(f.bgra[p], if y >= 3 && x >= 4 { 0 } else { 127 });
            }
            assert_eq!(f.bgra[y * f.stride as usize + 32], 127);
        }
    }
    #[test]
    fn scaled_union_merges_overlaps_and_updates_same_bounds() {
        let view = rect(-100, 50, 16, 12);
        let mut f = frame(8, 6);
        let screens = [
            rect(-100, 50, 8, 12),
            rect(-96, 50, 12, 6),
            rect(-96, 50, 12, 6),
        ];
        DesktopMask::new(view, 8, 6, &screens).apply(&mut f);
        assert_eq!(f.bgra[5 * f.stride as usize + 7 * 4], 0);
        assert_eq!(f.bgra[2 * f.stride as usize + 7 * 4], 127);
        let mut moved = frame(8, 6);
        DesktopMask::new(view, 8, 6, &[rect(-100, 50, 8, 6), rect(-92, 50, 8, 12)])
            .apply(&mut moved);
        assert_eq!(moved.bgra[5 * moved.stride as usize], 0);
        assert_eq!(moved.bgra[5 * moved.stride as usize + 7 * 4], 127);
    }
    #[test]
    fn fractional_adjacent_edges_have_no_seam_but_real_gaps_are_cleared() {
        let view = rect(0, 0, 10, 6);
        assert!(
            DesktopMask::new(view, 5, 3, &[rect(0, 0, 3, 6), rect(3, 0, 7, 6)])
                .gaps
                .is_empty()
        );
        let mut f = frame(5, 3);
        DesktopMask::new(view, 5, 3, &[rect(0, 0, 3, 6), rect(7, 0, 3, 6)]).apply(&mut f);
        for x in 0..5 {
            assert_eq!(f.bgra[x * 4], if x == 1 || x == 2 { 0 } else { 127 });
        }
    }
    #[test]
    fn full_rectangle_needs_no_clearing() {
        assert!(
            DesktopMask::new(rect(0, 0, 12, 8), 6, 4, &[rect(0, 0, 12, 8)])
                .gaps
                .is_empty()
        );
    }
}
