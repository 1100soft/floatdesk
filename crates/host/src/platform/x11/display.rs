//! RandR 1.5 logical monitors over an enlarged root framebuffer.
//!
//! A monitor object may list no output. That is the documented `none`
//! association: the rectangle exists in the X screen without a GPU connector.
//! The framebuffer is restored from a snapshot on drop, including after a crash
//! on the next `floatdesk restore`.

use super::{state_dir, FrameBuffer, X11Link};
use crate::core::{DisplayBackend, MonitorRect};
use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use x11rb::protocol::randr::{self, ConnectionExt as _, MonitorInfo};
use x11rb::protocol::xproto::ConnectionExt as _;
use x11rb::CURRENT_TIME;

const PREFIX: &str = "Floatdesk-";

#[derive(Debug, Serialize, Deserialize)]
struct ScreenSnapshot {
    width: u16,
    height: u16,
    width_mm: u32,
    height_mm: u32,
}

pub struct X11Display {
    link: X11Link,
    created: Vec<u32>,
    armed: bool,
}

impl X11Display {
    pub fn open() -> Result<Self> {
        Ok(Self {
            link: X11Link::open()?,
            created: Vec::new(),
            armed: false,
        })
    }

    pub fn link(&self) -> &X11Link {
        &self.link
    }

    fn snapshot_path() -> PathBuf {
        state_dir().join("x11-screen.json")
    }

    fn read_snapshot() -> Result<Option<ScreenSnapshot>> {
        let path = Self::snapshot_path();
        if !path.exists() {
            return Ok(None);
        }
        let text = fs::read_to_string(&path)?;
        Ok(Some(serde_json::from_str(&text).map_err(|err| {
            Error::Msg(format!("cannot read {}: {err}", path.display()))
        })?))
    }

    fn write_snapshot(fb: &FrameBuffer) -> Result<()> {
        let path = Self::snapshot_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        if path.exists() {
            return Ok(());
        }
        let snap = ScreenSnapshot {
            width: fb.width,
            height: fb.height,
            width_mm: u32::from(fb.width_mm),
            height_mm: u32::from(fb.height_mm),
        };
        fs::write(&path, serde_json::to_vec_pretty(&snap).unwrap())?;
        Ok(())
    }

    pub fn list_monitors(&self) -> Result<Vec<(String, MonitorInfo)>> {
        let reply = self
            .link
            .conn
            .randr_get_monitors(self.link.root, false)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let mut out = Vec::new();
        for monitor in reply.monitors {
            let name = atom_name(&self.link, monitor.name)?;
            out.push((name, monitor));
        }
        Ok(out)
    }

    pub fn outputs(&self) -> Result<Vec<CrtcBox>> {
        let resources = self
            .link
            .conn
            .randr_get_screen_resources_current(self.link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let mut boxes = Vec::new();
        for crtc in resources.crtcs {
            let info = self
                .link
                .conn
                .randr_get_crtc_info(crtc, CURRENT_TIME)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if info.mode == 0 || info.width == 0 || info.height == 0 {
                continue;
            }
            let (mm_w, mm_h) = info
                .outputs
                .first()
                .copied()
                .map(|output| output_mm(&self.link, output))
                .transpose()?
                .unwrap_or((0, 0));
            boxes.push(CrtcBox {
                x: i32::from(info.x),
                y: i32::from(info.y),
                width: u32::from(info.width),
                height: u32::from(info.height),
                mm_width: mm_w,
                mm_height: mm_h,
            });
        }
        Ok(boxes)
    }
}

impl DisplayBackend for X11Display {
    fn primary(&self) -> Result<MonitorRect> {
        let outputs = self.outputs()?;
        let primary = outputs
            .iter()
            .find(|output| output.x == 0 && output.y == 0)
            .or_else(|| outputs.first())
            .ok_or_else(|| Error::X11("no active CRTC".into()))?;
        Ok(MonitorRect {
            index: 0,
            x: primary.x,
            y: primary.y,
            width: primary.width,
            height: primary.height,
        })
    }

    fn create_virtual(&mut self, count: u8, width: u16, height: u16) -> Result<Vec<MonitorRect>> {
        if !(1..=6).contains(&count) {
            return Err(Error::Msg("monitor count must be 1 to 6".into()));
        }
        if width < 160 || height < 160 || width % 2 != 0 || height % 2 != 0 {
            return Err(Error::Msg(
                "virtual monitors need even width and height of at least 160".into(),
            ));
        }
        if Self::read_snapshot()?.is_some()
            || self
                .list_monitors()?
                .iter()
                .any(|(name, _)| name.starts_with(PREFIX))
        {
            return Err(Error::Msg(
                "a Floatdesk framebuffer snapshot or monitor is already present. Run `floatdesk restore` first."
                    .into(),
            ));
        }

        let range = self
            .link
            .conn
            .randr_get_screen_size_range(self.link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let version = self
            .link
            .conn
            .randr_query_version(1, 5)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        if version.major_version == 0 || (version.major_version == 1 && version.minor_version < 5) {
            return Err(Error::X11(format!(
                "RandR {}.{} is older than 1.5, so logical monitors without outputs are unavailable",
                version.major_version, version.minor_version
            )));
        }

        let fb = self.link.geometry()?;
        let outputs = self.outputs()?;
        let right = outputs
            .iter()
            .map(|output| output.x + output.width as i32)
            .max()
            .unwrap_or(0)
            .max(i32::from(fb.width));
        let origin = right;
        let new_w = origin + i32::from(width) * i32::from(count);
        let new_h = i32::from(fb.height).max(i32::from(height));
        if new_w > i32::from(range.max_width) || new_h > i32::from(range.max_height) {
            return Err(Error::X11(format!(
                "needed framebuffer {new_w}x{new_h} exceeds the server maximum {}x{}",
                range.max_width, range.max_height
            )));
        }
        let new_w =
            u16::try_from(new_w).map_err(|_| Error::X11("framebuffer wider than 65535".into()))?;
        let new_h =
            u16::try_from(new_h).map_err(|_| Error::X11("framebuffer taller than 65535".into()))?;

        let (px_per_mm_x, px_per_mm_y) = dpi_of(&outputs, &fb);
        Self::write_snapshot(&fb)?;
        self.armed = true;

        let new_mm_w = scale_mm(
            u32::from(fb.width_mm),
            u32::from(fb.width),
            u32::from(new_w),
        );
        let new_mm_h = if new_h == fb.height {
            u32::from(fb.height_mm)
        } else {
            scale_mm(
                u32::from(fb.height_mm),
                u32::from(fb.height),
                u32::from(new_h),
            )
        };
        self.link
            .conn
            .randr_set_screen_size(self.link.root, new_w, new_h, new_mm_w, new_mm_h)
            .map_err(Error::x11)?;
        self.link.roundtrip()?;
        let grown = self.link.geometry()?;
        if grown.width < new_w || grown.height < new_h {
            return Err(Error::X11(format!(
                "server left the framebuffer at {}x{} after a request for {new_w}x{new_h}",
                grown.width, grown.height
            )));
        }

        let mut created = Vec::new();
        for index in 0..count {
            let x = origin + i32::from(width) * i32::from(index);
            let x = i16::try_from(x)
                .map_err(|_| Error::X11(format!("monitor origin {x} does not fit in i16")))?;
            let name = format!("{PREFIX}{}", index + 1);
            let atom = self
                .link
                .conn
                .intern_atom(false, name.as_bytes())
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?
                .atom;
            let info = MonitorInfo {
                name: atom,
                primary: false,
                automatic: false,
                x,
                y: 0,
                width,
                height,
                width_in_millimeters: (u32::from(width) as f32 / px_per_mm_x).round().max(1.0)
                    as u32,
                height_in_millimeters: (u32::from(height) as f32 / px_per_mm_y).round().max(1.0)
                    as u32,
                outputs: Vec::new(),
            };
            self.link
                .conn
                .randr_set_monitor(self.link.root, info)
                .map_err(Error::x11)?;
            self.created.push(atom);
            created.push(MonitorRect {
                index,
                x: i32::from(x),
                y: 0,
                width: u32::from(width),
                height: u32::from(height),
            });
        }
        self.link.roundtrip()?;

        let present = self.list_monitors()?;
        let missing = created.iter().any(|rect| {
            let name = rect.name();
            !present.iter().any(|(got, _)| got == &name)
        });
        if missing {
            return Err(Error::X11(
                "RandR accepted the request but did not list the Floatdesk monitors afterwards"
                    .into(),
            ));
        }
        tracing::info!(
            framebuffer = format!("{}x{}", grown.width, grown.height),
            monitors = created.len(),
            "created logical monitors"
        );
        Ok(created)
    }

    fn restore(&mut self) -> Result<()> {
        let monitors = self.list_monitors()?;
        for (name, info) in &monitors {
            if name.starts_with(PREFIX) || self.created.contains(&info.name) {
                self.link
                    .conn
                    .randr_delete_monitor(self.link.root, info.name)
                    .map_err(Error::x11)?;
            }
        }
        self.link.roundtrip().ok();
        self.created.clear();

        let Some(snapshot) = Self::read_snapshot()? else {
            self.armed = false;
            return Ok(());
        };
        self.link
            .conn
            .randr_set_screen_size(
                self.link.root,
                snapshot.width,
                snapshot.height,
                snapshot.width_mm,
                snapshot.height_mm,
            )
            .map_err(Error::x11)?;
        match self.link.roundtrip() {
            Ok(()) => {
                let _ = fs::remove_file(Self::snapshot_path());
                self.armed = false;
                tracing::info!(
                    width = snapshot.width,
                    height = snapshot.height,
                    "restored X framebuffer"
                );
                Ok(())
            }
            Err(err) => Err(Error::X11(format!(
                "deleted Floatdesk monitors but could not restore {}x{}: {err}. The snapshot remains at {}",
                snapshot.width,
                snapshot.height,
                Self::snapshot_path().display()
            ))),
        }
    }
}

impl Drop for X11Display {
    fn drop(&mut self) {
        if self.armed {
            if let Err(err) = self.restore() {
                eprintln!(
                    "floatdesk: failed to restore the X screen ({err}). Run `floatdesk restore`."
                );
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct CrtcBox {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub mm_width: u32,
    pub mm_height: u32,
}

fn atom_name(link: &X11Link, atom: u32) -> Result<String> {
    let reply = link
        .conn
        .get_atom_name(atom)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    Ok(String::from_utf8_lossy(&reply.name).into_owned())
}

fn output_mm(link: &X11Link, output: u32) -> Result<(u32, u32)> {
    let info = link
        .conn
        .randr_get_output_info(output, CURRENT_TIME)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    Ok((info.mm_width, info.mm_height))
}

fn dpi_of(outputs: &[CrtcBox], fb: &FrameBuffer) -> (f32, f32) {
    if let Some(output) = outputs
        .iter()
        .find(|output| output.mm_width > 0 && output.mm_height > 0)
    {
        (
            output.width as f32 / output.mm_width as f32,
            output.height as f32 / output.mm_height as f32,
        )
    } else if fb.width_mm > 0 && fb.height_mm > 0 {
        (
            u32::from(fb.width) as f32 / u32::from(fb.width_mm) as f32,
            u32::from(fb.height) as f32 / u32::from(fb.height_mm) as f32,
        )
    } else {
        (96.0 / 25.4, 96.0 / 25.4)
    }
}

fn scale_mm(old_mm: u32, old_px: u32, new_px: u32) -> u32 {
    if old_px == 0 {
        return old_mm.max(1);
    }
    (u64::from(old_mm) * u64::from(new_px) / u64::from(old_px)).max(1) as u32
}

// Silence an unused import if randr is only referenced through the trait.
#[allow(dead_code)]
fn _randr_module() {
    let _ = randr::X11_EXTENSION_NAME;
}
