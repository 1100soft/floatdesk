#!/usr/bin/env python3
"""Boot-only VKMS provisioning. Never tear down another application's topology."""
import os
from pathlib import Path
import sys

root = Path('/sys/kernel/config/vkms')
count = int(sys.argv[1])
if not 1 <= count <= 5:
    raise SystemExit('Choose 1–5 virtual outputs')
if not root.is_dir():
    raise SystemExit('VKMS configfs unavailable; a compatible kernel and create_default_dev=0 are required')
base = root / 'floatdesk'
if base.exists():
    if (base/'enabled').read_text().strip() == '1' and len(list((base/'connectors').iterdir())) == count:
        raise SystemExit(0)
    raise SystemExit('Existing Floatdesk topology differs; change pool size during administrator maintenance')
if any(root.iterdir()):
    raise SystemExit('Another VKMS topology exists. Reuse it; do not create a second pool')
base.mkdir()
for i in range(count):
    plane, crtc, encoder, connector = [base/group/f'{name}{i}' for group,name in [('planes','plane'),('crtcs','crtc'),('encoders','encoder'),('connectors','connector')]]
    for item in (plane,crtc,encoder,connector):
        item.mkdir()
    (plane/'type').write_text('1\n')
    (crtc/'writeback').write_text('0\n')
    (connector/'status').write_text('1\n')
    os.symlink(crtc,plane/'possible_crtcs'/crtc.name)
    os.symlink(crtc,encoder/'possible_crtcs'/crtc.name)
    os.symlink(encoder,connector/'possible_encoders'/encoder.name)
(base/'enabled').write_text('1\n')
