# Floatdesk

An X11-first host that places logical monitors in an Android XR workspace. Each monitor is its own H.264 stream. The cursor travels separately, so a pointer moving over a still terminal does not force a new video frame. All monitors are children of one workspace anchor.

This tree is the LAN proof of concept: RandR 1.5 regions, MIT-SHM capture, XDamage, XFixes, GStreamer H.264, and RTP/UDP. It is not encrypted. Keep it on a trusted network. The session boundary (`DisplayBackend`, `CaptureBackend`, `CursorBackend`, `EncodeBackend`) is where Wayland, Windows, macOS, and a WebRTC transport plug in later.

## Host

Requires an X11 session (`DISPLAY`) and GStreamer with an H.264 encoder (`x264enc` is the portable fallback; `vah264enc`, `vaapih264enc`, and `nvh264enc` are used automatically when present).

```bash
cargo build --release
./target/release/floatdesk doctor
./target/release/floatdesk trial
./target/release/floatdesk serve --monitors 2 --width 1920 --height 1080
```

`doctor` only reads the server. `trial` creates one 1280×720 logical monitor named `Floatdesk-1`, checks that RandR lists it, and restores the framebuffer. `serve` enlarges the X screen, adds `Floatdesk-N` monitors with no physical output, and waits for a client hello on UDP `47000`.

`--existing` streams the current primary CRTC and does not resize the screen. That is the right mode for a loopback test:

```bash
./target/release/floatdesk recv --host 127.0.0.1:47000 --output /tmp/floatdesk.h264 --seconds 5 &
./target/release/floatdesk serve --existing --duration 4 --encoder x264enc --fps 15
```

Ctrl-C restores a virtual framebuffer. If the process is killed first, the saved size is in `$XDG_STATE_HOME/floatdesk/x11-screen.json` (or `~/.local/state/floatdesk/`). Put it back with:

```bash
floatdesk restore
```

`capture-once` writes a PPM of the top-left corner through MIT-SHM. `encode-once` writes an Annex-B file from a synthetic frame.

## Android XR client

`client/android-xr` is a Jetpack XR app. It decodes each RTP stream with `MediaCodec` straight into a `SpatialExternalSurface`, draws the cursor on top of that surface, and keeps monitor poses relative to one persisted ARCore anchor.

Open the project in Android Studio and run it on an Android XR device. Enter the host address, connect, then use the panel to nudge a monitor or save the room anchor. Scene understanding permission is required for the anchor.

The client binds UDP `48000` upward, one port per monitor, and sends hello packets to the host's port `47000`.

## Layout

```text
crates/protocol    control datagrams, RTP H.264, workspace math
crates/host        X11 capture, GStreamer encoder, RTP session
client/android-xr  Jetpack XR receiver
benchmarks         netem helper for latency, jitter, and loss
packaging          systemd unit
```
