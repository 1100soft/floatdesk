# Floatdesk

An X11-first host that places logical monitors in an Android XR workspace. In existing-desktop mode, active outputs form one H.264 stream and one XR screen, following the X11 arrangement. The host composites the cursor into the video and schedules frames when the pointer moves, even on an otherwise static desktop. All monitors share one workspace coordinate space.

This tree is the LAN proof of concept: RandR 1.5 regions, MIT-SHM capture, XDamage, XFixes, GStreamer H.264, and RTP/UDP. It is not encrypted. Keep it on a trusted network. The session boundary (`DisplayBackend`, `CaptureBackend`, `CursorBackend`, `EncodeBackend`) is where Wayland, Windows, macOS, and a WebRTC transport plug in later.

## Host

Use **Workspace → Screens** in the headset to add, resize or remove independent desktop screens from a pre-provisioned VKMS pool. Your existing four-output pool supports this without a reboot or privileged commands during use. Drag screen rectangles in Workspace to arrange them with snapping. External layout changes reload automatically. See [multi-monitor behavior](docs/multi-monitor.md) and [first-time setup](docs/virtual-display-setup.md).

Requires an X11 session (`DISPLAY`) and GStreamer with an H.264 encoder (`x264enc` is the portable fallback; `vah264enc`, `vaapih264enc`, and `nvh264enc` are used automatically when present).

```bash
cargo build --release
./target/release/floatdesk doctor
./target/release/floatdesk trial
./target/release/floatdesk serve --monitors 2 --width 1920 --height 1080
```

`doctor` only reads the server. `trial` creates one 1280×720 logical monitor named `Floatdesk-1`, checks that RandR lists it, and restores the framebuffer. `serve` enlarges the X screen, adds `Floatdesk-N` monitors with no physical output, and waits for a client hello on UDP `47000`.

`--existing` captures the bounding rectangle of active XRandR outputs (up to six), preserving their relative placement in one XR screen. It leaves their modes unchanged until you explicitly add or resize a Floatdesk-managed screen. It also works for a loopback test:

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

`client/android-xr` is a Jetpack XR app. It decodes RTP video with `MediaCodec` into a `SpatialExternalSurface`, receives the cursor within the video, and stores screen poses in the workspace. Room anchoring is available when supported by the headset.

The headset finds the computer on the LAN by itself. `floatdesk serve` announces itself once a second. Open the app and wait until the status says it found the machine. There is no address to type.

Face an open palm toward yourself to show the control panel, and touch its buttons with the other hand. A firmly clenched fist held briefly makes the desk follow your head; the Follow button toggles persistent follow.

Pinch two well-separated points on a monitor to manipulate it in 3D. Hand translation, depth, rotation, and separation move and scale a miniature monitor held between your hands; the real monitor mirrors that transformation. A wireframe, fixed contact markers, and tethers show the mapping. Aiming beams and mouse contact rings hide during preview/grabbing. Preview tethers and contact markers use native transparent surfaces so live video does not cut them off. Closely spaced contacts are rejected, and releasing either pinch or losing tracking ends the grab. During manipulation a frozen snapshot replaces the video visually so the guides stay visible. The hidden video surface continues receiving frames and reappears on release.

Use **Enable spherical / Disable spherical** on the palm panel to curve each monitor horizontally and vertically onto a sphere centered between your eyes. The screen center stays in place and an equal-edge angular mesh keeps neighboring grid rays uniformly spaced in both axes as you move your head. The setting is saved with the workspace. During a grab, the curved shape freezes with the image to keep the contact points rigid; it refits to your viewpoint on release.

Hand-ray mouse aiming is always available. **Enable eye mouse / Disable eye mouse** on the palm panel controls only gaze aiming: with no hand ray on a monitor, enabled gaze aims the mouse; one hand ray takes priority; two hand rays stop mouse movement and preview the miniature grab guides before pinching. Flat mode replaces the bright system reticle with a faint marker in one-hand mode. Spherical mode currently keeps the original SDK pointer styling while its input regression is investigated. Eye aiming uses an adaptive filter and small deadband to reduce jitter while following deliberate gaze changes. Grant eye-tracking permission when enabling it. Keep Android XR's aiming method on **Use hand aiming only**; the app reads gaze independently. Denied or unavailable gaze does not disable hand aiming. Android XR still controls its own ray beam. A faint app-rendered beam remains visible while a tracked hand has a valid monitor contact. Stationary tracked hands have no contact expiry; after a native hover-exit, moving more than 15 mm or rotating more than 5 degrees clears the retained target. The entire beam uses one thin transparent native custom-mesh surface, avoiding a seam between native and video-rendered portions. The contact ring is composited into live video; it cannot recover unavailable native aiming or tracking.

Mouse distances are measured between tracked joint positions, not the physical skin gap. Contact and release use the same fixed threshold: left index/middle 15.5/15 mm, right index/middle 12.5/14 mm. Each mouse gesture rearms independently when its own closed condition becomes false, without requiring other fingers to open. Palm-button pinch tuning is separate.

**Enable gestures / Disable gestures** separately controls mouse clicks and dragging, and defaults off. Bring the tracked thumb and index tips into contact range, then release for a left click; pinch twice quickly for a double-click. Hold the pinch for 350 ms to drag and release to drop. Pinch thumb and middle finger with the index open for right-click. Pinch thumb, index and middle together for middle-click. The clicking hand can point away while the other hand aims. Two rays on the monitor reserve the interaction for preview/grabbing, so they do not generate mouse clicks. Opening the palm controls, losing the clicking hand or disabling gestures cancels the gesture and releases a drag. The host also releases a held button after 300 ms without updates.

Pinch thumb and middle finger, then move that hand vertically or horizontally to scroll immediately. Scrolling follows palm translation so squeezing the fingers does not count as hand movement. An 8 mm movement threshold suppresses small tracking jitter; there is no hold delay. The cursor stays at the initial target; release ends scrolling without right-clicking. A middle pinch that does not enter scrolling right-clicks on release, regardless of hold duration. The index only needs to be outside its own contact range, rather than fully extended. Scrolling requires writable `/dev/uinput` and `libevdev.so.2` on the Linux host. Air swipes with raised fingers are disabled. On Linux, middle-click commonly pastes the primary selection; it is distinct from Ctrl+V clipboard paste.

The palm panel supports eye focus independently of desktop eye mouse aiming and the OS aiming method, subject to Android's eye-tracking permission. Permitted eye tracking stays ready while the app is active, so showing or briefly losing the panel does not restart sensor acquisition. The Eyes button still controls desktop cursor routing only. Look at a button until it highlights, then pinch that hand's thumb and middle finger to activate. Keep the other three fingers extended. Open the pinch before activating again. Buttons flash on activation and use the system touch sound when enabled. Ordinary blinking is not an activation gesture. Direct tapping with the other hand remains available. Discrete sticky focus helps with gaze jitter; continuous desktop gaze still depends on tracking accuracy and may be unsuitable for small targets.

In spherical mode, the hidden flat video owner is disabled so its native body cannot compete with the curved interaction mesh. The system move/resize rectangle sits behind the image, aligned with the viewer-facing tangent plane in spherical mode, with corners projected onto the image corners; returning to flat mode restores the original owner and handles.

Use the exposed border of the system rectangle for one-hand repositioning and its corners for resizing, or use the custom two-hand grab. The rectangle stays behind the image so image input remains available. Resize handles, Reset and Follow remain available. Previewing never moves the monitor. Eye and gesture toggles are saved independently.

**Gesture reliability is incomplete and further tuning is deferred.** Quick pinches, repeated/triple clicks, hand asymmetry and duplicate-hand behavior are recorded in [the deferred gesture issues](docs/gesture-issues.md). Automated replay checks do not establish reliable physical touch detection.

What changed after the first LAN proof of concept is recorded in [CHANGES.md](CHANGES.md). Regression checks and the limits of those checks are in [the Android checks README](client/android-xr/checks/README.md).

Plasma does not treat a RandR monitor with no physical output as a desktop, so a legacy virtual `Floatdesk-N` region stays empty. Use the VKMS screen controls above for independent desktops, or stream existing outputs:

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

Explicit resize handles and the two-point controller are available. Whole-image one-hand grabbing is disabled; the exposed system rectangle provides one-hand fallback movement. Flat video and standard handles retain the original Compose-owned surface. Spherical video uses a separate native canvas and a transparent interaction mesh with matching geometry. Two-point contacts use raw native hit events: remove the activity-root scale, then apply the event's hit transform to obtain screen-local coordinates. The prior native movement implementation remains covered by regression checks; it is not attached to the monitor body. Rearming a rejected two-point grab does not disable normal handles. Head-quality checks gate follow independently of tracked hands, so a paused head signal does not erase valid hand samples. A visible activity stays awake and reapplies tracking configuration on resume. Android XR still controls sensor availability and physical safety boundaries.

For persistent hand rays, choose **Settings → Input → Aiming method → Use hand aiming only**. The mixed eye/hand mode can switch away from hand aiming; hand-only mode was confirmed to remove the need to wave the hands to restore rays. This does not override tracking loss when the headset cannot see a hand.

Hand-state updates queue joint scalars immediately, with polling fallback for runtimes that do not emit buffer changes. Overlapping identities and pose jumps are guarded; a previously established owner can retain mouse input while a new clone is rejected. Uncertain input hides the palm panel and invalidates delayed callbacks. Normal palm presentation is unchanged; the duplicated system hand visual remains unresolved.
