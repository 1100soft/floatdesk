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

The palm panel's Enable spherical / Disable spherical button saves a workspace-wide setting, off by default. When enabled, the live video uses a rectangular mesh projected radially onto a sphere centered between the viewer's eyes. The radius is the distance to each monitor's center. Both axes curve, and the center and viewing directions stay fixed. The mesh updates as the viewer moves; unsupported viewpoints behind or too close to the monitor use the flat display.

The live video is rendered on a separate, non-interactive sibling surface. The original flat Compose quad stays enabled as an invisible input target, so mesh changes and grab snapshots cannot cancel its input sequence. Its logical pixel hits are projected onto the curved screen for grabbing. The decoder surface stays the same when toggling curvature. The curved geometry freezes at the first grab contact so the contact points, frozen image and miniature outline remain rigid throughout manipulation. It refits to the viewer on release. The new geometry checks run on the JVM; live mesh rendering and interaction still need device verification.

## Checks

From `client/android-xr`, with a directory of Kotlin compiler jars:

```sh
python3 checks/gestures.py /path/to/gradle/lib
python3 checks/frozen_frame.py /path/to/gradle/lib
python3 checks/rtp.py /path/to/gradle/lib
```

`gestures.py` checks the grab mapping, fist and palm thresholds, and the guide geometry. `frozen_frame.py` checks the snapshot mesh. `rtp.py` checks depacketizing and reordering. None of them run on the headset.

## Host process disappearance, 8 November 2026

A later complete stop had no host process and no listener on UDP 47000. The old `/tmp/floatdesk-va.log` ended mid-line at exactly 262,144 bytes. That is consistent with a file-size limit killing the background process; its exit signal was not retained, so the precise termination reason cannot be proven. This was different from the earlier live-process encoder deadlock.

`scripts/serve-existing.sh` starts the existing-desktop host as the `floatdesk-live` user service, with automatic restart on failure, journal output and an unlimited regular-file size limit. The packaged unit uses the same logging and limit settings. Host recovery was verified by observing client reconnection and fresh video packets on the headset after restarting the service.

Restart recovery also resets the headset reorder/depacketizer state when the RTP source changes, or when sequence numbers restart backwards after downtime, and requests a fresh keyframe. Capture-thread retries retain their packetizer sequence within one host process. The RTP regression checks cover host source changes, duplicate rejection, backwards sequence restart after idle and partial-frame cleanup.

## Grab, projection and tracking consistency

Device DOWN events reported local hit positions with more than a meter of plane depth, causing the old bounds check to reject actual monitor pinches. The controller now transforms the event's activity-space ray into the monitor input entity and intersects the actual flat or spherical surface. Input and video use identical geometry outside an active grab. Native move/resize callbacks were removed so they cannot write a second, inconsistent monitor transform. Shape-generated CANCEL events do not end an active grab while both raw pinches remain held; physical release and tracking loss remain authoritative.

Spherical mapping now uses a conformal stereographic parameterization so local horizontal and vertical pixel scales match at off-center viewpoints. Source aspect ratios are reconciled when connecting. The app uses a large explicit Subspace volume rather than the recommended content box. Tracking is gated by lifecycle and device tracking state; gaps and sudden pose changes suspend movement until 300 ms of stable samples, then follow rebases rather than applying accumulated displacement. A visible activity keeps the screen on and reapplies hand/head configuration on resume. Physical safety boundaries and OS tracking suspension remain system-controlled.

## Restore the confirmed interaction path

The later invisible proxy surface, mandatory ray-intersection gate and head-quality filtering of hand data regressed grabbing. Restore the original Compose-owned video/input surface and SDK-local DOWN hits from checkpoint `dbe8b36`, while keeping the spherical toggle and stream-recovery fixes. Restore native move/resize handles, with relative native gesture deltas so an absolute Start pose cannot snap a saved monitor position. Consume large native pose discontinuities as new references without replaying their displacement. Two-point rearm does not disable normal handles. Raw tracked hands remain available independently of the head-quality filter. Space-origin changes cancel gestures and rebase follow; they are logged for distinguishing system relocalization from app movement. The speculative 100 m Subspace override was removed.

The external commits after the working checkpoint changed host capture/encoding and documentation; they did not change Android grab or handle code. Those host changes are preserved.

A live test of the restored surface confirmed one successful lock but several rejected attempts with both hands tracked. SDK-reported local hits still had large plane depth. Ray resolution now explicitly inverts `getPose(Space.ACTIVITY)` and `getScale(Space.ACTIVITY)` instead of calling the SDK localization conversion used by the earlier failed ray gate. This is optional, retaining the checkpoint path when no intersection resolves. Diagnostic records include the event origin/direction, rendered pose/scale and resolved contact for device validation.

Live native-event records isolated the remaining offset: hit positions include the activity-root scale (about1.785 in the recorded session), but the hit-localization matrix does not. Applying the event matrix after removing that scale puts all62 recorded flat contacts on the screen plane (maximum residual below0.001mm). The native input listener now performs that conversion directly, tracks left/right action sequences independently, and forwards local contacts to the existing two-point controller. Contacts are validated against the actual surface; the known offset fallback is not accepted unless it independently lies on the surface. The earlier manual-ray and perception-space hypotheses were not validated and are not the active conversion. Tests include the recorded hit/matrix and a scaled native-event lifecycle case.

## Device-confirmed coordinate baseline, 8 November 2026

The user confirmed the scale-corrected coordinate path works. Two-point manipulation remains available above the safe minimum contact separation; native handles or Reset are the accepted fallback for smaller monitors. Intermittently disappearing hand rays remain unresolved; an OS limitation is possible but unproven. Future input changes should preserve this state and rerun the recorded-coordinate/native-event regressions, followed by center/edge and handle checks on the headset.
