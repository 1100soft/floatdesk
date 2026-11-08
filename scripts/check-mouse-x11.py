#!/usr/bin/env python3
"""Validate actual XTest button behavior on a private Xvfb, never the user's desktop."""
import os
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[1]
with subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','800x600x24','-nolisten','tcp'],
                      stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True) as server:
    try:
        number=server.stdout.readline().strip()
        if not number.isdigit():
            raise RuntimeError('Could not start isolated Xvfb')
        env=dict(os.environ,DISPLAY=':'+number,FLOATDESK_INPUT_TEST_DISPLAY='1')
        subprocess.run(['cargo','test','--offline','-p','floatdesk',
                        'platform::x11::pointer_device_tests::buttons_drag_timeout_and_drop_release',
                        '--','--ignored','--exact'],cwd=root,env=env,check=True)
    finally:
        server.terminate()
        server.wait(timeout=5)
