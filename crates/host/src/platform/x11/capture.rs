//! MIT-SHM capture of one rectangle, gated by XDamage.

use super::X11Link;
use crate::core::{CaptureBackend, CapturedFrame, MonitorRect};
use crate::error::{Error, Result};
use std::time::{Duration, Instant};
use x11rb::connection::Connection;
use x11rb::protocol::damage::{self, ConnectionExt as _};
use x11rb::protocol::shm::ConnectionExt as _;
use x11rb::protocol::xproto::ImageFormat;

pub struct X11Capture {
    link: X11Link,
    rect: MonitorRect,
    seg: u32,
    addr: *mut libc::c_void,
    mapping: usize,
    stride: u32,
    damage: u32,
    damage_ok: bool,
    dirty: bool,
    generation: u64,
    last: Option<Instant>,
}

// The SHM mapping and the X connection are used by one capture thread.
unsafe impl Send for X11Capture {}

impl X11Capture {
    pub fn open(rect: MonitorRect) -> Result<Self> {
        let link = X11Link::open()?;
        let format = link.pixel_format()?;
        let stride = format.stride(rect.width)?;
        let mapping = stride as usize * rect.height as usize;
        let mapping = mapping.max(1).div_ceil(4096) * 4096;

        let shm_ver = link
            .conn
            .shm_query_version()
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        if shm_ver.major_version == 0 && shm_ver.minor_version == 0 {
            return Err(Error::X11("MIT-SHM is not available".into()));
        }

        let shm_id = unsafe { libc::shmget(libc::IPC_PRIVATE, mapping, libc::IPC_CREAT | 0o600) };
        if shm_id < 0 {
            return Err(Error::X11(format!(
                "shmget failed: {}",
                std::io::Error::last_os_error()
            )));
        }
        let addr = unsafe { libc::shmat(shm_id, std::ptr::null(), 0) };
        if addr as usize == usize::MAX {
            unsafe { libc::shmctl(shm_id, libc::IPC_RMID, std::ptr::null_mut()) };
            return Err(Error::X11(format!(
                "shmat failed: {}",
                std::io::Error::last_os_error()
            )));
        }
        // The segment is removed when the last attachment drops, including the X server's.
        unsafe { libc::shmctl(shm_id, libc::IPC_RMID, std::ptr::null_mut()) };

        let seg = link.conn.generate_id().map_err(Error::x11)?;
        link.conn
            .shm_attach(seg, shm_id as u32, false)
            .map_err(Error::x11)?;
        if let Err(err) = link.roundtrip() {
            unsafe { libc::shmdt(addr) };
            return Err(err);
        }

        let mut damage_ok = true;
        let damage = link.conn.generate_id().map_err(Error::x11)?;
        if link
            .conn
            .damage_query_version(1, 1)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)
            .is_err()
        {
            damage_ok = false;
        }
        if damage_ok {
            link.conn
                .damage_create(damage, link.root, damage::ReportLevel::NON_EMPTY)
                .map_err(Error::x11)?;
            if link.roundtrip().is_err() {
                damage_ok = false;
            }
        }

        Ok(Self {
            link,
            rect,
            seg,
            addr,
            mapping,
            stride,
            damage: if damage_ok { damage } else { 0 },
            damage_ok,
            dirty: true,
            generation: 1,
            last: None,
        })
    }

    fn grab(&self) -> Result<CapturedFrame> {
        let x = i16::try_from(self.rect.x)
            .map_err(|_| Error::X11("capture x does not fit in i16".into()))?;
        let y = i16::try_from(self.rect.y)
            .map_err(|_| Error::X11("capture y does not fit in i16".into()))?;
        let reply = self
            .link
            .conn
            .shm_get_image(
                self.link.root,
                x,
                y,
                self.rect.width as u16,
                self.rect.height as u16,
                u32::MAX,
                u8::from(ImageFormat::Z_PIXMAP),
                self.seg,
                0,
            )
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let need = self.stride as usize * self.rect.height as usize;
        if self.mapping < need {
            return Err(Error::X11(
                "shared-memory mapping is smaller than the frame".into(),
            ));
        }
        let bytes = unsafe { std::slice::from_raw_parts(self.addr as *const u8, need) };
        if reply.size != 0 && reply.size as usize > need {
            return Err(Error::X11(format!(
                "server wrote {} bytes into a {} byte mapping",
                reply.size, need
            )));
        }
        Ok(CapturedFrame {
            width: self.rect.width,
            height: self.rect.height,
            stride: self.stride,
            bgra: bytes.to_vec(),
            monotonic_ns: crate::core::monotonic_ns(),
            damage_generation: self.generation,
        })
    }

    fn drain_damage(&mut self) {
        loop {
            match self.link.conn.poll_for_event() {
                Ok(Some(event)) => {
                    if self.event_hits_rect(&event) {
                        self.dirty = true;
                        self.generation = self.generation.wrapping_add(1);
                    }
                }
                Ok(None) => break,
                Err(err) => {
                    tracing::warn!("X event poll failed: {err}");
                    break;
                }
            }
        }
    }

    fn event_hits_rect(&self, event: &x11rb::protocol::Event) -> bool {
        let x11rb::protocol::Event::DamageNotify(notify) = event else {
            return false;
        };
        let area_x = i32::from(notify.area.x);
        let area_y = i32::from(notify.area.y);
        let area_r = area_x + i32::from(notify.area.width);
        let area_b = area_y + i32::from(notify.area.height);
        let mine_r = self.rect.x + self.rect.width as i32;
        let mine_b = self.rect.y + self.rect.height as i32;
        self.rect.x < area_r && mine_r > area_x && self.rect.y < area_b && mine_b > area_y
    }

    /// The cursor is composited after the grab, so a pointer move has to
    /// request a frame even when the framebuffer itself is unchanged.
    pub fn note_cursor(&mut self) {
        self.dirty = true;
        self.generation = self.generation.wrapping_add(1);
    }

    fn subtract_damage(&self) {
        if self.damage == 0 {
            return;
        }
        if self
            .link
            .conn
            .damage_subtract(self.damage, 0u32, 0u32)
            .is_ok()
        {
            let _ = self.link.conn.flush();
        }
    }
}

impl CaptureBackend for X11Capture {
    fn poll(&mut self, min_interval: Duration, force: bool) -> Result<Option<CapturedFrame>> {
        if self.damage_ok {
            self.drain_damage();
        } else {
            self.dirty = true;
        }
        let due = self
            .last
            .map(|last| last.elapsed() >= min_interval)
            .unwrap_or(true);
        if !force && (!self.dirty || !due) {
            return Ok(None);
        }
        if !self.dirty && !force {
            return Ok(None);
        }
        // Clear the damage region before reading pixels. A change that lands
        // during the grab then produces a new notification, instead of being
        // erased with the region we already decided to read.
        self.subtract_damage();
        let frame = self.grab()?;
        self.dirty = false;
        if self.damage_ok {
            self.drain_damage();
        }
        self.last = Some(Instant::now());
        Ok(Some(frame))
    }
}

impl Drop for X11Capture {
    fn drop(&mut self) {
        if self.damage != 0 {
            let _ = self.link.conn.damage_destroy(self.damage);
        }
        if self.seg != 0 {
            let _ = self.link.conn.shm_detach(self.seg);
            let _ = self.link.conn.flush();
        }
        if !self.addr.is_null() {
            unsafe { libc::shmdt(self.addr) };
            self.addr = std::ptr::null_mut();
        }
    }
}
