# Changes since the LAN proof of concept

This covers the work after `b8eba1a` ("Show the laptop screen on the headset without a typed address"). The headset still finds the computer by itself, and `--existing` still streams the physical panel.

These notes describe the code. The JVM checks do not prove headset placement, joint accuracy, or picture stability. Those still have to be confirmed on the device.

## Host

The pointer is no longer a separate overlay. XFixes does not put the cursor in the framebuffer, so the host reads the pointer position every few milliseconds and paints the cursor image into the frame before encode. The shape is fetched only when the cursor serial changes.

XDamage does not fire for a cursor-only move. A pointer move marks the capture dirty, and the next frame interval sends a picture with the new cursor. A still desktop with a still pointer does not keep encoding.

The H.264 encoder prefers `vah264enc` when it is present (`x264enc` remains the fallback). The VA profile is low-latency CBR: no B-frames, one reference frame, a 400 Kb coded-picture buffer. The GOP is about eight seconds, because a full-screen keyframe every second stalled the encoder. `appsrc` does not block. Encoded frames are always read back before another raw frame is submitted. The appsink keeps two buffers and drops incoming encoded buffers when full (`leaky-type=upstream`) instead of blocking the encoder thread.

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

The miniature outline/pinch guides use native glTF meshes; aiming beams, tethers and contact decorations use stable custom-mesh SurfaceEntities parented to the desk root. They are not interactable panels, so they do not take the hand rays. Regenerate the meshes with `python3 tools/generate_grab_guides.py` from `client/android-xr`.

## Spherical monitor display

The palm panel's Enable spherical / Disable spherical button saves a workspace-wide setting, off by default. The mesh lies on a sphere centered between the eyes, with radius equal to the distance to the monitor center. An equal-edge angular quadrilateral net gives horizontal and vertical neighboring grid rays their prescribed central-angle steps at a shared angular pitch per image pixel. The grid can shear as required by spherical geometry. Extreme fields are limited uniformly to avoid folds. Invalid viewpoints freeze the last valid geometry; viewer-coordinate changes above 1 cm refit it.

Curved video uses a dedicated native SurfaceEntity created with its mesh and supersampling disabled, outside Compose's rectangular canvas sizing. The retained flat video owner is masked and disabled in curved mode. A separate system rectangle behind the image provides native move/resize controls. A transparent native glTF interaction patch shares the curved video's mesh and transform. Resizing updates the bounds used to validate contacts. Mode switches route the decoder to the appropriate surface; head-driven curved updates reuse the same decoder surface.

Raw pinch state freezes geometry before platform DOWN acquisition. The frozen image, contact markers and miniature outline use that same geometry throughout a grab. The hidden video continues receiving frames and reappears immediately on release.

## Coordinates, tracking and hand aiming

Native contact positions include the activity-root scale, while the event's hit-localization matrix does not. The listener computes `local = hitInfo.transform * (rawHit / activityRootScale)` and validates the result against the actual surface. Recorded-contact regressions cover this conversion, including independent left/right sequences. Native move/resize handles use relative gesture deltas and consume large discontinuities as new references without moving the monitor. Rearming a rejected two-point grab does not disable handles.

Head-quality checks gate follow independently of raw hand tracking. Tracking gaps and discontinuities suspend follow until 300 ms of stable samples, then follow rebases. Space-origin changes cancel gestures and reset follow. A visible activity stays awake and reapplies tracking configuration on resume. Physical boundaries and sensor availability remain system-controlled.

The user confirmed that Settings → Input → Aiming method → Use hand aiming only keeps hand rays available without waving. Mixed eye/hand aiming can switch away from the hand pointer. Independent app-level eye gaze alongside hand aiming is now implemented, subject to eye-tracking permission and the limitations below.

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

## Mouse, interaction and stream checkpoint — 10 November 2026

This checkpoint adds direct hand/gaze mouse routing, optional click/drag/scroll gestures, palm gaze activation and interaction/rendering recovery. Gesture reliability remains incomplete. At the user’s request, further gesture work is deferred; [the gesture issue record](docs/gesture-issues.md) contains observed failures, current constants, diagnostics and resume criteria.

- Hand mouse aiming remains available independently of the desktop Eyes toggle. One ray takes precedence over gaze; two rays freeze pointer motion and show grab preview. Gesture and eye preferences persist separately.
- Index pinch releases click, a 350 ms hold drags, middle pinch releases right-click, and middle pinch plus 8 mm palm translation scrolls immediately. Combined contact produces middle-click. Contacts and rearming are binary and independent; no desktop click minimum hold is intended. Rapid physical pinches and triple-clicks are still unreliable despite synthetic replay checks.
- The palm panel supports sticky eye focus plus same-hand middle pinch, independent of desktop Eyes and OS aiming selection. Its existing activation threshold/dwell remain separate. Fine eye resources stay configured while active; head and eye poses are composed in the correct spaces.
- Native monitor input preserves the matched entity/hit matrix. In curved mode the old flat owner is disabled. A tangent-aligned system rectangle sits behind both flat and curved video, preventing interception and providing stable one-hand fallback movement/resizing. App geometry does not rewrite an active system controller; final resize dimensions refit the latest curved mesh.
- Left/right rays and grab guides use cyan/violet. Screen pointer icons and redundant contact dots are hidden. Stable unit ray meshes avoid the observed native Impress replacement crash. The guides remain visible across video and monitor edges.
- Tracking checks reject duplicates/discontinuities. A single identity established before a new overlapping clone can retain mouse ownership; ambiguous pairs remain blocked. This mitigates app input interruption, not the OS-drawn duplicate hand. Deferred notes preserve the user’s observations outside Floatdesk and recovery after a headset reboot.
- Mouse datagrams carry validated coordinates and independent sequences. The host scopes input to the connected peer, deduplicates clicks, uses XTest buttons/drag and a native libevdev/uinput two-contact touchpad. Buttons/contacts have release leases. Pointer and pad motion are coalesced off the UI thread; click and release edges are retained. Scrolling needs writable `/dev/uinput` and `libevdev.so.2`.
- Video uses a stable decoder input and EGL compositor across flat/curved surface handoffs. Output drains while input is idle; identical overlays do not resubmit stale frames. Hidden video releases decoder/output resources; visibility return requests a keyframe, and composition disposal closes the connection.
- Host capture is paced between starts rather than completion plus a full interval. Raw encoder input keeps one pending frame and replaces obsolete frames before encoding; nonblocking max-bytes alone had allowed backlog. X event failures now propagate to the existing reconnect loop. Read-only pacing and slow-consumer queue checks cover these cases.

Verification commands and their limits are documented in [the checks README](client/android-xr/checks/README.md). Hardware quick-pinch reliability is explicitly not established by the automated checks. Local troubleshooting captures/build backups remain ignored; this checkpoint contains no raw personal desktop images or logs.

Checkpoint validation on 10 November 2026: Android debug build and lint; all ten JVM checks; Rust workspace tests/release build/format check; private-Xvfb XTest checks; read-only live capture/reconnection and passive uinput classification; and offscreen headset EGL/MediaCodec pause/resume checks passed. The Android unit-test task has no test sources. Clippy completes with existing warnings in unchanged code; it is not a warning-free result. Gesture reliability remains deferred despite these checks.

## Dynamic screen pool — 10 November 2026

The headset's Workspace button now opens screen management. With `serve --existing`, active XRandR outputs stream independently; Add/Remove/resolution commands manage only virtual outputs created by the current host process. The already-provisioned VKMS pool supplies real KDE desktop screens without privileged runtime setup. Capture/decoder reconfiguration and source-identity layout reconciliation preserve existing placements. Retried commands are deduplicated, and graceful host shutdown releases its outputs. See [multi-monitor behavior and limitations](docs/multi-monitor.md).

11 November device follow-up: screen creation worked, but native renderer crashes reported unknown material instances. Curved colliders, snapshots and guide entities now release their native instances before their model resources. Late curved-collider loads reject disposed/replaced owners. Added lifetime and late-completion regressions; hardware stability remains to be rechecked.

11 November combined-desktop follow-up: the lifetime fix did not eliminate the reported native material crash. Existing-desktop mode now captures the X11 arrangement as one object/stream, with one spherical projection. Output inventory remains separate for Add/Remove/resolution management. Video fits within 4096 × 2160 while mouse input maps to full desktop bounds. Stability remains pending a sustained headset test.

11 November streaming follow-up: completed encoded pictures drain even without a new XDamage capture; video packet trains are paced to reduce UDP bursts. Conversion/scaling uses the measured faster fused four-worker path, with the prior path as fallback. Packet-loss recovery remains necessary on lossy links. Added pacing regression; validated static large-desktop output/recovery on private Xvfb. Device smoothness remains pending retest.

Workspace now includes a draggable desktop arrangement map with stronger edge/centre snapping and apply-on-release position commands. External X11 layout changes are detected in a background worker and reload capture/catalogue without opening Workspace. Virtual modes use the lowest advertised positive refresh rate. Addition/removal/resolution edits preserve deliberate positions.

The encoder's sender runs independently of capture; its bounded compressed queue applies backpressure instead of dropping H.264 reference frames. Raw input still replaces obsolete frames. Setup assistance adds read-only diagnostics and reviewable boot/login installation bundles, without modifying the existing VKMS installation. Distribution-specific provider automation remains release work.

Connector names/DRM roles now identify screens independently of primary selection and position; mirrored outputs stay listed. Plasma changes apply enablement/mode/position/priorities in one transaction, preserving the existing order. Cached XRandR observation is faster, unchanged desktop bounds avoid video restart, same-format source changes reuse decoding, and reconfiguration has a visible progress indicator. Display requests retry safely and reconcile automatically instead of warning users to refresh.

XRender downscales before SHM readback, with fallback; measured readback time fell from ~53 to ~26 ms on the current desktop. Cursor scaling preserves full-resolution input mapping. Bounded packet retransmission repairs isolated losses and incomplete frame tails before keyframe fallback, rejecting unbound peers, inactive sources and late retired-source traffic. These improve known bottlenecks without establishing stall-free playback on every link.

Sub-Hz modes are excluded from interactive selection after a real full-resolution Plasma transaction exposed a multi-second flip wait (`@0` mode). The lowest responsive supported rate is retained. Partially applied additions retain management ownership, and SIGTERM/SIGHUP now shut the host down gracefully.

12 November: blank non-screen pixels in the combined desktop before encoding, preventing stale window fragments in gaps. Cached clipping follows arrangement changes without changing the stream geometry or decoding path. Pixel-centre coverage avoids seams at fractional shared edges; the cursor is clipped too.

### Connected screen layout and XR gaps — 12 November 2026

Layout edits now reject orphan screens, including corner-only attachment and deleting a bridge output. The host validates against fresh output geometry before mutation, with the editor rejecting invalid drops locally. Existing externally disconnected arrangements can be repaired incrementally.

The client masks unused desktop areas to transparent in live video and snapshots. Workspace adds saved visual spacing with inverse mouse mapping; logical screen edges stay connected. Actual black screen content remains opaque. No gesture tuning is included.

Visual spacing now explains its per-dimension reduction and uses matching 0.1% slider/display precision. Previously the continuous value was displayed as a truncated integer.

Screen edits now reject overlapping output rectangles as well as orphan screens. The arrangement map centers/fits the screen union with space on every side, and adds edge-to-midpoint snapping. Moving beyond the top/left translates the full logical arrangement atomically to valid X11 coordinates while retaining screen priorities.

### Host capabilities and tray

The host now selects the lowest positive advertised virtual refresh, including sub-Hz modes. Slow display changes execute in a worker instead of blocking control/repair/heartbeat processing. Workspace receives a named output catalogue with actual modes and refresh, supports custom mode provisioning, and shows an advisory memory/CPU pixel estimate. The fixed client count/resolution choices and six-rectangle mask restriction are removed. A Python/PySide6 tray provides service controls, logs, output status and authorized first-time setup; server startup also attempts unprivileged backend preparation. Real kernel, X11, decoder and transport limits remain.

Workspace now has a pinned × close control, concise pixel totals and high-contrast resolution inputs. Resolution changes reflow connected output positions as needed and verify the resulting dimensions. Live video returns to opaque composition with exact geometric holes for transparent gaps, preserving the continuous native-ray path. Unanchored layouts persist a viewer reference, restore relative to the new XR session, and save atomically on pause/stop. Regression checks cover these geometry, blending and persistence contracts.

Workspace’s × control now has a circular background. Status, core actions, content/capture pixel totals and stream dimensions/scale stay fixed while arrangement and resolution controls scroll. Capture quality and gaze-centered projection feasibility are documented; stream packing and spherical projection are unchanged.

### Layout latency and release jitter

Position edits now suspend virtual scanout inside a guarded native RandR transaction before changing framebuffer bounds, then restore the exact original modes. This avoids repeated low-rate waits across intermediate configurations. A live production move measured 6.2 seconds; the earlier KScreen bounds-change comparison took 93 seconds. Refresh rates remain unchanged. Add/remove/resolution changes retain the desktop backend, and driver pending-scanout waits can still delay position changes. Partial failures attempt to restore the original layout.

Arrangement drops discard the final 100 ms of pinch-opening aim movement using input timestamps; preview remains live and sample history is bounded. Packed atlas streaming and view-adaptive detail remain proposals, with tradeoffs documented in `docs/multi-monitor.md`.

### Dynamic spherical geometry and lost-tracking recovery

Spherical mode now reshapes surface vertices with a view-centered spherical exponential map. The full gnomonic map was removed because it made the entire desktop read as planar. Checks require a straight viewed axis, curved peripheral edges, constant vertex radius, an anchored desktop center and non-rigid deformation. Flat mode remains unchanged under head turns. Texture-only correction is disabled. Video, snapshots, exact hand-ray resolution and backing controls share the geometry; the backing frame contains the complete curved outline.

Native input uses one reusable shared-material triangle grid, transformed to follow the current surface without loading/destroying GLTF models on head turns. Transform reconstruction and resource-identity checks cover the grid. Geometry freezes during actual interaction and resumes on release; tiny backing-frame size changes from float roundoff do not replace its material. Native hit behavior and long-running stability still need headset confirmation.

Substantial pose changes after a sustained tracking gap restore unanchored layouts relative to the last trusted viewer. The system warning remains under Android XR control. Adaptive/cropped streaming remains unimplemented.

### Recover discovery across network interfaces

A Wi-Fi beacon followed by Ethernet control replies could leave the client on Updating screens indefinitely, with no decoder started. Discovery now retries other fresh addresses advertised under the selected host name while retaining strict reply-endpoint matching. Healthy connections stay pinned. Beacon polling no longer adds a 200 ms wait per control packet. A real UDP regression reproduces asymmetric reply routing, and live selection of the reply interface restored video reception and resolved the pending screen query.
