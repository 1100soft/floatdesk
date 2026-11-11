//! MIT-SHM capture of one rectangle, gated by XDamage.

use super::X11Link;
use crate::core::{CaptureBackend, CapturedFrame, MonitorRect};
use crate::error::{Error, Result};
use std::time::{Duration, Instant};
use x11rb::connection::Connection;
use x11rb::protocol::damage::{self, ConnectionExt as _};
use x11rb::protocol::render::{self, ConnectionExt as _};
use x11rb::protocol::shm::ConnectionExt as _;
use x11rb::protocol::xproto::ImageFormat;
use x11rb::protocol::xproto::{ConnectionExt as _, SubwindowMode};

struct RenderTarget {
    pixmap: u32,
    source: u32,
    destination: u32,
    width: u16,
    height: u16,
}
impl RenderTarget {
    fn create(link: &X11Link, rect: MonitorRect, width: u16, height: u16) -> Result<Self> {
        let screen = &link.conn.setup().roots[link.screen];
        let formats = link
            .conn
            .render_query_pict_formats()
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let format = formats
            .screens
            .get(link.screen)
            .and_then(|s| {
                s.depths
                    .iter()
                    .flat_map(|d| &d.visuals)
                    .find(|v| v.visual == screen.root_visual)
            })
            .map(|v| v.format)
            .ok_or_else(|| Error::Msg("Root visual has no Render format".into()))?;
        let target = Self {
            pixmap: link.conn.generate_id().map_err(Error::x11)?,
            source: link.conn.generate_id().map_err(Error::x11)?,
            destination: link.conn.generate_id().map_err(Error::x11)?,
            width,
            height,
        };
        let result = (|| {
            link.conn
                .create_pixmap(link.root_depth, target.pixmap, link.root, width, height)
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            link.conn
                .render_create_picture(
                    target.source,
                    link.root,
                    format,
                    &render::CreatePictureAux::new()
                        .subwindowmode(SubwindowMode::INCLUDE_INFERIORS),
                )
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            link.conn
                .render_create_picture(
                    target.destination,
                    target.pixmap,
                    format,
                    &render::CreatePictureAux::new(),
                )
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            let fixed = |v: f64| (v * 65536.0).round() as i32;
            let transform = render::Transform {
                matrix11: fixed(f64::from(rect.width) / f64::from(width)),
                matrix12: 0,
                matrix13: fixed(f64::from(rect.x)),
                matrix21: 0,
                matrix22: fixed(f64::from(rect.height) / f64::from(height)),
                matrix23: fixed(f64::from(rect.y)),
                matrix31: 0,
                matrix32: 0,
                matrix33: 65536,
            };
            link.conn
                .render_set_picture_transform(target.source, transform)
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            link.conn
                .render_set_picture_filter(target.source, b"bilinear", &[])
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            Ok(())
        })();
        if let Err(error) = result {
            target.free(link);
            return Err(error);
        }
        Ok(target)
    }
    fn free(&self, link: &X11Link) {
        let _ = link.conn.render_free_picture(self.source);
        let _ = link.conn.render_free_picture(self.destination);
        let _ = link.conn.free_pixmap(self.pixmap);
    }
}

pub struct X11Capture {
    link: X11Link,
    rect: MonitorRect,
    render: Option<RenderTarget>,
    width: u32,
    height: u32,
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
        Self::open_scaled(rect, rect.width as u16, rect.height as u16)
    }
    pub fn open_scaled(rect: MonitorRect, width: u16, height: u16) -> Result<Self> {
        let link = X11Link::open()?;
        let render = if u32::from(width) != rect.width || u32::from(height) != rect.height {
            match RenderTarget::create(&link, rect, width, height) {
                Ok(target) => Some(target),
                Err(error) => {
                    tracing::warn!(%error,"Server scaling unavailable; using full desktop capture");
                    None
                }
            }
        } else {
            None
        };
        let (width, height) = render.as_ref().map_or((rect.width, rect.height), |r| {
            (u32::from(r.width), u32::from(r.height))
        });
        let format = link.pixel_format()?;
        let stride = format.stride(width)?;
        let mapping = stride as usize * height as usize;
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
            render,
            width,
            height,
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
        let (drawable, x, y) = if let Some(target) = &self.render {
            self.link
                .conn
                .render_composite(
                    render::PictOp::SRC,
                    target.source,
                    0u32,
                    target.destination,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    target.width,
                    target.height,
                )
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            (target.pixmap, 0, 0)
        } else {
            (self.link.root, x, y)
        };
        let reply = self
            .link
            .conn
            .shm_get_image(
                drawable,
                x,
                y,
                self.width as u16,
                self.height as u16,
                u32::MAX,
                u8::from(ImageFormat::Z_PIXMAP),
                self.seg,
                0,
            )
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let need = self.stride as usize * self.height as usize;
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
            width: self.width,
            height: self.height,
            stride: self.stride,
            bgra: bytes.to_vec(),
            monotonic_ns: crate::core::monotonic_ns(),
            damage_generation: self.generation,
        })
    }

    fn drain_damage(&mut self) -> Result<()> {
        loop {
            match self.link.conn.poll_for_event() {
                Ok(Some(event)) => {
                    if self.event_hits_rect(&event) {
                        self.dirty = true;
                        self.generation = self.generation.wrapping_add(1);
                    }
                }
                Ok(None) => break,
                Err(err) => return Err(Error::x11(err)),
            }
        }
        Ok(())
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

    /// Socket the X server replies on. Closing it unblocks a grab that would
    /// otherwise wait forever.
    pub fn pixel_size(&self) -> (u32, u32) {
        (self.width, self.height)
    }
    pub fn connection_fd(&self) -> i32 {
        use std::os::unix::io::AsRawFd;
        self.link.conn.stream().as_raw_fd()
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
            self.drain_damage()?;
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
        let started = Instant::now();
        self.subtract_damage();
        let frame = self.grab()?;
        self.dirty = false;
        if self.damage_ok {
            self.drain_damage()?;
        }
        // Pace capture starts, not capture completion plus another full interval.
        self.last = Some(started);
        Ok(Some(frame))
    }
}

impl Drop for X11Capture {
    fn drop(&mut self) {
        if let Some(target) = &self.render {
            target.free(&self.link);
        }
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

#[cfg(test)]
mod pacing_checks {
    use super::*;

    /// Read-only benchmark. Keeps pixels in memory and never changes desktop input.
    #[test]
    #[ignore = "requires an explicitly selected live X11 display"]
    fn capture_cadence_read_only() {
        assert_eq!(std::env::var("FLOATDESK_CAPTURE_BENCH").as_deref(), Ok("1"));
        let link = X11Link::open().unwrap();
        let screen = &link.conn.setup().roots[link.screen];
        let rect = MonitorRect {
            index: 0,
            x: 0,
            y: 0,
            width: u32::from(screen.width_in_pixels),
            height: u32::from(screen.height_in_pixels),
        };
        let mut capture = X11Capture::open(rect).unwrap();
        let began = Instant::now();
        let mut frames = 0;
        while began.elapsed() < Duration::from_secs(2) {
            capture.note_cursor();
            if capture
                .poll(Duration::from_secs_f64(1.0 / 60.0), false)
                .unwrap()
                .is_some()
            {
                frames += 1;
            } else {
                std::thread::sleep(Duration::from_millis(1));
            }
        }
        println!(
            "Read-only {}x{} capture: {:.1} fps at requested 60 fps",
            rect.width,
            rect.height,
            f64::from(frames) / began.elapsed().as_secs_f64()
        );
        assert!(frames > 0);
        // Close only this test's client socket, never the X server itself.
        unsafe {
            libc::shutdown(capture.connection_fd(), libc::SHUT_RDWR);
        }
        assert!(
            capture.poll(Duration::from_secs(60), false).is_err(),
            "a dead X connection must escape idle polling so the session can reconnect"
        );
    }
}

#[cfg(test)]
mod scaled_checks {
    use super::*;
    use x11rb::protocol::xproto::{CreateGCAux, CreateWindowAux, Rectangle, WindowClass};
    #[test]
    #[ignore = "writes synthetic pixels; run only on a private Xvfb with FLOATDESK_SYNTHETIC_DISPLAY=1"]
    fn server_downscale_keeps_crop_and_child_pixels() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let link = X11Link::open().unwrap();
        let gc = link.conn.generate_id().unwrap();
        link.conn
            .create_gc(gc, link.root, &CreateGCAux::new())
            .unwrap()
            .check()
            .unwrap();
        for (x, y, color) in [
            (128, 64, 0xFF0000u32),
            (384, 64, 0x00FF00),
            (128, 256, 0x0000FF),
            (384, 256, 0xFFFFFF),
        ] {
            link.conn
                .change_gc(
                    gc,
                    &x11rb::protocol::xproto::ChangeGCAux::new().foreground(color),
                )
                .unwrap();
            link.conn
                .poly_fill_rectangle(
                    link.root,
                    gc,
                    &[Rectangle {
                        x,
                        y,
                        width: 256,
                        height: 192,
                    }],
                )
                .unwrap();
        }
        let child = link.conn.generate_id().unwrap();
        link.conn
            .create_window(
                0,
                child,
                link.root,
                160,
                96,
                64,
                64,
                0,
                WindowClass::INPUT_OUTPUT,
                0,
                &CreateWindowAux::new().background_pixel(0xFFFF00u32),
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn.map_window(child).unwrap().check().unwrap();
        link.conn.flush().unwrap();
        let mut capture = X11Capture::open_scaled(
            MonitorRect {
                index: 0,
                x: 128,
                y: 64,
                width: 512,
                height: 384,
            },
            256,
            192,
        )
        .unwrap();
        assert!(capture.render.is_some());
        let frame = capture.poll(Duration::ZERO, true).unwrap().unwrap();
        assert_eq!((frame.width, frame.height), (256, 192));
        for (x, y, bgr) in [
            (64, 48, [0, 0, 255]),
            (192, 48, [0, 255, 0]),
            (64, 144, [255, 0, 0]),
            (192, 144, [255, 255, 255]),
            (24, 24, [0, 255, 255]),
        ] {
            let offset = y * frame.stride as usize + x * 4;
            assert_eq!(frame.bgra[offset..offset + 3], bgr, "pixel {x},{y}");
        }
        link.conn.destroy_window(child).unwrap();
        link.conn.free_gc(gc).unwrap();
        link.conn.flush().unwrap();
    }
    #[test]
    #[ignore = "read-only timing on an explicitly selected live X11 display"]
    fn benchmark_full_vs_scaled_readback() {
        let sources = crate::platform::x11::monitor_pool::MonitorPool::observer()
            .sources()
            .unwrap();
        let rect = crate::platform::x11::monitor_pool::desktop_rect(&sources).unwrap();
        let (w, h) = crate::platform::x11::monitor_pool::stream_size(rect);
        for scaled in [false, true] {
            let mut capture = if scaled {
                X11Capture::open_scaled(rect, w, h)
            } else {
                X11Capture::open(rect)
            }
            .unwrap();
            let started = Instant::now();
            for _ in 0..15 {
                let frame = capture.poll(Duration::ZERO, true).unwrap().unwrap();
                std::hint::black_box(frame);
            }
            eprintln!(
                "capture scaled={scaled} source={}x{} read={}x{} average_ms={:.2}",
                rect.width,
                rect.height,
                capture.width,
                capture.height,
                started.elapsed().as_secs_f64() * 1000.0 / 15.0
            );
        }
    }
}
