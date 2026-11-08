# Floatdesk

An X11-first host that places logical monitors in an Android XR workspace. Each monitor is its own H.264 stream. The host composites the cursor into the video and schedules frames when the pointer moves, even on an otherwise static desktop. All monitors share one workspace coordinate space.

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

`client/android-xr` is a Jetpack XR app. It decodes each RTP stream with `MediaCodec` straight into a `SpatialExternalSurface`, receives the cursor within the video, and stores monitor poses in the workspace. Room anchoring is available when supported by the headset.

The headset finds the computer on the LAN by itself. `floatdesk serve` announces itself once a second. Open the app and wait until the status says it found the machine. There is no address to type.

Face an open palm toward yourself to show the control panel, and touch its buttons with the other hand. A firmly clenched fist held briefly makes the desk follow your head; the Follow button toggles persistent follow.

Pinch two well-separated points on a monitor to manipulate it in 3D. Hand translation, depth, rotation, and separation move and scale a miniature monitor held between your hands; the real monitor mirrors that transformation. A wireframe, fixed contact markers, and tethers show the mapping. Closely spaced contacts are rejected, and releasing either pinch or losing tracking ends the grab. During manipulation a frozen snapshot replaces the video visually so the guides stay visible. The hidden video surface continues receiving frames and reappears on release.

Regression checks and device-validation limits are documented in [the Android checks README](client/android-xr/checks/README.md).

Plasma does not treat a RandR monitor with no physical output as a desktop, so a virtual `Floatdesk-N` region stays empty. Stream the laptop panel instead:

```bash
./target/release/floatdesk serve --existing
```

## Layout

```text
crates/protocol    control datagrams, RTP H.264, workspace math
crates/host        X11 capture, GStreamer encoder, RTP session
client/android-xr  Jetpack XR receiver
benchmarks         netem helper for latency, jitter, and loss
packaging          systemd unit
```
