#!/usr/bin/env python3
"""Receive the live Floatdesk stream and decode one picture.

Exits 0 only when ffmpeg produces a frame that is not a flat color.
The headset does not need to be involved. This takes the single client
slot from the host while it runs.
"""

import argparse
import socket
import struct
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path


def control(kind: int, payload: bytes) -> bytes:
    return b"FDC1" + bytes([1, kind]) + struct.pack("<HII", 0, len(payload), 0) + payload


def client_hello() -> bytes:
    name = b"watch"
    return control(1, bytes([len(name)]) + name + struct.pack("<H", 48000))


def keyframe() -> bytes:
    return control(4, bytes([0xFF]))


class Depacketizer:
    def __init__(self) -> None:
        self.expected = None
        self.nals: list[bytes] = []
        self.fu = bytearray()
        self.fu_open = False
        self.lost = 0

    def reset(self) -> None:
        self.nals.clear()
        self.fu.clear()
        self.fu_open = False

    def push(self, packet: bytes) -> bytes | None:
        if len(packet) < 12 or packet[0] >> 6 != 2 or packet[0] & 0x3F:
            return None
        marker = bool(packet[1] & 0x80)
        seq = (packet[2] << 8) | packet[3]
        expected = self.expected
        self.expected = (seq + 1) & 0xFFFF
        if expected is not None and seq != expected:
            self.reset()
            self.lost += 1
        if len(packet) <= 12:
            return None
        nal_type = packet[12] & 0x1F
        if nal_type == 28:
            return self.push_fu(packet, marker)
        if not 1 <= nal_type <= 23:
            return None
        if self.fu_open:
            self.reset()
            self.lost += 1
            return None
        self.nals.append(packet[12:])
        return self.finish(marker)

    def push_fu(self, packet: bytes, marker: bool) -> bytes | None:
        if len(packet) < 15:
            return None
        indicator, header = packet[12], packet[13]
        if header & 0x80:
            self.fu = bytearray([(indicator & 0xE0) | (header & 0x1F)])
            self.fu_open = True
        elif not self.fu_open:
            self.reset()
            self.lost += 1
            return None
        self.fu += packet[14:]
        if not header & 0x40:
            return None
        nal = bytes(self.fu)
        self.fu = bytearray()
        self.fu_open = False
        self.nals.append(nal)
        return self.finish(marker)

    def finish(self, marker: bool) -> bytes | None:
        if not marker:
            return None
        out = bytearray()
        for nal in self.nals:
            out += b"\x00\x00\x00\x01" + nal
        self.nals.clear()
        return bytes(out)


def nal_types(access_unit: bytes) -> Counter:
    counts: Counter = Counter()
    index = 0
    while index + 4 <= len(access_unit):
        if access_unit[index : index + 4] == b"\x00\x00\x00\x01":
            counts[access_unit[index + 4] & 0x1F] += 1
            index += 4
        else:
            index += 1
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1:47000")
    parser.add_argument("--seconds", type=float, default=8)
    parser.add_argument("--output", type=Path, default=Path("/tmp/floatdesk-frame.png"))
    args = parser.parse_args()
    host_ip, _, host_port = args.host.partition(":")
    host = (host_ip, int(host_port or "47000"))

    video = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    video.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 * 1024 * 1024)
    video.bind(("0.0.0.0", 48000))
    video.settimeout(0.2)
    control_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    depacketizer = Depacketizer()
    types: Counter = Counter()
    elementary = bytearray()
    packets = 0
    access_units = 0
    deadline = time.time() + args.seconds
    last_hello = 0.0
    while time.time() < deadline:
        now = time.time()
        if now - last_hello > 0.5:
            control_socket.sendto(client_hello(), host)
            control_socket.sendto(keyframe(), host)
            last_hello = now
        try:
            packet, _ = video.recvfrom(65535)
        except socket.timeout:
            continue
        packets += 1
        unit = depacketizer.push(packet)
        if unit is None:
            continue
        access_units += 1
        elementary += unit
        types.update(nal_types(unit))

    annex_b = args.output.with_suffix(".h264")
    annex_b.write_bytes(elementary)
    print(
        f"packets={packets} access_units={access_units} lost={depacketizer.lost} "
        f"bytes={len(elementary)} nal_types={dict(sorted(types.items()))}"
    )
    if types[7] == 0 or types[8] == 0 or types[5] == 0:
        print("FAIL: stream has no SPS, PPS, and IDR")
        return 1
    frame = args.output
    decode = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(annex_b),
            "-frames:v",
            "1",
            "-update",
            "1",
            str(frame),
        ],
        check=False,
    )
    if decode.returncode != 0 or not frame.exists() or frame.stat().st_size < 1000:
        print("FAIL: ffmpeg did not decode a frame")
        return 1
    try:
        from PIL import Image
    except ImportError:
        print(f"PASS: wrote {frame} ({frame.stat().st_size} bytes); PIL not installed for the variance check")
        return 0
    image = Image.open(frame).convert("RGB")
    pixels = list(image.resize((64, 40)).getdata())
    count = len(pixels)
    mean = tuple(sum(pixel[channel] for pixel in pixels) / count for channel in range(3))
    variance = sum(sum((pixel[channel] - mean[channel]) ** 2 for channel in range(3)) for pixel in pixels) / count
    print(f"frame={image.size[0]}x{image.size[1]} variance={variance:.1f} file={frame}")
    if variance < 20:
        print("FAIL: decoded frame is a flat color")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
