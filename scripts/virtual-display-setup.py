#!/usr/bin/env python3
"""Read-only setup diagnosis or export a reviewable boot installation bundle."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess


def run(*args):
    try:
        return subprocess.run(args,capture_output=True,text=True,timeout=10)
    except (OSError,subprocess.TimeoutExpired):
        return None


def check():
    params=run('modinfo','-p','vkms')
    query=run('xrandr','--query')
    outputs=re.findall(r'^(Virtual-[0-9-]+) connected',query.stdout if query else '',re.M)
    configfs=Path('/sys/kernel/config/vkms').is_dir()
    supported=configfs or bool(params and 'create_default_dev' in params.stdout)
    print('VKMS configfs support:', 'available' if supported else 'not detected — install a compatible distribution kernel')
    print('X11 virtual output pool:',len(outputs),'outputs')
    print('Mode utility (cvt):','available' if shutil.which('cvt') else 'missing — install your distribution cvt utility')
    print('Status:','Ready; existing pool will be reused' if outputs else 'Administrator setup/provider linking required; use --write-bundle DIRECTORY')


def bundle(folder,count):
    folder.mkdir(parents=True,exist_ok=False)
    templates=Path(__file__).parent/'vkms'
    for src,dst in [('topology.py','floatdesk-topology.py'),('modes.py','floatdesk-xrandr.py')]:
        shutil.copyfile(templates/src,folder/dst)
    (folder/'floatdesk-vkms-boot').write_text(f'''#!/bin/sh
set -eu
modprobe configfs
mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config
modprobe vkms create_default_dev=0
exec /usr/bin/python3 /usr/local/libexec/floatdesk-topology.py {count}
''')
    (folder/'floatdesk-vkms.service').write_text('''[Unit]
Description=Floatdesk boot-only VKMS pool
After=systemd-modules-load.service sys-kernel-config.mount
Before=display-manager.service
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/libexec/floatdesk-vkms-boot
[Install]
WantedBy=multi-user.target
''')
    (folder/'floatdesk-xrandr.service').write_text('''[Unit]
Description=Publish Floatdesk virtual screen modes
After=graphical-session.target
PartOf=graphical-session.target
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /usr/local/libexec/floatdesk-xrandr.py
[Install]
WantedBy=graphical-session.target
''')
    (folder/'install-system.sh').write_text('''#!/bin/sh
set -eu
[ "$(id -u)" = 0 ] || { echo "Run this installer as administrator after reviewing the bundle"; exit 1; }
cd -- "$(dirname -- "$0")"
modinfo -p vkms | grep -q create_default_dev || { echo "Kernel lacks VKMS configfs support"; exit 1; }
if [ -d /sys/kernel/config/vkms ] && [ -n "$(find /sys/kernel/config/vkms -mindepth 1 -maxdepth 1 ! -name floatdesk -print -quit)" ]; then
    echo "Another application owns the VKMS topology. Reuse its pool; it will not be replaced."; exit 1
fi
install -d /usr/local/libexec
install -m 755 floatdesk-topology.py floatdesk-xrandr.py floatdesk-vkms-boot /usr/local/libexec/
install -m 644 floatdesk-vkms.service /etc/systemd/system/
printf 'options vkms create_default_dev=0\\n' > /etc/modprobe.d/floatdesk-vkms.conf
systemctl daemon-reload
systemctl enable floatdesk-vkms.service
echo "Boot service enabled, not started. Reboot when convenient; no live kernel topology was changed."
''')
    (folder/'install-user.sh').write_text('''#!/bin/sh
set -eu
[ "$(id -u)" != 0 ] || { echo "Run this as your desktop user, without sudo"; exit 1; }
cd -- "$(dirname -- "$0")"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$unit_dir"
install -m 644 floatdesk-xrandr.service "$unit_dir/"
systemctl --user daemon-reload
systemctl --user enable floatdesk-xrandr.service
echo "Mode publication enabled for graphical login. Inspect provider linking after reboot."
''')
    (folder/'README.txt').write_text('''Review these files first. Requires Linux with VKMS configfs, X11, systemd, xrandr and cvt.
1. Existing third-party pool? Reuse it instead. Do not install this bundle over another topology service. A Floatdesk-owned pool can be resized at next boot.
2. As administrator: sh install-system.sh. As desktop user: sh install-user.sh.
3. Reboot when convenient. No script here reboots or restarts the graphical session for you.
4. If Virtual-* outputs are absent, run /usr/local/libexec/floatdesk-xrandr.py --providers.
   Identify VKMS and primary provider IDs; explicitly run that helper with --link VKMS_ID PRIMARY_ID.
   Provider attachment is Xorg/GPU-specific; never assume stable IDs. Repeat if IDs change after reboot.
5. Run the mode helper (without arguments) and restart Floatdesk host. Use Screens → Add screen.
Rollback: disable both floatdesk services; remove the installed Floatdesk-specific unit, helper and modprobe files, then reboot.
Changing count requires administrator maintenance before Xorg starts. Do not unload VKMS or remove active configfs pipelines.
This is a reviewable setup bundle, not a universal distribution installer. Provider auto-linking and distribution packaging remain release work.
''')
    print('Prepared',folder,'— no system settings were changed')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--write-bundle',type=Path)
    parser.add_argument('--count',type=int,default=4)
    args=parser.parse_args()
    if args.count<1:parser.error("count must be positive")
    if args.check or args.write_bundle is None:
        check()
    if args.write_bundle:
        bundle(args.write_bundle,args.count)
