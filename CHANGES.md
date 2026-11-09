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

Short, still thumb/middle pinch right-clicks; moving the pinching hand by 8 mm enters scrolling immediately using planar movement and freezes the cursor at the initial target. Releasing ends scrolling without a click. Tracking loss, jumps, disabled gestures and two-hand/panel interaction release scroll contacts and require a fresh pinch. Raised-finger air swipes were removed after device feedback. Only zero/two-contact virtual touchpad frames are accepted. Index+middle+thumb still provides middle-click, which commonly pastes the primary selection on Linux.

Palm gaze highlighting was confirmed on the headset. Same-hand thumb/middle pinch now activates the focused button instead of thumb folding. A panel that was opened with a full palm stays visible while the middle finger bends, provided the other three fingers remain extended and the palm still faces the viewer. Fresh-open arming, a 60 ms pinch confirmation and current gaze focus prevent repeats and stale-focus activation. Visual press feedback and the system touch sound remain.

A GPU compositor combines live decoded video with contact rings. The decoder feeds a stable SurfaceTexture; the compositor outputs to the existing native flat/curved surfaces. Native hit coordinates and ownership remain unchanged. Output pixel dimensions are set explicitly, and switching output surfaces retains the last frame without restarting the decoder. The video path now draws contact rings only. Aiming beams use one transparent native SurfaceEntity custom mesh for the entire physical segment, with no input policy or interaction component. This replaces the mixed native/glTF and projected-video beam whose position and intensity still disagreed at the monitor edge. The mesh faces the viewer and retains true stereo depth along the beam. This removes the dependency on native 3D decorations appearing above a separately composited video layer.

Android build/lint and JVM input checks cover these changes. An offscreen real-headset EGL check validates video orientation, ray/ring pixels, overlay clearing and output handoff without launching an Activity. Native aiming alignment, one-hand panel activation and scrolling still require user verification.

Stationary tracked hands retain their native contact indefinitely after an idle hover-exit; movement beyond 15 mm, rotation beyond 5 degrees or tracking loss releases a retained contact. Fresh native hover still takes priority, and hover never arms a monitor grab. Aiming beams and video contact rings now hide during two-hand preview/grabbing. Grab tethers and contact dots share the proven native-surface beam rendering path, including during live preview. Native event localization selects the monitor's matching hit from the full hit stack so decorations appearing before the curved interaction patch do not discard its contact or substitute another entity's matrix. Checks cover one-hour idle retention, pose-change release and a curved DOWN behind a foreign front hit.


Index and middle mouse pinches now use independent thresholds: index enters at 14 mm/releases at 19 mm; middle enters at 19 mm/releases at 25 mm. Combined middle-click requires both fingers to meet their respective thresholds. The user-confirmed palm gesture remains unchanged. The unsuccessful hidden-owner shape change is removed, restoring its prior ownership and shape behavior.

Decorative rays and grab markers now use flat custom-mesh canvases instead of native quad canvases, preventing decorations from adding rectangular input targets over the curved monitor. Native input remains on the existing monitor/interaction patch. This needs device verification of the persistent left-middle dead region and reported doubled-hand image. Floatdesk renders no hand meshes, one activity was active, and its main panel was already disabled. A temporary native hit sweep returned no intersections across both working and failing custom-mesh regions, so it was not useful for coverage and has been removed.


The previously confirmed palm presentation is restored: open-palm detection, facing threshold 0.5 and no added presentation dwell. Same-hand middle-pinch activation and feedback remain. The stricter palm presentation was an incorrect response to a symptom reported as a tracking clue.

Curved input now restores its confirmed interaction component and parent configuration, removing the pointer-style component added with mouse aiming. Recorded coordinate conversion is unchanged. The dead region and doubled-hand behavior still require a controlled device comparison against the archived, user-confirmed spherical APK. The currently built mouse-feature APK is retained separately during this temporary reference install.

The archived reference also rejected custom two-hand grabbing in the reported region, while system one-hand movement acquired that same area. Spherical mode now disables the hidden Compose-owned surface, preserving its pose/lifecycle but removing all of its input consumers. The dedicated curved canvas and interaction patch remain enabled. Flat mode and cleanup restore the owner's original enabled state. Native move/resize controls are provided by a separate backing rectangle with ResizableComponent and MovableComponent, fixed aspect, synchronized transform, pending-grab suppression and cleanup on mode changes. Stale resize callbacks are generation-guarded. The current mouse build replaces the temporary reference APK; palm behavior and mouse gestures remain available. Device confirmation is still required for the dead region and doubled hands.

Geometry tests reproduce the reported left-middle and bottom dead-region patterns for the two logged viewer positions: curved points lie behind the old monitor-local z=0 resize plane. The resize frame now uses the mesh's tangent basis, sits slightly behind the tangent plane, and uses corner-view-ray intersections for its bounds. Thus all curve vertices lie in front of the frame and the frame corners align visually with the image corners from the sphere center. Native resizing uses relative size changes anchored at START, preserving the source aspect and preventing a jump from differing frame/image extents or native size units. Native input interception still needs on-device confirmation.

The user confirmed that the aligned frame resolves the unresponsive areas. The accepted APK is archived before subsequent control changes. Native resize events now queue a geometry refresh that bypasses raw pre-pinch freezing, including the final END size. The queue clears only when the latest workspace size matches the rendered geometry, accounting for resize events arriving during asynchronous collider creation. Explicit resize/move controls cancel pending single-body contacts and own their sequence; body events cannot cancel an active handle operation. Actual two-hand grabs still freeze their captured geometry. Mode switches also bypass raw pinch freezing.

The custom Move grip is removed. A system-controlled rectangle behind the image provides move and resize in both modes. Its border extends slightly beyond the image to provide an exposed move target; resizing follows the image corner frame. Distance-based scaling remains disabled, and stale callbacks are ignored.

The resize flicker regression was caused by feeding changing app geometry back into the active native controller. `MonitorSystemControls` now leaves the rectangle's pose, dimensions, constraints and affordance size untouched from START through END. The system's proposed rectangle remains authoritative during the gesture; the app realigns it after release. Flat-mode frame geometry is also perspective-corrected to the source corner rays. Only one resize controller is attached: the old Compose resize modifier is removed. Canonical/rendered size mismatches force a geometry refresh whenever the two-hand controller is idle, preserving the actual-grab freeze.

The backing rectangle uses system movement (`createSystemMovable`) and the native resize overlay, with a transparent backing buffer. The app does not draw a second custom outline or move grip. The rectangle and overlay are shared by flat and curved modes.

The user confirmed native system move/resize and curved input coverage work. Palm gaze logs showed repeated fine-eye enable/disable cycles following panel visibility, sometimes less than a second apart. Eye tracking now stays configured while permission is granted and the app is resumed, independently of desktop eye-mouse routing and panel visibility. A small lifecycle controller reacquires resources after resume, retries missing/stalled eye resources and lost configuration at bounded intervals, and clears resources on permission loss. Device/head resource acquisition retries no longer depend on discovering fewer than two hands. Panel gaze uses valid tracked eye/device data independently of the follow-motion stability guard; desktop gaze retains that guard. Panel gesture/geometry and monitor controls are unchanged.

Palm-panel activation now accepts middle-pinch spacing below 26 mm, while retaining gaze focus, confirmation time and fresh-open arming. Desktop mouse pinch thresholds are independently tightened by 1 mm for each finger: index 15/20 mm entry/release and middle 20/26 mm. Combined middle-click uses those same separate finger thresholds. No monitor or gaze lifecycle changes accompany this tuning.

Each hand's state/joints are now captured once for an update and shared by pinch, palm, fist and grip logic. A hand-quality guard detects near-identical overlapping left/right samples, recent identity switches, implausible pose changes and jitter; rejected samples remain quarantined for 200 ms after stability returns. Rejected native hover identities are cleared so they cannot create false two-hand previews. Uncertain input hides palm UI and blocks actions, cancels native control gestures and resets mouse gestures; an epoch guard rejects late clicks from an invalidated panel. Stable first-frame input has no added dwell. Diagnostics log rejected sides and reason categories to determine whether the reported doubled-hand visual corresponds to bad joint data. The app does not render hand meshes, so removal of the system visual is not yet confirmed.

The accepted palm-panel pinch threshold remains 26 mm. Mouse index/middle entry is tightened by another 1 mm to 14/19 mm, with release at 19/25 mm.

Two device crashes during pinch testing resolve to Impress custom-mesh replacement in the aiming-ray renderer. Ray/tether/contact surfaces now retain a unit mesh and change only transform/scale, avoiding repeated native mesh replacement on the input thread. Mouse clicks no longer discard sampled contacts shorter than 35 ms; the input loop polls every 10 ms rather than 20 ms. Pinch distance thresholds and palm activation are unchanged. Scroll release logging distinguishes gesture release from desktop touchpad momentum during follow-up testing.

Mouse joint sampling now runs independently of scene submission, buffering 5 ms samples so a brief contact/release is not overwritten between UI updates. Buffered input older than 120 ms is cancelled rather than replayed. A rejected hand is excluded individually instead of disabling gestures on the healthy hand; overlapping identities still block both. Palm safety gating and pinch distance thresholds remain unchanged.

Hand reacquisition after a tracking gap starts a fresh motion history rather than automatically quarantining the hand for a pose jump. Left index mouse entry/release is independently eased to 15.5/20.5 mm; right index and both middle thresholds remain unchanged. Native pointer icons are suppressed on flat and curved screen targets, and redundant aiming contact dots are removed so the desktop cursor remains visible. Left/right aiming beams and grab guides consistently use cyan/violet. Aiming-beam transforms also update directly from native hover delivery, avoiding the extra gesture-loop wait; custom beams still depend on app event/compositor timing rather than system prediction.

Pinch release hysteresis is narrowed to 1 mm so contacts cannot remain held in the former 5–6 mm gap. Index gestures require an independently observed open position (24 mm) before each new pinch, preventing near-contact resting jitter from rearming clicks. Entry thresholds are unchanged. Native aiming surfaces are updated on display frames, with visual-only prediction capped at 30 ms / 3.5% of screen extent; prediction never changes cursor targets or grab contacts and stops for stale hits or discontinuities. Diagnostic counters separately report native hover delivery and display-frame cadence.
