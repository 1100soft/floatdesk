# Floatdesk

An X11-first host that places logical monitors in an Android XR workspace. In existing-desktop mode, active outputs form one H.264 stream and one XR screen, following the X11 arrangement. The host composites the cursor into the video and schedules frames when the pointer moves, even on an otherwise static desktop. All monitors share one workspace coordinate space.

This tree is the LAN proof of concept: RandR 1.5 regions, MIT-SHM capture, XDamage, XFixes, GStreamer H.264, and RTP/UDP. It is not encrypted. Keep it on a trusted network. The session boundary (`DisplayBackend`, `CaptureBackend`, `CursorBackend`, `EncodeBackend`) is where Wayland, Windows, macOS, and a WebRTC transport plug in later.

## Host

Run `floatdesk tray` (requires PySide6), or install a desktop launcher with `python3 scripts/install-tray.py`. The tray offers service controls, output status, logs and first-time backend setup. Status polling pauses while hidden; visible Qt helpers run without IBus input contexts. The server observes screen changes through a persistent native RandR connection.

Use **Settings → Workspace** in the headset to add, resize or remove independent desktop screens from a pre-provisioned VKMS pool. A pre-provisioned pool supports this without a reboot or privileged commands during use. Drag screen rectangles in Workspace to arrange them with snapping. Workspace lists actual modes and accepts custom dimensions without a product count/resolution limit; host metadata supplies an advisory resource estimate. External layout changes reload automatically. See [multi-monitor behavior](docs/multi-monitor.md) and [first-time setup](docs/virtual-display-setup.md).

Requires an X11 session (`DISPLAY`) and GStreamer with an H.264 encoder (`x264enc` is the portable fallback; `vah264enc`, `vaapih264enc`, and `nvh264enc` are used automatically when present).

```bash
cargo build --release
./target/release/floatdesk doctor
./target/release/floatdesk trial
./target/release/floatdesk serve --monitors 2 --width 1920 --height 1080
```

`doctor` only reads the server. `trial` creates one 1280×720 logical monitor named `Floatdesk-1`, checks that RandR lists it, and restores the framebuffer. `serve` enlarges the X screen, adds `Floatdesk-N` monitors with no physical output, and waits for a client hello on UDP `47000`.

`--existing` captures the bounding rectangle of active XRandR outputs, preserving their relative placement in one XR screen. It leaves their modes unchanged until you explicitly add or resize a Floatdesk-managed screen. It also works for a loopback test:

```bash
./target/release/floatdesk recv --host 127.0.0.1:47000 --output /tmp/floatdesk.h264 --seconds 5 &
./target/release/floatdesk serve --existing --duration 4 --encoder x264enc --fps 15
```

For long-running headset use, run the existing-desktop host under your user service manager instead of leaving a background process attached to a terminal or coding tool:

```bash
cargo build --release
./scripts/serve-existing.sh --encoder vah264enc --fps 60
journalctl --user -u floatdesk-live -f
```

`scripts/serve-existing.sh` installs a persistent graphical-session user unit through `scripts/manage-host.py`. It imports the current `DISPLAY` and `XAUTHORITY`, starts `floatdesk-live`, and does not stop an already running host. The unit restarts the host after a crash, logs to the journal, and is tied to the graphical session so it comes back after logout and reboot. Stop it with `systemctl --user stop floatdesk-live`. To change launch arguments, stop it and rerun the script. `FLOATDESK_BIN` can select a different executable. Recent reliability notes are in [docs/reliability-2026-11-17.md](docs/reliability-2026-11-17.md) and [docs/reliability-2026-11-18.md](docs/reliability-2026-11-18.md).

On a cold start, the headset centers the desktop and moves the saved arrangement with it. Under **Settings → Connection**, save the computer’s Wake-on-LAN adapter MAC address to have Floatdesk send a magic packet when it starts. This requires Wake-on-LAN to be enabled on the computer and a local network that forwards the broadcast to that adapter.

Ctrl-C restores a virtual framebuffer. If the process is killed first, the saved size is in `$XDG_STATE_HOME/floatdesk/x11-screen.json` (or `~/.local/state/floatdesk/`). Put it back with:

```bash
floatdesk restore
```

`capture-once` writes a PPM of the top-left corner through MIT-SHM. `encode-once` writes an Annex-B file from a synthetic frame.

## Android XR client

`client/android-xr` is a Jetpack XR app. It decodes RTP video with `MediaCodec` into a `SpatialExternalSurface`, receives the cursor within the video, and stores screen poses in the workspace. Room anchoring is available when supported by the headset.

The headset finds the computer on the LAN by itself. `floatdesk serve` announces itself once a second. Open the app and wait until the status says it found the machine. There is no address to type.

Face an open palm toward yourself to show the control panel centered 0.40 metres ahead for comfortable fingertip touch, and touch its buttons with the other hand. It no longer follows the palm. A firmly clenched fist held briefly makes the desk follow your head; the Follow button toggles persistent follow.

Pinch two points separated by at least five degrees in your view to manipulate a monitor in 3D. Grab and mouse index pinches share the same binary contact threshold. Hand translation, depth, rotation, and separation move and scale a miniature monitor held between your hands. Free manipulation keeps the chosen depth and orientation. Reset returns the layout origin to the configured farthest distance (1.2 metres by default). **Settings → Viewing distance** sets the nearest layer (default 0.90 m) and that farthest distance. A wireframe, fixed contact markers, and tethers show the mapping. Aiming beams and mouse contact rings hide during preview/grabbing. Aim beams, grab tethers, and contact markers use retained 3D meshes positioned in front of the desktop. Window-menu markers follow the projected window edge, and the menu faces the viewer. Closely spaced contacts are rejected, and releasing either pinch or losing tracking ends the grab. During manipulation a native snapshot replaces the video visually so the guides stay visible; spherical snapshots update their projected geometry while held. The hidden video surface continues receiving frames and reappears on release. **Reset desk** centers the layout origin in your view, restores the desktop’s default fitted size, and preserves object offsets. A palm-facing gesture opens the **Layout** submenu until you select **Done**. Its one-hand and two-hand modes display the near cyan grip and far violet marker, with straight axes in flat mode and great-circle axes in spherical mode. Pinch and drag the cyan grip to reposition the origin while the layout objects stay in place.

Use **Enable spherical / Disable spherical** on the palm panel for a viewer-centered spherical desktop with dynamic geometry. A geodesic map straightens the axis you look toward while retaining curvature in peripheral regions. The desktop focus stays at the configured farthest radius; vertices retain their distance from the eye center. Head direction deforms the mesh; flat mode keeps its original geometry. Original video pixels are used. Geometry freezes during mouse pinches and native move/resize. During a custom two-hand spherical grab, a reusable native snapshot grid shows the current projected result at up to 10 Hz. Very wide fields compress far peripheral angular spacing to avoid spherical overlap while preserving the local viewed region. Video, hit testing, snapshots and backing controls share the geometry. Streaming resolution is unchanged. The HUD and supporting video panes follow the same projection setting. Each pane keeps a native surface for each projection mode it has used. The curved desktop mesh leaves openings behind visible foreground video panes so the compositor cannot paint desktop pixels over them. The Android XR control panels use the platform’s planar panel geometry.

When Android reports severe thermal throttling, Floatdesk slows geometry updates and suspends optional desktop detail passes. At critical status or hotter it pauses video while keeping the controls available, then resumes after two seconds at moderate status or cooler. A return to severe restarts that wait.

The palm menu groups supporting objects, mode switches, and aim interactions, with Reset, Layout, and Settings within reach. Layout groups adjustment tools, history, and matched load/save rows. A separate strip below it holds **Hand mouse**, **Tap to focus**, **Drag to move**, and **Hands off**. Hands off temporarily suspends all three hand interactions and retains their settings.

Tap to focus and Drag to move operate on the desktop reference. Both are enabled by default and can be used together. A stationary pinch selects and a moving pinch drags. Hand mouse operates on the main desktop and supporting video panes other than the desktop reference. In the window menu, **Focus on this window** explicitly activates and frames the window. In the snap grid, drag across cells and release to apply the selected rectangle.

Selection raises/activates a window when applicable, moves the computer mouse to the ray contact captured at pinch-down, and reframes that region at the desktop’s saved position using Reset’s view-fit policy. The whole desktop stays available; no separate window video stream is needed. Spherical projection anchors the selected region, so off-center windows can fill the view without pulling its center elsewhere. Focus selection fits a central 36° × 24° region rather than filling the headset FOV. Focus leaves the Follow setting as configured. **Hand mouse** restores cursor control; **Reset desk** recenters the saved arrangement.

**Settings → Experimental viewed-area quality** keeps a square of source-native pixels around gaze, including when zoomed out. **Auto** is the default: it adjusts the area using capture/encode time and client delivery latency toward a configurable FPS target (30 by default). **Fixed area** specifies pixels per side, with the corresponding megapixel maximum displayed. The square clips to the visible crop; viewport and hardware limits can reduce its actual area. Visible peripheral screen regions start at 67.5% source sampling per axis, matching the density of a 5120 × 3200 desktop fitted to 3456 × 2160. This ratio does not depend on total desktop height or layout. Capture uses one contiguous visible bounding rectangle plus one native gaze patch. The headset draws the native patch over the matching low-resolution area; there are no internal peripheral tiles. Small duplicate coverage under the patch keeps the peripheral image stable as gaze moves. Gaps inside the rectangle are blanked before encoding and remain transparent in XR. When one fully native crop has a smaller estimated encoding allocation, the low-resolution layer is omitted. Both layers share one encoded frame and exact per-frame mappings. Codec selection honors the headset’s advertised AVC capacity; unsupported larger frames use HEVC. Auto retains a useful native patch (about 1 MP, or the whole visible crop if smaller). Under sustained load it lowers background sampling toward the FPS target, and restores background quality before growing native detail when time is available. A fixed native patch also uses adaptive background quality. The FPS target is available in both modes. Native quality floors and irreducible processing/network costs can still keep actual FPS below the target. Codec-capacity limits can also reduce density. The FPS target is a feedback goal, not a guarantee. Fast / Balanced / Quality set encoder effort, independently of the native-pixel allocation. Higher effort can improve compression at the same bitrate but consumes more processing time; Auto can consequently lower background sampling or reduce native area above its useful minimum. Curvature follows the head independently of the gaze-centered quality region. Native detail uses source-native XR output within an 8192-axis/16 MP cap. Older protocol compatibility paths remain available.

Workspace Settings also provides **Fast / Balanced / Quality** encoder presets, a bitrate override (Mbps; 0 uses the host default), and GPU sharpening. **Show diagnostics** works independently of experimental streaming. **Show diagnostics** is the master visibility switch for the entire HUD; **Live resources** includes host/headset CPU and memory, available GPU counters, FPS/Mbps, capture/encode times and client image/redraw rates. Bright Host, Headset, Stream and Quality clusters sit outside Focus’s central target rectangle. One head-relative foreground UI group carries square, transparent, spherically projected text patches; UI depth testing is disabled so enlarged desktop video cannot cover them. There is no center panel or background. CPU usage is summed across cores (200% = two busy cores). Mbps specifies the target compressed-video bitrate, not measured network speed. Sharpening defaults to zero and cannot recover missing source detail.

The Quality HUD reports actual frame sampling: **BG pixels 4.00:1** means one retained background pixel per four source pixels; **BG samples 25.0%** expresses the same spatial reduction as a percentage. These are sampling ratios, not H.264/HEVC byte compression. **HD 12.5% crop** is the source-native patch's share of the captured visible rectangle, including gaps in that rectangle. Full-native frames show background off and 100% HD coverage.

The HUD debug map outlines the visible crop in amber and the fresh high-density center in green. It shows crop coordinates, center density gain relative to normal streaming, requests, decoded responses, late frames, errors and actual GPU frame count/age. The experiment uses a separate hardware decoder and adds some processing overhead, so its performance remains experimental. Turn off **Show quality diagnostics** to compare text without the HUD while keeping quality streaming active. Disable the experiment to return to ordinary full-desktop streaming.

Mouse distances are measured between tracked joint positions, not the physical skin gap. Workspace temporarily hides the desktop while open so its controls remain accessible. Resolution fields request a numeric keypad; pinch fields request a decimal keypad. **Settings → Pinch thresholds** saves per-hand index/middle contact distances in millimetres. Mouse, focus and two-hand grabbing all read this profile, with the same boundary for contact and release; smaller values require firmer contact. Existing workspace values start from the legacy defaults, and sensitivity never adapts automatically. Each mouse gesture rearms independently when its own closed condition becomes false, without requiring other fingers to open. Palm-button pinch tuning is separate.

**Hand mouse** controls pointing, clicking and dragging. Bring the tracked thumb and index tips into contact range, then release for a left click; pinch twice quickly for a double-click. Move while pinching to start a drag, or hold for 350 ms to press down before moving. Release to drop. The hand beam tracks the current contact while dragging. Pinch thumb and middle finger with the index open for right-click. Pinch thumb, index and middle together for middle-click. The clicking hand can point away while the other hand aims. When two-hand manipulation is enabled, two rays on the same object reserve the interaction for preview and grabbing. Opening the palm controls, losing the clicking hand or disabling Hand mouse cancels the gesture and releases a drag. The host also releases a held button after 300 ms without updates.

Pinch thumb and middle finger, then move that hand vertically or horizontally to scroll immediately. Scrolling follows palm translation so squeezing the fingers does not count as hand movement. An 8 mm movement threshold suppresses small tracking jitter; there is no hold delay. A time-based filter and movement deadband stabilize the scroll. The direction is reversed by default and can be changed under Settings → Pinches. The cursor stays at the initial target; release ends scrolling without right-clicking. A middle pinch that does not enter scrolling right-clicks on release, regardless of hold duration. The index only needs to be outside its own contact range, rather than fully extended. Scrolling requires writable `/dev/uinput` and `libevdev.so.2` on the Linux host. Air swipes with raised fingers are disabled. On Linux, middle-click commonly pastes the primary selection; it is distinct from Ctrl+V clipboard paste.

The palm panel supports eye focus independently of the OS aiming method, subject to Android's eye-tracking permission. Permitted eye tracking stays ready while the app is active, so showing or briefly losing the panel does not restart sensor acquisition. Look at a button until it highlights, then pinch that hand's thumb and middle finger to activate. Keep the other three fingers extended. Open the pinch before activating again. Buttons flash on activation and use the system touch sound when enabled. Ordinary blinking is not an activation gesture. Direct tapping with the other hand remains available. Discrete sticky focus helps with gaze jitter; continuous desktop gaze still depends on tracking accuracy and may be unsuitable for small targets.

In spherical mode, the hidden flat video owner is disabled so its native body cannot compete with the curved interaction mesh. The system move/resize rectangle sits behind the image, aligned with the viewer-facing tangent plane in spherical mode, with corners projected onto the image corners; returning to flat mode restores the original owner and handles.

Use the **Edit** pencil on either palm menu to customize that page. Drag buttons and labels on the half-button grid. Select **Add label** or tap an existing label to edit it in place, then press **Done** on the system keyboard. Confirming an empty label removes it. **Save** stores the layout across restarts, and **Cancel** discards the draft.

Entering **Layout** restores the full desktop at its saved position and shows the 2D map. The palm menu groups motion modes, adjustment commands, and saved layouts. In the default layout, the **Saved layouts** group saves on the lower row and restores from the thumbnails above. In Layout, one-hand auto layout supports angular dragging and resize corners. Two-hand manual layout supports free transforms. Align, Snap, Face, and Auto adjust act on the arrangement. Snap seats logical cursor rectangles in nearest-first order, examining the edges of each placed rectangle in order and choosing the shortest clear displacement, including diagonal moves. Undo and Redo step through recent layout changes, and Reset is also available in Layout. The headset records up to 40 layout snapshots in its private `layout-history.json`. Reset origin moves the axes back to the desktop center while the layout objects stay in place. Window actions, focus, and hand mouse resume on **Done**. Previewing never moves the monitor.

**Gesture reliability is incomplete and further tuning is deferred.** Quick pinches, repeated/triple clicks, hand asymmetry and duplicate-hand behavior are recorded in [the deferred gesture issues](docs/gesture-issues.md). Automated replay checks do not establish reliable physical touch detection.

What changed after the first LAN proof of concept is recorded in [CHANGES.md](CHANGES.md). Regression checks and the limits of those checks are in [the Android checks README](client/android-xr/checks/README.md).

Plasma does not treat a RandR monitor with no physical output as a desktop, so a legacy virtual `Floatdesk-N` region stays empty. Use the VKMS screen controls above for independent desktops, or stream existing outputs:

```bash
./target/release/floatdesk serve --existing
```

## Layout

```text
crates/protocol    control datagrams, RTP H.264, workspace math
crates/host        X11 capture, GStreamer encoder, RTP session
client/android-xr  Jetpack XR receiver
benchmarks         netem helper for latency, jitter, and loss
packaging          systemd unit
```

Explicit resize handles and the two-point controller are available. Whole-image one-hand grabbing is disabled; the exposed system rectangle provides one-hand fallback movement. Flat video and standard handles retain the original Compose-owned surface. Spherical video uses a separate native canvas and a transparent interaction mesh with matching geometry. Two-point contacts use raw native hit events: remove the activity-root scale, then apply the event's hit transform to obtain screen-local coordinates. The prior native movement implementation remains covered by regression checks; it is not attached to the monitor body. Rearming a rejected two-point grab does not disable normal handles. Head-quality checks gate follow independently of tracked hands, so a paused head signal does not erase valid hand samples. A visible activity stays awake and reapplies tracking configuration on resume. Android XR still controls sensor availability and physical safety boundaries.

For persistent hand rays, choose **Settings → Input → Aiming method → Use hand aiming only**. The mixed eye/hand mode can switch away from hand aiming; hand-only mode was confirmed to remove the need to wave the hands to restore rays. This does not override tracking loss when the headset cannot see a hand.

Hand-state updates queue joint scalars immediately, with polling fallback for runtimes that do not emit buffer changes. Overlapping identities and pose jumps are guarded; a previously established owner can retain mouse input while a new clone is rejected. Uncertain input hides the palm panel and invalidates delayed callbacks. Normal palm presentation is unchanged; the duplicated system hand visual remains unresolved.

After substantial tracking-loss jumps, an unanchored workspace is restored relative to the last trusted view once tracking stabilizes. Normal head movement and small recovery drift retain world-relative placement. This does not suppress Android XR's system low-light warning or guarantee room localization while tracking is unavailable.

The host resynchronizes Qt/Plasma monitor geometry after layout changes, including moves that keep the total framebuffer size unchanged. This prevents missing desktop-background coverage from leaving old window fragments on an output. Monitor refresh rates remain at their chosen minimum.

In Focus mode, Android XR’s mixed eye/hand aiming can target windows and screens through native gaze when no current hand ray targets the desktop. Current hand targets keep priority and two-hand preview still blocks selection. Hand mouse uses hand aim exclusively. Native target icons are hidden on desktop surfaces, while UI panels retain their pointer feedback.

Desktop focus keeps its reference surface at the configured farthest distance. Explicit depth input changes size; ordinary head movement does not. Flat mode maintains plane clearance when tilted; large spherical desktops use a view-focused, bounded mesh grid so triangle chords cannot cut into the foreground HUD region.

**Gaze to magnify** and **Aim to magnify** select a window after a short dwell. The palm **Magnifier** switch shows or hides the saved magnifier panes. X11 companion windows with explicit transient ownership appear with their owner in the magnified view. Independent windows remain separate even when they share an application or process. Magnifier panes keep saved desktop-relative placements. Use the pane menu to add or remove one or to pin its window. Both projection modes use mesh video and explicit triangle input. Flat panes re-aim during manual movement rather than tilting toward head translation. Captured detail also sharpens the original window on the desktop using the same decoder. Focus & magnification settings also contain the configurable typing lock for automatic gaze focus.

The [runtime architecture review](docs/refactor-review-2026-11-16.md) describes module responsibilities, resource ownership and validation limits. Run `python3 scripts/verify.py` for sequential, bounded-memory Rust/Python/JVM checks; add `--group integration` or `--group android` to select platform checks. Magnifiers suspend while Workspace is open. Headset diagnostics include thermal severity (`T:`), independently of stream FPS and free memory.
