//! X11/XCB host path: RandR 1.5 logical monitors, MIT-SHM capture, XDamage,
//! and an XFixes cursor that is never burned into the video.

mod capture;
mod cursor;
mod display;
mod doctor;

pub use capture::X11Capture;
pub use cursor::X11Cursor;
pub use display::X11Display;
pub use doctor::doctor_report;

use crate::error::{Error, Result};
use x11rb::connection::Connection;
use x11rb::protocol::xproto::ConnectionExt as _;
use x11rb::rust_connection::RustConnection;

pub struct X11Link {
    pub conn: RustConnection,
    pub screen: usize,
    pub root: u32,
    pub root_depth: u8,
}

impl X11Link {
    pub fn open() -> Result<Self> {
        let (conn, screen) = x11rb::connect(None).map_err(Error::x11)?;
        let root = conn.setup().roots[screen].root;
        let root_depth = conn.setup().roots[screen].root_depth;
        Ok(Self {
            conn,
            screen,
            root,
            root_depth,
        })
    }

    pub fn roundtrip(&self) -> Result<()> {
        self.conn
            .get_input_focus()
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        Ok(())
    }

    pub fn geometry(&self) -> Result<FrameBuffer> {
        let geo = self
            .conn
            .get_geometry(self.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let screen = &self.conn.setup().roots[self.screen];
        Ok(FrameBuffer {
            width: geo.width,
            height: geo.height,
            width_mm: screen.width_in_millimeters,
            height_mm: screen.height_in_millimeters,
        })
    }

    /// Bits per pixel and scanline pad for the root depth.
    pub fn pixel_format(&self) -> Result<PixelFormat> {
        let setup = self.conn.setup();
        let format = setup
            .pixmap_formats
            .iter()
            .find(|format| format.depth == self.root_depth)
            .ok_or_else(|| Error::X11(format!("no pixmap format for depth {}", self.root_depth)))?;
        Ok(PixelFormat {
            depth: self.root_depth,
            bits_per_pixel: format.bits_per_pixel,
            scanline_pad: format.scanline_pad,
        })
    }
}

#[derive(Debug, Clone, Copy)]
pub struct FrameBuffer {
    pub width: u16,
    pub height: u16,
    pub width_mm: u16,
    pub height_mm: u16,
}

#[derive(Debug, Clone, Copy)]
pub struct PixelFormat {
    pub depth: u8,
    pub bits_per_pixel: u8,
    pub scanline_pad: u8,
}

impl PixelFormat {
    pub fn stride(&self, width: u32) -> Result<u32> {
        if self.bits_per_pixel != 32 {
            return Err(Error::X11(format!(
                "root pixmap is {} bpp; this build captures 32 bpp BGRX",
                self.bits_per_pixel
            )));
        }
        let pad = u32::from(self.scanline_pad.max(8));
        let bits = width.saturating_mul(u32::from(self.bits_per_pixel));
        Ok(bits.div_ceil(pad) * pad / 8)
    }
}

pub fn state_dir() -> std::path::PathBuf {
    if let Ok(dir) = std::env::var("XDG_STATE_HOME") {
        if !dir.is_empty() {
            return std::path::PathBuf::from(dir).join("floatdesk");
        }
    }
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
    std::path::PathBuf::from(home).join(".local/state/floatdesk")
}
