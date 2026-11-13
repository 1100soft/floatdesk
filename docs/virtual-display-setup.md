# First-time virtual screen setup

Use the computer’s **Floatdesk tray → Virtual screen setup** for an automated setup flow, or **Workspace → Screen setup** for console commands. Existing VKMS pools are reused. On the computer, from the Floatdesk checkout:

```bash
python3 scripts/virtual-display-setup.py --check
python3 scripts/virtual-display-setup.py --write-bundle ~/floatdesk-display-setup --count 4
```

The first command only diagnoses VKMS configfs capability, connected virtual outputs and the `cvt` utility. The second creates a new directory containing reviewable boot topology, mode-publication helpers, systemd units, installers and rollback instructions. It does not run sudo, change a service, load a module or restart anything. It refuses to overwrite an existing bundle directory.

The bundle requires Linux/X11, systemd, xrandr, cvt and a distribution kernel exposing VKMS configfs with `create_default_dev`. If unsupported, install a suitable distribution kernel before proceeding. It does not build or patch kernels. Configfs pipeline construction follows [the kernel VKMS documentation](https://docs.kernel.org/gpu/vkms.html).

Read the generated README and files. On a machine needing a pool, the administrator runs `sh install-system.sh` as root; the desktop user runs `sh install-user.sh` without sudo. These enable boot/login units without starting them in the current session. Reboot when convenient. The system installer refuses another application’s VKMS topology rather than replacing it. An existing Floatdesk-owned topology can have its next-boot output count updated without changing the running topology. Do not run this over another boot topology service; use that pool instead.

If Xorg exposes the VKMS device only as an unattached provider, the generated mode helper can list providers and link **explicitly supplied** VKMS/primary IDs. It never guesses IDs, which can change at boot. Then it registers modes without enabling or moving screens. Floatdesk's Add screen activates an output and chooses its lowest advertised positive refresh rate, including sub-Hz modes. Mode creation tries several refresh rates and skips timings rejected by cvt or XRandR.

Pool construction happens before the display manager starts. Changing pool size is administrator maintenance; no live module unload or configfs teardown is provided. To undo installation, disable both generated units, remove the Floatdesk-specific installed helpers/unit/modprobe file as listed in the bundle, and reboot. This never calls for deleting another application's configuration.

This is initial setup assistance, not a universal packaged installer. Distribution packaging, automatically identifying provider ownership and a desktop authorization flow remain release work. Server startup attempts safe backend detection/preparation with `scripts/auto-virtual-displays.py`; it does not elevate privileges automatically. The tray invokes the generated installers through system authorization when requested. Output count is configurable rather than capped at four or five. The readiness check, bundle export and syntax validation are tested; this machine's existing pool was not reinstalled for testing.
