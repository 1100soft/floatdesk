//! View-plane cursor topology. Raw physical motion is independent of root-edge clamping.
use serde::Deserialize;
#[derive(Clone, Debug, Deserialize)]
pub(super) struct Surface {
    #[serde(default)]
    pub key: String,
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
    owner: Option<String>,
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
        let mut point = self.point.or_else(|| {
            surfaces
                .iter()
                .filter_map(|s| s.map(root, true).map(|p| (s.depth, p)))
                .max_by(|a, b| a.0.total_cmp(&b.0))
                .map(|(_, p)| p)
        })?;
        // Content can change the bounds of the owning pane. Clamp within that
        // same space instead of reinterpreting the mirrored OS cursor as input.
        if let Some(key) = self.owner.as_ref() {
            let owned: Vec<_> = surfaces.iter().filter(|s| &s.key == key).collect();
            if !owned.is_empty() && !owned.iter().any(|s| s.map(point, false).is_some()) {
                if let Some(p) = owned
                    .iter()
                    .map(|s| {
                        let left = s.points.iter().map(|p| p[0]).fold(f64::INFINITY, f64::min);
                        let right = s
                            .points
                            .iter()
                            .map(|p| p[0])
                            .fold(f64::NEG_INFINITY, f64::max);
                        let top = s.points.iter().map(|p| p[1]).fold(f64::INFINITY, f64::min);
                        let bottom = s
                            .points
                            .iter()
                            .map(|p| p[1])
                            .fold(f64::NEG_INFINITY, f64::max);
                        [point[0].clamp(left, right), point[1].clamp(top, bottom)]
                    })
                    .min_by(|a, b| {
                        ((a[0] - point[0]).powi(2) + (a[1] - point[1]).powi(2))
                            .total_cmp(&((b[0] - point[0]).powi(2) + (b[1] - point[1]).powi(2)))
                    })
                {
                    point = p;
                    self.point = Some(p);
                }
            }
        }
        if delta == [0.0, 0.0]
            && self
                .owner
                .as_ref()
                .is_some_and(|key| !surfaces.iter().any(|s| &s.key == key))
        {
            return None;
        }
        if delta == [0.0, 0.0] {
            if let Some(key) = self.owner.as_ref() {
                if let Some(root) = surfaces
                    .iter()
                    .filter(|s| &s.key == key)
                    .find_map(|s| s.map(point, false))
                {
                    return Some(root);
                }
            }
        }
        let proposed = [
            point[0] + delta[0] * scale[0],
            point[1] + delta[1] * scale[1],
        ];
        if let Some((surface, target)) = surfaces
            .iter()
            .filter_map(|s| s.map(proposed, false).map(|p| (s, p)))
            .min_by(|a, b| a.0.depth.total_cmp(&b.0.depth))
        {
            self.point = Some(proposed);
            self.owner = Some(surface.key.clone());

            return Some(target);
        }
        // Cross visual gaps in the motion direction, choosing the closest edge.
        let mut nearest: Option<(f64, [f64; 2], [f64; 2], String)> = None;
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
                        nearest = Some((distance, q, target, s.key.clone()));
                    }
                }
            }
        }
        if let Some((_, p, target, owner)) = nearest {
            self.owner = Some(owner);
            self.point = Some(p);

            Some(target)
        } else {
            self.point = Some(point);
            None
        }
    }
    pub fn claim(&mut self, surfaces: &[Surface], key: &str, root: [f64; 2]) -> bool {
        if let Some(point) = surfaces
            .iter()
            .filter(|s| s.key == key)
            .find_map(|s| s.map(root, true))
        {
            self.point = Some(point);
            self.owner = Some(key.to_owned());
            true
        } else {
            false
        }
    }
    pub fn location(&self, surfaces: &[Surface]) -> Option<(String, [f64; 2], [f64; 2])> {
        let point = self.point?;
        let key = self.owner.as_ref()?;
        let surface = surfaces
            .iter()
            .find(|s| &s.key == key && s.map(point, false).is_some())?;
        let root = surface.map(point, false)?;
        Some((
            key.clone(),
            [
                (root[0] - surface.source[0]) / (surface.source[2] - surface.source[0]),
                (root[1] - surface.source[1]) / (surface.source[3] - surface.source[1]),
            ],
            root,
        ))
    }
    pub fn inactive(&mut self, motion: [f64; 2]) {
        // A sleeping viewer does not move the virtual cursor. Real desktop use
        // while XR is inactive deliberately relinquishes the old virtual owner.
        if motion != [0.0, 0.0] {
            self.reset()
        }
    }
    pub fn virtual_position(&self) -> Option<[f64; 2]> {
        self.point
    }
    pub fn has_point(&self) -> bool {
        self.point.is_some()
    }
    pub fn reset(&mut self) {
        self.owner = None;
        self.point = None;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn pane(x: f64, depth: f64, source: [f64; 4]) -> Surface {
        Surface {
            key: format!("pane:{x}:{depth}"),
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
    fn cursor_stays_in_owned_space_when_its_content_changes() {
        let mut surfaces = vec![
            pane(0., 2., [0., 0., 100., 100.]),
            pane(0.4, 1., [200., 0., 300., 100.]),
        ];
        let mut router = Router::default();
        assert_eq!(
            router.advance(&surfaces, [20., 50.], [30., 0.], [0.01, 0.01]),
            Some([210., 50.])
        );
        let (key, uv, _) = router.location(&surfaces).unwrap();
        surfaces[1].source = [500., 100., 700., 300.];
        let root = router
            .advance(&surfaces, [210., 50.], [0., 0.], [0.01, 0.01])
            .unwrap();
        assert!((root[0] - 520.).abs() < 0.00001 && (root[1] - 200.).abs() < 0.00001);
        let (next, local, _) = router.location(&surfaces).unwrap();
        assert_eq!(key, next);
        assert_eq!(uv, local);
    }
    #[test]
    fn explicit_claim_disambiguates_mirrored_sources() {
        let a = pane(0., 2., [0., 0., 100., 100.]);
        let mut b = pane(2., 1., [0., 0., 100., 100.]);
        b.key = "magnifier".into();
        let surfaces = vec![a, b];
        let mut router = Router::default();
        assert!(router.claim(&surfaces, "magnifier", [25., 50.]));
        assert_eq!(router.location(&surfaces).unwrap().0, "magnifier");
        assert_eq!(
            router.advance(&surfaces, [25., 50.], [0., 0.], [0.01, 0.01]),
            Some([25., 50.])
        );
        assert!(!router.claim(&surfaces, "missing", [25., 50.]));
    }
    #[test]
    fn virtual_position_is_authoritative_and_clamps_inside_its_owner() {
        let desktop = Surface {
            key: "desktop".into(),
            points: [[0., 0.], [1000., 0.], [1000., 1000.], [0., 1000.]],
            source: [0., 0., 1000., 1000.],
            depth: 2.,
        };
        let magnifier = Surface {
            key: "magnifier".into(),
            points: [[0., 1000.], [1000., 1000.], [1000., 2000.], [0., 2000.]],
            source: [0., 0., 500., 500.],
            depth: 1.,
        };
        let mut surfaces = vec![desktop, magnifier];
        let mut router = Router::default();
        assert_eq!(
            router.advance(&surfaces, [500., 900.], [0., 600.], [1., 1.]),
            Some([250., 250.])
        );
        assert_eq!(router.virtual_position(), Some([500., 1500.]));
        surfaces[1].source = [100., 200., 1100., 1200.];
        assert_eq!(
            router.advance(&surfaces, [250., 250.], [0., 0.], [1., 1.]),
            Some([600., 700.])
        );
        assert_eq!(router.virtual_position(), Some([500., 1500.]));
        // A reload gap cannot reassign this cursor to a duplicated desktop image.
        assert_eq!(
            router.advance(&surfaces[..1], [600., 700.], [0., 0.], [1., 1.]),
            None
        );
        assert_eq!(router.virtual_position(), Some([500., 1500.]));
        surfaces[1].points = [[0., 1000.], [400., 1000.], [400., 1400.], [0., 1400.]];
        assert_eq!(
            router.advance(&surfaces, [600., 700.], [0., 0.], [1., 1.]),
            Some([1100., 1200.])
        );
        assert_eq!(router.virtual_position(), Some([400., 1400.]));
    }
    #[test]
    fn sleep_retains_virtual_cursor_until_physical_desktop_use() {
        let surfaces = vec![pane(0., 1., [0., 0., 100., 100.])];
        let mut router = Router::default();
        router.claim(&surfaces, &surfaces[0].key, [25., 50.]);
        router.inactive([0., 0.]);
        assert_eq!(router.virtual_position(), Some([0.25, 0.5]));
        router.inactive([1., 0.]);
        assert_eq!(router.virtual_position(), None);
    }
    #[test]
    fn invalid_maps_rejected() {
        assert!(!pane(0., f64::NAN, [0., 0., 1., 1.]).valid());
    }
}
