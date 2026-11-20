//! XFixes cursor image and hotspot for composition into captured frames.
//!
//! Position comes from `QueryPointer`. The cursor image is read only when the
//! shape changes, so a moving pointer does not wait on a full image round-trip.

use super::X11Link;
use crate::core::{CursorBackend, CursorSample};
use crate::error::{Error, Result};
use x11rb::connection::Connection;
use x11rb::protocol::xfixes::{ConnectionExt as _, CursorNotifyMask};
use x11rb::protocol::xproto::ConnectionExt as _;
use x11rb::protocol::Event;

pub struct X11Cursor {
    link: X11Link,
    last_serial: u64,
    motion: [f64; 2],
    shape_pending: bool,
    hotspot_x: i16,
    hotspot_y: i16,
    width: u16,
    height: u16,
}

impl X11Cursor {
    pub fn open() -> Result<Self> {
        let link = X11Link::open()?;
        use x11rb::protocol::xinput::{ConnectionExt as _, EventMask, XIEventMask};
        let motion_setup = (|| -> Result<()> {
            link.conn
                .xinput_xi_query_version(2, 0)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            link.conn
                .xinput_xi_select_events(
                    link.root,
                    &[EventMask {
                        deviceid: 1,
                        mask: vec![XIEventMask::RAW_MOTION],
                    }],
                )
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            Ok(())
        })();
        if let Err(error) = motion_setup {
            tracing::warn!(%error,"Raw mouse routing unavailable; native cursor capture remains active");
        }
        link.conn
            .xfixes_query_version(5, 0)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        link.conn
            .xfixes_select_cursor_input(link.root, CursorNotifyMask::DISPLAY_CURSOR)
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
        Ok(Self {
            link,
            last_serial: u64::MAX,
            motion: [0.0, 0.0],
            shape_pending: true,
            hotspot_x: 0,
            hotspot_y: 0,
            width: 0,
            height: 0,
        })
    }

    pub fn take_motion(&mut self) -> [f64; 2] {
        std::mem::replace(&mut self.motion, [0.0, 0.0])
    }
    pub fn move_to(&self, x: i32, y: i32) -> Result<()> {
        self.link.move_pointer(x, y)
    }
    fn note_shape_events(&mut self) {
        loop {
            match self.link.conn.poll_for_event() {
                Ok(Some(Event::XfixesCursorNotify(notify))) => {
                    if u64::from(notify.cursor_serial) != self.last_serial {
                        self.shape_pending = true;
                    }
                }
                Ok(Some(Event::XinputRawMotion(event))) => {
                    let mut index = 0;
                    for (word, mask) in event.valuator_mask.iter().enumerate() {
                        for bit in 0..32 {
                            if mask & (1 << bit) != 0 {
                                if word * 32 + bit < 2 {
                                    if let Some(v) = event.axisvalues.get(index) {
                                        self.motion[word * 32 + bit] += f64::from(v.integral)
                                            + f64::from(v.frac) / 4294967296.0;
                                    }
                                }
                                index += 1;
                            }
                        }
                    }
                }
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(err) => {
                    tracing::debug!("cursor event poll: {err}");
                    break;
                }
            }
        }
    }
}

impl CursorBackend for X11Cursor {
    fn poll(&mut self) -> Result<CursorSample> {
        self.note_shape_events();
        let pointer = self
            .link
            .conn
            .query_pointer(self.link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let mut argb = Vec::new();
        if self.shape_pending {
            let image = self
                .link
                .conn
                .xfixes_get_cursor_image()
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            let serial = u64::from(image.cursor_serial);
            self.last_serial = serial;
            self.hotspot_x = image.xhot as i16;
            self.hotspot_y = image.yhot as i16;
            self.width = image.width;
            self.height = image.height;
            argb = image.cursor_image;
            self.shape_pending = false;
        }
        Ok(CursorSample {
            root_x: i32::from(pointer.root_x),
            root_y: i32::from(pointer.root_y),
            hotspot_x: self.hotspot_x,
            hotspot_y: self.hotspot_y,
            width: self.width,
            height: self.height,
            argb,
        })
    }
}
