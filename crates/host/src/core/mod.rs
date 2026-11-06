//! Host boundaries. Platform modules and the media backend implement these
//! traits; the session loop only talks to them. Wayland, Windows, and macOS
//! are further `CaptureBackend` / `DisplayBackend` implementations, not forks
//! of the session.

mod time;

pub use time::{epoch_ns, monotonic_ns};

use crate::error::Result;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MonitorRect {
    pub index: u8,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

impl MonitorRect {
    pub fn contains(&self, x: i32, y: i32) -> bool {
        x >= self.x
            && y >= self.y
            && x < self.x + self.width as i32
            && y < self.y + self.height as i32
    }

    pub fn name(&self) -> String {
        format!("Floatdesk-{}", self.index + 1)
    }
}

#[derive(Debug, Clone)]
pub struct CapturedFrame {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub bgra: Vec<u8>,
    pub monotonic_ns: u64,
    pub damage_generation: u64,
}

#[derive(Debug, Clone)]
pub struct EncodedAccessUnit {
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct CursorSample {
    pub root_x: i32,
    pub root_y: i32,
    pub hotspot_x: i16,
    pub hotspot_y: i16,
    pub serial: u64,
    pub width: u16,
    pub height: u16,
    /// Host-endian 0xAARRGGBB pixels. Empty when the pointer shape is unchanged
    /// and the caller only needs a position.
    pub argb: Vec<u32>,
}

#[derive(Debug, Clone, Copy)]
pub struct EncoderConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_bps: u32,
}

pub trait DisplayBackend {
    fn primary(&self) -> Result<MonitorRect>;
    fn create_virtual(&mut self, count: u8, width: u16, height: u16) -> Result<Vec<MonitorRect>>;
    fn restore(&mut self) -> Result<()>;
}

pub trait CaptureBackend {
    fn poll(&mut self, min_interval: Duration, force: bool) -> Result<Option<CapturedFrame>>;
}

pub trait CursorBackend {
    fn poll(&mut self) -> Result<CursorSample>;
}

pub trait EncodeBackend {
    fn submit(&mut self, frame: &CapturedFrame) -> Result<()>;
    fn request_keyframe(&mut self);
    fn set_bitrate(&mut self, bps: u32) -> Result<()>;
    fn pull(&mut self) -> Result<Option<EncodedAccessUnit>>;
}
