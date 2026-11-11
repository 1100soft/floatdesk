# Dynamic desktop screens

Open the palm panel, choose **Workspace**, then use **Screens**. Add creates a 1920 × 1080 desktop screen. Each added screen offers 1280 × 720, 1920 × 1080 and 2560 × 1600, plus Remove. Modes must already be advertised by that output; unsupported choices report an error. The host chooses the available refresh rate nearest 60 Hz within 25–61 Hz, avoiding the notebook's very low refresh defaults. Refresh reloads active outputs, capacity and management status.

Run the host with `serve --existing`. It reads up to six active XRandR outputs and captures their bounding rectangle as **one desktop object**, preserving the system arrangement, including vertical offsets, gaps and overlaps. Two 1920 × 1080 outputs side by side produce one 3840 × 1080 screen. Stacked outputs produce a taller screen. Moving, resizing or spherically warping this object applies to the complete desktop, with no seams between independently projected screens. Mouse coordinates map across the original desktop rectangle.

The single H.264 stream fits within 4096 × 2160, preserving aspect ratio to even-pixel precision. Larger desktops are downscaled; the actual system output resolutions and pointer coordinates remain unchanged. This trades fine text detail for bounded encoder/decoder dimensions. The Screens panel distinguishes streamed dimensions from the per-output resolutions and still manages each added desktop output separately. Capture cost grows with the full desktop area even when video is downscaled.

Adding/removing/changing resolution rebuilds capture and decoding; a brief video interruption is expected. Refresh rereads the system arrangement. The combined object keeps the existing primary source identity (`existing-0`) and its saved pose/physical width; height adapts to the combined aspect ratio. Older independent placements disappear from the active layout. Saved layouts store placement, not an instruction to recreate desktop outputs automatically. Legacy `serve --monitors` logical-region mode remains separate.

## VKMS pool

The supplied `vkms_flexible_monitors_kde_x11.ipynb` is reference material, not an installer executed by Floatdesk. Its configfs service provisions independent VKMS connectors/CRTCs before Xorg, links the provider and advertises modes. This machine already has four inactive `Virtual-1-*` outputs. Floatdesk activates that pool through unprivileged XRandR commands; it does not run notebook cells, use sudo, rebuild modules, change boot configuration or reboot.

This produces real KDE desktop outputs, unlike RandR logical monitor regions with no backing output. Creating a larger kernel/Xorg pool remains a separate administrator setup step, normally before the graphical session starts. If no unused `Virtual-*` output exists, Add is unavailable. The current implementation supports X11 and numeric `Virtual-*` output names; it does not provision VKMS or manage Wayland displays.

Physical and already-active outputs are included in the combined desktop but cannot be resized or removed from the app. Only outputs added by the current Floatdesk host process are manageable. Managed outputs are packed to the right of unowned outputs; growing one shifts subsequent managed screens without moving physical outputs. Existing desktop windows may be rearranged by KDE when outputs are added or removed.

Added outputs remain active across headset reconnects and are disabled on graceful host shutdown. Host service restart ends that ownership session. SIGKILL, power loss or a crash can leave outputs enabled; disable those specific virtual outputs in KDE Display Configuration before restarting if they should return to the available pool. Floatdesk does not adopt or disable pre-existing screens automatically.

## Control and validation

Monitor commands carry request IDs and are accepted only from the connected control peer. The host caches replies so retransmitted Add requests do not create duplicate screens. Dimensions and action/index values are bounded. A timeout asks the user to Refresh before issuing another change. This uses the existing trusted-LAN UDP transport, without authentication or encryption.

Validation includes Rust parser/protocol tests and `checks/monitor_management.py`, which checks Android/Rust wire fixtures and pose preservation through source changes. A live X11 test exercised two additions, retry deduplication, resolution change, index compaction, removal and graceful cleanup, receiving RTP on three independent ports. The physical display configuration matched its starting state afterward. Headset UI interaction and simultaneous multi-screen decoding still require a user device test.

Gesture reliability remains deferred in [gesture-issues.md](gesture-issues.md); this work does not tune gestures or change the confirmed monitor coordinate conversion.

## Native renderer lifetime fix — 11 November 2026

The first device test confirmed screen management worked but reported repeated crashes. The crash buffer showed native Impress/SplitEngine aborts for unknown material instance IDs. Review found that curved colliders and frozen images detached their entities and immediately closed the model; detachment does not synchronously destroy the native instances. The same pattern existed in grab-guide shutdown. This is the leading explanation for those material errors, pending another device test.

Owned glTF teardown now explicitly disposes native instances before closing their backing models, using the pinned SDK's restricted disposal API, which also clears its automatic-cleanup reference. Curved collider close is idempotent. A collider that finishes loading after its monitor owner has closed is immediately released and cannot disable or replace the restored input owner. The resource lifetime check exercises repeated close and failed construction with native-lifetime substitutes; the warp check covers late completion. These tests cannot establish that a native renderer crash is eliminated on hardware.

## Combined desktop follow-up — 11 November 2026

The user reported another unknown-material native crash after the lifetime fix; that fix alone did **not** establish stability. Existing-desktop mode now uses a single capture, decoder, video surface, interaction patch and spherical projection for the whole system arrangement. This reduces independent renderer lifetimes but is not proof that the native failure is eliminated. A sustained headset test remains required.

The extended monitor status carries a bounded per-output size inventory separately from the single stream's hello. Management indices still refer to desktop outputs; mouse stream index zero refers to the combined capture rectangle. Rust tests cover side-by-side, stacked and negative-offset arrangements, aspect-preserving size limits, and scaled encoding. Live testing checks one stream through add/resize/remove, retry deduplication, protection of the physical output, and restoration of the starting arrangement.

## Large-desktop stalls — 11 November 2026

The user confirmed the combined object works, but video and the visible mouse cursor intermittently freeze more often with additional outputs. Headset logs showed recurring RTP loss/recovery requests while XR display/input frame logs continued. The host also only drained its asynchronous encoder after obtaining another capture, leaving completed pictures unsent when XDamage was idle or the capture interval was not due.

The host now drains ready video before polling for another capture. A bounded packet pacer spreads large H.264 units rather than dumping an unrestricted UDP burst, while input processing remains on its separate session thread. Pacing permits a 9,600-byte packet train at four times configured bitrate, bounded to 8–80 Mbps; it is burst control, not adaptive congestion control. It cannot repair actual packet loss.

Conversion/scaling uses `videoconvertscale n-threads=4` where available, with the previous software path as fallback. A local 30-frame synthetic 5120 × 3200 to 3456 × 2160 comparison measured 0.98 s for fused processing versus 1.57 s for the previous separate stages. VA postprocessing was tested and rejected on this machine because that path took 11.77 s. These are pipeline throughput measurements, not end-to-end LAN latency guarantees.

Rust tests cover bounded pacing/idle credit and the encoder paths. An isolated static Xvfb desktop at 5120 × 3200 delivered a complete frame without further activity and answered an explicit recovery request (171 ms initial and 105 ms recovery in that run). Actual Wi-Fi smoothness still needs user confirmation. Large X11 captures still read the complete unscaled desktop; capture cost is not eliminated by smaller encoded dimensions.
