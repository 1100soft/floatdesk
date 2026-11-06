# Source provenance

Floatdesk's host, protocol, and Android XR client in this repository are original code. Nothing was copied from Sunshine, Moonlight, Weylus, Deskreen, Emersia, OpenDisplay, or vd-streamer-linux.

Those projects are useful as behavior references. Their GPL and AGPL terms are not compatible with treating this tree as a closed product, so their source is not imported.

Third-party components are used as libraries, not copied in:

| Component | How it is used | License note |
| --- | --- | --- |
| x11rb 0.13.2 | Rust X11 client for RandR, MIT-SHM, XDamage, and XFixes | MIT OR Apache-2.0 |
| GStreamer 1.x | Loaded at runtime from the system (`libgstreamer`, `libgstapp`, `libgstvideo`). The host does not vendor it. | LGPL-2.1. Confirm each plugin before distribution. `x264enc` links libx264, which is GPL; a commercial build should prefer VA-API, NVENC, or a separately licensed encoder. |
| Jetpack XR, ARCore for XR, MediaCodec | Android client dependencies | AndroidX / Android SDK terms |
| clap, serde, tracing, libloading, thiserror, ctrlc | Rust crates | See each crate's license at build time |

Record the upstream URL, commit, license, and local diff here before adding any further third-party source file.
