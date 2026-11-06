//! Minimal RFC 6184 packetizer. Single NAL units and FU-A only.
//! Payload type 96, 90 kHz clock, no CSRCs, no header extensions.

pub const RTP_PAYLOAD_TYPE: u8 = 96;
pub const RTP_CLOCK_HZ: u32 = 90_000;
const RTP_HEADER: usize = 12;
const DEFAULT_MAX_PAYLOAD: usize = 1200;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Depacketized {
    NeedMore,
    /// One access unit in Annex-B (each NAL prefixed with a 4-byte start code).
    AccessUnit(u32),
    Lost,
}

pub struct RtpH264Packetizer {
    ssrc: u32,
    sequence: u16,
    max_payload: usize,
}

impl RtpH264Packetizer {
    pub fn new(ssrc: u32) -> Self {
        Self {
            ssrc,
            sequence: 0,
            max_payload: DEFAULT_MAX_PAYLOAD,
        }
    }

    pub fn packetize(&mut self, annex_b: &[u8], rtp_timestamp: u32) -> Vec<Vec<u8>> {
        let nals = split_annex_b(annex_b);
        let mut packets = Vec::new();
        let last_nal = nals.len().saturating_sub(1);
        for (nal_index, nal) in nals.iter().enumerate() {
            if nal.is_empty() {
                continue;
            }
            let marker_nal = nal_index == last_nal;
            if nal.len() <= self.max_payload {
                packets.push(self.packet(rtp_timestamp, marker_nal, nal));
                continue;
            }
            let nal_header = nal[0];
            let fu_indicator = (nal_header & 0xE0) | 28;
            let nal_type = nal_header & 0x1F;
            let body = &nal[1..];
            let chunk = self.max_payload.saturating_sub(2).max(1);
            let pieces: Vec<_> = body.chunks(chunk).collect();
            let last_piece = pieces.len().saturating_sub(1);
            for (i, piece) in pieces.iter().enumerate() {
                let mut fu_header = nal_type;
                if i == 0 {
                    fu_header |= 0x80;
                }
                if i == last_piece {
                    fu_header |= 0x40;
                }
                let mut payload = Vec::with_capacity(2 + piece.len());
                payload.push(fu_indicator);
                payload.push(fu_header);
                payload.extend_from_slice(piece);
                packets.push(self.packet(rtp_timestamp, marker_nal && i == last_piece, &payload));
            }
        }
        packets
    }

    fn packet(&mut self, timestamp: u32, marker: bool, payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(RTP_HEADER + payload.len());
        out.push(0x80);
        out.push((u8::from(marker) << 7) | (RTP_PAYLOAD_TYPE & 0x7F));
        out.extend_from_slice(&self.sequence.to_be_bytes());
        out.extend_from_slice(&timestamp.to_be_bytes());
        out.extend_from_slice(&self.ssrc.to_be_bytes());
        out.extend_from_slice(payload);
        self.sequence = self.sequence.wrapping_add(1);
        out
    }
}

#[derive(Debug, Default)]
pub struct RtpH264Depacketizer {
    expected_seq: Option<u16>,
    nals: Vec<Vec<u8>>,
    fu: Vec<u8>,
    fu_open: bool,
    au: Vec<u8>,
}

impl RtpH264Depacketizer {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one datagram. On [`Depacketized::AccessUnit`] the Annex-B bytes are in [`Self::take_access_unit`].
    pub fn push(&mut self, packet: &[u8]) -> Result<Depacketized, ()> {
        if packet.len() < RTP_HEADER || packet[0] >> 6 != 2 {
            return Err(());
        }
        if packet[0] & 0x3F != 0 {
            // CSRC, extension, or padding. The host does not send these.
            return Err(());
        }
        let marker = packet[1] & 0x80 != 0;
        let seq = u16::from_be_bytes([packet[2], packet[3]]);
        if let Some(expected) = self.expected_seq {
            if seq != expected {
                self.reset_picture();
                self.expected_seq = Some(seq.wrapping_add(1));
                return Ok(Depacketized::Lost);
            }
        }
        self.expected_seq = Some(seq.wrapping_add(1));
        let payload = &packet[RTP_HEADER..];
        if payload.is_empty() {
            return Err(());
        }
        let nal_type = payload[0] & 0x1F;
        if nal_type == 28 {
            self.push_fu(payload, marker)
        } else if (1..=23).contains(&nal_type) {
            if self.fu_open {
                self.reset_picture();
                return Ok(Depacketized::Lost);
            }
            self.nals.push(payload.to_vec());
            self.finish_if_marker(marker)
        } else {
            Err(())
        }
    }

    pub fn take_access_unit(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.au)
    }

    fn push_fu(&mut self, payload: &[u8], marker: bool) -> Result<Depacketized, ()> {
        if payload.len() < 3 {
            return Err(());
        }
        let indicator = payload[0];
        let header = payload[1];
        let start = header & 0x80 != 0;
        let end = header & 0x40 != 0;
        let body = &payload[2..];
        if start {
            self.fu.clear();
            self.fu.push((indicator & 0xE0) | (header & 0x1F));
            self.fu_open = true;
        } else if !self.fu_open {
            self.reset_picture();
            return Ok(Depacketized::Lost);
        }
        self.fu.extend_from_slice(body);
        if end {
            let nal = std::mem::take(&mut self.fu);
            self.fu_open = false;
            self.nals.push(nal);
            self.finish_if_marker(marker)
        } else if marker {
            self.reset_picture();
            Ok(Depacketized::Lost)
        } else {
            Ok(Depacketized::NeedMore)
        }
    }

    fn finish_if_marker(&mut self, marker: bool) -> Result<Depacketized, ()> {
        if !marker {
            return Ok(Depacketized::NeedMore);
        }
        let nals = std::mem::take(&mut self.nals);
        self.au.clear();
        for nal in &nals {
            self.au.extend_from_slice(&[0, 0, 0, 1]);
            self.au.extend_from_slice(nal);
        }
        let ts_placeholder = 0;
        Ok(Depacketized::AccessUnit(ts_placeholder))
    }

    fn reset_picture(&mut self) {
        self.nals.clear();
        self.fu.clear();
        self.fu_open = false;
    }
}

pub fn split_annex_b(data: &[u8]) -> Vec<&[u8]> {
    let mut marks = Vec::new();
    let mut i = 0;
    while i + 3 <= data.len() {
        if let Some(len) = start_code_len(data, i) {
            marks.push((i, i + len));
            i += len;
        } else {
            i += 1;
        }
    }
    let mut nals = Vec::with_capacity(marks.len());
    for (index, (_code, nal)) in marks.iter().copied().enumerate() {
        let end = marks
            .get(index + 1)
            .map(|(code, _)| *code)
            .unwrap_or(data.len());
        if end > nal {
            nals.push(&data[nal..end]);
        }
    }
    nals
}

fn start_code_len(data: &[u8], i: usize) -> Option<usize> {
    if i + 4 <= data.len() && data[i..i + 4] == [0, 0, 0, 1] {
        Some(4)
    } else if i + 3 <= data.len() && data[i..i + 3] == [0, 0, 1] {
        Some(3)
    } else {
        None
    }
}

/// 90 kHz RTP timestamp from a shared host monotonic epoch.
pub fn rtp_timestamp(capture_ns: u64, epoch_ns: u64) -> u32 {
    let delta_us = capture_ns.saturating_sub(epoch_ns) / 1_000;
    ((delta_us.saturating_mul(90)) / 1_000) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_annex_b_start_codes() {
        let data = [
            0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE, 0, 0, 0, 1, 0x65, 0x88,
        ];
        let nals = split_annex_b(&data);
        assert_eq!(
            nals,
            vec![&[0x67, 0x42][..], &[0x68, 0xCE][..], &[0x65, 0x88][..]]
        );
    }

    #[test]
    fn roundtrip_small_and_fragmented_access_unit() {
        let mut annex = vec![0, 0, 0, 1, 0x67, 0x42, 0x00];
        annex.extend_from_slice(&[0, 0, 0, 1, 0x65]);
        annex.extend(std::iter::repeat(0x11).take(4000));
        let mut packetizer = RtpH264Packetizer::new(0xAABB_CCDD);
        let packets = packetizer.packetize(&annex, 9000);
        assert!(packets.len() > 2);
        assert!(packets.iter().rev().nth(0).unwrap()[1] & 0x80 != 0);
        assert!(packets.iter().rev().nth(1).unwrap()[1] & 0x80 == 0);

        let mut de = RtpH264Depacketizer::new();
        let mut done = false;
        for packet in &packets {
            if matches!(de.push(packet).unwrap(), Depacketized::AccessUnit(_)) {
                done = true;
            }
        }
        assert!(done);
        let got = de.take_access_unit();
        assert_eq!(split_annex_b(&got), split_annex_b(&annex));
    }

    #[test]
    fn a_missing_fragment_discards_the_access_unit() {
        let mut annex = vec![0, 0, 0, 1, 0x65];
        annex.extend(std::iter::repeat(0x22).take(5000));
        let mut packetizer = RtpH264Packetizer::new(7);
        let packets = packetizer.packetize(&annex, 1);
        let mut de = RtpH264Depacketizer::new();
        let mut lost = false;
        for (i, packet) in packets.iter().enumerate() {
            if i == 1 {
                continue;
            }
            if matches!(de.push(packet).unwrap(), Depacketized::Lost) {
                lost = true;
            }
        }
        assert!(lost);
        assert!(de.take_access_unit().is_empty());
    }

    #[test]
    fn timestamps_share_an_epoch() {
        let epoch = 1_000_000_000;
        assert_eq!(rtp_timestamp(epoch, epoch), 0);
        assert_eq!(rtp_timestamp(epoch + 1_000_000_000, epoch), 90_000);
    }
}
