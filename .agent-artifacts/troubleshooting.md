Troubleshooting pass, 2026-11-07 (Europe/Oslo)

Changes in this pass:
- All panel placements now compose translation outside rotation (T * R). Previously R * T rotated panel centers around the origin. This also affected debug geometry and follow.
- Summon uses the head rotation without an extra 180-degree yaw.
- Latch follow persists during manipulation. Following pauses for manipulation and captures a fresh relative pose afterward; the button clears fist suppression.
- Two-hand DOWN uses SpatialInputEvent.hitPosition, which the installed Compose source explicitly converts into entity-local pixels. Ray origin and direction are still activity-space values converted through virtualPixelDensity. Added DOWN diagnostics. Removed debug panel rendering and PixelCopy snapshot work so those panels cannot intercept rays.
- Fist/open measurements use wrist-to-middle-proximal length, which stays stable while curling fingers. Tracking-state gating remains; no inference from stale untracked joints.
- RTP loss discards the rest of a damaged AU. Loss/queue overflow request IDR and suppress dependent frames until IDR. Decoder input waits/drains instead of silently dropping an AU when no input buffer is immediately available. Returns empty input buffer on capacity failure and requests recovery.
- Host GOP reduced to one second. appsrc block=false was left intact: dropping raw input before encoding does not itself corrupt H.264 references, contrary to the earlier handoff. Compressed AU drops on the client do.

Validation:
- cargo test --workspace: 14 tests passed.
- Android assembleDebug passed using the existing Gradle cache at /tmp/cursor-sandbox-cache/599d926aea12da00df4a5ad5056d6bda/gradle (GRADLE_USER_HOME). Default cache attempted JVM provisioning and stalled; no project build config changed.
- Standalone extracted Kotlin RTP checks passed: assembly, sequence wrap, fragmented loss, rejection of partial single-NAL AUs, packet reorder. Harness saved alongside these notes.
- APK installed with adb install -r; no activity launched.
- Host rebuilt and restarted with original arguments and environment. PID 3266820. Log: host-troubleshooting.log.

Still needs headset verification:
- Open Floatdesk from the app list. Reset desk once if saved poses are already reversed.
- Check control facing while following and after a summon; two-hand ray alignment/scale/rotation; latch persistence during and after a drag; image recovery under desktop motion.
- Joint tracking is intermittent in pre-install logs despite permissions being granted. Fist/palm correctness cannot be confirmed without tracked joints and a user gesture. New fist thresholds are a correction, not a confirmed hardware fix.
- Debug dots/arrows/ghost are intentionally no longer rendered.
- Nothing committed or pushed. Pre-existing uncommitted work preserved.

Follow-up after headset feedback (fist good, palm invisible, grab geared down):
- Discovered Compose beta02 parents Subspace to its recommended pose/scale container (SpatialConfiguration.kt / Subspace.kt source). Previously joint and head coordinates were only in ActivitySpace.
- Discover the actual SurfaceEntity parent via public scene graph children; convert perception poses/vectors directly to that entity using ScenePose.transformPoseTo/transformVectorTo. No fallback to unconverted poses. Before video, use the implicit main panel parent if present.
- Palm panel dimensions and contact thresholds compensate for root scale, so they keep physical size. Removed consecutive-frame reveal gate, relaxed palm openness only, retained signed facing, added detailed palm diagnostics. Hold through at most 120ms of missing joints; tracked turning-away hides immediately and stale poses cannot accept pokes.
- Removed scale/size clamps from the similarity transform so both held endpoints can remain locked. System resize handles retain their own bounds.
- Added regression coverage for root scale/rotation, unclamped endpoint locking, open palms and scaled fingertip contacts. Gesture checks and Android assembleDebug pass.
- Fist tightness unchanged. Still requires headset confirmation of physical placement and motion after reinstall.

Follow-up: user clarified the grab contract is ray contact locking, not physical hand displacement.
- Replaced physical pinch-delta targets with current system rays transformed from ActivitySpace into the actual desk parent. At pair lock, intersect both rays with the current glass once and freeze those local pixel coordinates and ray ranges. MOVE immediately solves the similarity transform using targets on both rays. No ray/plane projection against the moving glass after capture and no physical-joint lateral offset.
- Hand/controller rays carry depth movement in their origins. GAZE_AND_GESTURE rays use the eye-to-hand distance ratio for push/pull depth; their angular movement still comes entirely from system rays. Temporary missing raw joints freezes only that depth ratio; UP/CANCEL releases the gesture.
- Added DOWN diagnostics comparing SDK local hit to reconstructed ray hit.
- Use wrist/MCP knuckle plane for palm orientation, retaining the signed runtime palm surface for front/back. Reduce button padding and use BasicText automatic single-line fitting.
- Regressions include a far-monitor case demanding the entire ray movement, both endpoint constraints, immediate MOVE, hand ray depth, gaze depth, raw-joint gaps, and an anatomically flat palm with a biased joint orientation. Tests pass.

Virtual monitor follow-up (2026-11-08):
- User requested a miniature copy held at the same pixel fractions in the hands, mirroring its similarity transform onto the distant screen. Implemented fixed gain = selected monitor contact span / physical hand span, and a fixed orientation mapping between the initial hand vector and selected screen vector. Each held target is original contact + gain * alignment * physical hand displacement, including depth. System rays select only fresh DOWN contacts; MOVE never arms or drives the transform.
- Reject contact spans below max(12cm physical, 18% monitor width), and hand spans below 9cm. Rejected pairs must release before trying again.
- UP/CANCEL, including DEFAULT pointer end events, clear the gesture. Missing joints freeze movement; 120ms missing updates cancel permanently. A sustained physical release cancels even without UP. New DOWNs for both hands are required after release; raising hands/MOVE cannot reactivate old locks. Reject physically discontinuous >25cm sample jumps and collapsed <6cm held hand spans.
- Track accepted starts for native monitor move/resize callbacks; ignore their late updates/end after our custom gesture takes over. These were a second pose writer after the raw grab was gone.
- Tests cover virtual gain, exact endpoints, depth, release paths, rejected contacts, joint loss/reacquisition, fresh rearm and discontinuities; all pass. Android build passes.
- Ray graphic visibility is controlled by the system; this change avoids depending on ray updates during an active gesture.

Grab visualization follow-up (2026-11-08):
- Hide the monitor's system contact icon with pointerHoverIcon(SpatialPointerIcon.NONE) only while engaged and the guide renderer is ready. Restore DEFAULT when the grab ends or rendering fails. This uses the installed SDK's explicit pointer-icon API, without pointer capture.
- Add cyan/amber markers at the frozen local grabbed pixels on the actual moving screen, matching pinch markers, and matching thin tethers. Derive the miniature wireframe by inverting the same fixed similarity mapping as manipulation, so the same fractional pixels sit at the physical pinch positions.
- Render guides as pooled native glTF entities under the actual desk parent, with no interaction/move components, instead of SpatialPanel overlays. Four tiny generated unlit GLBs contain only unit boxes/spheres; generator is tools/generate_grab_guides.py. No changes to the grab mapping or rejection/release thresholds.
- Build and regression checks pass, including wireframe fractional-pixel/pinch alignment, real screen marker placement, and guide removal on release/tracking loss. Mesh containers and triangle indices validated. Device visual confirmation remains necessary.

Guide occlusion follow-up (2026-11-08):
- Desktop SurfaceEntity now uses MediaBlendingMode.OPAQUE, set when the decoder surface is created. The pinned SceneCore 1.0 implementation exposes this as a restricted public API; suppression is limited to this helper. No dependency upgrade, reflection or decoder changes. Failure is logged rather than breaking the stream.
- Transparent media composition can cover native guide meshes independently of their expected depth. Opaque mode lets the media participate in ordinary 3D occlusion. Reference: AndroidX commit 62be56b1f2e735b80cfbc8796b6b33d66baa2c75.
- Contact decoration/tether endpoint is offset up to 8mm physically toward the viewer along its view ray. The stored grabbed pixel and manipulation geometry remain exactly on the glass; this avoids coplanar clipping without shifting the apparent contact location.
- Build and gesture/guide checks pass, including visibility-offset direction, physical size and preservation of view-ray alignment. Device visual confirmation remains necessary.

Frozen manipulation follow-up (2026-11-08):
- Merely stopping new buffers leaves the compositor video rectangle present. During a paired grab, freeze MediaCodec presentation, copy its last frame with PixelCopy, and load the snapshot as an embedded-PNG opaque/unlit native glTF quad. Hide the original SurfaceEntity while the snapshot is ready, and track the original monitor pose/size with the quad. It shares the 3D render pass with guides.
- Continue receiving, decoding and draining all H.264 access units; release output buffers with render=false while frozen. Release/cancellation restores the original video entity and resumes presentation without codec restart or deliberate reference-frame loss.
- During the native snapshot, ignore the system ray CANCEL generated by hiding the video input entity. Existing raw pinch release and tracking-loss cancellation still terminate the hand-driven gesture, including when UP is missing.
- Snapshot PNG encoding runs off the main thread; cancellation/error cleanup restores live presentation. Snapshot creation errors leave the ordinary live stream available and are logged.
- Android build and gesture/guide checks pass. Additional JVM checks validate production snapshot GLB output, image buffer, opaque material and upright texture coordinates. Device confirmation still needed for PixelCopy/native texture load and compositor behavior.

Video resume follow-up (2026-11-08):
- Grab engagement was a plain Kotlin field read by LaunchedEffect. Release could leave DeskContent skipped until another workspace/palm update, delaying snapshot removal. Make engagement Compose mutableStateOf so entering/leaving the grab directly invalidates the UI and cancels/restores the snapshot effect.
- Keep submitting decoder frames to the hidden live SurfaceEntity throughout manipulation; only the native snapshot is visible. Removed decoder presentation-freeze flag and connection API. Release reveals a warm video surface, with no output pause or codec restart.
- Android assembleDebug and production gesture/guide regression harness pass; git diff --check clean. Installed on connected headset; device timing confirmation remains necessary.

Spherical warp toggle (2026-11-08):
- Added persisted workspace spherical_warp flag (default false) and a fifth palm-panel button, Spherical on/off. Existing four actions retain direct fingertip interaction and automatic text fitting.
- Live SurfaceEntity gets a 64x40 CustomMesh via the existing pinned SceneCore 1.0 restricted API, locally suppressed. Each original plane vertex P projects to E + |E|*(P-E)/|P-E| in monitor-local units. This keeps the center/visual directions fixed and all vertices at the same distance from the binocular viewer midpoint. Updates follow head translation at 20ms polling with 1mm change threshold. Too-close/behind/untracked viewpoints fall back to the ordinary quad.
- No decoder or video Surface replacement. Compose size changes are detected and the custom shape reapplied. Freeze geometry from the first contact until both contacts release; actual curved hit coordinates are preserved, inverted for image-plane bounds checking, and used by the existing full-3D endpoint solver. Frozen snapshots use the same mesh, with normalized XYZ scales preserving curvature under resizing. Miniature outline follows the curved mesh border with pooled native beams.
- Geometry/gesture regressions validate vertex radius and inverse projection for offset eyes, curved contact/guide capture and release cleanup, and the new palm button. Snapshot checks validate flat and curved GLBs, embedded PNG/buffer alignment, indices and radius after entity scaling. Android build/lint pass; headset disconnected, so live custom-mesh hit testing/UVs/performance and deployment need device confirmation.
