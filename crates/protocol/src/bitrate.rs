//! Semantic bitrate split. A static monitor should not be funded like the
//! focused one, and a hidden monitor should not be funded at all.

#[derive(Debug, Clone, Copy)]
pub struct MonitorLoad {
    pub index: u8,
    pub focused: bool,
    pub damaged: bool,
    pub visible: bool,
}

/// Split `total_bps` across monitors. The returned vector matches `loads` order.
/// Visible monitors get at least 400 kbit/s when the budget allows.
pub fn allocate_bitrate(total_bps: u32, loads: &[MonitorLoad]) -> Vec<u32> {
    if loads.is_empty() || total_bps == 0 {
        return vec![0; loads.len()];
    }
    let weights: Vec<u32> = loads
        .iter()
        .map(|load| {
            if !load.visible {
                0
            } else {
                let mut weight = 1;
                if load.damaged {
                    weight += 3;
                }
                if load.focused {
                    weight += 3;
                }
                weight
            }
        })
        .collect();
    let sum: u32 = weights.iter().sum();
    if sum == 0 {
        return vec![0; loads.len()];
    }
    const FLOOR: u32 = 400_000;
    let visible = weights.iter().filter(|w| **w > 0).count() as u32;
    let floor_budget = FLOOR.saturating_mul(visible).min(total_bps);
    let floor_each = if visible == 0 {
        0
    } else {
        floor_budget / visible
    };
    let remainder = total_bps.saturating_sub(floor_each.saturating_mul(visible));
    weights
        .into_iter()
        .map(|weight| {
            if weight == 0 {
                0
            } else {
                floor_each + remainder * weight / sum
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn focused_monitor_gets_more_than_a_static_one() {
        let loads = [
            MonitorLoad {
                index: 0,
                focused: true,
                damaged: true,
                visible: true,
            },
            MonitorLoad {
                index: 1,
                focused: false,
                damaged: false,
                visible: true,
            },
            MonitorLoad {
                index: 2,
                focused: false,
                damaged: false,
                visible: false,
            },
        ];
        let bits = allocate_bitrate(12_000_000, &loads);
        assert_eq!(bits.len(), 3);
        assert!(bits[0] > bits[1]);
        assert_eq!(bits[2], 0);
        let sum: u32 = bits.iter().sum();
        assert!(sum <= 12_000_000);
        assert!(12_000_000 - sum < 3);
    }
}
