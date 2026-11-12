# Deferred gesture issues

Status: **deferred at the user’s request on 10 November 2026**. Stop further gesture tuning until the user resumes this work. This checkpoint is not a claim that quick pinches are reliable.

## Current user-observed failures

| Area | Remaining problem and evidence | Implementation / limits |
| --- | --- | --- |
| Quick index pinches | Brief taps are intermittently missed; rapid triple-clicks do not work reliably on the headset. Right index has repeatedly felt tighter than left. | Fixed thresholds and independent binary rearming; the recognizer emits three clicks for three supplied contacts in JVM checks. Actual sensor capture and desktop triple-click behavior remain unverified. |
| Quick middle pinches | Some quick taps work, others seem to need roughly 0.2–0.3 seconds of contact. Latest feedback still reports unreliable, slow registration. | No desktop minimum-hold timer. Logs contain recognized contacts as short as 18–22 ms, but that does not explain missing physical taps. Sound occurs only for recognized clicks, with no noticeable downstream delay when they work. |
| Repeated middle pinches | Earlier failures required opening the hand widely between actions, including after opening a context menu or scrolling. Reliability can degrade after hand switches or time in use. | Removed the shared all-fingers-open gate, extra index-extension gate and stationary-hold timeout. Each gesture now rearms when its own closed condition becomes false. Minimal-opening replay checks pass; device reliability is still unresolved. |
| Contact firmness | Relaxed fingers sometimes triggered gestures; tighter thresholds then required squeezing or missed contact entirely. Settings differ by hand. | Distances are between estimated fingertip joints, not skin surfaces. Current fixed values are listed below. No adaptive threshold calibration exists. Further blind tuning is deferred. |
| Drag / scroll release | Dragging or scrolling sometimes continued after physical release. | Explicit release, loss/disable cancellation and host 300 ms leases exist. Earlier wider release hysteresis was removed. Desktop touchpad momentum was considered but not established as the cause. End-to-end release timing remains a follow-up. |
| Right-click versus scroll | Right-click could fail even while scrolling worked, or valid middle pinches could be discarded. | Removed a 45 mm index-extension requirement and a 350 ms stationary-middle timeout. Scroll now measures palm translation, not fingertip-midpoint movement. Any non-scrolling middle pinch right-clicks on release. These changes have code checks, not final user acceptance. |
| Duplicated hand | Rarely two displaced hands appear, mostly on the right; they can wiggle and provoke unintended palm UI. User also observed it outside Floatdesk after closing it. | Floatdesk renders no hand meshes. At a matching reported time, SDK LEFT/RIGHT skeletons overlapped and identities switched. The guard preserves a clearly established owner and rejects a new duplicate; user confirmed right middle input can continue during duplication. OS visual remains unresolved; app causation is not established. |
| System-wide lag | Cursor and beams were choppy; user also found XR Home laggy. A headset restart cleared lag. | Before restart, native hover was about 15–16 updates/sec; a later smooth baseline measured about 60–72/sec. UI queue delays were much smaller. Restart recovery does not establish whether an app, accumulated load or the OS triggered the problem. |
| Ray availability / latency | Mixed OS eye/hand aiming faded hand rays; stationary aiming also faded. Custom beams initially clipped at video edges and later lagged/skipped. | User confirmed OS hand-only aiming helps. App retains a stationary tracked contact and renders continuous native surfaces. Current cyan/violet beams and streamed cursor retain a small user-accepted lag in the smooth baseline. Do not add another timeout or speculative extrapolation. |
| Gaze mouse | Continuous gaze remains too jittery for small desktop targets despite filtering. | Desktop Eyes toggle is independent of hand mouse aiming. Palm gaze focus is separate and was confirmed useful. Tracking accuracy and eye permission are still required. |
| Palm panel | Earlier gaze acquisition was intermittent; duplicate hands sometimes opened or operated the panel unexpectedly. | Warm fine-eye lifecycle, sticky focus, tracking rejection and callback epochs mitigate these cases. Same-hand middle pinch was user-confirmed usable. Preserve its separate tuning; do not change palm presentation to address OS duplication. |

## Frozen behavior at this checkpoint

- Mouse index contact/release: left **15.5 mm**, right **12.5 mm**. Middle: left **15 mm**, right **14 mm**. One threshold per finger defines closed versus open; there is no extra release distance or release dwell. Invalid/untracked data still cancels input.
- Index release clicks; repeated releases support double/triple-click commands. Index contact held for **350 ms** starts a drag. That intentional drag timer is not a click-recognition requirement.
- Middle release right-clicks unless scrolling began. Palm translation of **8 mm** starts scrolling immediately; release ends scrolling without a click. Combined index+middle contact maps to middle-click (often primary-selection paste on Linux).
- Index, middle and combined gestures rearm independently. A combined pinch opens when either participating contact opens. Chord acquisition can combine two finger contacts within 150 ms; it does not defer a completed click.
- Mouse gestures can be disabled. Hand aiming remains available independently of desktop Eyes. One ray aims; two rays reserve input for grab preview; an active monitor grab, palm UI, invalid tracking or an ambiguous duplicate can suppress mouse gestures. These mode gates remain separate from contact thresholds.
- Palm-button activation is separate: 26 mm middle contact, reopening above 35 mm, 60 ms confirmation, sticky gaze focus. The user clarified that the reported short-hold issue concerns desktop right-clicks, not these buttons.
- Sampling subscribes to published SDK hand states and copies scalar joint positions immediately. Polling remains a fallback for runtimes that mutate buffers without flow emissions. A bounded event queue preserves short reported pulses, and discards stale batches after 120 ms. This cannot establish touches never reported by the SDK.
- Diagnostics include local right-click sound, gesture start/release/cancel reasons, source/preview changes, tracking rejection/owner, SDK-versus-fallback frame counts, outgoing click sequence/queue delay and host processing time. A host click is currently ignored if a left drag is still held; a warning records that case. No evidence yet ties it to the remaining misses.

## Confirmed improvements and regressions to preserve

The user confirmed a smooth streaming baseline with minor usable ray/cursor lag, working native move/resize controls, matching live/frozen spherical shape and edge grabs. Small monitors below minimum two-hand contact separation intentionally require native handles or Reset. Native coordinates must keep the forward event-matrix conversion with activity-root scale removal. A hold uses a fixed miniature-monitor transform; never replace this with a screen-ray-only depth controller.

The previous Impress crash involved replacing ray meshes every input update. Beams now retain a unit mesh and change transforms. Keep the continuous cyan-left / violet-right rendering, hidden screen pointer markers, snapshot grab visibility and immediate live-video restoration.

Hidden video now releases its decoder/output window and requests a new keyframe on return; inactive input/geometry loops idle, and connection disposal closes sockets/threads. Offscreen device checks exercise pause and fresh-frame resume. These resource fixes do not prove the origin of the rare OS hand visual.

A separate host queue/pacing bug was fixed: capture start pacing improved a read-only 2560×1600 benchmark from ~40 to ~58 fps, obsolete raw encoder frames are bounded/replaced before encoding, and dead X connections reach the reconnect loop. These were host issues, not a measure of headset resource use.

## Resume investigation only when requested

1. Preserve the working stream/coordinate/native-handle baseline before editing. Ignored `.agent-artifacts/` contains local APK/source checkpoints, including `confirmed-smooth-stream-and-input-2026-11-09`; they are not committed or portable test fixtures.
2. Correlate a failed physical tap with raw contact, SDK/fallback delivery, current mode, fist filtering, hand-quality rejection, buffered-event acceptance, gesture output and host sequence. The UI currently filters buffered samples against the latest accepted hand snapshot; investigate temporal mismatches rather than assuming all failures are distance tuning.
3. Determine whether a failed tap was absent from SDK updates, filtered by application arbitration, or emitted but mishandled downstream. Audio/log correlation narrowed recent reports to recognition, but does not isolate the raw-joint cause.
4. Exercise repeated minimal openings and click–scroll–click sequences on both hands, hand switching, long sessions, triple-click target stability, tracking loss and duplication. Keep all acceptance claims separate from supplied-sample JVM tests.
5. Avoid speculative palm changes, new gesture delays, whole-hand reopening gates, or simultaneous threshold and transport changes. Any future calibration should be explicit and user-controlled.

Multi-finger air swipes were tried and disabled at the user’s request because they were too easy to misfire. Wink activation and configurable host gesture/action mappings remain unimplemented future work. Pinch-hold scrolling and same-hand palm middle-pinch activation are the current design.

### OS aiming selection — 12 November 2026

The pinned Jetpack XR SDK exposes hand and gaze input sources, but no app-scoped override of Android XR's hand/eye aiming selection was found in its public SceneCore/runtime APIs. Mixed gaze-and-gesture events do not provide hover events. Floatdesk's custom screen targeting still uses native hand hover contacts; its own eye tracking and gesture processing do not force native hand-hover availability. Keeping identical hand targeting in mixed OS mode would require an independently generated hand ray and hit-test path, including two-hand grab acquisition. This is not implemented by changing session hand-tracking configuration or capturing pointers. Keep the working native coordinate conversion as the baseline.

References: [input-source contract](https://developer.android.com/reference/kotlin/androidx/xr/scenecore/InputEvent.Source), [system aiming options](https://support.google.com/android-xr/answer/16637308).
