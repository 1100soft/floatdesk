# Floatdesk stability and maintainability plan

Written by the architect on 2026-11-27 against `b110e16` plus the uncommitted work in the tree. It turns the priorities in the [architect handoff](architect-handoff.md) into ordered tasks. The [regression requirements](xr-regression-requirements.md) remain the behavior contract, and every task here must preserve them.

## Evidence

- **Native crashes are the main stability risk.** The [crash-route inventory](xr-crash-routes.md) records SplitEngine unknown-material aborts on cold start, wake, child spawn and resize, and custom-mesh setter faults. Each recurrence has been hard to attribute, because nothing records which native operation preceded the abort.
- **Native video lifetime is implemented twice.** Magnifier panes keep their state in loose fields on `MagnifierResources` (`visible`, `hasFrame`, `generation`, `rendered`) inside `WindowMagnifierPanel.kt`. The desktop has its own path in `MonitorWarp.kt`. Curved input (`CurvedMonitorInput.kt`) and corner handles each manage further native entities. The required ordering (detach the producer and input, then retire geometry) is a convention in each place, not an enforced rule.
- **Submitted surface state has no single owner.** Panes publish geometry into `tracking.displayedPanes`. `WorkspaceRuntime.kt` keeps separate readiness maps (`fixedReady`, `popupReady`, `systemReady`, `referenceReady`). The desktop recomputes cutouts from those maps with its own 120 ms throttle in `DesktopScene.kt`.
- **`WorkspaceRuntime.kt` is one composable of about 2,000 lines,** with 115 `remember` sites and 44 effects. Lifecycle, wake, input arbitration and pane bookkeeping are interleaved there.
- **The checks are fragile.** 23 of the 29 JVM check scripts copy production Kotlin by string markers, and some assert on source text. Moving or renaming a declaration can break a check without any behavior change. There is no `src/test` source set, and JUnit is not in the offline Gradle cache.
- **Baseline.** Rust fmt, tests and clippy pass, and so does Python lint and format. JVM: 26 of 27 pass. `jvm-gltf_lifecycle` fails because its stub `Entity` lacks `setEnabled`, and it fails the same way on a clean `b110e16`.
- **The protocol is fragile for extensions.** Some control decoders reject unexpected trailing bytes, so a peer that adds a field can make an older peer drop the packet.
- **Concurrent work is in flight** in `WorkspaceRuntime.kt`, `WindowMagnifierPanel.kt`, `VideoCompositor.kt`, `DeskConnection.kt`, `WindowCapturePool.kt` and host `windows.rs`/`typing.rs`. These are the files most of this plan touches.

## Tasks

Priorities: **P0** blocks everything else. **P1** addresses crash stability. **P2** addresses maintainability and keeps stability from regressing. **P3** can wait. Within a priority, do tasks in the order listed.

| ID | Priority | Task | Done when |
| --- | --- | --- | --- |
| T0.1 | P0 | Green baseline. Add `setEnabled` (and anything else now missing) to the `gltf_lifecycle` stub. | `python3 scripts/verify.py` passes all groups by default. |
| T0.2 | P0 | Land the in-flight work. The user or the owning agents commit the current uncommitted changes after their own verification. Refactors must not start on top of uncommitted changes owned by someone else. | `git status` is clean for the files a task touches, or the user explicitly approves working on top of them. |
| T1.1 | P1 | Native lifecycle journal. See the details below. | Every native create, shape, resize, visibility, producer, collider and dispose call goes through one recorder. A crash report script pulls the crash buffer plus the preceding journal lines for the same PID. |
| T1.2 | P1 | One native video surface owner with an enforced state machine. See the details below. | Panes and the desktop use it, the pure state machine has a JVM check, and an out-of-order transition is refused and logged. |
| T1.3 | P1 | One submitted-surface registry that replaces `displayedPanes` and the readiness maps. See the details below. | Cutouts, hit testing, cursor topology and menu fitting read only published records, and authored-geometry fallbacks are removed. |
| T2.1 | P2 | Robust check harness. | No check depends on a declaration marker or source-text assertion for code it exercises. A missing marker in any remaining legacy check fails with a clear message. |
| T2.2 | P2 | Decompose `WorkspaceRuntime.kt` into a wake and lifecycle coordinator, input arbitration, and the pane registry from T1.3. | `FloatdeskApp` mainly wires components together, no client file exceeds about 1,000 lines, and arbitration has a JVM check. |
| T2.3 | P2 | Scripted headset matrix. | `scripts/headset-matrix.py` runs the adb-automatable scenarios, prompts for the manual ones and writes a dated result file. |
| T2.4 | P2 | Protocol forward compatibility. Decoders in Rust and `net/Protocol.kt` ignore unknown trailing bytes after the fields they know, while still validating known fields. | Tests cover old-to-new and new-to-old decoding for each extended packet kind. |
| T3.1 | P3 | Extract X11 window grouping into a pure policy over a window-property snapshot, with one fixture test per named exception. Wait until the in-flight `windows.rs` work lands. | Thunderbird Compose, Brave popup, detached ChatGPT, separated modal child and standalone password-manager fixtures pass. |
| T3.2 | P3 | Soak summary. Log native allocation counts, FPS, CPU, memory and thermal state once a minute, and have the matrix script summarize them. | A one-hour run produces a trend table. |
| T3.3 | P3 | Readability. Wrap very long lines (some exceed 700 characters) only in code you extract or rewrite. Do not reformat the whole repository. | Extracted files read cleanly, with no unrelated churn. |
| T3.4 | P3 | Move history notes into `docs/history/` and keep the README user-facing. | Links in `AGENTS.md`, the handoff and the README still resolve. |

### T1.1 Native lifecycle journal

A native abort kills the process before Java can report anything, so the evidence has to be in logcat already. Create one recorder, for example `NativeJournal` with log tag `FloatdeskNative`, and route every native operation through it:
- surface create and dispose
- shape or canvas replacement
- texture size change
- `setEnabled`
- decoder surface attach and detach
- collider attach, detach and dispose
- glTF model load and release

Each line carries uptime in ms, PID, thread, pane key, X11 owner and child IDs, native surface generation, projection mode and the operation.

Log lifecycle operations every time. Log shape submissions only when the canvas or vertex count changes, and log ordinary shape updates as a one-line count summary at most once a second. Keep an in-memory ring of about 512 events for the diagnostics overlay.

Add `scripts/xr-crash-report.py`. It runs `adb logcat -b crash` and the `FloatdeskNative` lines for the crashed PID, then writes a bounded report into `.agent-artifacts/`. It must not leave recurring capture dumps behind.

### T1.2 Native video surface owner

Introduce one owner, for example `NativeVideoSurface`, for each video surface. It holds the SceneCore entity, the retained canvas, the producer attachment and the collider. A platform-free state machine enforces this order:

Allocating → TextureInit → Attached → Presented ↔ Hidden → Retiring → Disposed

Rules the state machine must enforce:
- Shape and size mutations go through `DeskConnection.tryNativeMeshUpdate`.
- Retiring detaches the producer and input before geometry is released.
- Hide and show reuse the same native resources.
- Only a material size increase, an authored resize or a projection change allocates a new canvas.

SceneCore calls go through a narrow interface so a JVM check can substitute it. Migrate in separate changes, each followed by a headset check: magnifier and companion panes, then curved input, then the desktop path in `MonitorWarp`.

### T1.3 Submitted-surface registry

Introduce one record, for example `SubmittedSurface`, with these fields:
- pane key, owner ID and child ID
- surface generation and frame generation
- submitted outline or geometry, visible aperture and source rectangle
- projection mode
- whether opaque content has been presented

Only the surface owner publishes and withdraws a record. It publishes after first presentation and withdraws on hide, Reset, resize and mode change. Consumers read the registry and never authored placement.

Before removing them, list every remaining fallback to authored or provisional geometry and its replacement in the change description. Add JVM checks for two cases: a pending or decorative surface never produces a cutout, and a withdrawn record removes the cutout on the next desktop submission.

## Non-goals

Do not migrate the engine or language, retune gestures, reformat the repository, reintroduce removed features, or change accepted visuals, input behavior or stream quality as part of this plan.

## Implementation prompt

Give the implementing agent the following prompt.

```text
You are implementing the Floatdesk stability and maintainability plan in
/home/eh930/project/apps/floatdesk. Read AGENTS.md first, then
docs/stability-plan.md, docs/xr-regression-requirements.md,
docs/architect-handoff.md and docs/xr-crash-routes.md. The regression
requirements are the behavior contract; nothing in this work may change
accepted visuals, input behavior or stream quality.

Work through the tasks in docs/stability-plan.md strictly in order: T0.1,
T0.2, T1.1, T1.2, T1.3, then the P2 tasks. Do one task, or one migration step
within T1.2, per change. Do not start the next task until the current one
passes its checks and you have reported it.

Before each task:
- Run `git status` and `git log --oneline -5`. Other agents work in this tree.
  If a file you need has uncommitted changes you did not make, do not edit or
  commit it. Stop and ask the user whether that work has landed (task T0.2).
- Read the owning modules named in the architect handoff's ownership table and
  the relevant JVM checks under client/android-xr/checks/. Many checks copy
  production code by string markers, so run them after moving or renaming code.

Constraints:
- Behavior-preserving refactors only, unless the task says otherwise.
- Every native mesh or shape submission goes through
  DeskConnection.tryNativeMeshUpdate. Never dispose or replace a surface,
  material or collider while a producer, collider or the renderer can still
  reference it. Detach the producer and input first.
- Keep pure logic free of Android and SceneCore imports so a JVM check can
  compile it whole. Put SceneCore calls behind narrow interfaces.
- Keep persisted JSON keys and protocol fields backward compatible.
- Do not retune gesture constants, reformat unrelated code, or reintroduce
  removed features (Eye Mouse, floating magnifiers, per-output streams).
- Match the surrounding code style. Comments only state constraints the code
  cannot show.
- Gradle runs offline and JUnit is not cached. Extend the existing Python and
  Kotlin compiler check harness unless the user approves adding dependencies.

Verification for every change:
- `python3 scripts/verify.py` must pass. Add `--group android` when you change
  client code, and `--group integration` when you change host code.
- Add or update a focused JVM check for each invariant you introduce.
- Anything involving composition, depth, hit areas, native lifetime or wake
  needs a headset observation. Use ~/Android/Sdk/platform-tools/adb outside
  the sandbox, and follow .cursor/rules/adb-headset.mdc before saying the
  headset is disconnected. If a step needs the wearer, write the exact steps
  for the user and wait. Do not report it as verified.

After each task:
- Update docs/stability-plan.md (mark the task done with the date and
  evidence), docs/architect-handoff.md if a contract changed,
  docs/xr-regression-requirements.md if you fixed a regression, and the
  "Architect handoff state" section of AGENTS.md.
- Report what changed, which checks ran and their results, what still needs
  a headset observation, and any fallback or risk you found but did not fix.
- Do not commit unless the user asks. Never commit .agent-artifacts/.

Stop and ask the user when a task would change accepted behavior, when the
headset shows a new crash signature, or when two documents conflict about
intended behavior.
```

## Status

| ID | Status |
| --- | --- |
| All | Not started as of 2026-11-27. |
