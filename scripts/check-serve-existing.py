#!/usr/bin/env python3
"""Persistent launcher installation without touching the real user service manager."""

import json
import os
from pathlib import Path
import subprocess
import tempfile

launcher = Path(__file__).with_name("serve-existing.sh").resolve()
with tempfile.TemporaryDirectory(prefix="floatdesk-launch-") as temp:
    root = Path(temp)
    binary = root / "host with spaces"
    binary.write_text("#!/bin/sh\nexit 0\n")
    binary.chmod(0o755)
    capture = root / "arguments.jsonl"
    stub = root / "systemctl"
    stub.write_text(
        '#!/usr/bin/env python3\nimport json,os,sys\nwith open(os.environ["LAUNCH_CHECK_ARGS"],"a") as f:f.write(json.dumps(sys.argv[1:])+"\\n")\n'
    )
    stub.chmod(0o755)
    env = dict(
        os.environ,
        PATH=str(root) + os.pathsep + os.environ["PATH"],
        FLOATDESK_BIN=str(binary),
        DISPLAY=":77",
        XAUTHORITY="/tmp/test auth",
        XDG_CONFIG_HOME=str(root / "config"),
        LAUNCH_CHECK_ARGS=str(capture),
    )
    subprocess.run(
        [str(launcher), "--fps", "60", "--encoder", "vah264enc"], env=env, check=True
    )
    calls = [json.loads(line) for line in capture.read_text().splitlines()]
    assert calls == [
        ["--user", "import-environment", "DISPLAY", "XAUTHORITY"],
        ["--user", "daemon-reload"],
        ["--user", "start", "floatdesk-live.service"],
    ]
    unit = (root / "config/systemd/user/floatdesk-live.service").read_text()
    assert (
        f'ExecStart="{binary}" "serve" "--existing" "--fps" "60" "--encoder" "vah264enc"'
        in unit
    )
    for option in (
        "Restart=on-failure",
        "LimitFSIZE=infinity",
        "StandardOutput=journal",
        "WantedBy=graphical-session.target",
    ):
        assert option in unit
    assert (
        root
        / "config/systemd/user/graphical-session.target.wants/floatdesk-live.service"
    ).resolve() == (root / "config/systemd/user/floatdesk-live.service")
    assert (
        "XAUTHORITY=" not in unit
    )  # Session credentials come from the current manager environment.
    capture.unlink()
    env["FLOATDESK_BIN"] = str(root / "missing")
    result = subprocess.run([str(launcher)], env=env, capture_output=True)
    assert result.returncode != 0 and not capture.exists()
print(
    "PASS persistent login service, session environment, quoted arguments and missing-binary failure"
)
