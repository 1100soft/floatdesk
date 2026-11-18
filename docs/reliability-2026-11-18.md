# Panel and menu follow-up — 2026-11-18

Fresh navigation crashes include SplitEngineBridge ALREADY_EXISTS for MaterialInstance IDs and a texture released with an outstanding model_creator borrower. These are distinct from the previously recorded final-session-teardown fault. Menu views previously created/disposed native entities and glTF models for every changing popup ID.

Popup views now retain a slot's native surface and input instance. Transparent input assets are shared per grid/session, and individual input closures never close that shared material/texture. Asset loads use the session lifecycle scope and coalesce callers, so a cancelled popup cannot poison another pane's pending input load. A bounded number of slots is retained per owner, keyed by pane identity so two magnifiers of the same window do not collide.

The horizontal panel has a 36-pixel reserved strip, matching its canvas. The vertical panel has a 62-pixel canvas but reserves 46 pixels; capture now trims the extra 16 pixels using the same EWMH strip intersection for both orientations. Shell panels and menu notes have no magnifier pin frame. Vertical native resize minimums use the same long-axis policy as horizontal panels.

Shell metadata is event-driven with a coalesced 40 ms sampling budget and immediate changed-revision publication. Popups occupy the radial gap in front of their owner, with successive levels nearer still. Invalid/disappeared crop regions are skipped, preventing a stale menu view from painting the entire atlas. Apply/Revert are inside the editor with reserved toolbar space.

Validation includes shared-model reuse across 1,000 input instances, cancelled-loader/coalesced-waiter behavior, deferred session cleanup, dock-strip geometry, shell map/property observation under 500 ms, radial geometry and ordinary input regressions. The actual native navigation crashes still require sustained headset verification; passing substitutes does not establish that every upstream native fault is eliminated.

Native output canvases now keep their first allocation as a retained slot changes menu IDs/dimensions. Allocation headroom stays within the existing two-megapixel budget. This avoids replacing a canvas texture while its native material can still borrow it. Both shell orientations use frameless rendering and the same long-axis resize constraints.

Live host checks confirmed horizontal content at 591 × 36 and cropped vertical content at 46 × 1600, with padded coded frames and paused/resumed capture working. Android build/lint, 62 Rust tests, strict Clippy, four targeted JVM suites and isolated KWin event checks passed. Device crop, padded-panel decoding, capture fan-out and compositor/beam checks passed. The source-model cancellation/reuse test and immutable-canvas budget checks passed separately.

The shell observer also tracks recently mapped helpers through their first geometry change, so a Qt menu mapped initially at one pixel is not lost when it expands. Its private-X11 regression requires this final geometry and property changes to be observed under 500 ms.

## Sleep recovery and shared projection follow-up

Power state is now reconciled periodically, rather than trusting the screen-on broadcast's immediate PowerManager read. A wake can arrive before that read changes. Session activation is handled once for lifecycle and power transitions; it requests full space and restores hand/device tracking. The input loop reacquires hands, both viewpoints and the device after inactivity rather than retaining pre-sleep perception handles.

Flat UI meshes now stay coplanar instead of projecting flat viewing rays onto a sphere. Magnifiers, shell panels, menus and HUD use the selected flat/curved projection; HUD gaze interception invalidates its geometry when this mode changes. The custom native canvas type remains stable across modes.

Pane resize controls reuse MonitorSystemControls, including its projected backing frame, stable resize baseline and visibility handling. The native quad and resize affordance now have matching padded dimensions. Panel aspect follows cropped capture metadata while preserving the long-axis extent, avoiding stale shadow-width letterboxing. Direct pane movement remains separate from the resize controller.

The desktop retains its curved video surface and input mesh across flat/curved toggles. System menus retain native pane slots across visibility changes. New panes no longer replace an initial 2048x1536 canvas with a second allocation immediately after creation. These changes remove specific texture/material allocation and disposal cycles associated with the native faults; they do not establish that every SplitEngineBridge failure is resolved.

Validation: Android assembleDebug and lintDebug passed. Six distinct JVM suites passed: focus_interaction, curved_resize, magnifier_transition, viewed_detail, gltf_lifecycle and monitor_warp. Added checks cover flat pane/HUD geometry, HUD gaze-mode invalidation, matching resize bounds and retained curved resources. The new APK installed successfully and produced live video/hand/eye/palm-panel logs. ADB disconnected during a sleep/wake test, so complete wake recovery and sustained menu/mode-switch stability remain unverified on the headset. No host changes or service restarts were needed for this follow-up.

The headset subsequently reconnected after a reboot. Four real-device probes passed on the installed build: VideoCropSmoke, ThinPanelSmoke (both orientations), WindowFanoutSmoke and VideoCompositorSmoke. Fresh startup logs show successful hand/device configuration, resumed video and palm-panel opening. The reboot still prevents treating the interrupted test as a successful ordinary sleep/wake cycle.
