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
fn query() -> Result<Vec<Output>> {
    let out = Command::new("xrandr").arg("--query").output()?;
    if !out.status.success() {
        return Err(Error::Msg("Cannot query XRandR outputs".into()));
    }
    Ok(parse_outputs(&String::from_utf8_lossy(&out.stdout)))
}
fn virtual_name(name: &str) -> bool {
    name.strip_prefix("Virtual-").is_some_and(|s| {
        !s.is_empty()
            && s.split('-')
                .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
    })
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
    pub fn sources(&self) -> Result<Vec<(String, MonitorRect)>> {
        let mut outputs = query()?;
        outputs.sort_by_key(|o| (!o.primary, virtual_name(&o.name), o.name.clone()));
        let mut result = Vec::new();
        for output in outputs {
            if let Some((x, y, width, height)) = output.geometry {
                if result.iter().any(|(_, r): &(String, MonitorRect)| {
                    r.x == x && r.y == y && r.width == width && r.height == height
                }) {
                    continue;
                }
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
        // Keep the existing primary source identity used by saved headset layouts.
        if let Some(first) = result.first_mut() {
            first.0 = "existing-0".into();
        }
        Ok(result)
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
    pub fn apply(
        &mut self,
        action: u8,
        index: u8,
        width: u16,
        height: u16,
        sources: &[(String, MonitorRect)],
    ) -> Result<()> {
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
        let mut args = vec!["--output".to_string(), output.name.clone()];
        if action == 2 {
            args.push("--off".into());
        } else {
            let mode = output
                .modes
                .iter()
                .filter(|(_, w, h, hz)| *w == width && *h == height && *hz >= 25.0 && *hz <= 61.0)
                .min_by(|a, b| (a.3 - 60.0).abs().total_cmp(&(b.3 - 60.0).abs()))
                .ok_or_else(|| {
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
        // Keep app-owned screens adjacent, beyond every unowned output. Never move
        // physical/pre-existing screens, including when a managed resolution grows.
        let mut layout = std::collections::BTreeMap::new();
        for o in &outputs {
            if self.owned.contains(&o.name) {
                if let Some(g) = o.geometry {
                    layout.insert(o.name.clone(), g.2);
                }
            }
        }
        if action == 2 {
            layout.remove(&output.name);
        } else {
            layout.insert(output.name.clone(), u32::from(width));
        }
        let mut right = outputs
            .iter()
            .filter(|o| !self.owned.contains(&o.name))
            .filter_map(|o| o.geometry.map(|g| g.0 + g.2 as i32))
            .max()
            .unwrap_or(0);
        for (name, w) in &layout {
            args.extend([
                "--output".into(),
                name.clone(),
                "--pos".into(),
                format!("{right}x0"),
            ]);
            right += *w as i32;
        }
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
        for name in &self.owned {
            let _ = Command::new("xrandr")
                .args(["--output", name, "--off"])
                .status();
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
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
