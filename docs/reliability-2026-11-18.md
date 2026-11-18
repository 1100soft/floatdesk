# Panel and menu follow-up — 2026-11-18

Fresh navigation crashes include SplitEngineBridge ALREADY_EXISTS for MaterialInstance IDs and a texture released with an outstanding model_creator borrower. These are distinct from the previously recorded final-session-teardown fault. Menu views previously created/disposed native entities and glTF models for every changing popup ID.

Popup views now retain a slot's native surface and input instance. Transparent input assets are shared per grid/session, and individual input closures never close that shared material/texture. Asset loads use the session lifecycle scope and coalesce callers, so a cancelled popup cannot poison another pane's pending input load. A bounded number of slots is retained per owner, keyed by pane identity so two magnifiers of the same window do not collide.

The horizontal panel has a 36-pixel reserved strip, matching its canvas. The vertical panel has a 62-pixel canvas but reserves 46 pixels; capture now trims the extra 16 pixels using the same EWMH strip intersection for both orientations. Shell panels and menu notes have no magnifier pin frame. Vertical native resize minimums use the same long-axis policy as horizontal panels.

Shell metadata is event-driven with a coalesced 40 ms sampling budget and immediate changed-revision publication. Popups occupy the radial gap in front of their owner, with successive levels nearer still. Invalid/disappeared crop regions are skipped, preventing a stale menu view from painting the entire atlas. Apply/Revert are inside the editor with reserved toolbar space.

Validation includes shared-model reuse across 1,000 input instances, cancelled-loader/coalesced-waiter behavior, deferred session cleanup, dock-strip geometry, shell map/property observation under 500 ms, radial geometry and ordinary input regressions. The actual native navigation crashes still require sustained headset verification; passing substitutes does not establish that every upstream native fault is eliminated.

Native output canvases now keep their first allocation as a retained slot changes menu IDs/dimensions. Allocation headroom stays within the existing two-megapixel budget. This avoids replacing a canvas texture while its native material can still borrow it. Both shell orientations use frameless rendering and the same long-axis resize constraints.

Live host checks confirmed horizontal content at 591 × 36 and cropped vertical content at 46 × 1600, with padded coded frames and paused/resumed capture working. Android build/lint, 62 Rust tests, strict Clippy, four targeted JVM suites and isolated KWin event checks passed. Device crop, padded-panel decoding, capture fan-out and compositor/beam checks passed. The source-model cancellation/reuse test and immutable-canvas budget checks passed separately.

The shell observer also tracks recently mapped helpers through their first geometry change, so a Qt menu mapped initially at one pixel is not lost when it expands. Its private-X11 regression requires this final geometry and property changes to be observed under 500 ms.
