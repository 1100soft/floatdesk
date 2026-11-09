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

On the headset, enable eye mouse aiming and grant eye permission with hand-only system aiming. Check gaze-only motion, immediate one-hand takeover, a faint contact marker, cursor freeze with both hands, a preview without a pinch, and actual grab/release in flat and spherical modes. Also verify toggle-off, looking away, tracking loss, permission denial and return to the standard handles.

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


`python3 checks/panel_input.py /path/to/gradle/lib` checks panel gaze dwell/stickiness, fresh middle-pinch activation and stale gaze rejection. Mouse gesture checks cover immediate movement-triggered middle-pinch scrolling, 8 mm planar movement threshold, no hold delay, short-still click versus long-hold suppression, cursor anchoring, release without right-click and cancellation/rearm after blocking or tracking jumps. Protocol and dispatch checks cover two-contact scrolling frames and retried release packets.

`FLOATDESK_UINPUT_TEST=1 cargo test --offline -p floatdesk platform::touchpad::tests::creates_classified_touchpad_without_emitting_touches -- --ignored --exact` creates and removes a passive virtual device, asserting udev classifies it as a touchpad. It never emits touches. Raised-finger air swipes are disabled; only zero/two-contact frames are accepted. Device-check immediate movement-triggered middle-pinch scrolling, short-pinch right-click and release on tracking loss or gesture disable.

With desktop eye aiming disabled, open the palm panel, look for a stable button highlight and pinch that hand's thumb and middle finger, keeping the other three fingers extended. Check one activation per pinch, press feedback, direct taps and cancellation on gaze loss. Hold an aiming hand still for 30 seconds to check the app beam, including its portion over the live video. The whole aiming beam is a single native transparent SurfaceEntity without an input component; verify that it stays continuous, keeps brightness and does not intercept native monitor events. Native contact/tracking availability and binocular alignment still require device verification.

`VideoCompositorSmoke.java` is an offscreen device check for the production EGL compositor. It feeds a red/blue Canvas frame into the decoder surface, asserts upright video and actual overlay pixel colors, clears the overlay, verifies repeated unchanged overlays do not requeue frames, and switches output surfaces without sending a new source frame. The same runner executes `DecoderIdleSmoke.java`: a generated red 320×240 baseline keyframe passes through the actual decoder, and must display without a subsequent compressed frame. The 320×240 fixture accommodates the headset hardware decoder’s size requirements. Run with `python3 checks/video_compositor_device.py` after building and installing the current debug APK. It does not open an Activity or send desktop input.

Mouse checks exercise binary contact/release thresholds, including repeated pinches separated by a tiny opening, and reject relaxed combined 21 mm spacing. Current mouse thresholds are left index/middle 15.5/19 mm and right index/middle 11/15 mm, measured between tracked tip joints. Palm activation is tuned separately. Headset checks remain necessary for actual fingertip contact and the new single-surface beam.

User-confirmed interaction baseline: short middle-pinch right-click, middle-pinch scrolling, and gaze-focused same-hand middle-pinch palm activation work on the headset. Preserve palm activation while changing scroll arbitration. Immediate scrolling and the continuous native aiming beam were subsequently confirmed on the headset.

Idle-contact checks hold a stationary tracked wrist past a native hover-exit for one hour, then assert that wrist translation or rotation releases it. Native curved hit tests put a foreign front surface before the actual interaction patch and preserve the monitor hit/matrix. Device-check the previously failing left-middle spherical region, aiming-beam suppression during preview, unclipped preview tethers/contact markers, relaxed hands versus actual pinches, and stationary-ray availability. The last native-surface aiming beam was user-confirmed visually consistent; preserve that rendering path.

The user confirmed unclipped preview tethers and no apparent idle timeout. The hidden-owner shape change did not resolve the dead region and has been removed. Flat and curved native lifecycle tests retain their original ownership expectations. The main panel was already disabled. Temporary native hit sweeps returned no intersections even for working custom-mesh regions, so they cannot establish coverage and were removed. Decorative surfaces now use custom meshes rather than native quad canvases to avoid rectangular input targets; device-check the persistent dead region and doubled-hand image.

Pinch regressions now use separate finger thresholds: a 16 mm index sample and 20 mm middle sample activate independently, while relaxed entry remains rejected. Combined pinches use both thresholds. Check both fingers separately on the headset rather than assuming equal joint-center spacing corresponds to equal physical contact.


Palm presentation is restored to the user-confirmed behavior; the added strict extension/facing/dwell gate is removed. Curved input also restores the original parent and interaction-only component configuration. The archived confirmed spherical APK is being used for an on-device comparison of the persistent dead region and doubled-hand visualization, while the current restored-palm APK is retained for reinstall. Do not label either issue fixed based on JVM checks.

Native lifecycle checks assert that spherical mode disables the original flat owner while the dedicated curved canvas remains enabled, then restores the original owner and interaction in flat mode. `python3 checks/curved_resize.py /path/to/gradle/lib` checks system-rectangle ownership, fixed aspect and bounds, transform updates, grab suppression, callback freshness, mode-switch disposal, recreation and rejection of stale callbacks. Device-check curved handles, full-surface aiming/two-hand grabbing and normal flat-mode handles. The archived reference build showed the same dead region for custom grabs while system movement could acquire it; do not mistake the reference's older native movement feature for the current app behavior.

The resize geometry check uses both recorded off-axis viewer positions. It verifies that the reported left-middle/bottom sample is behind the old z=0 plane, every curve vertex is in front of the new tangent frame, and all four frame corners match the image corner viewing rays. Resize-event checks include source-relative factors and a non-unit native START size so acquiring a handle does not change source size. Headset checks remain necessary for actual event interception and binocular handle placement.

The user confirmed full curved input coverage after frame alignment; the accepted device APK is archived in the ignored artifacts folder. Native lifecycle checks now hold a raw pinch while forcing a native resize, verify geometry/source size matching with a temporarily missing eye pose, and switch modes while a raw pinch remains held. Native-control checks also verify the native backing rectangle, no MovableComponent on the video, active-drag pose ownership and stale callback rejection. Device-check repeated curved resize/release followed immediately by flat/curved toggling, system rectangle movement, and continued full-surface two-hand input.

Native-control regression checks simulate the system updating its proposed resize size while app geometry and video pose change. The app must preserve the active rectangle pose, shape, scale and affordance size until END; the old controller reset would fail this check. The same ownership check covers native movement. Both flat and curved frames are tested, including projected flat corner rays, paired resize/move components on the backing rectangle, and stale callback cleanup. Device-check resize stability, the exposed rectangle border for movement, and continued image input.

Panel checks also run the production eye lifecycle controller: one-time sensor configuration and warmup, stable resources across repeated panel visibility/routing changes, stalled resource refresh without sensor resets, configuration recovery, resume handle refresh and permission revocation. On device, leave desktop eye mouse off, wait after opening the app, then repeatedly show/hide the palm panel and confirm gaze highlighting returns promptly without new sensor configure cycles. Logs include resource/tracking/open-eye counts and panel hit counts, not raw gaze vectors. Native system monitor controls and input coverage were confirmed before this eye-only change.

Separate panel/mouse threshold checks accept a focused 25 mm palm pinch while rejecting desktop index 15.5 mm and middle 20.5 mm pre-contact samples, including holds with planar motion. Existing click, drag, scroll and fresh-release checks remain.

`python3 checks/hand_quality.py /path/to/gradle/lib` runs the production quality guard against stable first-frame samples, duplicate identities, cross-hand identity switches, large/oscillatory pose changes, invalid data and recovery. It also checks explicit removal of rejected native hover targets. Native-control checks cancel an active move when input becomes disabled. Mouse checks reject 14.5 mm index/19.5 mm middle pre-contact samples; the palm check still accepts 25 mm. Headset verification is required for detection during an actual doubled-hand episode and prevention of accidental panel actions. Normal palm openness, facing and middle-pinch behavior are unchanged.
