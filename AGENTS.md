# Floatdesk engineering notes

Floatdesk streams an X11/Plasma desktop to an Android XR headset (Samsung Galaxy XR) and lets the wearer place, magnify and drive it with hands and gaze. This file is the entry point for every agent. It records the architecture, the decisions that are load-bearing, the assumptions that have repeatedly been wrong, and the architect's running handoff.

Read before changing behavior:

1. [XR regression requirements](docs/xr-regression-requirements.md). This is the behavior contract. Later directions in it supersede earlier experiments recorded elsewhere.
2. [Architect handoff](docs/architect-handoff.md). Covers subsystem ownership, native lifecycle constraints and verification priorities.
3. [Crash-route inventory](docs/xr-crash-routes.md), before touching native surfaces, materials, colliders or wake.
4. [Deferred gesture issues](docs/gesture-issues.md), before touching pinch thresholds or gesture timing.
5. [Stability plan](docs/stability-plan.md), before refactoring native surfaces, `WorkspaceRuntime.kt`, the checks or the protocol.

`README.md` is current for host setup and commands, but parts of its feature descriptions are older. `CHANGES.md`, `docs/multi-monitor.md`, `docs/refactor-review-2026-11-16.md` and the reliability notes are history. They describe features that have since changed or been removed, such as floating magnifiers, Eye Mouse and per-output streams. Do not restore behavior from them without checking the regression requirements.

## System map

```text
crates/protocol   FDC1 control datagrams (UDP, little-endian, VERSION 1), RTP H.264/HEVC, workspace math
crates/host       `floatdesk` binary: X11 capture, GStreamer encode, session, tray helpers
  session.rs + session/   streaming, window capture (window.rs), cursor (cursor.rs, cursor_map.rs),
                          gaze native-detail atlas (detail/), transport, receiver
  platform/x11/           capture, windows.rs (companion-window grouping), typing.rs (XI2 activity),
                          monitor_pool (VKMS virtual screens), display/arrange, cursor
  platform/touchpad.rs    uinput two-contact touchpad for scrolling
client/android-xr Jetpack XR app (Kotlin, SceneCore, Compose spatial panels)
  WorkspaceRuntime.kt     `FloatdeskApp` composable: lifecycle, scene epochs, almost all runtime state
  DesktopScene.kt         desktop presentation; MonitorMesh/MonitorWarp spherical geometry
  WindowMagnification.kt, WindowMagnifierPanel.kt   magnifier selection and pane rendering
  net/DeskConnection.kt   control channel, decoders, native mesh submission gate
  media/VideoCompositor.kt  EGL compositor: crops, cutouts, cursor/typing ink, overlays
  workspace/              persisted workspace, placement, ViewLayers depth policy
  checks/                 JVM and device regression scripts (see Verification)
scripts/          verify.py, host service management, tray, setup, integration checks
```

Data flow: the host captures the combined X11 arrangement as one desktop stream, plus a gaze-driven native-detail patch and per-window XComposite captures for magnifiers (TCP on control port + 2). Companion windows (menus, dialogs, children) share their owner's capture atlas and arrive as crops with per-frame metadata. The headset decodes with `MediaCodec`, composites through `VideoCompositor`, and presents on SceneCore `SurfaceEntity` custom meshes. Input goes back as pointer, click and touchpad datagrams. The host owns the real cursor position and its logical topology.

## Load-bearing design decisions

Each item below exists because the alternative failed on the headset. Do not reverse one without new device evidence and an update to the regression requirements.

**Geometry and composition**
- One geometry path. Startup, mode switches, source changes and layout preview all build geometry through the same `MonitorMesh`/`MonitorWarp`/`VideoGeometry` code. Spherical projection applies to every element that follows the flat/curved setting, with the viewer at the sphere center.
- Submitted geometry is the truth. Visible bounds, hit testing, cursor placement, overlay depth and desktop cutouts must use the geometry a surface actually submitted. They must not use authored, provisional or guessed geometry. This contract is only partly centralized today (see Architect handoff state below).
- Depth sorting of separately composited external video does not work. Foreground panes are made visible by cutting apertures in the desktop mesh (`foregroundCutout`, `clippedVideoGeometry`). Only panes that have presented opaque content may cut a hole. Stale, pending or decorative surfaces must never punch passthrough.
- Depth order is policy in `workspace/WorkspacePlacement.kt` `ViewLayers`: desktop farthest, then magnifiers, panels and menus, then diagnostics nearest. Layers step in diopters.

**Native resource lifetime (highest crash risk)**
- Treat replacing a video surface, material, mesh or collider as a transaction. Detach the video producer and input first, then retire the geometry. SplitEngine `unknown material` aborts and custom-mesh setter faults have followed every shortcut here.
- Reuse surfaces across hide/show, ordinary pose updates and source changes. Curved panes keep a retained spherical canvas and move a transparent aperture inside it. Allocate a new immutable canvas only for a material size increase, authored resize or projection change.
- All native mesh submissions, including desktop curved-surface creation, go through `DeskConnection.tryNativeMeshUpdate`. It paces submissions across decoders and pauses them under thermal limits. `nativeVideoMesh` validates arrays and uploads independent triangles on purpose, because the indexed SDK path faulted.
- `NativeNodeState` caches app-owned submissions. Invalidate it around native system manipulation.
- Curved support-pane input uses a retained triangle collider derived from the submitted video geometry. Attaching input directly to the custom video surface regressed hand aiming.

**Identity and ownership**
- Keys have different lifetimes: a layout slot or pane key (`menu:`, `popup:`, `system:` prefixes in `WorkspaceRuntime`), an X11 window ID, a captured region and a native surface. Never use one as another. A live child stays on the same native slot when siblings change. Depth comes from stacking order, not slot index.
- Window grouping is explicit only. Use X11 transient ownership, plus the named exceptions in the requirements (Thunderbird Compose, Brave extension popups, separated modal children). Never group by app class, PID, overlap or recency.
- The cursor topology is an axis-aligned 2D workspace map, independent of head pose and curvature. Hand Mouse sends host root pixels taken from the captured source region. The host (`cursor_map.rs`) owns the real cursor's position and logical space, and reports its owner key. The headset draws a separate cyan marker on that owner's submitted pane, while the cursor sprite in the video is a mirrored image.

**Placement and persistence**
- Authored placement is persisted. Derived poses (focus, wake, head tracking, aperture fit) are never persisted. Only explicit layout edits write the workspace.
- Persistence is `org.json` with `opt*` defaults and atomic temp-file writes. Add fields with defaults and never rename saved keys. `gaze_to_focus` intentionally backs the Gaze to magnify control, and `aim_to_magnify` is separate.
- The control protocol stays at `VERSION 1`. New fields are appended as length-checked trailing extensions (see the `MonitorStatus` decoder in `crates/protocol/src/control.rs`). Since 2026-11-27 both decoders (Rust and `net/Protocol.kt`) validate the fields they know and ignore any trailing bytes after them. `Cursor` is the exception, because its tail is pixel data, and `Workspace` is JSON. `MonitorStatus` extensions are tagged: tag 1 is the primary mask, roles and names, and an unknown tag is skipped. Builds from before that date still reject tails, so a new field reaches an older peer only once both sides run the tolerant decoders. Cover every extension in `compatibility_tests` in `control.rs` and in `checks/monitor_management.py`.

**Input**
- Resolve the owner and contact before interpreting a gesture. Pinch thresholds come from `PinchPolicy` and are shared by mouse, focus and grab. Palm buttons have separate tuning. A captured drag keeps its owner through hover loss and always releases the host button on cancel or pause.
- Gesture threshold tuning is deferred at the user's request. Do not retune constants as a side effect of other work.

**Host**
- Never create or remove XInput master devices. Removing one crashed the Plasma session.
- Keep the paced follow-up frame after an isolated change, low virtual refresh rates, monitor-layout notifications and output identity by connector rather than primary flag.
- Do not trade magnifier image quality for thermals. A 30 FPS cap is acceptable.

## Assumptions that have been wrong

- "The build passes and frames decode, so it works." Composition, depth, hit areas and input need a headset observation. Report them as unverified otherwise.
- "`adb devices` is empty, so the headset is unplugged." See `.cursor/rules/adb-headset.mdc`: restart the server and check `lsusb` first.
- "A pane reporting ready means its geometry is final." Readiness, first presentation and the cutout update are separate steps, and the cutout is deferred deliberately (`deferNativeMeshUpdates`).
- "One crash fix covers the signature." The same SplitEngine abort has occurred on cold start, wake, child spawn and resize. Record each recurrence as the crash-route inventory describes.
- "An X11 window ID identifies a pane." IDs change. Layout slots and pane keys persist.
- "Removed features are missing features." Eye Mouse, floating magnifiers, whole-image one-hand grab and per-output streams were removed on purpose.
- "Renaming a Kotlin declaration is safe if it compiles." Many JVM checks splice production source by string markers (for example `marker(w,'internal data class Pose')`) or assert on source text. A missing marker now fails with the check name and the marker text. Run the checks after moving or renaming code.

## Rules for implementation agents

- Check `git status` and recent history first. Other agents work concurrently in this tree. Keep their changes unless you have a concrete reason to revise them, and say so when you do.
- Find the owning module in the architect handoff's ownership table and preserve its contract. If a change needs a second per-mode or per-pane path, centralize the shared policy first.
- For a regression, name the behavior that was lost, add a focused check for the invariant where possible, and update the regression requirements in the same change.
- Keep pure mapping and arbitration logic outside Compose and SceneCore so checks can run it on the JVM.
- Match the surrounding style. Client Kotlin is dense, with compact one-line functions and few comments, and the docs use plain full sentences. Comments state constraints the code cannot show.
- Do not commit `.agent-artifacts/`, which holds local captures, logs and APKs.

## Review checklist

- Does any path substitute authored or provisional geometry where submitted geometry is required?
- Is any native surface, material or collider replaced or disposed while a producer, collider or the renderer can still reference it? Does every new mesh submission go through the gate?
- Are paint, input and readiness keyed to the same pane and frame? Are X11 IDs, slots and surfaces kept distinct?
- Are persisted keys and protocol fields backward compatible?
- Do flat and curved, startup and mode switch, and normal and enlarged desktops all take the same path?
- Does the change reintroduce a removed feature or retune deferred gesture constants?
- Does the report separate check results from headset observations, and list what still needs the device?

## Verification

- `python3 scripts/verify.py` runs the Rust (fmt, tests, clippy `-D warnings`), Python (ruff) and JVM groups sequentially with bounded memory. Add `--group android` for the debug build and lint, and `--group integration` for the host release build and private-X checks. Use `--only jvm-<name>` to select individual checks. The input, placement and geometry subset is `--group jvm --only jvm-focus_interaction --only jvm-gestures --only jvm-workspace_regressions`.
- JVM checks compile extracted production code with platform substitutes. They cannot validate SceneCore rendering, native hit coordinates, tracking or the compositor.
- Headset work uses `~/Android/Sdk/platform-tools/adb` outside the sandbox. The device matrix is in the architect handoff (priority 3) and the crash-route inventory. Capture bounded crash-buffer stacks rather than recurring capture dumps.

## Architect handoff state

This section is for whoever holds the architect role next. Update it at the end of every architect session: replace the state snapshot, and add a line to the decision log for each new decision.

**Role.** Keep this file, the architect handoff and the regression requirements consistent with the code and the user's latest direction. Triage crashes into the crash-route inventory. Steer refactors toward the handoff priorities in small, behavior-preserving steps. Feature agents implement. The architect records contracts, reviews them against this file, and does not silently rewrite their work.

**State as of 2026-11-27 (end of the stability-plan implementation session).** HEAD is still `40c5078`. Nothing is committed: the working tree holds this session's uncommitted changes, which are listed in [the stability findings](docs/stability-findings-2026-11-27.md) for review. T0.1 is done. T1.1 (native lifecycle journal, `scripts/xr-crash-report.py`) and T1.2 step 1 (`NativeSurfaceLifecycle` for magnifier and companion panes) are code complete and still need headset observation. T1.2 steps 2 and 3 (desktop and support surfaces) are blocked on a decision, because the curved desktop is resized immediately after creation and gets its decoder in the same frame, with no texture-initialization interval. T1.3 is inventoried but not implemented. T2.1 is partial (missing markers now fail clearly, but the markers remain). T2.3 (`scripts/headset-matrix.py`) and T2.4 (tolerant decoders, plus a fix for a `decode_layout` slice panic) are done. `python3 scripts/verify.py` passes 34 of 34, `--group android` passes, and `--group integration` passes 8 of 8 when run outside the sandbox (inside it, `kill` of child processes is denied). Headset observation was limited: the device was asleep and unworn, so the build only launched and the journal reached logcat. An earlier `adb logcat -c` in this session cleared the crash buffer, so crashes from before about 19:00 on 2026-11-27 are lost.

**Open priorities.** The [stability plan](docs/stability-plan.md) is the ordered task list, with priorities, completion criteria, evidence and the prompt for the implementing agent. In summary:
- P0: done. The baseline is green and the in-flight work has landed. Recheck `git status` before each later task.
- P1: a native lifecycle journal for crash attribution, one native video surface owner with an enforced state machine, and one submitted-surface registry that replaces `displayedPanes` and the readiness maps in `WorkspaceRuntime` (`fixedReady`, `popupReady`, `systemReady`, `referenceReady`).
- P2: a check harness without string markers, decomposition of `WorkspaceRuntime.kt`, a scripted headset matrix, and protocol forward compatibility.
- P3: an X11 grouping policy with fixtures, soak summaries, readability limited to touched code, and archiving the history docs.

Keep the plan's status table current. Live Mountlet child and menu-cascade stress for the SplitEngine abort remains open and belongs in the headset matrix.

**Decision log.** Newest first. Each entry gives the date, the decision and why.
- 2026-11-27: Control decoders ignore unknown trailing bytes after validated fields, and `MonitorStatus` skips extension tags other than 1. A new field must not make an older peer drop the whole packet. Version stays 1.
- 2026-11-27: The desktop is not migrated onto `NativeSurfaceLifecycle` without user approval and the headset. Enforcing a texture-initialization interval changes cold-start and mode-switch timing, which is accepted behavior.
- 2026-11-27: The native journal records before each native call, logs visibility only on change and summarizes repeated shape submissions once a second. A crash must be attributable without a per-frame log flood.
- 2026-11-27: Added `docs/stability-plan.md`. The crash journal comes before the surface-owner refactor, because the SplitEngine aborts cannot yet be attributed and the refactor needs that evidence. Refactors wait for the in-flight work to land, because they touch the same files. The check harness stays Python plus the Kotlin compiler, because Gradle is offline and JUnit is not cached.
- 2026-11-27: Rewrote this file as the shared architecture entry point and the architect's own handoff, so that feature and review agents stop relying on outdated README and CHANGES behavior.
