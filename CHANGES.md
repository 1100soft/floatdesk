# Changes since the LAN proof of concept

This covers the work after `b8eba1a` ("Show the laptop screen on the headset without a typed address"). The headset still finds the computer by itself, and `--existing` still streams the physical panel.

These notes describe the code. The JVM checks do not prove headset placement, joint accuracy, or picture stability. Those still have to be confirmed on the device.

## Host

The pointer is no longer a separate overlay. XFixes does not put the cursor in the framebuffer, so the host reads the pointer position every few milliseconds and paints the cursor image into the frame before encode. The shape is fetched only when the cursor serial changes.

XDamage does not fire for a cursor-only move. A pointer move marks the capture dirty, and the next frame interval sends a picture with the new cursor. A still desktop with a still pointer does not keep encoding.

The H.264 encoder prefers `vah264enc` when it is present (`x264enc` remains the fallback). The VA profile is low-latency CBR: no B-frames, one reference frame, a 400 Kb coded-picture buffer. The GOP is about eight seconds, because a full-screen keyframe every second stalled the encoder. `appsrc` does not block; if the encoder cannot take a frame, that frame is skipped and the capture thread continues.

## Headset video

RTP is reassembled on its own thread, with a short reorder window so a packet that arrives slightly out of order is not treated as a hole. A real sequence gap, or a decoder queue that cannot accept another frame, drops non-keyframes until the next keyframe and asks the host for one. SPS and PPS are sent together in `csd-0`. The decoder is configured for low latency, and frames are rendered to the `SpatialExternalSurface`.

The cursor is inside that video. There is no second cursor panel on the stream.

## Spatial desk

Monitor and control-panel poses are stored in the workspace file and applied in the Compose subspace. The subspace root is not activity space: its scale and pose are read from the live surface parent, and head and hand poses are converted into that space before they move anything.

Follow keeps each panel fixed relative to the head by applying `headNow * inverse(headAtLock)` to the pose captured when follow started. A closed fist has to stay closed (about a third of a second) before follow starts, and opening the hand stops it. The Follow button latches follow until Unlock, until a grab starts, or until the workspace is reset. Follow does not run during a two-hand grab.

Turning an open palm toward the face shows a small control panel in front of that hand. The other hand's index fingertip pokes its buttons: follow, reset, and disconnect. The panel hides when that hand turns away.

## Two-hand monitor grab

A grab starts when both hands pinch the same monitor, far enough apart. The two contact points are fixed on the screen. Between the hands, a miniature of the monitor is held so those contacts match the pinch points. Moving, separating, or turning the hands moves that miniature; the real monitor repeats the same translation, rotation, and uniform scale.

While the grab lasts, the live video surface is hidden and a frozen snapshot of the current frame is shown in its place, so the guides stay visible. The decoder keeps writing to the hidden surface. Releasing either pinch, or losing tracking, ends the grab and shows the live video again. Contacts that are too close together are rejected.

The guides are native glTF meshes (points and tethers) parented to the desk root. They are not interactable panels, so they do not take the hand rays. Regenerate the meshes with `python3 tools/generate_grab_guides.py` from `client/android-xr`.

## Checks

From `client/android-xr`, with a directory of Kotlin compiler jars:

```sh
python3 checks/gestures.py /path/to/gradle/lib
python3 checks/frozen_frame.py /path/to/gradle/lib
python3 checks/rtp.py /path/to/gradle/lib
```

`gestures.py` checks the grab mapping, fist and palm thresholds, and the guide geometry. `frozen_frame.py` checks the snapshot mesh. `rtp.py` checks depacketizing and reordering. None of them run on the headset.
