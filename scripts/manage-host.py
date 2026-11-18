#!/usr/bin/env python3
"""Install a persistent user service and start it without disturbing active scanout."""

import os
from pathlib import Path
import subprocess
import sys


def quoted(value):
    # systemd command quoting, not shell quoting. Escape specifiers and expansion.
    return (
        '"'
        + str(value)
        .replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("%", "%%")
        .replace("$", "$$")
        + '"'
    )


def main():
    root = Path(__file__).resolve().parents[1]
    binary = Path(
        os.environ.get("FLOATDESK_BIN", root / "target/release/floatdesk")
    ).resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise SystemExit(
            "Build the host with cargo build --release, or set FLOATDESK_BIN."
        )
    if not os.environ.get("DISPLAY"):
        raise SystemExit("Start Floatdesk from the X11 desktop session.")
    folder = (
        Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
        / "systemd/user"
    )
    folder.mkdir(parents=True, exist_ok=True)
    command = " ".join(map(quoted, [binary, "serve", "--existing", *sys.argv[1:]]))
    unit = (
        "[Unit]\nDescription=Floatdesk desktop streaming\nAfter=graphical-session-pre.target\n"
        "PartOf=graphical-session.target\n\n[Service]\nType=simple\n"
        f"ExecStart={command}\nRestart=on-failure\nRestartSec=2\nLimitFSIZE=infinity\n"
        "StandardOutput=journal\nStandardError=journal\n\n[Install]\nWantedBy=graphical-session.target\n"
    )
    path = folder / "floatdesk-live.service"
    temporary = path.with_suffix(".tmp")
    temporary.write_text(unit)
    temporary.replace(path)
    env_keys = [key for key in ("DISPLAY", "XAUTHORITY") if os.environ.get(key)]
    subprocess.run(["systemctl", "--user", "import-environment", *env_keys], check=True)
    subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
    # A running legacy transient unit shadows enable's name lookup. Install the
    # standard Wants link directly; do not stop it and tear down virtual outputs.
    wants = folder / "graphical-session.target.wants"
    wants.mkdir(exist_ok=True)
    link = wants / path.name
    if link.is_symlink() and link.resolve() != path.resolve():
        raise SystemExit("Floatdesk startup link points to a different service.")
    if not link.exists():
        link.symlink_to(path)
    subprocess.run(
        ["systemctl", "--user", "start", "floatdesk-live.service"], check=True
    )


if __name__ == "__main__":
    main()
