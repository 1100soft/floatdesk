//! X11/XCB host path: RandR 1.5 logical monitors, MIT-SHM capture, XDamage,
//! and an XFixes cursor that is never burned into the video.

mod arrange;
mod capture;
mod cursor;
mod display;
mod doctor;
pub mod monitor_pool;
pub mod windows;

pub use capture::X11Capture;
pub use cursor::X11Cursor;
pub use display::X11Display;
pub use doctor::doctor_report;

use crate::error::{Error, Result};
use x11rb::connection::Connection;
use x11rb::protocol::xproto::ConnectionExt as _;
use x11rb::protocol::xtest::ConnectionExt as _;
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

    pub fn move_pointer(&self, x: i32, y: i32) -> Result<()> {
        let x = i16::try_from(x).map_err(|_| Error::Msg("pointer x outside X11 range".into()))?;
        let y = i16::try_from(y).map_err(|_| Error::Msg("pointer y outside X11 range".into()))?;
        self.conn
            .warp_pointer(x11rb::NONE, self.root, 0, 0, 0, 0, x, y)
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
        self.conn.flush().map_err(Error::x11)
    }

    pub fn mouse_button(&self, button: u8, down: bool) -> Result<()> {
        let event = if down {
            x11rb::protocol::xproto::BUTTON_PRESS_EVENT
        } else {
            x11rb::protocol::xproto::BUTTON_RELEASE_EVENT
        };
        self.conn
            .xtest_fake_input(event, button, 0, self.root, 0, 0, 0)
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
        self.conn.flush().map_err(Error::x11)
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

/// Drag state has a lease, so loss of the headset never leaves a button held indefinitely.
pub struct X11Mouse {
    link: X11Link,
    left_down: bool,
    last_input: std::time::Instant,
}
impl X11Mouse {
    pub fn open() -> Result<Self> {
        Ok(Self {
            link: X11Link::open()?,
            left_down: false,
            last_input: std::time::Instant::now(),
        })
    }
    pub fn move_pointer(&mut self, x: i32, y: i32, left_down: bool) -> Result<()> {
        self.link.move_pointer(x, y)?;
        if self.left_down != left_down {
            self.link.mouse_button(1, left_down)?;
            self.left_down = left_down;
        }
        self.last_input = std::time::Instant::now();
        Ok(())
    }
    pub fn click(&mut self, x: i32, y: i32, button: u8) -> Result<()> {
        if self.left_down {
            tracing::warn!(button, "atomic click ignored while left drag held");
            return Ok(());
        }
        self.link.move_pointer(x, y)?;
        self.link.mouse_button(button, true)?;
        self.link.mouse_button(button, false)
    }
    pub fn release(&mut self) -> Result<()> {
        if self.left_down {
            self.link.mouse_button(1, false)?;
            self.left_down = false;
        }
        Ok(())
    }
    pub fn expire(&mut self) {
        if self.left_down && self.last_input.elapsed() >= std::time::Duration::from_millis(300) {
            if let Err(error) = self.release() {
                tracing::warn!(%error,"could not release timed-out drag");
            }
        }
    }
}
impl Drop for X11Mouse {
    fn drop(&mut self) {
        let _ = self.release();
    }
}

#[cfg(test)]
mod pointer_device_tests {
    use super::*;

    #[test]
    #[ignore = "requires an isolated Xvfb display; run scripts/check-mouse-x11.py"]
    fn buttons_drag_timeout_and_drop_release() {
        assert_eq!(
            std::env::var("FLOATDESK_INPUT_TEST_DISPLAY").as_deref(),
            Ok("1")
        );
        let watcher = X11Link::open().unwrap();
        watcher
            .conn
            .change_window_attributes(
                watcher.root,
                &x11rb::protocol::xproto::ChangeWindowAttributesAux::new().event_mask(
                    x11rb::protocol::xproto::EventMask::BUTTON_PRESS
                        | x11rb::protocol::xproto::EventMask::BUTTON_RELEASE,
                ),
            )
            .unwrap()
            .check()
            .unwrap();
        let held = || {
            watcher
                .conn
                .query_pointer(watcher.root)
                .unwrap()
                .reply()
                .unwrap()
                .mask
                .contains(x11rb::protocol::xproto::KeyButMask::BUTTON1)
        };
        let mut mouse = X11Mouse::open().unwrap();
        mouse.click(120, 160, 1).unwrap();
        mouse.click(120, 160, 2).unwrap();
        watcher.roundtrip().unwrap();
        let mut edges = Vec::new();
        while let Some(event) = watcher.conn.poll_for_event().unwrap() {
            match event {
                x11rb::protocol::Event::ButtonPress(event) => edges.push((event.detail, true)),
                x11rb::protocol::Event::ButtonRelease(event) => edges.push((event.detail, false)),
                _ => {}
            }
        }
        assert_eq!(edges, vec![(1, true), (1, false), (2, true), (2, false)]);
        mouse.move_pointer(200, 200, true).unwrap();
        assert!(held());
        mouse.move_pointer(240, 220, true).unwrap();
        assert!(held());
        mouse.move_pointer(240, 220, false).unwrap();
        assert!(!held());
        mouse.click(250, 230, 3).unwrap();
        let pointer = watcher
            .conn
            .query_pointer(watcher.root)
            .unwrap()
            .reply()
            .unwrap();
        assert_eq!((pointer.root_x, pointer.root_y), (250, 230));
        assert!(!pointer
            .mask
            .contains(x11rb::protocol::xproto::KeyButMask::BUTTON3));
        mouse.move_pointer(300, 240, true).unwrap();
        mouse.last_input = std::time::Instant::now() - std::time::Duration::from_millis(301);
        mouse.expire();
        assert!(!held());
        {
            let mut temporary = X11Mouse::open().unwrap();
            temporary.move_pointer(320, 260, true).unwrap();
            assert!(held());
        }
        assert!(!held());
    }
}

/// Qt's RandR 1.5 path rescans logical monitor geometry on root ConfigureNotify.
/// Moving outputs within an unchanged framebuffer need not generate that event.
/// Publish the final real geometry, never an intermediate suspended-CRTC layout.
pub fn notify_layout_clients() -> Result<()> {
    use x11rb::protocol::xproto::{ConfigureNotifyEvent, EventMask, CONFIGURE_NOTIFY_EVENT};
    let link = X11Link::open()?;
    let geometry = link
        .conn
        .get_geometry(link.root)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    let event = ConfigureNotifyEvent {
        response_type: CONFIGURE_NOTIFY_EVENT,
        event: link.root,
        window: link.root,
        width: geometry.width,
        height: geometry.height,
        ..Default::default()
    };
    link.conn
        .send_event(false, link.root, EventMask::STRUCTURE_NOTIFY, event)
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    link.conn.flush().map_err(Error::x11)
}

#[cfg(test)]
mod notification_checks {
    use super::*;
    #[test]
    #[ignore = "requires a private Xvfb and FLOATDESK_SYNTHETIC_DISPLAY=1"]
    fn unchanged_framebuffer_still_notifies_layout_consumers() {
        use x11rb::protocol::{
            xproto::{ChangeWindowAttributesAux, EventMask},
            Event,
        };
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let listener = X11Link::open().unwrap();
        listener
            .conn
            .change_window_attributes(
                listener.root,
                &ChangeWindowAttributesAux::new().event_mask(EventMask::STRUCTURE_NOTIFY),
            )
            .unwrap()
            .check()
            .unwrap();
        let before = listener
            .conn
            .get_geometry(listener.root)
            .unwrap()
            .reply()
            .unwrap();
        notify_layout_clients().unwrap();
        let Event::ConfigureNotify(event) = listener.conn.wait_for_event().unwrap() else {
            panic!("Missing final layout notification")
        };
        assert_eq!(event.event, listener.root);
        assert_eq!(event.window, listener.root);
        assert_eq!((event.width, event.height), (before.width, before.height));
        assert_ne!(event.response_type & 0x80, 0); // It is a final-state notification, not a modeset.
        let after = listener
            .conn
            .get_geometry(listener.root)
            .unwrap()
            .reply()
            .unwrap();
        assert_eq!((before.width, before.height), (after.width, after.height));
    }
}
