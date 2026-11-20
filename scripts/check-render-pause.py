#!/usr/bin/env python3
"""Private Xvfb: inactive XR clients must stop baseline capture without losing control."""

from pathlib import Path
import json
import os
import signal
import socket
import struct
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="floatdesk-pause-") as temp:
    folder = Path(temp)
    x = subprocess.Popen(
        [
            "Xvfb",
            "-displayfd",
            "1",
            "-screen",
            "0",
            "800x600x24",
            "-nolisten",
            "tcp",
            "-ac",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    number = x.stdout.readline().strip()
    assert number.isdigit()
    env = dict(os.environ, DISPLAY=":" + number)
    env.pop("XAUTHORITY", None)
    control = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    control.bind(("127.0.0.1", 0))
    port = control.getsockname()[1]
    control.close()
    video = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    video.bind(("127.0.0.1", 0))
    video.settimeout(0.1)
    client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client.bind(("127.0.0.1", 0))
    client.settimeout(0.1)
    log = open(folder / "host.log", "w")
    host = subprocess.Popen(
        [
            str(root / "target/release/floatdesk"),
            "serve",
            "--existing",
            "--listen",
            f"127.0.0.1:{port}",
            "--encoder",
            "x264enc",
            "--fps",
            "30",
        ],
        env=env,
        stdout=log,
        stderr=log,
    )

    def send(kind, payload):
        client.sendto(
            b"FDC1" + struct.pack("<BBHII", 1, kind, 0, len(payload), 0) + payload,
            ("127.0.0.1", port),
        )

    def state(active):
        send(15, json.dumps({"type": "renderState", "active": active}).encode())

    def packets(seconds):
        end = time.monotonic() + seconds
        count = 0
        while time.monotonic() < end:
            try:
                video.recvfrom(65535)
                count += 1
            except socket.timeout:
                pass
        return count

    try:
        deadline = time.monotonic() + 15
        while True:
            send(1, b"\x04test" + struct.pack("<H", video.getsockname()[1]))
            try:
                reply, _ = client.recvfrom(65535)
                if reply[5] == 2:
                    break
            except socket.timeout:
                pass
            assert time.monotonic() < deadline, "Host hello timed out"
        assert packets(1) > 0, "No initial video"
        state(False)
        packets(0.4)
        assert packets(0.6) == 0, (
            "Inactive client still received encoded desktop frames"
        )
        send(1, b"\x04test" + struct.pack("<H", video.getsockname()[1]))
        assert client.recvfrom(65535)[0], "Paused control stopped responding"
        state(True)
        send(4, bytes([0]))
        assert packets(1.5) > 0, "Video did not resume"
        print("PASS baseline video pauses, control stays alive, and video resumes")
    finally:
        host.send_signal(signal.SIGTERM)
        host.wait(timeout=5)
        x.terminate()
        x.wait(timeout=5)
        video.close()
        client.close()
        log.close()
