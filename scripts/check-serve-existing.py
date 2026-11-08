#!/usr/bin/env python3
"""Check the managed launcher arguments without touching the user's service manager."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

launcher = Path(__file__).with_name('serve-existing.sh').resolve()
with tempfile.TemporaryDirectory(prefix='floatdesk-launch-') as temp:
    root = Path(temp)
    binary = root/'host with spaces'
    binary.write_text('#!/bin/sh\nexit 0\n'); binary.chmod(0o755)
    capture = root/'arguments.json'
    stub = root/'systemd-run'
    stub.write_text('#!/usr/bin/env python3\nimport json, os, sys\nfrom pathlib import Path\nPath(os.environ["LAUNCH_CHECK_ARGS"]).write_text(json.dumps(sys.argv[1:]))\n')
    stub.chmod(0o755)
    env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'], FLOATDESK_BIN=str(binary),
               DISPLAY=':77', XAUTHORITY='/tmp/test auth', LAUNCH_CHECK_ARGS=str(capture))
    subprocess.run([str(launcher),'--fps','60','--encoder','vah264enc'],env=env,check=True)
    args = json.loads(capture.read_text())
    for option in ('--user','--collect','--unit=floatdesk-live','--property=Restart=on-failure',
                   '--property=RestartSec=2s','--property=LimitFSIZE=infinity',
                   '--property=StandardOutput=journal','--property=StandardError=journal',
                   '--setenv=DISPLAY=:77','--setenv=XAUTHORITY=/tmp/test auth'):
        assert option in args, option
    assert args[-7:] == [str(binary),'serve','--existing','--fps','60','--encoder','vah264enc']
    del env['XAUTHORITY']
    subprocess.run([str(launcher)],env=env,check=True)
    assert not any('XAUTHORITY=' in value for value in json.loads(capture.read_text()))
    capture.unlink()
    env['FLOATDESK_BIN'] = str(root/'missing')
    result = subprocess.run([str(launcher)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    assert result.returncode != 0 and not capture.exists()
print('Passed: managed restart/journal/file limits, quoted binary/environment/arguments, optional authority, missing binary failure')
