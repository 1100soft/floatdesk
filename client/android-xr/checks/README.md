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


## Mouse and interaction checkpoint checks

Remaining device gesture failures are [deferred](../../../docs/gesture-issues.md). Passing a supplied-sample test does not prove physical quick-touch detection, click timing or OS hand rendering.

Run all JVM checks from the repository root with a cached Gradle Kotlin compiler directory:

```bash
for check in gestures monitor_warp frozen_frame rtp pointer_protocol pointer_dispatch mouse_gestures panel_input curved_resize hand_quality; do
  python3 "client/android-xr/checks/$check.py" /path/to/gradle/lib || exit
done
```

| Check | Coverage |
| --- | --- |
| `pointer_protocol.py` | Pointer/click/touchpad datagram layout and invalid payload rejection. |
| `pointer_dispatch.py` | Real local UDP, outgoing UI-thread avoidance, bounded motion coalescing, button edges and deduplicated click retries. |
| `mouse_gestures.py` | Gaze filter; cross-hand clicks; drag/scroll cancellation; stationary and short middle clicks; independent binary rearming; minimal opening; event-buffer triple-click bursts; stale-batch cancellation. |
| `panel_input.py` | Sticky eye focus, same-hand palm pinch, timing/rearming and hand switching. |
| `curved_resize.py` | Tangent/perspective frame placement, bounds, native controller ownership and resize synchronization. |
| `hand_quality.py` | Pose jumps, unknown/overlapping identities, established-owner retention, clone disappearance, continued mouse routing and invalidated palm callback epochs. |

Current mouse joint contact/release thresholds: left index/middle 15.5/15 mm, right index/middle 12.5/14 mm. These are fixed tracked-joint distances, not skin gaps. Palm activation remains separate (26 mm contact, 35 mm rearm, 60 ms confirmation). No device acceptance of fast desktop taps is implied.

### Offscreen headset checks

After building and installing the current debug APK:

```bash
python3 client/android-xr/checks/video_compositor_device.py
```

Requires ADB and JDK 17+ (`JAVA_HOME` or `/opt/android-studio/jbr`). The runner compiles `VideoCompositorSmoke.java` and `DecoderIdleSmoke.java` against the installed APK. It opens no Activity and injects no desktop input. It checks real EGL video orientation, overlay pixel colors/clearing, identical-overlay suppression and output handoff without a new source frame. A generated 320×240 red baseline H.264 keyframe must display with no later compressed input; hiding must stop output, and resuming must display a fresh blue keyframe. Synthetic fixtures contain no captured desktop pixels.

### Host checks

```bash
cargo fmt --all -- --check
cargo test --workspace --offline
cargo clippy --workspace --all-targets --offline
cargo build --release --offline
python3 scripts/check-mouse-x11.py
```

The XTest runner creates a private Xvfb and tests actual click edges, drag release, timeout and object-drop cleanup. Never run its ignored input test on the user’s desktop. The normal Rust suite also tests a real GStreamer slow-consumer raw queue: obsolete raw frames must not accumulate and the latest frame must survive.

Two explicit opt-in tests are separate from the default suite:

```bash
FLOATDESK_CAPTURE_BENCH=1 cargo test --offline -p floatdesk capture_cadence_read_only -- --ignored --nocapture
FLOATDESK_UINPUT_TEST=1 cargo test --offline -p floatdesk platform::touchpad::tests::creates_classified_touchpad_without_emitting_touches -- --ignored --exact
```

The capture benchmark reads the selected live X11 screen into memory for two seconds, writes no screenshots and emits no input. It closes only its own X11 client socket to verify idle polling propagates failure. A prior 2560×1600 observation improved from ~40 to ~58 fps after capture-start pacing; that excludes encode/network/display latency. The uinput test creates a passive device and checks classification without emitting touches.

Preserve the forward native event-matrix/root-scale coordinate conversion, matching live/frozen curved meshes, native frame behind the image, immediate grab release restoration, and working palm presentation. Rerun center/edge grabs, flat/curved toggling and native handles on the device only when interaction work resumes. Ignored `.agent-artifacts/` holds local baselines, including the user-confirmed smooth-stream APK/source checkpoint. They are not portable regression fixtures or committed artifacts.

### Screen management

`python3 checks/monitor_management.py /path/to/gradle/lib` checks monitor-management wire fixtures and production layout reconciliation across add/remove/resize. Rust workspace tests validate command bounds and signed XRandR geometry. Neither synthetic check establishes simultaneous device decoder capacity; test Workspace → Screens on the headset. See [multi-monitor behavior](../../../docs/multi-monitor.md).

`python3 checks/gltf_lifecycle.py /path/to/gradle/lib` compiles production curved-collider cleanup and its model-release helper against native-lifetime substitutes. It checks instance-before-model teardown, repeated close and failed setup. `monitor_warp.py` also simulates teardown during a suspended collider load. Native renderer stability still requires a sustained headset test, including multiple curved screens, resize/removal and grab/release.

From the repository root, `python3 scripts/check-static-stream.py` runs the release host on a private 5120 × 3200 Xvfb desktop and checks complete RTP output and recovery without desktop activity. It needs Xvfb, x264enc and a release build; it does not read the real desktop or save video. Rust workspace tests include deterministic bounded packet pacing and idle-credit checks.

`python3 checks/screen_arrangement.py /path/to/gradle/lib` covers production edge/centre/adjacency snapping and desktop bounds. Monitor-management wire fixtures include per-output positions. Rust tests cover lowest advertised refresh selection and independent encoder-output lifetime. External-topology reload was tested with a spare live VKMS output; private Xvfb does not support the required output-position/transform change, so the static-stream check remains limited to static output and IDR recovery.

`python3 checks/display_requests.py /path/to/gradle/lib` uses a deterministic handler clock to verify original-ID retries, automatic read-only reconciliation and late-reply isolation. `rtp.py` now covers missing-tail repair, bounded requests and retired-source rejection. The static stream test also checks byte-identical cached retransmission and rejection of an unbound peer.

Two Rust capture checks require explicit selection: `benchmark_full_vs_scaled_readback` only reads the chosen X11 desktop and prints timing metadata; `server_downscale_keeps_crop_and_child_pixels` draws synthetic colors/windows and must run **only on private Xvfb with `FLOATDESK_SYNTHETIC_DISPLAY=1`**. Do not run the latter against the real desktop. The source includes an explicit environment guard. Normal Rust tests skip both.

### Connected layouts and transparent presentation

`screen_arrangement.py` also checks primary-rooted edge connectivity, bridge/corner rejection and inverse pointer mapping through visual screen insets. Rust monitor-pool tests validate move/resize/removal safeguards and repairs of externally disconnected configurations. `frozen_frame.py` verifies alpha-masked snapshots. `video_compositor_device.py` exercises actual EGL alpha output for holes, opaque black desktop pixels, spacing and redraws without a new decoded frame, plus existing orientation/overlay/decoder-idle checks. It does not open an Activity.

Arrangement checks also cover overlap rejection, half-screen offset snaps, centered viewport transforms and room beyond the top/left edges. Host tests cover normalized top/left moves and desktop bounds.

### Host capabilities and lowest-rate configuration

`python3 scripts/check-display-worker.py` from the repository root uses private Xvfb and synthetic XRandR/KScreen commands. A deliberately slow, sub-Hz named-output operation must keep control heartbeats available, tolerate a same-ID retry, and report its actual low rate. The native compositor check also parses nine outputs and verifies that mask batches preserve both early and late screens. Setup bundle generation with nine outputs and shell/Python syntax checks do not execute privileged installers.

`workspace_regressions.py` validates exact flat/curved mesh coverage holes, the full-coverage geometry fast path and viewer-relative restart translation/rotation with retained screen dimensions. `monitor_warp.py` explicitly requires opaque live video in both modes to catch the transparency-path beam regression. `video_compositor_device.py` also performs a real atomic save/reload of the viewer reference in an isolated temporary file. None of these checks launch an Activity or overwrite the user's workspace.

Arrangement checks also verify the bounded 100 ms release history: live drag preview, release-jitter rejection, a held destination, and clearing between drags.

`python3 checks/dynamic_projection.py /path/to/gradle/lib` checks actual vertex changes, constant sphere radius, straight viewed axes AND curved peripheral edges while facing left/right ends, a fixed center and non-rigid deformation, collider-triangle transform reconstruction, exact native-ray resolution and tracking recovery. `monitor_warp.py` checks decoder continuity, reused collider identity, disable/reparent/re-enable behavior and freeze/resume. `curved_resize.py` covers the dynamic behind-image frame and complete curved-perimeter containment. `frozen_frame.py` verifies dynamic snapshot geometry and the reusable shared-material triangle-grid asset. The offscreen device compositor/decoder checks do not validate native SceneCore node hit testing or prolonged app stability.

`python3 checks/host_discovery.py /path/to/gradle/lib` reproduces asymmetric UDP reply routing with two loopback addresses and checks advertised-interface fallback, healthy-peer stability, expiry, unrelated-host isolation and disconnect reset. No real desktop layout is changed.

`python3 checks/resize_beam.py /path/to/gradle/lib` checks retained UV contact reconstruction against current rendered dimensions across oversized curved/flat desktops, source-coordinate preservation and foreground ray depth. `curved_resize.py` additionally checks growing native bounds, uncapped aspect-preserving sizing and invalid final native proposals releasing resize ownership. `dynamic_projection.py` checks the recorded enlargement no longer crosses the obsolete 2.2-radian envelope prematurely.

`surface_input.py` reproduces coarse native collider contacts inside the ideal sphere on large desktops and resolves them against actual video triangles using the event origin/direction. It explicitly counts cases rejected by the old 2% sphere tolerance. Gesture checks compile the extracted production grab module and shared binary pinch function; they cover large desktops, missed second native DOWN, delayed fresh input ticks, physical rearming and independent contacts on different monitors. `monitor_warp.py` covers transformed native input and source-mode ownership. Native hit delivery and visual compositor ordering still require headset validation.

Frozen-frame checks also validate the deformable snapshot asset's 320 independently textured triangles, complete upright UV coverage, one embedded image/material, buffer bounds and bounded asset size. Gesture checks verify projected grab-marker positions without changing the rigid hand transform. Live and frozen projections use the same mesh, with the snapshot grid intentionally coarser to bound native-node updates.

Workspace regressions also exercise viewer-centered reset fitting for portrait, normal and ultrawide desktops, flat/spherical modes, rotated/translating viewers, scaled scene units, empty layouts and unavailable FOV. They verify corner containment with the margin and unchanged pixel dimensions/aspect ratio.

`focus_interaction.py` covers focus pinch edges, cross-hand selection, disabled/blocked rearming, topmost-window selection, screen fallback, visual insets, and fitting an off-center selected region in flat/spherical geometry. It verifies the selected center and corners against the view while retaining source resolution. Device compositor checks verify cyan/violet highlight colors and redraw on color-only changes.

The Rust `catalogue_and_activation_private_x11` test requires private Xvfb and `FLOATDESK_SYNTHETIC_DISPLAY=1`; it creates only synthetic windows, verifies stacking/dock filtering and EWMH requests, then exercises the production LAN focus command, captured-contact cursor placement and same-ID replay cache. Never opt into it on the real desktop. Ordinary Rust tests leave it ignored.

Focus and mouse checks now exercise injected shared pinch profiles, release without a hold timer, incidental chords, rearming during blocks, fresh taps following a stale queue prefix and central-region framing. The device persistence check also round-trips the saved profile. Tracking recovery includes a vertical floor-origin jump; no test claims to relocate the OS viewer or disable boundaries.

`viewed_detail.py` checks view-dependent crop coverage for flat and spherical geometry, out-of-view rejection and source mapping across visual gaps. `scripts/check-viewed-detail.py` starts private Xvfb and a release host, verifies actual JPEG colors after crop changes, exact geometry, size bounds, session rejection and recovery. It never reads the real desktop. Device compositor checks cover detail placement, live-cursor exclusion, expiry back to baseline and normal overlay rendering. No benchmark claims an improvement in overall motion or bandwidth.

The detail debug device check verifies that the HUD appears without an active patch, that green tint is absent before a real upload, that the upload counter advances on actual GPU submissions, and that disabling diagnostics removes its tint. The overlay shares the video compositor and creates no native input entity.
