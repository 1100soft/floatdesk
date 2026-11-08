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

These run the production curvature controller with SceneCore substitutes. They verify that video and input retain the original Compose-owned surface, alpha, parent and enabled state, that curvature freezes while held, and that cleanup does not detach Compose's entity. Gesture checks cover native Start identity, relative translation/rotation without orbiting an unrelated native origin, handle availability during two-point rearm, curved contacts and physical release.

Headset checks remain necessary for native handle visibility, SDK-local DOWN hit coordinates, pointer behavior and system-origin relocalization. The app cannot guarantee physical world locking while the operating system has lost its spatial map.

Native localization regressions include activity-root scale compensation, a recorded device hit/matrix reproducing the offset, and independent left/right DOWN/UP sequences through the production native event bridge. The runtime hit matrix maps unscaled event coordinates into screen-local coordinates.

Confirmed device baseline — 2026-11-08:

The user confirmed that two-point manipulation and coordinate alignment work in the installed scale-corrected build. Preserve this as the reference for future interaction changes. The conversion is `local = hitInfo.transform * (rawHit / activityRootScale)`, using the original Compose-owned surface and native input listener. Native move/resize handles must remain available.

Accepted limitation: two-point control requires sufficiently separated contacts. At small monitor sizes, use the native handle or Reset; do not weaken separation safeguards as an unsolicited fix. Current thresholds are contact span >= max(12 cm in physical units, 18% of monitor width), and hand span >= 9 cm.

Known unresolved issue: system hand rays intermittently disappear and return after moving/waving the hands. An OS limitation is suspected, not confirmed. This acceptance confirms coordinates and usable manipulation, not a general resolution of platform tracking/relocalization jumps.

Before modifying input coordinates, surface ownership, scale conversion or movement arbitration, run the gesture and native-event lifecycle checks and compare with this baseline. Device-check center/edge contact alignment, repeat grab/release, native handles, and the small-monitor fallback.
