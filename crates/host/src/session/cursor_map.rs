//! View-plane cursor topology. Raw physical motion is independent of root-edge clamping.
use serde::Deserialize;
#[derive(Clone, Debug, Deserialize)]
pub(super) struct Surface {
    pub points: [[f64; 2]; 4],
    pub source: [f64; 4],
    pub depth: f64,
}
impl Surface {
    fn map(&self, p: [f64; 2], inverse: bool) -> Option<[f64; 2]> {
        let source = [
            [self.source[0], self.source[1]],
            [self.source[2], self.source[1]],
            [self.source[2], self.source[3]],
            [self.source[0], self.source[3]],
        ];
        let (from, to) = if inverse {
            (&source, &self.points)
        } else {
            (&self.points, &source)
        };
        for [a, b, c] in [[0, 1, 2], [0, 2, 3]] {
            let det = (from[b][1] - from[c][1]) * (from[a][0] - from[c][0])
                + (from[c][0] - from[b][0]) * (from[a][1] - from[c][1]);
            if det.abs() < 1e-12 {
                continue;
            }
            let u = ((from[b][1] - from[c][1]) * (p[0] - from[c][0])
                + (from[c][0] - from[b][0]) * (p[1] - from[c][1]))
                / det;
            let v = ((from[c][1] - from[a][1]) * (p[0] - from[c][0])
                + (from[a][0] - from[c][0]) * (p[1] - from[c][1]))
                / det;
            if u >= -1e-6 && v >= -1e-6 && u + v <= 1.0 + 1e-6 {
                return Some([
                    u * to[a][0] + v * to[b][0] + (1.0 - u - v) * to[c][0],
                    u * to[a][1] + v * to[b][1] + (1.0 - u - v) * to[c][1],
                ]);
            }
        }
        None
    }
    pub fn valid(&self) -> bool {
        self.depth > 0.0
            && self.depth.is_finite()
            && self
                .points
                .iter()
                .flatten()
                .chain(self.source.iter())
                .all(|v| v.is_finite())
            && self.source[2] > self.source[0]
            && self.source[3] > self.source[1]
    }
}
#[derive(Default)]
pub(super) struct Router {
    point: Option<[f64; 2]>,
}
impl Router {
    pub fn advance(
        &mut self,
        surfaces: &[Surface],
        root: [f64; 2],
        delta: [f64; 2],
        scale: [f64; 2],
    ) -> Option<[f64; 2]> {
        if surfaces.is_empty() {
            self.point = None;

            return None;
        }
        let point = self.point.or_else(|| {
            surfaces
                .iter()
                .filter_map(|s| s.map(root, true).map(|p| (s.depth, p)))
                .min_by(|a, b| a.0.total_cmp(&b.0))
                .map(|(_, p)| p)
        })?;
        let proposed = [
            point[0] + delta[0] * scale[0],
            point[1] + delta[1] * scale[1],
        ];
        if let Some((_, target)) = surfaces
            .iter()
            .filter_map(|s| s.map(proposed, false).map(|p| (s.depth, p)))
            .min_by(|a, b| a.0.total_cmp(&b.0))
        {
            self.point = Some(proposed);

            return Some(target);
        }
        // Cross visual gaps in the motion direction, choosing the closest edge.
        let mut nearest: Option<(f64, [f64; 2], [f64; 2])> = None;
        for s in surfaces {
            for edge in 0..4 {
                let a = s.points[edge];
                let b = s.points[(edge + 1) % 4];
                let d = [b[0] - a[0], b[1] - a[1]];
                let n = d[0] * d[0] + d[1] * d[1];
                if n < 1e-12 {
                    continue;
                }
                let t = (((proposed[0] - a[0]) * d[0] + (proposed[1] - a[1]) * d[1]) / n)
                    .clamp(0.0, 1.0);
                let q = [a[0] + t * d[0], a[1] + t * d[1]];
                if (q[0] - proposed[0]) * delta[0] * scale[0]
                    + (q[1] - proposed[1]) * delta[1] * scale[1]
                    <= 0.0
                {
                    continue;
                }
                let distance = (q[0] - proposed[0]).powi(2) + (q[1] - proposed[1]).powi(2);
                if let Some(target) = s.map(q, false) {
                    if nearest.as_ref().is_none_or(|n| distance < n.0) {
                        nearest = Some((distance, q, target));
                    }
                }
            }
        }
        if let Some((_, p, target)) = nearest {
            self.point = Some(p);

            Some(target)
        } else {
            self.point = Some(point);
            None
        }
    }
    pub fn reset(&mut self) {
        self.point = None;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn pane(x: f64, depth: f64, source: [f64; 4]) -> Surface {
        Surface {
            points: [[x, 0.], [x + 1., 0.], [x + 1., 1.], [x, 1.]],
            source,
            depth,
        }
    }
    #[test]
    fn front_occlusion_and_gap_crossing() {
        let surfaces = vec![
            pane(0., 2., [0., 0., 100., 100.]),
            pane(0.4, 1., [200., 0., 300., 100.]),
            pane(2., 0.5, [400., 0., 500., 100.]),
        ];
        let mut r = Router::default();
        assert_eq!(
            r.advance(&surfaces, [20., 50.], [30., 0.], [0.01, 0.01]),
            Some([210., 50.])
        );
        let p = r
            .advance(&surfaces, [210., 50.], [110., 0.], [0.01, 0.01])
            .unwrap();
        assert!(p[0] >= 400., "{p:?}");
    }
    #[test]
    fn invalid_maps_rejected() {
        assert!(!pane(0., f64::NAN, [0., 0., 1., 1.]).valid());
    }
}
