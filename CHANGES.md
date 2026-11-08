# Changes since the LAN proof of concept

This covers the work after `b8eba1a` ("Show the laptop screen on the headset without a typed address"). The headset still finds the computer by itself, and `--existing` still streams the physical panel.

These notes describe the code. The JVM checks do not prove headset placement, joint accuracy, or picture stability. Those still have to be confirmed on the device.

## Host

The pointer is no longer a separate overlay. XFixes does not put the cursor in the framebuffer, so the host reads the pointer position every few milliseconds and paints the cursor image into the frame before encode. The shape is fetched only when the cursor serial changes.

XDamage does not fire for a cursor-only move. A pointer move marks the capture dirty, and the next frame interval sends a picture with the new cursor. A still desktop with a still pointer does not keep encoding.

The H.264 encoder prefers `vah264enc` when it is present (`x264enc` remains the fallback). The VA profile is low-latency CBR: no B-frames, one reference frame, a 400 Kb coded-picture buffer. The GOP is about eight seconds, because a full-screen keyframe every second stalled the encoder. `appsrc` does not block. Encoded frames are always read back before another raw frame is submitted. The appsink keeps two buffers and leaks the oldest (`leaky-type=upstream`) instead of blocking the encoder thread.

### Capture stall, 8 November 2026

After the pointer started forcing a frame on every interval, the stream began to stop while the process stayed up. It happened twice: the frame and skip counters froze at 08:07, and again at 09:04. The capture thread was asleep on a futex, and the GStreamer `src` thread was too. The status line kept printing the same counters.

The cause is a queue deadlock, not an X11 reply that never arrives. A moving pointer now captures continuously at 2560×1600. The encoder was limited to one finished frame and blocked when that slot was full. The path that treated "encoder busy" as a dropped input returned without reading the output. The encoder then refused more input until that output was read, and nothing read it.

The capture thread now drains encoded frames before each submit, and again if a submit is refused. A refused submit also requests a keyframe, so a discarded picture does not leave the headset decoder on a broken reference. If a connected client still sees no capture progress for two seconds, the host shuts down the X11 socket. That unblocks a reply wait that has no timeout, and the capture thread opens a new connection instead of staying stuck. The logs for those two stalls are in `.agent-artifacts/host-troubleshooting.log`, which is not part of the tree.

## Headset video

RTP is reassembled on its own thread, with a short reorder window so a packet that arrives slightly out of order is not treated as a hole. A real sequence gap, or a decoder queue that cannot accept another frame, drops non-keyframes until the next keyframe and asks the host for one. SPS and PPS are sent together in `csd-0`. The decoder is configured for low latency, and frames are rendered to the `SpatialExternalSurface`.

The cursor is inside that video. There is no second cursor panel on the stream.

## Spatial desk

Monitor and control-panel poses are stored in the workspace file and applied in the Compose subspace. The subspace root is not activity space: its scale and pose are read from the live surface parent, and head and hand poses are converted into that space before they move anything.

Follow keeps each panel fixed relative to the head by applying `headNow * inverse(headAtLock)` to the pose captured when follow started. A closed fist has to stay closed (about a third of a second) before follow starts, and opening the hand stops it. The Follow button latches follow until Unlock, until a grab starts, or until the workspace is reset. Follow does not run during a two-hand grab.

Turning an open palm toward the face shows a small control panel in front of that hand. The other hand's index fingertip pokes its buttons: Follow, Reset desk, Reconnect, Workspace, and Enable spherical / Disable spherical. The panel hides when that hand turns away.

## Two-hand monitor grab

A grab starts when both hands pinch the same monitor, far enough apart. The two contact points are fixed on the screen. Between the hands, a miniature of the monitor is held so those contacts match the pinch points. Moving, separating, or turning the hands moves that miniature; the real monitor repeats the same translation, rotation, and uniform scale.

While the grab lasts, the live video surface is hidden and a frozen snapshot of the current frame is shown in its place, so the guides stay visible. The decoder keeps writing to the hidden surface. Releasing either pinch, or losing tracking, ends the grab and shows the live video again. Contacts that are too close together are rejected.

The guides are native glTF meshes (points and tethers) parented to the desk root. They are not interactable panels, so they do not take the hand rays. Regenerate the meshes with `python3 tools/generate_grab_guides.py` from `client/android-xr`.

## Spherical monitor display

The palm panel's Enable spherical / Disable spherical button saves a workspace-wide setting, off by default. The mesh lies on a sphere centered between the eyes, with radius equal to the distance to the monitor center. An equal-edge angular quadrilateral net gives horizontal and vertical neighboring grid rays their prescribed central-angle steps at a shared angular pitch per image pixel. The grid can shear as required by spherical geometry. Extreme fields are limited uniformly to avoid folds. Invalid viewpoints freeze the last valid geometry; viewer-coordinate changes above 1 cm refit it.

Curved video uses a dedicated native SurfaceEntity created with its mesh and supersampling disabled, outside Compose's rectangular canvas sizing. A transparent alpha mask hides the retained flat video buffer without hiding its entity or standard handles. A transparent native glTF interaction patch shares the curved video's mesh and transform. Resizing updates the bounds used to validate contacts. Mode switches route the decoder to the appropriate surface; head-driven curved updates reuse the same decoder surface.

Raw pinch state freezes geometry before platform DOWN acquisition. The frozen image, contact markers and miniature outline use that same geometry throughout a grab. The hidden video continues receiving frames and reappears immediately on release.

## Coordinates, tracking and hand aiming

Native contact positions include the activity-root scale, while the event's hit-localization matrix does not. The listener computes `local = hitInfo.transform * (rawHit / activityRootScale)` and validates the result against the actual surface. Recorded-contact regressions cover this conversion, including independent left/right sequences. Native move/resize handles use relative gesture deltas and consume large discontinuities as new references without moving the monitor. Rearming a rejected two-point grab does not disable handles.

Head-quality checks gate follow independently of raw hand tracking. Tracking gaps and discontinuities suspend follow until 300 ms of stable samples, then follow rebases. Space-origin changes cancel gestures and reset follow. A visible activity stays awake and reapplies tracking configuration on resume. Physical boundaries and sensor availability remain system-controlled.

The user confirmed that Settings → Input → Aiming method → Use hand aiming only keeps hand rays available without waving. Mixed eye/hand aiming can switch away from the hand pointer. Independent app-level eye gaze alongside hand aiming remains a future feature requiring permission and device verification.

## Streaming recovery

`scripts/serve-existing.sh` starts the existing-desktop host as the `floatdesk-live` user service, with restart on failure, journal output and an unlimited regular-file size limit. The packaged unit uses the same logging and limit settings. This avoids tying the host to a terminal/tool lifetime or regular-file log limit. Restart recovery was verified on the headset.

The receiver resets reorder/depacketizer state when the RTP source changes or sequence numbers restart backwards after downtime, then requests a keyframe. Capture-thread retries retain packetizer sequence numbers within one host process. The other agent's existing host capture/encoder fixes are preserved.

## Checks and confirmed regression baseline

From `client/android-xr`, with a directory of Kotlin compiler jars:

```bash
python3 checks/gestures.py /path/to/gradle/lib
python3 checks/monitor_warp.py /path/to/gradle/lib
python3 checks/frozen_frame.py /path/to/gradle/lib
python3 checks/rtp.py /path/to/gradle/lib
```

These cover grab transforms, gesture thresholds, spherical radius and angular sampling, pixel inversion, mode-switch surface ownership, curved interaction routing, snapshot geometry/materials, native coordinate conversion, tracking guards and RTP recovery. `python3 scripts/check-serve-existing.py` checks launcher arguments without changing a service. JVM checks cannot validate physical placement or native rendering; those need the headset.

On 8 November 2026 the user confirmed flat coordinate alignment, matching live/frozen spherical shape, and successful spherical grabs near both outer edges. Preserve the separate native curved video canvas, matching interaction patch, resized contact bounds, and event-matrix/root-scale conversion. Native handles or Reset remain the accepted fallback below the minimum safe contact separation. Future interaction changes must rerun the regressions and verify center/edge grabs, live/frozen agreement, mode toggling, and native handles on the headset. Local APK/source backups preserve both confirmed baselines and are excluded from Git.

## Direct mouse control

A saved, initially disabled palm toggle enables pointer movement. With no hand hover, fine eye tracking aims the cursor; one tracked native hand hover overrides gaze; two hands freeze the cursor and preview the grab guide when both contacts are on the same monitor and meet the existing separation safeguards. Native hovers use the confirmed event-matrix/root-scale localization, and gaze rays intersect the same flat or spherical mesh. Preview generation is observational and does not arm or move a monitor. Actual grabs retain the existing controller and frozen-image behavior.

The app requests fine eye permission on enable and configures gaze independently of hand/head tracking. Eye poses are relative to the head and are composed with the current device pose before localization. Invalid/closed eyes do not aim the cursor. Permission denial leaves hand pointing available. An 80 ms hover/joint handoff grace and 120 ms post-grab pause prevent transient collider replacement from handing the cursor to gaze. One-hand mode hides the bright platform reticle and draws a small 20%-opacity native marker; platform beam brightness remains system-controlled.

Pointer packets contain a monitor index, normalized coordinates and an unsigned sequence number. The host accepts them only from its current client, validates finite normalized coordinates, rejects stale/duplicate sequences, maps into the source monitor's desktop rectangle and moves the X11 pointer. There are no generated mouse clicks. Eye tracking data stays on the headset apart from the resulting normalized cursor target. Tests cover arbitration, tracking loss, preview geometry, hover routing, cross-language wire format, host peer/sequence validation and monitor offsets. Device validation remains necessary for actual eye tracking, marker visibility and hover transitions.

The first device test confirmed preview but found no remote cursor motion. Pointer sends from the UI thread were blocked by Android and silently caught by the preexisting transport wrapper. Outgoing UI commands now dispatch on a HandlerThread; pointer updates coalesce into one pending target. Send failures are logged. A real-UDP JVM regression uses a socket that rejects UI-thread sends and verifies pointer/keyframe delivery, bounded queuing and the latest target after a burst.

## Independent eye control, stabilized gaze and mouse gestures

The user confirmed hand and eye mouse aiming and grab preview work. The former whole-mouse toggle is replaced with an eye-only toggle; hand aiming is always active. Workspace loading migrates the earlier `mouse_control` value to `eye_aiming`, while the new `mouse_gestures` flag defaults off. Normal one-hand movable behavior is removed from the monitor; explicit resize handles and the custom two-hand controller remain.

Gaze uses a speed-adaptive low-pass filter (low cutoff at rest, higher cutoff during deliberate movement), a small deadband, rejection of isolated large jumps, and reset after tracking gaps or monitor changes. Hand targets are not filtered. This reduces jitter but cannot repair inaccurate eye calibration.

Optional gestures use tracked finger distances independently of the aiming hand. A short thumb/index pinch clicks, two quick pinches keep the same click location for double-click, and holding for 350 ms begins dragging. Thumb/middle pinch with the index open right-clicks. Hysteresis and fresh-open arming prevent bounce and held gestures from triggering on enable. Two-hand preview/grab, palm controls, tracking loss and gesture disable cancel pending clicks and release dragging. Fists retain their follow function rather than generating mouse clicks.

Atomic click commands include their own deduplication sequence and are retried without producing duplicate clicks. Motion frames carry left-button state; transitions bypass motion coalescing so the press anchor and release cannot be overwritten by newer motion. XTest supplies actual X11 button events. The host releases a drag on client replacement, disconnect, shutdown or a 300 ms input timeout. An isolated-Xvfb integration check verifies real clicks, drag state, timeout and drop cleanup without moving the user's desktop pointer.


## Middle-pinch scrolling, one-hand panel and live aiming overlays

Short thumb/middle pinch right-clicks; holding for 350 ms enters scrolling using that hand's planar movement and freezes the cursor at the initial target. Releasing ends scrolling without a click. Tracking loss, jumps, disabled gestures and two-hand/panel interaction release scroll contacts and require a fresh pinch. Raised-finger air swipes were removed after device feedback. Only zero/two-contact virtual touchpad frames are accepted. Index+middle+thumb still provides middle-click, which commonly pastes the primary selection on Linux.

Palm gaze highlighting was confirmed on the headset. Same-hand thumb/middle pinch now activates the focused button instead of thumb folding. A panel that was opened with a full palm stays visible while the middle finger bends, provided the other three fingers remain extended and the palm still faces the viewer. Fresh-open arming, a 60 ms pinch confirmation and current gaze focus prevent repeats and stale-focus activation. Visual press feedback and the system touch sound remain.

A GPU compositor now combines live decoded video with faint projected ray segments and contact rings. The decoder feeds a stable SurfaceTexture; the compositor outputs to the existing native flat/curved surfaces. Native hit coordinates and ownership remain unchanged. Output pixel dimensions are set explicitly, and switching output surfaces retains the last frame without restarting the decoder. Native beams remain visible outside the monitor silhouette. This removes the dependency on native 3D decorations appearing above a separately composited video layer.

Android build/lint and JVM input checks cover these changes. An offscreen real-headset EGL check validates video orientation, ray/ring pixels, overlay clearing and output handoff without launching an Activity. Native aiming alignment, one-hand panel activation and scrolling still require user verification.
