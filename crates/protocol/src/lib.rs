//! Wire types shared by the desktop host and the XR client.
//!
//! Video access units travel as RTP payload type 96 (RFC 6184 single-NAL and
//! FU-A). Everything else — cursor, layout, keyframe requests, hello — is a
//! little-endian datagram on the control socket. A later WebRTC data channel
//! can carry the same control payload without a schema change.

mod bitrate;
mod control;
mod rtp;
mod workspace;

pub use bitrate::{allocate_bitrate, MonitorLoad};
pub use control::{
    cursor_packets, decode_packet, ControlPacket, CursorAssembler, CursorState, LayoutEntry,
    MonitorHello, ProtocolError, KIND_CLIENT_HELLO, KIND_CURSOR, KIND_GOODBYE, KIND_HOST_HELLO,
    KIND_KEYFRAME_REQUEST, KIND_LAYOUT, MAGIC, MAX_DATAGRAM, VERSION,
};
pub use rtp::{
    rtp_timestamp, split_annex_b, Depacketized, RtpH264Depacketizer, RtpH264Packetizer,
    RTP_CLOCK_HZ, RTP_PAYLOAD_TYPE,
};
pub use workspace::{
    compose_pose, desk_layout, Pose, WorkspaceFile, WorkspaceMonitor, SCHEMA_VERSION,
};

/// One encoded picture, plus the timing the XR client uses to correlate monitors.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameMeta {
    pub monitor_index: u8,
    pub sequence: u64,
    pub capture_monotonic_ns: u64,
    pub rtp_timestamp: u32,
    pub damage_generation: u64,
}
