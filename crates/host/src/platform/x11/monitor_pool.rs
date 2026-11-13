//! Activate pre-provisioned VKMS outputs; never rebuild kernel topology or alter physical modes.
use crate::core::MonitorRect;
use crate::error::{Error, Result};
use std::collections::BTreeSet;
use std::process::Command;

#[derive(Clone, Debug)]
pub struct Output {
    pub name: String,
    pub primary: bool,
    pub quarter_turn: bool,
    pub current_rate: Option<f32>,
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
                    current_rate: None,
                    primary: words.contains(&"primary"),
                    quarter_turn: words
                        .iter()
                        .take_while(|w| !w.starts_with('('))
                        .any(|w| matches!(*w, "left" | "right")),
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
                    if rate.contains('*') {
                        output.current_rate = Some(hz);
                    }
                    output.modes.push((words[0].into(), w, h, hz));
                }
            }
        }
    }
    outputs
}
// Always honor the lowest advertised positive rate; configuration runs off the control loop.
const MIN_INTERACTIVE_REFRESH_HZ: f32 = 0.0;
fn lowest_mode(output: &Output, width: u16, height: u16) -> Option<&(String, u16, u16, f32)> {
    output
        .modes
        .iter()
        .filter(|(_, w, h, hz)| {
            *w == width && *h == height && hz.is_finite() && *hz > MIN_INTERACTIVE_REFRESH_HZ
        })
        .min_by(|a, b| a.3.total_cmp(&b.3))
}
fn desktop_limits() -> (u32, u32) {
    let text = Command::new("xrandr")
        .arg("--current")
        .output()
        .ok()
        .map(|r| String::from_utf8_lossy(&r.stdout).into_owned())
        .unwrap_or_default();
    let words: Vec<_> = text
        .lines()
        .next()
        .unwrap_or("")
        .split_whitespace()
        .collect();
    words
        .iter()
        .position(|w| *w == "maximum")
        .and_then(|i| {
            Some((
                words.get(i + 1)?.parse().ok()?,
                words.get(i + 3)?.parse().ok()?,
            ))
        })
        .unwrap_or((32767, 32767))
}
pub(super) fn query() -> Result<Vec<Output>> {
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
            result.push((
                output.name,
                MonitorRect {
                    index: u8::try_from(result.len()).unwrap_or(u8::MAX),
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
pub(super) fn virtual_name(name: &str) -> bool {
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
fn reachable_screens(rects: &[MonitorRect], root: usize) -> Vec<bool> {
    let mut reached = vec![false; rects.len()];
    if rects.is_empty() {
        return reached;
    }
    let root = root.min(rects.len() - 1);
    reached[root] = true;
    let mut pending = vec![root];
    while let Some(i) = pending.pop() {
        for j in 0..rects.len() {
            let a = rects[i];
            let b = rects[j];
            let dx = (i64::from(a.x) + i64::from(a.width)).min(i64::from(b.x) + i64::from(b.width))
                - i64::from(a.x.max(b.x));
            let dy = (i64::from(a.y) + i64::from(a.height))
                .min(i64::from(b.y) + i64::from(b.height))
                - i64::from(a.y.max(b.y));
            if !reached[j] && ((dx > 0 && dy >= 0) || (dy > 0 && dx >= 0)) {
                reached[j] = true;
                pending.push(j);
            }
        }
    }
    reached
}
fn validate_connectivity(
    outputs: &[Output],
    name: &str,
    placement: Option<(i32, i32, u32, u32)>,
) -> Result<()> {
    let before: Vec<_> = outputs
        .iter()
        .filter_map(|o| {
            o.geometry.map(|g| {
                (
                    o.name.as_str(),
                    MonitorRect {
                        index: 0,
                        x: g.0,
                        y: g.1,
                        width: g.2,
                        height: g.3,
                    },
                )
            })
        })
        .collect();
    let primary = outputs
        .iter()
        .find(|o| o.primary && o.geometry.is_some())
        .map(|o| o.name.as_str())
        .or_else(|| before.first().map(|(n, _)| *n));
    let mut after: Vec<_> = before.iter().filter(|(n, _)| *n != name).copied().collect();
    if let Some((x, y, width, height)) = placement {
        after.push((
            name,
            MonitorRect {
                index: 0,
                x,
                y,
                width,
                height,
            },
        ));
    }
    if after.is_empty() {
        return Err(Error::Msg("Keep at least one active screen".into()));
    }
    for (i, (_, a)) in after.iter().enumerate() {
        for (_, b) in after.iter().skip(i + 1) {
            if (i64::from(a.x) + i64::from(a.width)).min(i64::from(b.x) + i64::from(b.width))
                > i64::from(a.x.max(b.x))
                && (i64::from(a.y) + i64::from(a.height)).min(i64::from(b.y) + i64::from(b.height))
                    > i64::from(a.y.max(b.y))
            {
                return Err(Error::Msg(
                    "Screens cannot overlap. Place them against a shared edge.".into(),
                ));
            }
        }
    }
    let count = |items: &[(&str, MonitorRect)]| {
        let root = items
            .iter()
            .position(|(n, _)| Some(*n) == primary)
            .unwrap_or(0);
        reachable_screens(&items.iter().map(|(_, r)| *r).collect::<Vec<_>>(), root)
            .into_iter()
            .filter(|v| !*v)
            .count()
    };
    let orphaned = count(&after);
    let previous = count(&before);
    if orphaned == 0
        || (primary != Some(name) || placement.is_some()) && previous > 0 && orphaned < previous
    {
        return Ok(());
    }
    Err(Error::Msg("Screens must stay connected to the primary through shared edges. This change would leave a disconnected screen.".into()))
}
// X11 needs nonnegative positions: moving past top/left translates the entire
// proposed layout in one transaction, preserving relative placement and priority.
fn moved_positions(
    outputs: &[Output],
    name: &str,
    x: i32,
    y: i32,
    limits: (u32, u32),
) -> Result<Vec<(String, i32, i32)>> {
    let placed: Vec<_> = outputs
        .iter()
        .filter_map(|o| {
            o.geometry.map(|g| {
                (
                    o.name.clone(),
                    if o.name == name { x } else { g.0 },
                    if o.name == name { y } else { g.1 },
                    g.2,
                    g.3,
                )
            })
        })
        .collect();
    let left = placed.iter().map(|r| r.1).min().unwrap_or(0).min(0);
    let top = placed.iter().map(|r| r.2).min().unwrap_or(0).min(0);
    if placed.iter().any(|r| {
        i64::from(r.1) - i64::from(left) + i64::from(r.3) > i64::from(limits.0)
            || i64::from(r.2) - i64::from(top) + i64::from(r.4) > i64::from(limits.1)
    }) {
        return Err(Error::Msg(
            "Arrangement exceeds the X server’s framebuffer dimensions".into(),
        ));
    }
    Ok(placed
        .into_iter()
        .map(|(n, x, y, _, _)| (n, x - left, y - top))
        .collect())
}
fn resize_layout(
    outputs: &[Output],
    name: &str,
    width: u32,
    height: u32,
    limits: (u32, u32),
) -> Result<Vec<Output>> {
    let mut proposed = outputs.to_vec();
    let target = proposed
        .iter_mut()
        .find(|o| o.name == name)
        .ok_or_else(|| Error::Msg("Unknown screen".into()))?;
    let old = target
        .geometry
        .ok_or_else(|| Error::Msg("Screen is inactive".into()))?;
    target.geometry = Some((old.0, old.1, width, height));
    if validate_connectivity(&proposed, name, target_geometry(&proposed, name)).is_ok() {
        return Ok(proposed);
    }
    let active: Vec<_> = proposed
        .iter()
        .enumerate()
        .filter(|(_, o)| o.geometry.is_some())
        .map(|(i, _)| i)
        .collect();
    let root = active
        .iter()
        .copied()
        .find(|i| proposed[*i].primary)
        .unwrap_or(active[0]);
    let mut placed = vec![root];
    let mut remaining: Vec<_> = active.into_iter().filter(|i| *i != root).collect();
    fn touch(a: (i32, i32, u32, u32), b: (i32, i32, u32, u32)) -> bool {
        let dx = (a.0 + i32::try_from(a.2).unwrap()).min(b.0 + b.2 as i32) - a.0.max(b.0);
        let dy = (a.1 + a.3 as i32).min(b.1 + b.3 as i32) - a.1.max(b.1);
        (dx == 0 && dy > 0) || (dy == 0 && dx > 0)
    }
    fn overlap(a: (i32, i32, u32, u32), b: (i32, i32, u32, u32)) -> bool {
        (a.0 + a.2 as i32).min(b.0 + b.2 as i32) > a.0.max(b.0)
            && (a.1 + a.3 as i32).min(b.1 + b.3 as i32) > a.1.max(b.1)
    }
    while !remaining.is_empty() {
        let which = remaining
            .iter()
            .position(|i| {
                placed
                    .iter()
                    .any(|j| touch(outputs[*i].geometry.unwrap(), outputs[*j].geometry.unwrap()))
            })
            .unwrap_or(0);
        let i = remaining.remove(which);
        let (ox, oy, w, h) = proposed[i].geometry.unwrap();
        let mut best = None;
        for j in &placed {
            let a = proposed[*j].geometry.unwrap();
            let olda = outputs[*j].geometry.unwrap();
            let oldb = outputs[i].geometry.unwrap();
            let x_values = [
                ox,
                a.0 - w as i32,
                a.0 + a.2 as i32,
                a.0,
                a.0 + a.2 as i32 - w as i32,
                a.0 + (a.2 as i32 - w as i32) / 2,
            ];
            let y_values = [
                oy,
                a.1 - h as i32,
                a.1 + a.3 as i32,
                a.1,
                a.1 + a.3 as i32 - h as i32,
                a.1 + (a.3 as i32 - h as i32) / 2,
            ];
            for x in x_values {
                for y in y_values {
                    let b = (x, y, w, h);
                    if !touch(a, b)
                        || placed
                            .iter()
                            .any(|k| overlap(proposed[*k].geometry.unwrap(), b))
                    {
                        continue;
                    }
                    let left = placed
                        .iter()
                        .map(|k| proposed[*k].geometry.unwrap().0)
                        .chain([x])
                        .min()
                        .unwrap()
                        .min(0);
                    let top = placed
                        .iter()
                        .map(|k| proposed[*k].geometry.unwrap().1)
                        .chain([y])
                        .min()
                        .unwrap()
                        .min(0);
                    let right = placed
                        .iter()
                        .map(|k| {
                            let g = proposed[*k].geometry.unwrap();
                            g.0 + g.2 as i32
                        })
                        .chain([x + w as i32])
                        .max()
                        .unwrap();
                    let bottom = placed
                        .iter()
                        .map(|k| {
                            let g = proposed[*k].geometry.unwrap();
                            g.1 + g.3 as i32
                        })
                        .chain([y + h as i32])
                        .max()
                        .unwrap();
                    if right - left > limits.0 as i32 || bottom - top > limits.1 as i32 {
                        continue;
                    }
                    let cost = i64::from((x - ox).abs())
                        + i64::from((y - oy).abs())
                        + if touch(olda, oldb) { 0 } else { 1_000_000 };
                    if best
                        .as_ref()
                        .is_none_or(|(previous, _, _)| cost < *previous)
                    {
                        best = Some((cost, x, y));
                    }
                }
            }
        }
        let (_, x, y) = best
            .ok_or_else(|| Error::Msg("Resolution cannot fit the available framebuffer".into()))?;
        proposed[i].geometry = Some((x, y, w, h));
        placed.push(i);
    }
    let positions = moved_positions(
        &proposed,
        name,
        proposed
            .iter()
            .find(|o| o.name == name)
            .unwrap()
            .geometry
            .unwrap()
            .0,
        proposed
            .iter()
            .find(|o| o.name == name)
            .unwrap()
            .geometry
            .unwrap()
            .1,
        limits,
    )?;
    for (n, x, y) in positions {
        let row = proposed.iter_mut().find(|o| o.name == n).unwrap();
        let g = row.geometry.unwrap();
        row.geometry = Some((x, y, g.2, g.3));
    }
    validate_connectivity(&proposed, name, target_geometry(&proposed, name))?;
    Ok(proposed)
}
fn target_geometry(outputs: &[Output], name: &str) -> Option<(i32, i32, u32, u32)> {
    outputs
        .iter()
        .find(|o| o.name == name)
        .and_then(|o| o.geometry)
}

fn verify_resolution(name: &str, expected: (u32, u32)) -> Result<()> {
    let current = query()?
        .into_iter()
        .find(|o| o.name == name)
        .and_then(|o| o.geometry)
        .map(|g| (g.2, g.3));
    if current != Some(expected) {
        return Err(Error::Msg(format!(
            "Display did not apply {}×{}; current size is {:?}",
            expected.0, expected.1, current
        )));
    }
    Ok(())
}
fn added_position(outputs: &[Output]) -> (i32, i32) {
    outputs
        .iter()
        .filter_map(|o| o.geometry)
        .max_by_key(|g| (i64::from(g.0) + i64::from(g.2), std::cmp::Reverse(g.1)))
        .map(|g| (g.0 + g.2 as i32, g.1))
        .unwrap_or((0, 0))
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
        .args(["120", "kscreen-doctor"])
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
fn resume_outputs(names: &str, outputs: &[Output]) -> BTreeSet<String> {
    names
        .split(',')
        .filter(|name| {
            outputs
                .iter()
                .any(|o| o.name == *name && virtual_name(name) && o.geometry.is_some())
        })
        .map(str::to_owned)
        .collect()
}
pub struct MonitorPool {
    owned: BTreeSet<String>,
}
impl MonitorPool {
    pub fn new() -> Self {
        // One-time explicit ownership transfer for a host upgrade that retains scanout.
        // Worker placeholders must never adopt the same outputs a second time.
        static RESUMED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        let owned = if !RESUMED.swap(true, std::sync::atomic::Ordering::Relaxed) {
            std::env::var("FLOATDESK_RESUME_OUTPUTS")
                .ok()
                .map(|names| resume_outputs(&names, &query().unwrap_or_default()))
                .unwrap_or_default()
        } else {
            BTreeSet::new()
        };
        Self { owned }
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
        let sources = &sources[..sources.len().min(6)];
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
                    .min(255) as u8
            })
            .unwrap_or(0)
    }
    pub fn workspace_info(&self, request_id: u32, busy: bool, fps: u32) -> serde_json::Value {
        let outputs = query().unwrap_or_default();
        let screens:Vec<_>=outputs.iter().filter_map(|o|o.geometry.map(|(x,y,w,h)| {
            let modes: BTreeSet<_>=o.modes.iter().filter(|m|m.3.is_finite() && m.3>0.0).map(|m|(m.1,m.2)).collect();
            serde_json::json!({"name":o.name,"x":x,"y":y,"width":w,"height":h,"refreshHz":o.current_rate,"managed":self.owned.contains(&o.name),"primary":o.primary,"role":output_role(&o.name),"modes":modes})
        })).collect();
        let available = outputs
            .iter()
            .filter(|o| virtual_name(&o.name) && o.geometry.is_none())
            .count();
        let mem = std::fs::read_to_string("/proc/meminfo")
            .unwrap_or_default()
            .lines()
            .find(|l| l.starts_with("MemAvailable:"))
            .and_then(|l| l.split_whitespace().nth(1))
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(0)
            * 1024;
        let cpus = std::thread::available_parallelism().map_or(1, |n| n.get());
        let estimated = if mem > 0 {
            (mem / 128).min(cpus as u64 * 1_000_000 * 60 / u64::from(fps.max(1)))
        } else {
            0
        };
        let modes: BTreeSet<_> = outputs
            .iter()
            .filter(|o| virtual_name(&o.name))
            .flat_map(|o| o.modes.iter().filter(|m| m.3 > 0.0).map(|m| (m.1, m.2)))
            .collect();
        let (window_focus, windows) = super::windows::observed_catalogue();
        serde_json::json!({"type":"status","requestId":request_id,"busy":busy,"screens":screens,"windows":windows,"windowFocus":window_focus,"available":available,"modes":modes,"availableMemoryBytes":mem,"cpuThreads":cpus,"recommendedPixels":estimated,"targetFps":fps,"estimate":"Rough planning estimate: available memory / 128 bytes per desktop pixel and 1 megapixel per CPU thread at 60 FPS. Advisory only; GPU, content and network can dominate.","desktopLimits":desktop_limits(),"setupRequired":!outputs.iter().any(|o|virtual_name(&o.name))})
    }
    pub fn owned_mask(&self, sources: &[(String, MonitorRect)]) -> u8 {
        sources.iter().take(6).fold(0, |mask, (name, r)| {
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
        index: usize,
        x: i16,
        y: i16,
        sources: &[(String, MonitorRect)],
    ) -> Result<()> {
        let (name, _) = sources
            .get(index)
            .ok_or_else(|| Error::Msg("Unknown screen".into()))?;
        let outputs = query()?;
        let output = outputs
            .iter()
            .find(|o| o.name == *name)
            .ok_or_else(|| Error::Msg("Output changed; refresh the arrangement".into()))?;
        let current = output
            .geometry
            .ok_or_else(|| Error::Msg("Screen is no longer active".into()))?;
        validate_connectivity(
            &outputs,
            &output.name,
            Some((i32::from(x), i32::from(y), current.2, current.3)),
        )?;
        let positions =
            moved_positions(&outputs, name, i32::from(x), i32::from(y), desktop_limits())?;
        if super::arrange::move_positions(&positions)? {
            return Ok(());
        }
        if let Some((_, priorities)) = plasma_snapshot(&outputs) {
            return plasma_transaction(
                positions
                    .iter()
                    .map(|(n, x, y)| format!("output.{n}.position.{x},{y}"))
                    .collect(),
                priorities,
            );
        }
        let mut args = Vec::new();
        for (n, x, y) in positions {
            args.extend(["--output".into(), n, "--pos".into(), format!("{x}x{y}")]);
        }
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
        index: usize,
        width: u16,
        height: u16,
        sources: &[(String, MonitorRect)],
    ) -> Result<()> {
        if action == 4 {
            return self.move_output(index, width as i16, height as i16, sources);
        }
        let mut outputs = query()?;
        if action == 1 || action == 3 {
            let candidate = if action == 1 {
                outputs
                    .iter()
                    .find(|o| virtual_name(&o.name) && o.geometry.is_none())
            } else {
                sources.get(index).and_then(|(n, _)| {
                    outputs
                        .iter()
                        .find(|o| o.name == *n && self.owned.contains(n))
                })
            };
            if candidate.is_some_and(|o| lowest_mode(o, width, height).is_none()) {
                let script = std::env::var_os("FLOATDESK_DATA_DIR")
                    .map(std::path::PathBuf::from)
                    .unwrap_or_else(|| {
                        std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
                    })
                    .join("scripts/vkms/modes.py");
                let status = Command::new("python3")
                    .arg(script)
                    .args(["--resolution", &width.to_string(), &height.to_string()])
                    .status()?;
                if !status.success() {
                    return Err(Error::Msg("Could not provision requested X11 mode".into()));
                }
                outputs = query()?;
            }
        }
        let output = if action == 1 {
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
                .get(index)
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
        let dimensions = if output.quarter_turn {
            (u32::from(height), u32::from(width))
        } else {
            (u32::from(width), u32::from(height))
        };
        let planned = if action == 3 {
            Some(resize_layout(
                &outputs,
                &output.name,
                dimensions.0,
                dimensions.1,
                desktop_limits(),
            )?)
        } else {
            None
        };
        let position = planned
            .as_ref()
            .and_then(|p| target_geometry(p, &output.name))
            .or(output.geometry)
            .map(|g| (g.0, g.1))
            .unwrap_or_else(|| added_position(&outputs));
        if planned.is_none() {
            validate_connectivity(
                &outputs,
                &output.name,
                if action == 2 {
                    None
                } else {
                    Some((position.0, position.1, dimensions.0, dimensions.1))
                },
            )?;
        }
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
                let (x, y) = position;
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
            if let Some(layout) = &planned {
                for row in layout.iter().filter(|o| o.name != output.name) {
                    if let Some((x, y, _, _)) = row.geometry {
                        commands.push(format!("output.{}.position.{x},{y}", row.name));
                    }
                }
            }
            // Claim a newly inactive output before the transaction: a timed-out
            // desktop service can have applied part of the requested configuration.
            if action == 1 {
                self.owned.insert(output.name.clone());
            }
            plasma_transaction(commands, priorities)?;
            if action != 2 {
                verify_resolution(&output.name, dimensions)?;
            }
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
            let (x, y) = position;
            args.extend([
                "--mode".into(),
                mode.0.clone(),
                "--rate".into(),
                format!("{}", mode.3),
                "--pos".into(),
                format!("{x}x{y}"),
            ]);
        }
        if let Some(layout) = &planned {
            for row in layout.iter().filter(|o| o.name != output.name) {
                if let Some((x, y, _, _)) = row.geometry {
                    args.extend([
                        "--output".into(),
                        row.name.clone(),
                        "--pos".into(),
                        format!("{x}x{y}"),
                    ]);
                }
            }
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
            verify_resolution(&output.name, dimensions)?;
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
    fn explicit_resume_only_adopts_active_virtual_outputs() {
        let rows=parse_outputs("eDP-1 connected primary 100x100+0+0\nVirtual-1-1 connected 100x100+100+0\nVirtual-1-2 connected\n");
        assert_eq!(
            resume_outputs("eDP-1,Virtual-1-1,Virtual-1-2,unknown", &rows),
            BTreeSet::from(["Virtual-1-1".into()])
        );
    }
    #[test]
    fn rejects_orphans_corner_contacts_and_bridge_removal() {
        let rows=parse_outputs("eDP-1 connected primary 100x100+0+0\nVirtual-1-1 connected 100x100+100+0\nVirtual-1-2 connected 100x100+200+0\n");
        assert!(validate_connectivity(&rows, "Virtual-1-1", None).is_err());
        assert!(validate_connectivity(&rows, "Virtual-1-2", Some((201, 0, 100, 100))).is_err());
        assert!(validate_connectivity(&rows, "Virtual-1-2", Some((200, 100, 100, 100))).is_err());
        assert!(validate_connectivity(&rows, "Virtual-1-2", Some((0, 100, 100, 100))).is_ok());
        assert!(validate_connectivity(&rows, "Virtual-1-2", None).is_ok());
        assert!(validate_connectivity(&rows, "Virtual-1-1", Some((100, 0, 90, 100))).is_err());
        assert!(validate_connectivity(&rows, "Virtual-1-1", Some((90, 0, 120, 100))).is_err());
    }
    #[test]
    fn add_attaches_to_actual_rightmost_screen_and_invalid_layout_can_be_repaired() {
        let rows = parse_outputs(
            "eDP-1 connected primary 100x100+0+0\nVirtual-1-1 connected 100x100+100+80\n",
        );
        assert_eq!(added_position(&rows), (200, 80));
        assert!(validate_connectivity(&rows, "Virtual-1-2", Some((200, 80, 100, 100))).is_ok());
        let invalid=parse_outputs("eDP-1 connected primary 100x100+0+0\nVirtual-1-1 connected 100x100+200+0\nVirtual-1-2 connected 100x100+400+0\n");
        assert!(validate_connectivity(&invalid, "Virtual-1-1", Some((100, 0, 100, 100))).is_ok());
        assert!(
            validate_connectivity(&invalid, "Virtual-1-1", Some((300, 100, 100, 100))).is_err()
        );
    }
    #[test]
    fn rejects_overlap_and_translates_top_left_moves() {
        let rows = parse_outputs(
            "eDP-1 connected primary 100x100+0+0\nVirtual-1-1 connected 100x100+100+0\n",
        );
        assert!(validate_connectivity(&rows, "Virtual-1-1", Some((99, 0, 100, 100))).is_err());
        assert!(validate_connectivity(&rows, "Virtual-1-1", Some((0, 0, 100, 100))).is_err());
        assert!(validate_connectivity(&rows, "Virtual-1-1", Some((-100, 0, 100, 100))).is_ok());
        assert_eq!(
            moved_positions(&rows, "Virtual-1-1", -100, 0, (16384, 16384)).unwrap(),
            vec![("eDP-1".into(), 100, 0), ("Virtual-1-1".into(), 0, 0)]
        );
        assert_eq!(
            moved_positions(&rows, "Virtual-1-1", 0, -100, (16384, 16384)).unwrap(),
            vec![("eDP-1".into(), 0, 100), ("Virtual-1-1".into(), 0, 0)]
        );
        assert!(moved_positions(&rows, "Virtual-1-1", -16384, 0, (16384, 16384)).is_err());
    }
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
    fn resize_reflows_connected_chain_without_overlaps() {
        let rows=parse_outputs("eDP-1 connected primary 100x100+0+0\nVirtual-1-1 connected 100x100+100+0\nVirtual-1-2 connected 100x100+200+0\n");
        for (w, h) in [(50, 50), (200, 200), (150, 50)] {
            let planned = resize_layout(&rows, "Virtual-1-1", w, h, (16384, 16384)).unwrap();
            assert_eq!(
                target_geometry(&planned, "Virtual-1-1").map(|r| (r.2, r.3)),
                Some((w, h))
            );
            assert!(validate_connectivity(
                &planned,
                "Virtual-1-1",
                target_geometry(&planned, "Virtual-1-1")
            )
            .is_ok());
            assert!(planned[0].primary);
        }
    }
    #[test]
    fn lowest_refresh_includes_sub_hz() {
        let output=parse_outputs("Virtual-1-1 connected\n   1920x1080_60 60.00\n   1920x1080_0.1 0.10\n   1920x1080_1 1.00\n   1280x720_0.1 0.10\n").remove(0);
        assert_eq!(lowest_mode(&output, 1920, 1080).unwrap().3, 0.1);
        assert!(lowest_mode(&output, 2560, 1600).is_none());
        assert_eq!(lowest_mode(&output, 1280, 720).unwrap().3, 0.1);
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
