//! Native source-pixel area and measured-time feedback, independent of viewport percentage.
pub(super) struct Governor {
    pub area: u64,
    requested: u64,
    target: u32,
    ema: f64,
    last: u64,
    samples: u32,
    warm: u32,
    maximum: u64,
    peripheral_factor: f64,
    minimum: u64,
}
impl Governor {
    pub fn new() -> Self {
        Self {
            area: 1_048_576,
            requested: u64::MAX,
            target: 30,
            ema: 0.,
            last: 0,
            samples: 0,
            warm: 3,
            maximum: 4_194_304,
            peripheral_factor: 1.,
            minimum: 65_536,
        }
    }
    pub fn configure(&mut self, requested: u64, target: u32, normal_pixels: u64) {
        self.maximum = (normal_pixels * 3 / 5).clamp(65_536, 4_194_304);
        if requested != self.requested || target != self.target {
            self.area = if requested == 0 {
                1_048_576.min(self.maximum)
            } else {
                requested.min(16_777_216)
            };
            self.requested = requested;
            self.target = target.max(1);
            self.ema = 0.;
            self.samples = 0;
            self.last = 0;
            self.warm = 3;
            self.peripheral_factor = 1.;
        }
    }
    pub fn protect_quality(&mut self, enabled: bool) {
        self.minimum = if enabled {
            1_048_576.min(self.maximum)
        } else {
            65_536
        };
        if self.automatic() {
            self.area = self.area.max(self.minimum);
        }
        if enabled {
            self.peripheral_factor = 1.;
        }
    }
    pub fn automatic(&self) -> bool {
        self.requested == 0
    }
    pub fn periphery(&self, normal_pixels: u64) -> u64 {
        let ceiling = (normal_pixels * 35 / 100).max(65_536);
        if self.automatic() {
            (self.area * 2).clamp(65_536, ceiling)
        } else {
            ceiling
        }
    }
    pub fn peripheral_factor(&self) -> f64 {
        self.peripheral_factor
    }
    pub fn rebuilt(&mut self) {
        self.warm = 3;
    }
    pub fn observe(&mut self, local_ms: f64, feedback_ms: f64, now_ms: u64, whole_native: bool) {
        if !self.automatic() {
            return;
        }
        if self.warm > 0 {
            self.warm -= 1;
            return;
        }
        let measured = local_ms.max(if feedback_ms.is_finite() {
            feedback_ms.clamp(0., 1000.)
        } else {
            0.
        });
        self.ema = if self.ema == 0. {
            measured
        } else {
            self.ema * 0.85 + measured * 0.15
        };
        self.samples += 1;
        if self.last == 0 {
            self.last = now_ms;
            return;
        }
        if now_ms - self.last < 1000 || self.samples < 10 {
            return;
        }
        let budget = 1000. / self.target as f64;
        let next = if self.ema > budget * 1.08 {
            (self.area as f64 * (budget * 0.8 / self.ema).clamp(0.5, 0.9)) as u64
        } else if self.ema < budget * 0.75 && !whole_native {
            (self.area as f64 * 1.18) as u64
        } else {
            self.area
        };
        // Quantized squares and a one-second control period bound codec resizing.
        let side = ((next.clamp(self.minimum, self.maximum) as f64).sqrt() / 64.)
            .round()
            .max(4.) as u64
            * 64;
        if self.minimum == 65_536 && self.ema > budget * 1.08 && self.area <= 65_536 {
            self.peripheral_factor = (self.peripheral_factor
                * (budget * 0.8 / self.ema).sqrt().clamp(0.85, 0.97))
            .max(0.25);
        } else if self.ema < budget * 0.75 && self.peripheral_factor < 1. {
            self.peripheral_factor = (self.peripheral_factor * 1.08).min(1.);
        }
        self.area = (side * side).clamp(self.minimum, self.maximum);
        self.last = now_ms;
        self.samples = 0;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn auto_responds_to_contention_and_does_not_grow_a_fully_native_view() {
        let mut g = Governor::new();
        g.configure(0, 30, 7_464_960);
        for i in 0..100 {
            g.observe(8., 10., i * 40, false)
        }
        assert!(g.area > 1_048_576);
        let high = g.area;
        for i in 100..200 {
            g.observe(50., 60., i * 60, false)
        }
        assert!(g.area < high && g.area >= 65_536);
        g.configure(0, 60, 7_464_960);
        let prior = g.area;
        for i in 0..100 {
            g.observe(2., 3., i * 20, true)
        }
        assert_eq!(g.area, prior);
    }
    #[test]
    fn regional_auto_preserves_useful_detail_and_peripheral_density_under_pressure() {
        let mut g = Governor::new();
        g.configure(0, 30, 4096 * 2160);
        g.protect_quality(true);
        for i in 0..300 {
            g.observe(100., 200., i * 100, false)
        }
        assert_eq!(g.area, 1_048_576);
        assert_eq!(g.peripheral_factor(), 1.);
        g.configure(65_536, 30, 4096 * 2160);
        g.protect_quality(true);
        assert_eq!(g.area, 65_536);
    }
    #[test]
    fn peripheral_density_only_changes_after_native_area_reaches_its_floor() {
        let mut g = Governor::new();
        g.configure(0, 30, 4096 * 2160);
        for i in 0..20 {
            g.observe(100., 100., i * 100, false)
        }
        assert!(g.area > 65_536);
        assert_eq!(g.peripheral_factor(), 1.);
        for i in 20..250 {
            g.observe(100., 100., i * 100, false)
        }
        assert_eq!(g.area, 65_536);
        assert!(g.peripheral_factor() < 1.);
        for i in 250..600 {
            g.observe(2., 2., i * 100, false)
        }
        assert_eq!(g.peripheral_factor(), 1.);
    }
    #[test]
    fn manual_area_does_not_drift_and_fps_is_configurable() {
        let mut g = Governor::new();
        g.configure(2_250_000, 30, 7_464_960);
        for i in 0..100 {
            g.observe(100., 100., i * 100, false)
        }
        assert_eq!(g.area, 2_250_000);
        g.configure(0, 60, 7_464_960);
        for i in 0..100 {
            g.observe(25., 25., i * 25, false)
        }
        assert!(g.area < 1_048_576);
    }
}
