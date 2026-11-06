//! XFixes cursor image and hotspot. The pointer is not composited into the video.

use super::X11Link;
use crate::core::{CursorBackend, CursorSample};
use crate::error::{Error, Result};
use x11rb::protocol::xfixes::ConnectionExt as _;

pub struct X11Cursor {
    link: X11Link,
    last_serial: u64,
}

impl X11Cursor {
    pub fn open() -> Result<Self> {
        let link = X11Link::open()?;
        link.conn
            .xfixes_query_version(5, 0)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        Ok(Self {
            link,
            last_serial: u64::MAX,
        })
    }
}

impl CursorBackend for X11Cursor {
    fn poll(&mut self) -> Result<CursorSample> {
        let image = self
            .link
            .conn
            .xfixes_get_cursor_image()
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let serial = u64::from(image.cursor_serial);
        let shape_changed = serial != self.last_serial;
        self.last_serial = serial;
        let argb = if shape_changed {
            image.cursor_image.clone()
        } else {
            Vec::new()
        };
        Ok(CursorSample {
            root_x: i32::from(image.x),
            root_y: i32::from(image.y),
            hotspot_x: image.xhot as i16,
            hotspot_y: image.yhot as i16,
            serial,
            width: image.width,
            height: image.height,
            argb,
        })
    }
}
