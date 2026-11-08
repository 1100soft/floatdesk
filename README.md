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

For long-running headset use, run the existing-desktop host under your user service manager instead of leaving a background process attached to a terminal or coding tool:

```bash
cargo build --release
./scripts/serve-existing.sh --encoder vah264enc --fps 60
journalctl --user -u floatdesk-live -f
```

This session service restarts the host after a crash, sends logs to the journal, and removes inherited regular-file size limits. Omit `--encoder` to select an available encoder automatically. It uses the current X11 `DISPLAY` and `XAUTHORITY`. Stop it with `systemctl --user stop floatdesk-live`; use `systemctl --user restart floatdesk-live` to restart the running service. To change launch arguments, stop it and rerun the script. `FLOATDESK_BIN` can select a different executable.

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

Use **Enable spherical / Disable spherical** on the palm panel to curve each monitor horizontally and vertically onto a sphere centered between your eyes. The screen center stays in place and an equal-edge angular mesh keeps neighboring grid rays uniformly spaced in both axes as you move your head. The setting is saved with the workspace. During a grab, the curved shape freezes with the image to keep the contact points rigid; it refits to your viewpoint on release.

Hand-ray mouse aiming is always available. **Enable eyes / Disable eyes** on the palm panel controls only gaze aiming: with no hand ray on a monitor, enabled gaze aims the mouse; one hand ray takes priority; two hand rays stop mouse movement and preview the miniature grab guides before pinching. The bright system reticle is replaced with a small faint marker in one-hand mode. Eye aiming uses an adaptive filter and small deadband to reduce jitter while following deliberate gaze changes. Grant eye-tracking permission when enabling it. Keep Android XR's aiming method on **Use hand aiming only**; the app reads gaze independently. Denied or unavailable gaze does not disable hand aiming. Android XR still controls the ray beam itself.

**Enable gestures / Disable gestures** separately controls mouse clicks and dragging, and defaults off. Pinch thumb and index, then release for a left click; pinch twice quickly for a double-click. Hold the pinch for 350 ms to drag and release to drop. Pinch thumb and middle finger with the index open for right-click. The clicking hand can point away while the other hand aims. Two rays on the monitor reserve the interaction for preview/grabbing, so they do not generate mouse clicks. Opening the palm controls, losing the clicking hand or disabling gestures cancels the gesture and releases a drag. The host also releases a held button after 300 ms without updates.

Normal one-hand monitor movement is disabled to avoid competing with mouse input. Use the custom two-hand grab to move the monitor; explicit resize handles, Reset and Follow remain available. Previewing never moves the monitor. Eye and gesture toggles are saved independently.

What changed after the first LAN proof of concept is recorded in [CHANGES.md](CHANGES.md). Regression checks and the limits of those checks are in [the Android checks README](client/android-xr/checks/README.md).

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

Explicit resize handles and the two-point controller are available; normal one-hand monitor movement is disabled. Flat video and standard handles retain the original Compose-owned surface. Spherical video uses a separate native canvas and a transparent interaction mesh with matching geometry. Two-point contacts use raw native hit events: remove the activity-root scale, then apply the event's hit transform to obtain screen-local coordinates. The prior native movement implementation remains covered by regression checks; it is not attached to the monitor body. Rearming a rejected two-point grab does not disable normal handles. Head-quality checks gate follow independently of tracked hands, so a paused head signal does not erase valid hand samples. A visible activity stays awake and reapplies tracking configuration on resume. Android XR still controls sensor availability and physical safety boundaries.

For persistent hand rays, choose **Settings → Input → Aiming method → Use hand aiming only**. The mixed eye/hand mode can switch away from hand aiming; hand-only mode was confirmed to remove the need to wave the hands to restore rays. This does not override tracking loss when the headset cannot see a hand.
