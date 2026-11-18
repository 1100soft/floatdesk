//! One persisted transaction shared by headset controls and the desktop shortcut.
use super::*;
use serde::{Deserialize, Serialize};
use std::{fs::OpenOptions, os::fd::AsRawFd, path::PathBuf};

#[derive(Clone, Debug, Serialize, Deserialize)]
struct SavedOutput {
    name: String,
    geometry: (i32, i32, u32, u32),
    primary: bool,
}
#[derive(Default, Serialize, Deserialize)]
struct SavedLayout {
    disabled: bool,
    outputs: Vec<SavedOutput>,
    priorities: std::collections::BTreeMap<String, u32>,
}
fn state_path() -> Result<PathBuf> {
    let root = std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".local/state")))
        .ok_or_else(|| Error::Msg("No user state directory".into()))?;
    Ok(root.join("floatdesk/virtual-screens.json"))
}
fn load(path: &std::path::Path) -> Result<SavedLayout> {
    match std::fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map_err(|e| Error::Msg(format!("Invalid saved virtual-screen layout: {e}"))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(SavedLayout::default()),
        Err(e) => Err(e.into()),
    }
}
fn save(path: &std::path::Path, layout: &SavedLayout) -> Result<()> {
    use std::io::Write;
    let temporary = path.with_extension("tmp");
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(&temporary)?;
    file.write_all(&serde_json::to_vec_pretty(layout).map_err(|e| Error::Msg(e.to_string()))?)?;
    file.sync_all()?;
    std::fs::rename(temporary, path)?;
    std::fs::File::open(path.parent().unwrap())?.sync_all()?;
    Ok(())
}
pub(super) fn disabled() -> bool {
    state_path()
        .ok()
        .and_then(|p| load(&p).ok())
        .is_some_and(|s| s.disabled)
}
pub(super) fn transaction_lock() -> Result<std::fs::File> {
    let path = state_path()?;
    std::fs::create_dir_all(path.parent().unwrap())?;
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(path.with_extension("lock"))?;
    // Serialize screen mutations from desktop and headset entry points.
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(Error::Msg(
            "A virtual-screen change is already running".into(),
        ));
    }
    Ok(lock)
}
pub fn set_disabled(disable: bool) -> Result<()> {
    let _lock = transaction_lock()?;
    let path = state_path()?;
    let mut saved = load(&path)?;
    let outputs = query()?;
    let plasma = plasma_snapshot(&outputs);
    let mut args = Vec::<String>::new();
    let mut commands = Vec::new();
    let priorities;
    if disable {
        let virtuals: Vec<_> = outputs
            .iter()
            .filter(|o| virtual_name(&o.name) && o.geometry.is_some())
            .collect();
        if virtuals.is_empty() {
            return Ok(());
        } // Never overwrite a restorable snapshot.
        if !outputs
            .iter()
            .any(|o| !virtual_name(&o.name) && o.geometry.is_some())
        {
            return Err(Error::Msg(
                "Keep a physical display enabled before disabling virtual screens".into(),
            ));
        }
        saved = SavedLayout {
            disabled: true,
            outputs: outputs
                .iter()
                .filter_map(|o| {
                    o.geometry.map(|geometry| SavedOutput {
                        name: o.name.clone(),
                        geometry,
                        primary: o.primary,
                    })
                })
                .collect(),
            priorities: plasma.as_ref().map(|(_, p)| p.clone()).unwrap_or_default(),
        };
        // Persist before touching scanout, including on partial desktop-service failure.
        save(&path, &saved)?;
        for output in virtuals {
            args.extend(["--output".into(), output.name.clone(), "--off".into()]);
            commands.push(format!("output.{}.disable", output.name));
        }
        priorities = saved
            .priorities
            .iter()
            .filter(|(n, _)| !virtual_name(n))
            .map(|(n, p)| (n.clone(), *p))
            .collect();
    } else {
        if !saved.disabled {
            return Ok(());
        }
        // Resolve every output/mode before making any change. Never silently restore a subset.
        for row in &saved.outputs {
            let output = outputs
                .iter()
                .find(|o| o.name == row.name)
                .ok_or_else(|| Error::Msg(format!("Saved output {} is unavailable", row.name)))?;
            let (x, y, w, h) = row.geometry;
            if virtual_name(&row.name) {
                let width =
                    u16::try_from(w).map_err(|_| Error::Msg("Saved width is invalid".into()))?;
                let height =
                    u16::try_from(h).map_err(|_| Error::Msg("Saved height is invalid".into()))?;
                let mode = lowest_mode(output, width, height).ok_or_else(|| {
                    Error::Msg(format!("Saved mode {w}x{h} is unavailable on {}", row.name))
                })?;
                args.extend([
                    "--output".into(),
                    row.name.clone(),
                    "--mode".into(),
                    mode.0.clone(),
                    "--rate".into(),
                    mode.3.to_string(),
                    "--pos".into(),
                    format!("{x}x{y}"),
                ]);
                commands.push(format!("output.{}.enable", row.name));
                if let Some((snapshot, _)) = &plasma {
                    let mode_id = plasma_mode_id(snapshot, &row.name, width, height, mode.3)?;
                    commands.push(format!("output.{}.mode.{mode_id}", row.name));
                }
            } else if output.geometry.is_some() {
                args.extend([
                    "--output".into(),
                    row.name.clone(),
                    "--pos".into(),
                    format!("{x}x{y}"),
                ]);
            } else {
                return Err(Error::Msg(format!(
                    "Saved physical output {} is disconnected",
                    row.name
                )));
            }
            commands.push(format!("output.{}.position.{x},{y}", row.name));
            if row.primary {
                args.extend(["--output".into(), row.name.clone(), "--primary".into()]);
            }
        }
        priorities = saved.priorities.clone();
    }
    if plasma.is_some() {
        plasma_transaction(commands, priorities)?;
    } else {
        let reply = Command::new("xrandr").args(args).output()?;
        if !reply.status.success() {
            return Err(Error::Msg(format!(
                "Virtual-screen change failed: {}",
                String::from_utf8_lossy(&reply.stderr)
            )));
        }
    }
    let now = query()?;
    if disable {
        if now
            .iter()
            .any(|o| virtual_name(&o.name) && o.geometry.is_some())
        {
            return Err(Error::Msg(
                "Some virtual screens are still enabled; saved layout retained".into(),
            ));
        }
    } else {
        for row in saved.outputs.iter().filter(|o| virtual_name(&o.name)) {
            if now
                .iter()
                .find(|o| o.name == row.name)
                .and_then(|o| o.geometry)
                != Some(row.geometry)
            {
                return Err(Error::Msg(format!(
                    "{} was not restored completely; saved layout retained",
                    row.name
                )));
            }
        }
        saved.disabled = false;
        save(&path, &saved)?;
    }
    Ok(())
}
