//! Control datagrams. One UDP message is one packet. Little-endian.
//!
//! ```text
//! offset  size  field
//! 0       4     magic "FDC1"
//! 4       1     version
//! 5       1     kind
//! 6       2     flags
//! 8       4     payload length
//! 12      4     reserved
//! 16      …     payload
//! ```

use thiserror::Error;

pub const MAGIC: &[u8; 4] = b"FDC1";
pub const VERSION: u8 = 1;
pub const MAX_DATAGRAM: usize = 1400;
pub const HEADER_LEN: usize = 16;

pub const KIND_CLIENT_HELLO: u8 = 1;
pub const KIND_HOST_HELLO: u8 = 2;
pub const KIND_CURSOR: u8 = 3;
pub const KIND_KEYFRAME_REQUEST: u8 = 4;
pub const KIND_LAYOUT: u8 = 5;
pub const KIND_GOODBYE: u8 = 6;
pub const KIND_BEACON: u8 = 7;
pub const KIND_POINTER_MOVE: u8 = 8;
pub const KIND_POINTER_CLICK: u8 = 9;
pub const KIND_TOUCHPAD_STATE: u8 = 10;
pub const KIND_MONITOR_COMMAND: u8 = 11;
pub const KIND_MONITOR_STATUS: u8 = 12;
pub const BEACON_PORT: u16 = 47001;

const CURSOR_FIXED: usize = 38;
const CURSOR_CHUNK: usize = 1024;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ProtocolError {
    #[error("datagram is shorter than the control header")]
    Truncated,
    #[error("bad magic")]
    Magic,
    #[error("unsupported version {0}")]
    Version(u8),
    #[error("unknown kind {0}")]
    Kind(u8),
    #[error("payload length does not match the datagram")]
    Length,
    #[error("payload is incomplete for kind {0}")]
    Payload(u8),
    #[error("cursor fragment is inconsistent")]
    CursorFragment,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MonitorHello {
    pub index: u8,
    pub width: u16,
    pub height: u16,
    pub port: u16,
    pub ssrc: u32,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CursorState {
    pub monitor_index: u8,
    pub visible: bool,
    pub hotspot_x: i16,
    pub hotspot_y: i16,
    pub norm_x: f32,
    pub norm_y: f32,
    pub shape_id: u64,
    pub timestamp_ns: u64,
    pub bitmap_w: u16,
    pub bitmap_h: u16,
    pub frag_index: u16,
    pub frag_count: u16,
    /// BGRA bytes for this fragment. Empty when `frag_count` is 0.
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct LayoutEntry {
    pub index: u8,
    pub x: i32,
    pub y: i32,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ControlPacket {
    ClientHello {
        name: String,
        video_port: u16,
    },
    HostHello {
        session_id: u64,
        monitors: Vec<MonitorHello>,
    },
    Cursor(CursorState),
    PointerMove {
        monitor_index: u8,
        norm_x: f32,
        norm_y: f32,
        sequence: u32,
        left_down: bool,
    },
    PointerClick {
        monitor_index: u8,
        norm_x: f32,
        norm_y: f32,
        button: u8,
        sequence: u32,
    },
    TouchpadState {
        fingers: u8,
        x: f32,
        y: f32,
        sequence: u32,
    },
    MonitorCommand {
        request_id: u32,
        action: u8,
        index: u8,
        width: u16,
        height: u16,
    },
    MonitorStatus {
        request_id: u32,
        ok: bool,
        available: u8,
        owned: u8,
        message: String,
    },
    KeyframeRequest {
        monitor_index: u8,
    },
    Layout {
        monitors: Vec<LayoutEntry>,
    },
    Goodbye,
    /// Broadcast so a headset can learn the host address without typing it.
    Beacon {
        listen_port: u16,
        name: String,
    },
}

impl ControlPacket {
    pub fn encode(&self) -> Vec<u8> {
        let payload = self.encode_payload();
        let mut out = Vec::with_capacity(HEADER_LEN + payload.len());
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.push(self.kind());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&payload);
        out
    }

    fn kind(&self) -> u8 {
        match self {
            Self::ClientHello { .. } => KIND_CLIENT_HELLO,
            Self::HostHello { .. } => KIND_HOST_HELLO,
            Self::Cursor(_) => KIND_CURSOR,
            Self::MonitorCommand { .. } => KIND_MONITOR_COMMAND,
            Self::MonitorStatus { .. } => KIND_MONITOR_STATUS,
            Self::PointerMove { .. } => KIND_POINTER_MOVE,
            Self::PointerClick { .. } => KIND_POINTER_CLICK,
            Self::TouchpadState { .. } => KIND_TOUCHPAD_STATE,
            Self::KeyframeRequest { .. } => KIND_KEYFRAME_REQUEST,
            Self::Layout { .. } => KIND_LAYOUT,
            Self::Goodbye => KIND_GOODBYE,
            Self::Beacon { .. } => KIND_BEACON,
        }
    }

    fn encode_payload(&self) -> Vec<u8> {
        let mut w = Vec::new();
        match self {
            Self::ClientHello { name, video_port } => {
                write_name(&mut w, name);
                w.extend_from_slice(&video_port.to_le_bytes());
            }
            Self::HostHello {
                session_id,
                monitors,
            } => {
                w.extend_from_slice(&session_id.to_le_bytes());
                w.push(monitors.len() as u8);
                for m in monitors {
                    w.push(m.index);
                    w.extend_from_slice(&m.width.to_le_bytes());
                    w.extend_from_slice(&m.height.to_le_bytes());
                    w.extend_from_slice(&m.port.to_le_bytes());
                    w.extend_from_slice(&m.ssrc.to_le_bytes());
                    write_name(&mut w, &m.name);
                }
            }
            Self::Cursor(c) => {
                w.push(c.monitor_index);
                w.push(u8::from(c.visible));
                w.extend_from_slice(&c.hotspot_x.to_le_bytes());
                w.extend_from_slice(&c.hotspot_y.to_le_bytes());
                w.extend_from_slice(&c.norm_x.to_le_bytes());
                w.extend_from_slice(&c.norm_y.to_le_bytes());
                w.extend_from_slice(&c.shape_id.to_le_bytes());
                w.extend_from_slice(&c.timestamp_ns.to_le_bytes());
                w.extend_from_slice(&c.bitmap_w.to_le_bytes());
                w.extend_from_slice(&c.bitmap_h.to_le_bytes());
                w.extend_from_slice(&c.frag_index.to_le_bytes());
                w.extend_from_slice(&c.frag_count.to_le_bytes());
                w.extend_from_slice(&c.pixels);
            }
            Self::PointerMove {
                monitor_index,
                norm_x,
                norm_y,
                sequence,
                left_down,
            } => {
                w.push(*monitor_index);
                w.extend_from_slice(&norm_x.to_le_bytes());
                w.extend_from_slice(&norm_y.to_le_bytes());
                w.extend_from_slice(&sequence.to_le_bytes());
                w.push(u8::from(*left_down));
            }
            Self::PointerClick {
                monitor_index,
                norm_x,
                norm_y,
                button,
                sequence,
            } => {
                w.push(*monitor_index);
                w.extend_from_slice(&norm_x.to_le_bytes());
                w.extend_from_slice(&norm_y.to_le_bytes());
                w.push(*button);
                w.extend_from_slice(&sequence.to_le_bytes());
            }
            Self::TouchpadState {
                fingers,
                x,
                y,
                sequence,
            } => {
                w.push(*fingers);
                w.extend_from_slice(&x.to_le_bytes());
                w.extend_from_slice(&y.to_le_bytes());
                w.extend_from_slice(&sequence.to_le_bytes());
            }
            Self::MonitorCommand {
                request_id,
                action,
                index,
                width,
                height,
            } => {
                w.extend_from_slice(&request_id.to_le_bytes());
                w.extend_from_slice(&[*action, *index]);
                w.extend_from_slice(&width.to_le_bytes());
                w.extend_from_slice(&height.to_le_bytes());
            }
            Self::MonitorStatus {
                request_id,
                ok,
                available,
                owned,
                message,
            } => {
                let bytes = message.as_bytes();
                let count = bytes.len().min(240);
                w.extend_from_slice(&request_id.to_le_bytes());
                w.extend_from_slice(&[u8::from(*ok), *available, *owned, count as u8]);
                w.extend_from_slice(&bytes[..count]);
            }
            Self::KeyframeRequest { monitor_index } => w.push(*monitor_index),
            Self::Layout { monitors } => {
                w.push(monitors.len() as u8);
                for m in monitors {
                    w.push(m.index);
                    w.extend_from_slice(&m.x.to_le_bytes());
                    w.extend_from_slice(&m.y.to_le_bytes());
                    w.extend_from_slice(&m.width.to_le_bytes());
                    w.extend_from_slice(&m.height.to_le_bytes());
                }
            }
            Self::Goodbye => {}
            Self::Beacon { listen_port, name } => {
                w.extend_from_slice(&listen_port.to_le_bytes());
                write_name(&mut w, name);
            }
        }
        w
    }
}

/// Split a cursor image into datagrams. Position-only when `pixels` is empty.
pub fn cursor_packets(state: &CursorState) -> Result<Vec<ControlPacket>, ProtocolError> {
    if state.pixels.is_empty() || state.bitmap_w == 0 || state.bitmap_h == 0 {
        let mut only = state.clone();
        only.pixels.clear();
        only.frag_index = 0;
        only.frag_count = 0;
        return Ok(vec![ControlPacket::Cursor(only)]);
    }
    let expected = state.bitmap_w as usize * state.bitmap_h as usize * 4;
    if state.pixels.len() != expected {
        return Err(ProtocolError::CursorFragment);
    }
    let chunks: Vec<_> = state.pixels.chunks(CURSOR_CHUNK).collect();
    let frag_count = u16::try_from(chunks.len()).map_err(|_| ProtocolError::CursorFragment)?;
    Ok(chunks
        .into_iter()
        .enumerate()
        .map(|(i, chunk)| {
            ControlPacket::Cursor(CursorState {
                frag_index: i as u16,
                frag_count,
                pixels: chunk.to_vec(),
                ..state.clone()
            })
        })
        .collect())
}

/// Reassemble cursor fragments. Returns `None` until every fragment of a shape has arrived.
#[derive(Debug, Default)]
pub struct CursorAssembler {
    shape_id: u64,
    frag_count: u16,
    parts: Vec<Option<Vec<u8>>>,
}

impl CursorAssembler {
    /// Apply one fragment. The returned state has the full bitmap when reassembly finishes,
    /// and an empty bitmap when the packet was position-only.
    pub fn push(&mut self, incoming: CursorState) -> Result<CursorState, ProtocolError> {
        if incoming.frag_count == 0 {
            return Ok(incoming);
        }
        if incoming.frag_index >= incoming.frag_count {
            return Err(ProtocolError::CursorFragment);
        }
        if self.shape_id != incoming.shape_id || self.frag_count != incoming.frag_count {
            self.shape_id = incoming.shape_id;
            self.frag_count = incoming.frag_count;
            self.parts = vec![None; incoming.frag_count as usize];
        }
        self.parts[incoming.frag_index as usize] = Some(incoming.pixels.clone());
        if self.parts.iter().any(Option::is_none) {
            let mut pending = incoming;
            pending.pixels.clear();
            return Ok(pending);
        }
        let mut pixels = Vec::new();
        for part in self.parts.iter().flatten() {
            pixels.extend_from_slice(part);
        }
        let expected = incoming.bitmap_w as usize * incoming.bitmap_h as usize * 4;
        if pixels.len() != expected {
            return Err(ProtocolError::CursorFragment);
        }
        Ok(CursorState {
            pixels,
            frag_index: 0,
            frag_count: 1,
            ..incoming
        })
    }
}

pub fn decode_packet(bytes: &[u8]) -> Result<ControlPacket, ProtocolError> {
    if bytes.len() < HEADER_LEN {
        return Err(ProtocolError::Truncated);
    }
    if &bytes[0..4] != MAGIC {
        return Err(ProtocolError::Magic);
    }
    if bytes[4] != VERSION {
        return Err(ProtocolError::Version(bytes[4]));
    }
    let kind = bytes[5];
    let payload_len = u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
    if bytes.len() != HEADER_LEN + payload_len {
        return Err(ProtocolError::Length);
    }
    let p = &bytes[HEADER_LEN..];
    match kind {
        KIND_CLIENT_HELLO => {
            let (name, rest) = read_name(p).ok_or(ProtocolError::Payload(kind))?;
            if rest.len() != 2 {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::ClientHello {
                name,
                video_port: u16::from_le_bytes(rest.try_into().unwrap()),
            })
        }
        KIND_HOST_HELLO => decode_host_hello(p).ok_or(ProtocolError::Payload(kind)),
        KIND_CURSOR => decode_cursor(p).ok_or(ProtocolError::Payload(kind)),
        KIND_POINTER_MOVE => {
            if p.len() != 13 && p.len() != 14 {
                return Err(ProtocolError::Payload(kind));
            }
            if p.len() == 14 && p[13] > 1 {
                return Err(ProtocolError::Payload(kind));
            }
            let norm_x = f32::from_le_bytes(p[1..5].try_into().unwrap());
            let norm_y = f32::from_le_bytes(p[5..9].try_into().unwrap());
            if !norm_x.is_finite()
                || !norm_y.is_finite()
                || !(0.0..=1.0).contains(&norm_x)
                || !(0.0..=1.0).contains(&norm_y)
            {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::PointerMove {
                monitor_index: p[0],
                norm_x,
                norm_y,
                sequence: u32::from_le_bytes(p[9..13].try_into().unwrap()),
                left_down: p.get(13).copied().unwrap_or(0) == 1,
            })
        }
        KIND_POINTER_CLICK => {
            if p.len() != 14 || !matches!(p[9], 1..=3) {
                return Err(ProtocolError::Payload(kind));
            }
            let norm_x = f32::from_le_bytes(p[1..5].try_into().unwrap());
            let norm_y = f32::from_le_bytes(p[5..9].try_into().unwrap());
            if !norm_x.is_finite()
                || !norm_y.is_finite()
                || !(0.0..=1.0).contains(&norm_x)
                || !(0.0..=1.0).contains(&norm_y)
            {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::PointerClick {
                monitor_index: p[0],
                norm_x,
                norm_y,
                button: p[9],
                sequence: u32::from_le_bytes(p[10..14].try_into().unwrap()),
            })
        }
        KIND_TOUCHPAD_STATE => {
            if p.len() != 13 || !matches!(p[0], 0 | 2) {
                return Err(ProtocolError::Payload(kind));
            }
            let x = f32::from_le_bytes(p[1..5].try_into().unwrap());
            let y = f32::from_le_bytes(p[5..9].try_into().unwrap());
            if !x.is_finite()
                || !y.is_finite()
                || !(0.0..=1.0).contains(&x)
                || !(0.0..=1.0).contains(&y)
            {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::TouchpadState {
                fingers: p[0],
                x,
                y,
                sequence: u32::from_le_bytes(p[9..13].try_into().unwrap()),
            })
        }
        KIND_MONITOR_COMMAND => {
            if p.len() != 10 || p[4] > 3 || (p[4] != 1 && p[5] >= 6) {
                return Err(ProtocolError::Payload(kind));
            }
            let width = u16::from_le_bytes(p[6..8].try_into().unwrap());
            let height = u16::from_le_bytes(p[8..10].try_into().unwrap());
            if matches!(p[4], 1 | 3)
                && (width < 320
                    || height < 200
                    || width > 3840
                    || height > 2160
                    || width % 2 != 0
                    || height % 2 != 0)
            {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::MonitorCommand {
                request_id: u32::from_le_bytes(p[..4].try_into().unwrap()),
                action: p[4],
                index: p[5],
                width,
                height,
            })
        }
        KIND_MONITOR_STATUS => {
            if p.len() < 8 || p[4] > 1 || p.len() != 8 + p[7] as usize {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::MonitorStatus {
                request_id: u32::from_le_bytes(p[..4].try_into().unwrap()),
                ok: p[4] != 0,
                available: p[5],
                owned: p[6],
                message: String::from_utf8_lossy(&p[8..]).into_owned(),
            })
        }
        KIND_KEYFRAME_REQUEST => {
            if p.len() != 1 {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::KeyframeRequest {
                monitor_index: p[0],
            })
        }
        KIND_LAYOUT => decode_layout(p).ok_or(ProtocolError::Payload(kind)),
        KIND_GOODBYE => {
            if p.is_empty() {
                Ok(ControlPacket::Goodbye)
            } else {
                Err(ProtocolError::Payload(kind))
            }
        }
        KIND_BEACON => {
            if p.len() < 3 {
                return Err(ProtocolError::Payload(kind));
            }
            let listen_port = u16::from_le_bytes(p[0..2].try_into().unwrap());
            let (name, rest) = read_name(&p[2..]).ok_or(ProtocolError::Payload(kind))?;
            if !rest.is_empty() {
                return Err(ProtocolError::Payload(kind));
            }
            Ok(ControlPacket::Beacon { listen_port, name })
        }
        other => Err(ProtocolError::Kind(other)),
    }
}

fn decode_host_hello(mut p: &[u8]) -> Option<ControlPacket> {
    if p.len() < 9 {
        return None;
    }
    let session_id = u64::from_le_bytes(p[0..8].try_into().ok()?);
    let count = p[8] as usize;
    p = &p[9..];
    let mut monitors = Vec::with_capacity(count);
    for _ in 0..count {
        if p.len() < 11 {
            return None;
        }
        let index = p[0];
        let width = u16::from_le_bytes(p[1..3].try_into().ok()?);
        let height = u16::from_le_bytes(p[3..5].try_into().ok()?);
        let port = u16::from_le_bytes(p[5..7].try_into().ok()?);
        let ssrc = u32::from_le_bytes(p[7..11].try_into().ok()?);
        let (name, rest) = read_name(&p[11..])?;
        p = rest;
        monitors.push(MonitorHello {
            index,
            width,
            height,
            port,
            ssrc,
            name,
        });
    }
    if !p.is_empty() {
        return None;
    }
    Some(ControlPacket::HostHello {
        session_id,
        monitors,
    })
}

fn decode_cursor(p: &[u8]) -> Option<ControlPacket> {
    if p.len() < CURSOR_FIXED {
        return None;
    }
    let cursor = CursorState {
        monitor_index: p[0],
        visible: p[1] != 0,
        hotspot_x: i16::from_le_bytes(p[2..4].try_into().ok()?),
        hotspot_y: i16::from_le_bytes(p[4..6].try_into().ok()?),
        norm_x: f32::from_le_bytes(p[6..10].try_into().ok()?),
        norm_y: f32::from_le_bytes(p[10..14].try_into().ok()?),
        shape_id: u64::from_le_bytes(p[14..22].try_into().ok()?),
        timestamp_ns: u64::from_le_bytes(p[22..30].try_into().ok()?),
        bitmap_w: u16::from_le_bytes(p[30..32].try_into().ok()?),
        bitmap_h: u16::from_le_bytes(p[32..34].try_into().ok()?),
        frag_index: u16::from_le_bytes(p[34..36].try_into().ok()?),
        frag_count: u16::from_le_bytes(p[36..38].try_into().ok()?),
        pixels: p[38..].to_vec(),
    };
    Some(ControlPacket::Cursor(cursor))
}

fn decode_layout(mut p: &[u8]) -> Option<ControlPacket> {
    if p.is_empty() {
        return None;
    }
    let count = p[0] as usize;
    p = &p[1..];
    let mut monitors = Vec::with_capacity(count);
    for _ in 0..count {
        if p.len() < 11 {
            return None;
        }
        monitors.push(LayoutEntry {
            index: p[0],
            x: i32::from_le_bytes(p[1..5].try_into().ok()?),
            y: i32::from_le_bytes(p[5..9].try_into().ok()?),
            width: u16::from_le_bytes(p[9..11].try_into().ok()?),
            height: u16::from_le_bytes(p[11..13].try_into().ok()?),
        });
        p = &p[13..];
    }
    if p.is_empty() {
        Some(ControlPacket::Layout { monitors })
    } else {
        None
    }
}

fn write_name(w: &mut Vec<u8>, name: &str) {
    let bytes = name.as_bytes();
    let len = bytes.len().min(64);
    w.push(len as u8);
    w.extend_from_slice(&bytes[..len]);
}

fn read_name(p: &[u8]) -> Option<(String, &[u8])> {
    if p.is_empty() {
        return None;
    }
    let len = p[0] as usize;
    if p.len() < 1 + len {
        return None;
    }
    let name = String::from_utf8_lossy(&p[1..1 + len]).into_owned();
    Some((name, &p[1 + len..]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_hello_roundtrip_and_magic() {
        let packet = ControlPacket::ClientHello {
            name: "xr".into(),
            video_port: 48000,
        };
        let bytes = packet.encode();
        assert_eq!(&bytes[0..4], b"FDC1");
        assert_eq!(bytes[4], VERSION);
        assert_eq!(bytes[5], KIND_CLIENT_HELLO);
        assert!(bytes.len() <= MAX_DATAGRAM);
        assert_eq!(decode_packet(&bytes).unwrap(), packet);
    }

    #[test]
    fn host_hello_and_keyframe_roundtrip() {
        let packet = ControlPacket::HostHello {
            session_id: 0x1122_3344_5566_7788,
            monitors: vec![MonitorHello {
                index: 0,
                width: 1920,
                height: 1080,
                port: 48000,
                ssrc: 0xA1B2_C3D4,
                name: "Floatdesk-1".into(),
            }],
        };
        assert_eq!(decode_packet(&packet.encode()).unwrap(), packet);
        let key = ControlPacket::KeyframeRequest {
            monitor_index: 0xff,
        };
        assert_eq!(decode_packet(&key.encode()).unwrap(), key);
        assert_eq!(
            decode_packet(&ControlPacket::Goodbye.encode()).unwrap(),
            ControlPacket::Goodbye
        );
        let beacon = ControlPacket::Beacon {
            listen_port: 47000,
            name: "desk".into(),
        };
        assert_eq!(decode_packet(&beacon.encode()).unwrap(), beacon);
    }

    #[test]
    fn cursor_fragments_reassemble() {
        let pixels: Vec<u8> = (0..30 * 28 * 4).map(|i| i as u8).collect();
        let state = CursorState {
            monitor_index: 1,
            visible: true,
            hotspot_x: 3,
            hotspot_y: 4,
            norm_x: 0.25,
            norm_y: 0.5,
            shape_id: 99,
            timestamp_ns: 1234,
            bitmap_w: 30,
            bitmap_h: 28,
            frag_index: 0,
            frag_count: 0,
            pixels,
        };
        let packets = cursor_packets(&state).unwrap();
        assert!(packets.len() > 1);
        let mut asm = CursorAssembler::default();
        let mut full = None;
        for (i, packet) in packets.iter().enumerate() {
            let ControlPacket::Cursor(c) = packet else {
                panic!("cursor");
            };
            let got = asm.push(c.clone()).unwrap();
            if i + 1 == packets.len() {
                full = Some(got);
            } else {
                assert!(got.pixels.is_empty());
            }
        }
        let full = full.unwrap();
        assert_eq!(full.pixels, state.pixels);
        assert_eq!(full.norm_x, state.norm_x);
        assert!(full.visible);
    }

    #[test]
    fn rejects_bad_magic_and_length() {
        assert_eq!(
            decode_packet(b"nope").unwrap_err(),
            ProtocolError::Truncated
        );
        let mut bytes = ControlPacket::Goodbye.encode();
        bytes[0] = b'X';
        assert_eq!(decode_packet(&bytes).unwrap_err(), ProtocolError::Magic);
        bytes = ControlPacket::Goodbye.encode();
        bytes.push(0);
        assert_eq!(decode_packet(&bytes).unwrap_err(), ProtocolError::Length);
    }
}

#[cfg(test)]
mod pointer_tests {
    use super::*;
    #[test]
    fn pointer_move_roundtrip_and_validation() {
        let packet = ControlPacket::PointerMove {
            monitor_index: 2,
            norm_x: 0.25,
            norm_y: 1.0,
            sequence: u32::MAX,
            left_down: false,
        };
        let bytes = packet.encode();
        assert_eq!(bytes.len(), HEADER_LEN + 14);
        assert_eq!(bytes[5], KIND_POINTER_MOVE);
        assert_eq!(
            &bytes[HEADER_LEN..],
            &[2, 0, 0, 128, 62, 0, 0, 128, 63, 255, 255, 255, 255, 0]
        );
        assert_eq!(decode_packet(&bytes), Ok(packet));
        for x in [f32::NAN, f32::INFINITY, -0.01, 1.01] {
            let invalid = ControlPacket::PointerMove {
                monitor_index: 0,
                norm_x: x,
                norm_y: 0.0,
                sequence: 0,
                left_down: false,
            }
            .encode();
            assert_eq!(
                decode_packet(&invalid),
                Err(ProtocolError::Payload(KIND_POINTER_MOVE))
            );
        }
    }
    #[test]
    fn touchpad_wire_validation() {
        for fingers in [0, 2] {
            let packet = ControlPacket::TouchpadState {
                fingers,
                x: 0.25,
                y: 1.0,
                sequence: u32::MAX,
            };
            let bytes = packet.encode();
            assert_eq!(bytes.len(), HEADER_LEN + 13);
            assert_eq!(bytes[5], KIND_TOUCHPAD_STATE);
            assert_eq!(decode_packet(&bytes), Ok(packet));
        }
        for (fingers, x, y) in [
            (1, 0.5, 0.5),
            (5, 0.5, 0.5),
            (2, f32::NAN, 0.5),
            (3, 0.5, f32::INFINITY),
            (4, -0.01, 0.5),
            (2, 0.5, 1.01),
        ] {
            let packet = ControlPacket::TouchpadState {
                fingers,
                x,
                y,
                sequence: 0,
            };
            assert_eq!(
                decode_packet(&packet.encode()),
                Err(ProtocolError::Payload(KIND_TOUCHPAD_STATE))
            );
        }
    }
    #[test]
    fn monitor_management_roundtrip_and_validation() {
        for action in 0..=3 {
            let packet = ControlPacket::MonitorCommand {
                request_id: u32::MAX,
                action,
                index: 1,
                width: 1920,
                height: 1080,
            };
            assert_eq!(decode_packet(&packet.encode()), Ok(packet));
        }
        for (action, index, width, height) in [
            (4, 0, 1920, 1080),
            (3, 6, 1920, 1080),
            (1, 0, 319, 1080),
            (3, 0, 1921, 1080),
            (1, 0, 1920, 2162),
        ] {
            let p = ControlPacket::MonitorCommand {
                request_id: 1,
                action,
                index,
                width,
                height,
            };
            assert!(decode_packet(&p.encode()).is_err());
        }
        let packet = ControlPacket::MonitorStatus {
            request_id: 7,
            ok: false,
            available: 4,
            owned: 6,
            message: "Mode unavailable".into(),
        };
        assert_eq!(decode_packet(&packet.encode()), Ok(packet));
    }
    #[test]
    fn click_and_drag_wire_validation() {
        for button in [1, 2, 3] {
            let packet = ControlPacket::PointerClick {
                monitor_index: 0,
                norm_x: 0.5,
                norm_y: 0.25,
                button,
                sequence: 7,
            };
            assert_eq!(decode_packet(&packet.encode()), Ok(packet));
        }
        let bad = ControlPacket::PointerClick {
            monitor_index: 0,
            norm_x: 0.5,
            norm_y: 0.5,
            button: 8,
            sequence: 1,
        };
        assert!(decode_packet(&bad.encode()).is_err());
        let packet = ControlPacket::PointerMove {
            monitor_index: 0,
            norm_x: 0.5,
            norm_y: 0.5,
            sequence: 9,
            left_down: true,
        };
        let mut bytes = packet.encode();
        assert_eq!(decode_packet(&bytes), Ok(packet));
        bytes[HEADER_LEN + 13] = 2;
        assert!(decode_packet(&bytes).is_err());
    }
}
