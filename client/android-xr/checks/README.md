Run gesture regression checks with a cached Kotlin compiler (no Android device or Gradle daemon required):

```sh
python3 checks/gestures.py /path/to/gradle/lib
```

The directory must contain Kotlin compiler and standard library jars. The checks extract the production controller and math unchanged, substituting only platform input/value types. They verify the miniature-monitor mapping, initial identity/no jump, fixed gain from contact span versus hand span, amplified translation and independent depth, both held pixels under rotation/scale, minimum contact/hand separation, DEFAULT and physical release, tracking-loss cancellation, fresh-grip rearming, and discontinuity rejection. They also cover continuous depth rotation, scaled roots, unclamped endpoints, anatomical palm alignment, fist thresholds, and palm touches. The grab guide checks verify that the miniature corners interpolate to the pinch positions at the same pixel fractions, that real markers lie on the screen, and that release/tracking loss clears the guide.

They do not validate headset coordinate conversion, joint accuracy, visual ray availability, native move event ordering, text rendering, or physical placement. Those require device verification.

Frozen image mesh validation:

```sh
python3 checks/frozen_frame.py /path/to/gradle/lib
```

This runs the production frozen-frame GLB writer on the JVM and validates the embedded PNG, chunk alignment/bounds, opaque material, and texture orientation. PixelCopy, native model loading, hidden-surface video updates and immediate surface restoration need a headset check.

RTP recovery checks:

```sh
python3 checks/rtp.py /path/to/gradle/lib
```

These extract the production depacketizer and reorder buffer and check access-unit assembly, sequence wrap, fragment loss, partial-frame rejection, out-of-order delivery, host source changes, and sequence restart after downtime.

The native guide assets are checked in for packaging. Regenerate them with `python3 tools/generate_grab_guides.py` from `client/android-xr`.

The gesture checks also validate spherical projection at centered and offset viewpoints, constant vertex radius, pixel hit inversion, curved contact capture, the curved guide outline, and the Spherical palm button. Frozen-frame checks cover both flat and spherical snapshot meshes, including radius preservation under monitor scaling. Live custom-mesh support, UV orientation, input hit testing and curvature updates still require headset verification.

Display lifecycle checks:

```sh
python3 checks/monitor_warp.py /path/to/gradle/lib
```

These run the production curvature controller with SceneCore substitutes. They verify that flat mode retains the original Compose-owned surface, alpha, parent and enabled state; spherical mode uses a separate native video canvas and matching transparent interaction patch; returning to flat mode restores its surface and listener; and cleanup does not detach Compose's entity. Curvature and decoder surface identity remain fixed during a grab and during head-driven mesh updates within the same mode. Gesture checks cover native Start identity, relative translation/rotation without orbiting an unrelated native origin, handle availability during two-point rearm, curved contacts and physical release.

Headset checks remain necessary for native handle visibility, SDK-local DOWN hit coordinates, pointer behavior and system-origin relocalization. The app cannot guarantee physical world locking while the operating system has lost its spatial map.

Native localization regressions include activity-root scale compensation, a recorded device hit/matrix reproducing the offset, and independent left/right DOWN/UP sequences through the production native event bridge. The runtime hit matrix maps unscaled event coordinates into screen-local coordinates.

Confirmed device baseline — 2026-11-08:

The user confirmed that two-point manipulation and coordinate alignment work in the installed scale-corrected build. Preserve this as the reference for future interaction changes. The conversion is `local = hitInfo.transform * (rawHit / activityRootScale)`, using the original Compose-owned surface and native input listener. Native move/resize handles must remain available.

Accepted limitation: two-point control requires sufficiently separated contacts. At small monitor sizes, use the native handle or Reset; do not weaken separation safeguards as an unsolicited fix. Current thresholds are contact span >= max(12 cm in physical units, 18% of monitor width), and hand span >= 9 cm.

Hand-ray availability was subsequently confirmed with Android XR set to hand-only aiming. Mixed eye/hand aiming can switch away from hand rays. This acceptance confirms coordinates and usable manipulation; system tracking loss and relocalization still require device checks.

Before modifying input coordinates, surface ownership, scale conversion or movement arbitration, run the gesture and native-event lifecycle checks and compare with this baseline. Device-check center/edge contact alignment, repeat grab/release, native handles, and the small-monitor fallback.

The equal-angle warp checks measure chord lengths (equivalently central angles at a fixed radius) across every horizontal and vertical neighboring vertex, for both centered and off-axis viewers. They also check projection/inverse texture alignment, constant radius, angular aspect under extreme-field limiting, and flat/curved snapshot geometry. Uniform longitude/elevation alone is deliberately not accepted as proof of equal neighboring-ray angles. Native hand-only aiming was confirmed on the headset by the user; no application pointer override is needed.

### Confirmed spherical rendering and input

On 8 November 2026 the user confirmed that the dedicated native curved video surface matches the native frozen image on grab and that outer-edge two-hand grabs work. Keep the curved video and interaction patch on the same mesh and transform. Flat mode continues to use the original Compose-owned surface and its recorded coordinate conversion.

### Direct pointer control

`gestures.py` additionally checks off/gaze/one-hand/two-hand/grab arbitration, hover handoff and post-grab protection, loss of hand tracking, gaze bounds, preview endpoints matching an actual grab and both bottom-row palm buttons. `monitor_warp.py` verifies that hover updates are observational and do not enter the pinch event sequence.

```sh
python3 checks/pointer_protocol.py /path/to/gradle/lib
```

This checks the production Android encoder against the Rust pointer packet fixture, including unsigned sequence values and rejection of non-finite/out-of-bounds targets. Rust tests check that only the active peer can move the pointer, sequence wrap works and monitor offsets map correctly.

On the headset, enable mouse control and grant eye permission with hand-only system aiming. Check gaze-only motion, immediate one-hand takeover, a faint contact marker, cursor freeze with both hands, a preview without a pinch, and actual grab/release in flat and spherical modes. Also verify toggle-off, looking away, tracking loss, permission denial and return to the standard handles.

```sh
python3 checks/pointer_dispatch.py /path/to/gradle/lib
```

This extracts the production outgoing methods and uses real loopback UDP with a socket that rejects UI-thread sends. It verifies background pointer/keyframe delivery, coalescing a burst into the latest target and a bounded pending queue. The Android runtime itself still requires device testing.

### Stabilized gaze and optional mouse gestures

```sh
python3 checks/mouse_gestures.py /path/to/gradle/lib
```

These run the actual filter and gesture state machines: reduced fixation jitter, isolated-outlier rejection, fast intentional shifts, tracking reset, click/double-pinch location, right-click, hold-to-drag, cross-hand clicking and cancellation on disable/preview/tracking loss. The existing pointer tests also cover click wire format, retries and uncoalesced button edges.

From the repository root, `python3 scripts/check-mouse-x11.py` starts a private Xvfb display and runs the ignored real-XTest integration test. It verifies button edges, pointer coordinates, dragging, the timeout lease and cleanup; it never targets the user's display.

The current eye toggle controls only gaze; hand aiming remains active. The separate gesture toggle defaults off. Device-check short and double pinches, hold/release, middle-finger right-click, opposite-hand clicks, cancellation during two-hand grab/preview, gesture disable during a drag, gaze smoothing and lack of normal one-hand body movement. Explicit resize handles are retained; the old standard movement behavior was intentionally disabled at the user's request.
