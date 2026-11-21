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
            // XI 2.0 withholds root raw events while any client holds a pointer grab,
            // including the implicit grab of every button drag; 2.1+ always delivers them.
            link.conn
                .xinput_xi_query_version(2, 2)
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

#[cfg(test)]
mod tests {
    use super::*;
    use x11rb::protocol::xtest::ConnectionExt as _;

    fn moved(cursor: &mut X11Cursor, link: &X11Link, steps: i16) -> ([f64; 2], (i16, i16), (i16, i16)) {
        let start = link.conn.query_pointer(link.root).unwrap().reply().unwrap();
        let _ = cursor.poll().unwrap();
        cursor.take_motion();
        let mut total = [0.0, 0.0];
        for _ in 0..steps {
            link.conn
                .xtest_fake_input(x11rb::protocol::xproto::MOTION_NOTIFY_EVENT, 1, 0, link.root, 4, 0, 0)
                .unwrap()
                .check()
                .unwrap();
            std::thread::sleep(std::time::Duration::from_millis(15));
            let _ = cursor.poll().unwrap();
            let m = cursor.take_motion();
            total[0] += m[0];
            total[1] += m[1];
        }
        let end = link.conn.query_pointer(link.root).unwrap().reply().unwrap();
        (total, (start.root_x, start.root_y), (end.root_x, end.root_y))
    }

    #[test]
    #[ignore = "live X session probe"]
    fn raw_motion_survives_another_clients_implicit_grab() {
        let link = X11Link::open().unwrap();
        let mut cursor = X11Cursor::open().unwrap();
        link.move_pointer(300, 300).unwrap();
        let mut term = std::process::Command::new("xterm").args(["-geometry", "80x24+200+200"]).spawn().unwrap();
        std::thread::sleep(std::time::Duration::from_millis(1500));
        link.move_pointer(300, 300).unwrap();
        let free = moved(&mut cursor, &link, 10);
        link.conn.xtest_fake_input(x11rb::protocol::xproto::BUTTON_PRESS_EVENT, 1, 0, link.root, 0, 0, 0).unwrap().check().unwrap();
        std::thread::sleep(std::time::Duration::from_millis(100));
        let grabbed = moved(&mut cursor, &link, 10);
        link.conn.xtest_fake_input(x11rb::protocol::xproto::BUTTON_RELEASE_EVENT, 1, 0, link.root, 0, 0, 0).unwrap().check().unwrap();
        let _ = term.kill();
        eprintln!("free={free:?} grabbed={grabbed:?}");
        assert!(free.0[0] > 0.0, "no raw motion without a grab");
        assert!(grabbed.0[0] > 0.0, "raw motion withheld during another client's grab");
    }
}
