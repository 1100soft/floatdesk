//! A real protocol-B virtual touchpad, so libinput/Touchégg receive native gestures.
use crate::error::{Error, Result};
use libloading::Library;
use std::ffi::c_void;
use std::time::{Duration, Instant};

type WriteEvent = unsafe extern "C" fn(*mut c_void, u32, u32, i32) -> i32;
type Destroy = unsafe extern "C" fn(*mut c_void);

pub struct VirtualTouchpad {
    _library: Library,
    device: *mut c_void,
    write_event: WriteEvent,
    destroy: Destroy,
    fingers: u8,
    last_input: Instant,
}

impl VirtualTouchpad {
    pub fn open() -> Result<Self> {
        unsafe {
            let library = Library::new("libevdev.so.2").map_err(|e| Error::Msg(e.to_string()))?;
            let new = *library
                .get::<unsafe extern "C" fn() -> *mut c_void>(b"libevdev_new\0")
                .map_err(|e| Error::Msg(e.to_string()))?;
            let free = *library
                .get::<Destroy>(b"libevdev_free\0")
                .map_err(|e| Error::Msg(e.to_string()))?;
            let name = *library
                .get::<unsafe extern "C" fn(*mut c_void, *const i8)>(b"libevdev_set_name\0")
                .map_err(|e| Error::Msg(e.to_string()))?;
            let property = *library
                .get::<unsafe extern "C" fn(*mut c_void, u32) -> i32>(b"libevdev_enable_property\0")
                .map_err(|e| Error::Msg(e.to_string()))?;
            let enable = *library
                .get::<unsafe extern "C" fn(*mut c_void, u32, u32, *const c_void) -> i32>(
                    b"libevdev_enable_event_code\0",
                )
                .map_err(|e| Error::Msg(e.to_string()))?;
            let create = *library
                .get::<unsafe extern "C" fn(*mut c_void, i32, *mut *mut c_void) -> i32>(
                    b"libevdev_uinput_create_from_device\0",
                )
                .map_err(|e| Error::Msg(e.to_string()))?;
            let write_event = *library
                .get::<WriteEvent>(b"libevdev_uinput_write_event\0")
                .map_err(|e| Error::Msg(e.to_string()))?;
            let destroy = *library
                .get::<Destroy>(b"libevdev_uinput_destroy\0")
                .map_err(|e| Error::Msg(e.to_string()))?;
            let descriptor = new();
            if descriptor.is_null() {
                return Err(Error::Msg("could not allocate virtual touchpad".into()));
            }
            let result: Result<*mut c_void> = (|| {
                name(descriptor, c"Floatdesk Air Touchpad".as_ptr());
                check(property(descriptor, 0))?; // INPUT_PROP_POINTER
                check(property(descriptor, 2))?; // INPUT_PROP_BUTTONPAD
                for code in [0x110, 0x145, 0x14a, 0x14d, 0x14e, 0x14f] {
                    check(enable(descriptor, 1, code, std::ptr::null()))?;
                }
                for (code, max, resolution) in [
                    (0, 10000, 100),
                    (1, 6000, 100),
                    (0x18, 255, 0),
                    (0x2f, 3, 0),
                    (0x30, 100, 0),
                    (0x35, 10000, 100),
                    (0x36, 6000, 100),
                    (0x39, 65535, 0),
                    (0x3a, 255, 0),
                ] {
                    let info = libc::input_absinfo {
                        value: 0,
                        minimum: 0,
                        maximum: max,
                        fuzz: 0,
                        flat: 0,
                        resolution,
                    };
                    check(enable(
                        descriptor,
                        3,
                        code,
                        &info as *const _ as *const c_void,
                    ))?;
                }
                let mut device = std::ptr::null_mut();
                check(create(descriptor, -2, &mut device))?; // managed /dev/uinput fd
                Ok(device)
            })();
            free(descriptor);
            let device = result?;
            Ok(Self {
                _library: library,
                device,
                write_event,
                destroy,
                fingers: 0,
                last_input: Instant::now(),
            })
        }
    }

    fn emit(&self, kind: u32, code: u32, value: i32) -> Result<()> {
        check(unsafe { (self.write_event)(self.device, kind, code, value) })
    }

    pub fn syspath(&self) -> Option<String> {
        unsafe {
            let get = self
                ._library
                .get::<unsafe extern "C" fn(*mut c_void) -> *const i8>(
                    b"libevdev_uinput_get_syspath\0",
                )
                .ok()?;
            let path = get(self.device);
            if path.is_null() {
                None
            } else {
                Some(
                    std::ffi::CStr::from_ptr(path)
                        .to_string_lossy()
                        .into_owned(),
                )
            }
        }
    }

    fn contacts(&self, count: u8, x: f32, y: f32, begin: bool) -> Result<()> {
        let center_x = (x.clamp(0.15, 0.85) * 10000.0).round() as i32;
        let center_y = (y.clamp(0.1, 0.9) * 6000.0).round() as i32;
        for slot in 0..count {
            self.emit(3, 0x2f, i32::from(slot))?;
            if begin {
                self.emit(3, 0x39, i32::from(slot) + 1)?;
            }
            self.emit(
                3,
                0x35,
                center_x + (i32::from(slot) * 2 - i32::from(count) + 1) * 400,
            )?;
            self.emit(3, 0x36, center_y)?;
            self.emit(3, 0x30, 30)?;
            self.emit(3, 0x3a, 80)?;
        }
        self.emit(3, 0, center_x)?;
        self.emit(3, 1, center_y)?;
        self.emit(3, 0x18, 80)?;
        self.emit(1, 0x14a, 1)?;
        for (n, code) in [(1, 0x145), (2, 0x14d), (3, 0x14e), (4, 0x14f)] {
            self.emit(1, code, i32::from(count == n))?;
        }
        self.emit(0, 0, 0)
    }

    pub fn update(&mut self, fingers: u8, x: f32, y: f32) -> Result<()> {
        if !matches!(fingers, 0 | 2) || !x.is_finite() || !y.is_finite() {
            return Err(Error::Msg("invalid touchpad frame".into()));
        }
        if fingers != self.fingers {
            self.release()?;
            if fingers > 0 {
                self.contacts(fingers, 0.5, 0.5, true)?;
                self.fingers = fingers;
            }
        }
        if fingers > 0 {
            self.contacts(fingers, x, y, false)?;
        }
        self.last_input = Instant::now();
        Ok(())
    }

    pub fn release(&mut self) -> Result<()> {
        if self.fingers == 0 {
            return Ok(());
        }
        for slot in 0..self.fingers {
            self.emit(3, 0x2f, i32::from(slot))?;
            self.emit(3, 0x39, -1)?;
        }
        for code in [0x145, 0x14a, 0x14d, 0x14e, 0x14f] {
            self.emit(1, code, 0)?;
        }
        self.emit(3, 0x18, 0)?;
        self.emit(0, 0, 0)?;
        self.fingers = 0;
        Ok(())
    }

    pub fn expire(&mut self) {
        if self.fingers > 0 && self.last_input.elapsed() >= Duration::from_millis(300) {
            if let Err(error) = self.release() {
                tracing::warn!(%error,"virtual touchpad release failed");
            }
        }
    }
}
impl Drop for VirtualTouchpad {
    fn drop(&mut self) {
        let _ = self.release();
        unsafe { (self.destroy)(self.device) };
    }
}
fn check(code: i32) -> Result<()> {
    if code < 0 {
        Err(Error::Msg(format!(
            "virtual touchpad: {}",
            std::io::Error::from_raw_os_error(-code)
        )))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires writable /dev/uinput; creates a passive device without touch events"]
    fn creates_classified_touchpad_without_emitting_touches() {
        assert_eq!(std::env::var("FLOATDESK_UINPUT_TEST").as_deref(), Ok("1"));
        let device = VirtualTouchpad::open().unwrap();
        let path = device.syspath().unwrap();
        let _ = std::process::Command::new("udevadm")
            .args(["settle", "--timeout=2"])
            .status();
        let event = std::fs::read_dir(&path)
            .unwrap()
            .flatten()
            .find(|entry| entry.file_name().to_string_lossy().starts_with("event"))
            .unwrap();
        let info = std::process::Command::new("udevadm")
            .args([
                "info",
                "--query=property",
                "--path",
                event.path().to_str().unwrap(),
            ])
            .output()
            .unwrap();
        assert!(
            String::from_utf8_lossy(&info.stdout).contains("ID_INPUT_TOUCHPAD=1"),
            "{}",
            String::from_utf8_lossy(&info.stdout)
        );
        assert_eq!(device.fingers, 0);
    }
}
