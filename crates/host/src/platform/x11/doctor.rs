//! Read-only environment report. This never resizes the framebuffer.

use super::display::X11Display;
use super::X11Link;
use crate::error::{Error, Result};
use x11rb::protocol::damage::ConnectionExt as _;
use x11rb::protocol::randr::ConnectionExt as _;
use x11rb::protocol::shm::ConnectionExt as _;
use x11rb::protocol::xfixes::ConnectionExt as _;

const ENCODERS: &[&str] = &[
    "nvh264enc",
    "vah264enc",
    "vaapih264enc",
    "x264enc",
    "openh264enc",
];

#[derive(Debug)]
pub struct DoctorReport {
    pub lines: Vec<String>,
    pub ok: bool,
}

pub fn doctor_report() -> Result<DoctorReport> {
    let link = X11Link::open()?;
    let mut lines = Vec::new();
    let mut ok = true;

    let randr = link
        .conn
        .randr_query_version(1, 5)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    let randr_ok =
        randr.major_version > 1 || (randr.major_version == 1 && randr.minor_version >= 5);
    lines.push(format!(
        "RandR: {}.{} {}",
        randr.major_version,
        randr.minor_version,
        if randr_ok { "ok" } else { "need 1.5" }
    ));
    ok &= randr_ok;

    let range = link
        .conn
        .randr_get_screen_size_range(link.root)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    let fb = link.geometry()?;
    lines.push(format!(
        "Framebuffer: {}x{} mm {}x{}, range {}x{} .. {}x{}",
        fb.width,
        fb.height,
        fb.width_mm,
        fb.height_mm,
        range.min_width,
        range.min_height,
        range.max_width,
        range.max_height
    ));

    match link.pixel_format() {
        Ok(format) => lines.push(format!(
            "Pixmap: depth {} bpp {} pad {}",
            format.depth, format.bits_per_pixel, format.scanline_pad
        )),
        Err(err) => {
            ok = false;
            lines.push(format!("Pixmap: {err}"));
        }
    }

    match link.conn.shm_query_version().map_err(Error::x11)?.reply() {
        Ok(ver) => lines.push(format!(
            "MIT-SHM: {}.{}",
            ver.major_version, ver.minor_version
        )),
        Err(err) => {
            ok = false;
            lines.push(format!("MIT-SHM: unavailable ({err})"));
        }
    }
    match link
        .conn
        .damage_query_version(1, 1)
        .map_err(Error::x11)?
        .reply()
    {
        Ok(ver) => lines.push(format!(
            "XDamage: {}.{}",
            ver.major_version, ver.minor_version
        )),
        Err(err) => {
            ok = false;
            lines.push(format!("XDamage: unavailable ({err})"));
        }
    }
    match link
        .conn
        .xfixes_query_version(5, 0)
        .map_err(Error::x11)?
        .reply()
    {
        Ok(ver) => lines.push(format!(
            "XFixes: {}.{}",
            ver.major_version, ver.minor_version
        )),
        Err(err) => {
            ok = false;
            lines.push(format!("XFixes: unavailable ({err})"));
        }
    }

    let display = X11Display::open()?;
    let outputs = display.outputs()?;
    if outputs.is_empty() {
        ok = false;
        lines.push("CRTCs: none active".into());
    } else {
        for output in &outputs {
            lines.push(format!(
                "CRTC: {}x{}+{}+{} mm {}x{}",
                output.width, output.height, output.x, output.y, output.mm_width, output.mm_height
            ));
        }
    }
    for (name, info) in display.list_monitors()? {
        lines.push(format!(
            "Monitor: {name} {}x{}+{}+{} outputs {}",
            info.width,
            info.height,
            info.x,
            info.y,
            info.outputs.len()
        ));
    }

    let present = crate::media::available_encoders();
    let mut any_encoder = false;
    for name in ENCODERS {
        if present.iter().any(|found| found == name) {
            any_encoder = true;
            lines.push(format!("Encoder: {name} present"));
        } else {
            lines.push(format!("Encoder: {name} absent"));
        }
    }
    if !any_encoder {
        ok = false;
        lines.push("Encoder: no H.264 element found".into());
    }
    lines.push(
        "Transport: RTP/UDP is the LAN proof of concept. Do not expose it beyond a trusted network."
            .into(),
    );
    Ok(DoctorReport { lines, ok })
}
