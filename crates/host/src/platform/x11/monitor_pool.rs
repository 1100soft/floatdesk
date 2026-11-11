//! Activate pre-provisioned VKMS outputs; never rebuild kernel topology or alter physical modes.
use crate::core::MonitorRect;
use crate::error::{Error, Result};
use std::collections::BTreeSet;
use std::process::Command;

#[derive(Clone, Debug)]
pub struct Output {
    pub name: String,
    pub primary: bool,
    pub geometry: Option<(i32, i32, u32, u32)>,
    pub modes: Vec<(String, u16, u16, f32)>,
}
fn geometry(value: &str) -> Option<(i32, i32, u32, u32)> {
    let (w, rest) = value.split_once('x')?;
    let sign = rest.find(['+', '-'])?;
    let h = &rest[..sign];
    let positions = &rest[sign..];
    let second = positions[1..].find(['+', '-'])? + 1;
    Some((
        positions[..second].parse().ok()?,
        positions[second..].parse().ok()?,
        w.parse().ok()?,
        h.parse().ok()?,
    ))
}
pub fn parse_outputs(text: &str) -> Vec<Output> {
    let mut outputs: Vec<Output> = Vec::new();
    let mut current = None;
    for line in text.lines() {
        let words: Vec<_> = line.split_whitespace().collect();
        if words.len() >= 2 && !line.starts_with(char::is_whitespace) {
            current = None;
            if words[1] == "connected" {
                outputs.push(Output {
                    name: words[0].into(),
                    primary: words.contains(&"primary"),
                    geometry: words.iter().find_map(|v| geometry(v)),
                    modes: Vec::new(),
                });
                current = Some(outputs.len() - 1);
            }
        } else if line.starts_with(char::is_whitespace) && words.len() >= 2 {
            let Some(output) = current.and_then(|index| outputs.get_mut(index)) else {
                continue;
            };
            let dims = words[0].split('_').next().unwrap_or("");
            let Some((w, h)) = dims.split_once('x') else {
                continue;
            };
            let (Ok(w), Ok(h)) = (w.parse::<u16>(), h.parse::<u16>()) else {
                continue;
            };
            for rate in &words[1..] {
                if let Ok(hz) = rate.trim_end_matches(['*', '+']).parse::<f32>() {
                    output.modes.push((words[0].into(), w, h, hz));
                }
            }
        }
    }
    outputs
}
// Sub-Hz scanout can stall Plasma configuration for an entire multi-second flip.
const MIN_INTERACTIVE_REFRESH_HZ: f32 = 1.0;
fn lowest_mode(output: &Output, width: u16, height: u16) -> Option<&(String, u16, u16, f32)> {
    output
        .modes
        .iter()
        .filter(|(_, w, h, hz)| {
            *w == width && *h == height && hz.is_finite() && *hz >= MIN_INTERACTIVE_REFRESH_HZ
        })
        .min_by(|a, b| a.3.total_cmp(&b.3))
}
fn query() -> Result<Vec<Output>> {
    let out = Command::new("xrandr").arg("--current").output()?;
    if !out.status.success() {
        return Err(Error::Msg("Cannot query XRandR outputs".into()));
    }
    Ok(parse_outputs(&String::from_utf8_lossy(&out.stdout)))
}
fn ordered_sources(mut outputs: Vec<Output>) -> Result<Vec<(String, MonitorRect)>> {
    outputs.sort_by_key(|o| (virtual_name(&o.name), o.name.clone()));
    let mut result = Vec::new();
    for output in outputs {
        if let Some((x, y, width, height)) = output.geometry {
            if result.len() == 6 {
                break;
            }
            result.push((
                output.name,
                MonitorRect {
                    index: result.len() as u8,
                    x,
                    y,
                    width,
                    height,
                },
            ));
        }
    }
    if result.is_empty() {
        return Err(Error::Msg("No active X11 outputs".into()));
    }
    Ok(result)
}
fn virtual_name(name: &str) -> bool {
    name.strip_prefix("Virtual-").is_some_and(|s| {
        !s.is_empty()
            && s.split('-')
                .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
    })
}
/// One capture rectangle preserves X11's arrangement, including gaps and overlaps.
pub fn desktop_rect(sources: &[(String, MonitorRect)]) -> Result<MonitorRect> {
    let x = sources
        .iter()
        .map(|(_, r)| r.x)
        .min()
        .ok_or_else(|| Error::Msg("No desktop outputs".into()))?;
    let y = sources.iter().map(|(_, r)| r.y).min().unwrap();
    let right = sources
        .iter()
        .map(|(_, r)| i64::from(r.x) + i64::from(r.width))
        .max()
        .unwrap();
    let bottom = sources
        .iter()
        .map(|(_, r)| i64::from(r.y) + i64::from(r.height))
        .max()
        .unwrap();
    let width = u32::try_from(right - i64::from(x))
        .map_err(|_| Error::Msg("Desktop width overflow".into()))?;
    let height = u32::try_from(bottom - i64::from(y))
        .map_err(|_| Error::Msg("Desktop height overflow".into()))?;
    Ok(MonitorRect {
        index: 0,
        x,
        y,
        width,
        height,
    })
}
/// Fit a combined desktop within a practical single H.264 decoder envelope.
pub fn stream_size(rect: MonitorRect) -> (u16, u16) {
    let scale = (4096.0 / f64::from(rect.width))
        .min(2160.0 / f64::from(rect.height))
        .min(1.0);
    (
        ((f64::from(rect.width) * scale).floor() as u16 / 2 * 2).max(2),
        ((f64::from(rect.height) * scale).floor() as u16 / 2 * 2).max(2),
    )
}
/// 0 unknown, 1 virtual, 2 physical, 3 built-in physical. No primary/order inference.
pub fn output_role(name: &str) -> u8 {
    if virtual_name(name) {
        return 1;
    }
    let Ok(entries) = std::fs::read_dir("/sys/class/drm") else {
        return 0;
    };
    for entry in entries.flatten() {
        let full = entry.file_name().to_string_lossy().into_owned();
        let Some((card, connector)) = full.split_once('-') else {
            continue;
        };
        if !card
            .strip_prefix("card")
            .is_some_and(|n| !n.is_empty() && n.bytes().all(|c| c.is_ascii_digit()))
            || (connector != name
                && connector
                    .strip_prefix("HDMI-A-")
                    .map(|n| format!("HDMI-{n}"))
                    .as_deref()
                    != Some(name))
        {
            continue;
        }
        let driver = std::fs::canonicalize(format!("/sys/class/drm/{card}/device/driver")).ok();
        let driver = driver
            .as_ref()
            .and_then(|p| p.file_name())
            .and_then(|s| s.to_str())
            .unwrap_or("");
        if matches!(driver, "vkms" | "evdi") {
            return 1;
        }
        if driver.is_empty() {
            return 0;
        }
        if ["eDP-", "LVDS-", "DSI-"]
            .iter()
            .any(|p| name.starts_with(p))
        {
            return 3;
        }
        return 2;
    }
    0
}
fn plasma_snapshot(
    outputs: &[Output],
) -> Option<(serde_json::Value, std::collections::BTreeMap<String, u32>)> {
    let mut result = std::collections::BTreeMap::new();
    let Ok(reply) = Command::new("timeout")
        .args(["1", "kscreen-doctor", "-j"])
        .output()
    else {
        return None;
    };
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(&reply.stdout) else {
        return None;
    };
    let Some(rows) = value["outputs"].as_array() else {
        return None;
    };
    let active: BTreeSet<_> = outputs
        .iter()
        .filter(|o| o.geometry.is_some())
        .map(|o| o.name.as_str())
        .collect();
    let reported: BTreeSet<_> = rows
        .iter()
        .filter(|o| o["enabled"].as_bool() == Some(true))
        .filter_map(|o| o["name"].as_str())
        .collect();
    // Never address another X session's KScreen daemon (e.g. private Xvfb checks).
    if active != reported {
        return None;
    }
    for output in outputs.iter().filter(|o| o.geometry.is_some()) {
        let Some(row) = rows
            .iter()
            .find(|r| r["name"].as_str() == Some(output.name.as_str()))
        else {
            return None;
        };
        let (x, y, w, h) = output.geometry.unwrap();
        if row["pos"]["x"].as_i64() != Some(i64::from(x))
            || row["pos"]["y"].as_i64() != Some(i64::from(y))
            || row["size"]["width"].as_u64() != Some(u64::from(w))
            || row["size"]["height"].as_u64() != Some(u64::from(h))
        {
            return None;
        }
    }
    for row in rows {
        if let (Some(name), Some(priority)) = (row["name"].as_str(), row["priority"].as_u64()) {
            if active.contains(name) && (1..=100).contains(&priority) {
                result.insert(name.into(), priority as u32);
            }
        }
    }
    Some((value, result))
}
fn plasma_transaction(
    mut commands: Vec<String>,
    priorities: std::collections::BTreeMap<String, u32>,
) -> Result<()> {
    let mut ordered: Vec<_> = priorities.into_iter().collect();
    ordered.sort_by_key(|(_, p)| *p);
    commands.extend(
        ordered
            .into_iter()
            .enumerate()
            .map(|(i, (n, _))| format!("output.{n}.priority.{}", i + 1)),
    );
    let result = Command::new("timeout")
        .args(["3", "kscreen-doctor"])
        .args(commands)
        .output()?;
    if !result.status.success() {
        return Err(Error::Msg(format!(
            "Plasma rejected screen change: {}",
            String::from_utf8_lossy(&result.stderr).trim()
        )));
    }
    Ok(())
}
pub struct MonitorPool {
    owned: BTreeSet<String>,
}
impl MonitorPool {
    pub fn new() -> Self {
        Self {
            owned: BTreeSet::new(),
        }
    }
    pub fn observer() -> Self {
        Self {
            owned: BTreeSet::new(),
        }
    }
    pub fn sources(&self) -> Result<Vec<(String, MonitorRect)>> {
        ordered_sources(query()?)
    }
    pub fn identity(&self, sources: &[(String, MonitorRect)]) -> (Vec<String>, Vec<u8>, u8) {
        let primary = query()
            .ok()
            .and_then(|rows| rows.into_iter().find(|o| o.primary))
            .map(|o| o.name);
        let names = sources.iter().map(|(n, _)| n.clone()).collect();
        let roles = sources.iter().map(|(n, _)| output_role(n)).collect();
        let mask = sources.iter().fold(0, |m, (n, r)| {
            m | if Some(n) == primary.as_ref() {
                1 << r.index
            } else {
                0
            }
        });
        (names, roles, mask)
    }
    pub fn capacity(&self) -> u8 {
        query()
            .map(|o| {
                o.iter()
                    .filter(|o| virtual_name(&o.name) && o.geometry.is_none())
                    .count()
                    .min(5) as u8
            })
            .unwrap_or(0)
    }
    pub fn owned_mask(&self, sources: &[(String, MonitorRect)]) -> u8 {
        sources.iter().fold(0, |mask, (name, r)| {
            mask | if self.owned.contains(name) {
                1 << r.index
            } else {
                0
            }
        })
    }
    /// Arrangement editing changes position only, including explicitly dragged physical screens.
    fn move_output(
        &self,
        index: u8,
        x: i16,
        y: i16,
        sources: &[(String, MonitorRect)],
    ) -> Result<()> {
        if x < 0 || y < 0 {
            return Err(Error::Msg(
                "Screen positions must be nonnegative; move the other screen to make space.".into(),
            ));
        }
        let (name, rect) = sources
            .get(usize::from(index))
            .ok_or_else(|| Error::Msg("Unknown screen".into()))?;
        if i32::from(x) + rect.width as i32 > 16384 || i32::from(y) + rect.height as i32 > 16384 {
            return Err(Error::Msg(
                "Arrangement exceeds the supported 16384-pixel desktop bounds".into(),
            ));
        }
        let outputs = query()?;
        let output = outputs
            .iter()
            .find(|o| o.name == *name)
            .ok_or_else(|| Error::Msg("Output changed; refresh the arrangement".into()))?;
        if let Some((_, priorities)) = plasma_snapshot(&outputs) {
            return plasma_transaction(
                vec![format!("output.{}.position.{x},{y}", output.name)],
                priorities,
            );
        }
        let mut args = vec![
            "--output".into(),
            output.name.clone(),
            "--pos".into(),
            format!("{x}x{y}"),
        ];
        if let Some(primary) = outputs.iter().find(|o| o.primary && o.geometry.is_some()) {
            args.extend(["--output".into(), primary.name.clone(), "--primary".into()]);
        }
        let result = Command::new("xrandr").args(args).output()?;
        if !result.status.success() {
            return Err(Error::Msg(format!(
                "Cannot move screen: {}",
                String::from_utf8_lossy(&result.stderr).trim()
            )));
        }
        Ok(())
    }
    pub fn apply(
        &mut self,
        action: u8,
        index: u8,
        width: u16,
        height: u16,
        sources: &[(String, MonitorRect)],
    ) -> Result<()> {
        if action == 4 {
            return self.move_output(index, width as i16, height as i16, sources);
        }
        let outputs = query()?;
        let output = if action == 1 {
            if sources.len() >= 6 {
                return Err(Error::Msg("At most six screens can be streamed".into()));
            }
            outputs
                .iter()
                .find(|o| virtual_name(&o.name) && o.geometry.is_none())
                .ok_or_else(|| {
                    Error::Msg(
                        "No unused VKMS outputs. Provision a larger pool before Xorg starts."
                            .into(),
                    )
                })?
        } else {
            let name = &sources
                .iter()
                .find(|(_, r)| r.index == index)
                .ok_or_else(|| Error::Msg("Unknown screen".into()))?
                .0;
            if !self.owned.contains(name) {
                return Err(Error::Msg(
                    "Only displays added by Floatdesk can be changed or removed".into(),
                ));
            }
            outputs
                .iter()
                .find(|o| &o.name == name)
                .ok_or_else(|| Error::Msg("Output disappeared".into()))?
        };
        if let Some((snapshot, mut priorities)) = plasma_snapshot(&outputs) {
            let mut commands = Vec::new();
            if action == 2 {
                commands.push(format!("output.{}.disable", output.name));
                priorities.remove(&output.name);
            } else {
                let selected = lowest_mode(output, width, height)
                    .ok_or_else(|| Error::Msg(format!("{width}x{height} is unavailable")))?;
                let mode = snapshot["outputs"]
                    .as_array()
                    .and_then(|rows| {
                        rows.iter()
                            .find(|r| r["name"].as_str() == Some(output.name.as_str()))
                    })
                    .and_then(|row| row["modes"].as_array())
                    .and_then(|modes| {
                        modes
                            .iter()
                            .filter(|m| {
                                m["size"]["width"].as_u64() == Some(u64::from(width))
                                    && m["size"]["height"].as_u64() == Some(u64::from(height))
                            })
                            .min_by(|a, b| {
                                (a["refreshRate"].as_f64().unwrap_or(1e9) - f64::from(selected.3))
                                    .abs()
                                    .total_cmp(
                                        &(b["refreshRate"].as_f64().unwrap_or(1e9)
                                            - f64::from(selected.3))
                                        .abs(),
                                    )
                            })
                    })
                    .filter(|m| {
                        (m["refreshRate"].as_f64().unwrap_or(1e9) - f64::from(selected.3)).abs()
                            < 0.1
                    })
                    .and_then(|m| m["id"].as_str())
                    .ok_or_else(|| {
                        Error::Msg("Requested mode is not available in Plasma".into())
                    })?;
                let (x, y) = output.geometry.map(|g| (g.0, g.1)).unwrap_or((
                    outputs
                        .iter()
                        .filter_map(|o| o.geometry.map(|g| g.0 + g.2 as i32))
                        .max()
                        .unwrap_or(0),
                    0,
                ));
                commands.extend([
                    format!("output.{}.mode.{mode}", output.name),
                    format!("output.{}.enable", output.name),
                    format!("output.{}.position.{x},{y}", output.name),
                ]);
                if !priorities.contains_key(&output.name) {
                    priorities.insert(
                        output.name.clone(),
                        priorities.values().max().copied().unwrap_or(0) + 1,
                    );
                }
            }
            // Claim a newly inactive output before the transaction: a timed-out
            // desktop service can have applied part of the requested configuration.
            if action == 1 {
                self.owned.insert(output.name.clone());
            }
            plasma_transaction(commands, priorities)?;
            if action == 2 {
                self.owned.remove(&output.name);
            } else {
                self.owned.insert(output.name.clone());
            }
            return Ok(());
        }
        let mut args = vec!["--output".to_string(), output.name.clone()];
        if action == 2 {
            args.push("--off".into());
        } else {
            let mode = lowest_mode(output, width, height).ok_or_else(|| {
                Error::Msg(format!(
                    "{width}x{height} at a usable refresh rate is not provisioned"
                ))
            })?;
            let (x, y) = output.geometry.map(|g| (g.0, g.1)).unwrap_or_else(|| {
                (
                    outputs
                        .iter()
                        .filter_map(|o| o.geometry.map(|g| g.0 + g.2 as i32))
                        .max()
                        .unwrap_or(0),
                    0,
                )
            });
            args.extend([
                "--mode".into(),
                mode.0.clone(),
                "--rate".into(),
                format!("{}", mode.3),
                "--pos".into(),
                format!("{x}x{y}"),
            ]);
        }
        if let Some(primary) = outputs
            .iter()
            .find(|o| o.primary && (action != 2 || o.name != output.name))
        {
            args.extend(["--output".into(), primary.name.clone(), "--primary".into()]);
        }
        // Preserve the user's arrangement. Newly added outputs start to the right;
        // resize/removal must not repack screens placed with the arrangement editor.
        let result = Command::new("xrandr").args(&args).output()?;
        if !result.status.success() {
            // XRandR can apply part of a multi-output request before reporting failure.
            // Do not strand a newly created output outside this session's ownership.
            if action == 1 {
                let _ = Command::new("xrandr")
                    .args(["--output", &output.name, "--off"])
                    .output();
            }
            return Err(Error::Msg(format!(
                "XRandR rejected the display change: {}",
                String::from_utf8_lossy(&result.stderr).trim()
            )));
        }
        if action == 2 {
            self.owned.remove(&output.name);
        } else {
            self.owned.insert(output.name.clone());
        }
        Ok(())
    }
}
impl Drop for MonitorPool {
    fn drop(&mut self) {
        if self.owned.is_empty() {
            return;
        }
        let outputs = query().unwrap_or_default();
        if let Some((_, mut priorities)) = plasma_snapshot(&outputs) {
            for name in &self.owned {
                priorities.remove(name);
            }
            let commands = self
                .owned
                .iter()
                .map(|n| format!("output.{n}.disable"))
                .collect();
            if plasma_transaction(commands, priorities).is_ok() {
                return;
            }
        }
        let mut args = Vec::new();
        for name in &self.owned {
            args.extend(["--output".into(), name.clone(), "--off".into()]);
        }
        let remaining: Vec<_> = outputs
            .iter()
            .filter(|o| o.geometry.is_some() && !self.owned.contains(&o.name))
            .collect();
        if let Some(primary) = remaining
            .iter()
            .find(|o| o.primary)
            .or_else(|| remaining.first())
        {
            args.extend(["--output".into(), primary.name.clone(), "--primary".into()]);
        }
        let _ = Command::new("xrandr").args(args).output();
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identities_and_order_do_not_follow_primary_or_position() {
        let mut rows = parse_outputs(
            "Virtual-1-1 connected primary 1920x1080+0+0\neDP-1 connected 2560x1600+1920+0\n",
        );
        let before = ordered_sources(rows.clone()).unwrap();
        rows[0].primary = false;
        rows[1].primary = true;
        assert_eq!(ordered_sources(rows).unwrap(), before);
        assert_eq!(before[0].0, "eDP-1");
        assert_eq!(before[1].0, "Virtual-1-1");
        assert_eq!(output_role("Virtual-1-1"), 1);
    }
    #[test]
    fn lowest_responsive_refresh_excludes_sub_hz() {
        let output=parse_outputs("Virtual-1-1 connected\n   1920x1080_60 60.00\n   1920x1080_0.1 0.10\n   1920x1080_1 1.00\n   1280x720_0.1 0.10\n").remove(0);
        assert_eq!(lowest_mode(&output, 1920, 1080).unwrap().3, 1.0);
        assert!(lowest_mode(&output, 2560, 1600).is_none());
        assert!(lowest_mode(&output, 1280, 720).is_none());
    }
    #[test]
    fn combines_arrangement_and_bounds_stream_size() {
        let rect = |index, x, y, width, height| MonitorRect {
            index,
            x,
            y,
            width,
            height,
        };
        let side = vec![
            ("a".into(), rect(0, 0, 0, 1920, 1080)),
            ("b".into(), rect(1, 1920, 0, 1920, 1080)),
        ];
        assert_eq!(desktop_rect(&side).unwrap(), rect(0, 0, 0, 3840, 1080));
        assert_eq!(stream_size(desktop_rect(&side).unwrap()), (3840, 1080));
        let offset = vec![
            ("a".into(), rect(0, -1920, -1080, 1920, 1080)),
            ("b".into(), rect(1, 0, 0, 2560, 1600)),
        ];
        assert_eq!(
            desktop_rect(&offset).unwrap(),
            rect(0, -1920, -1080, 4480, 2680)
        );
        let (w, h) = stream_size(desktop_rect(&offset).unwrap());
        assert!(w <= 4096 && h <= 2160 && w % 2 == 0 && h % 2 == 0);
        assert!((f64::from(w) / f64::from(h) - 4480.0 / 2680.0).abs() < 0.003);
        let stacked = vec![
            ("a".into(), rect(0, 0, 0, 1920, 1080)),
            ("b".into(), rect(1, 0, 1080, 1920, 1080)),
        ];
        assert_eq!(desktop_rect(&stacked).unwrap(), rect(0, 0, 0, 1920, 2160));
    }
    #[test]
    fn parses_signed_geometry() {
        assert_eq!(geometry("1920x1080-1920+40"), Some((-1920, 40, 1920, 1080)));
        assert_eq!(geometry("1280x720+2560-720"), Some((2560, -720, 1280, 720)));
        assert_eq!(geometry("1920x1080"), None);
    }
    #[test]
    fn parses_inactive_pool_and_active_geometry() {
        let o=parse_outputs("eDP-1 connected primary 2560x1600+0+0\n   2560x1600 60.00*+\nVirtual-1-1 connected\n   1920x1080_60 59.93\n");
        assert_eq!(o.len(), 2);
        assert_eq!(o[0].geometry, Some((0, 0, 2560, 1600)));
        assert!(o[1].geometry.is_none());
        assert_eq!(o[1].modes[0].1, 1920);
        assert!(virtual_name("Virtual-1-1"));
        assert!(!virtual_name("eDP-1"));
    }
}
