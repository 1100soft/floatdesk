#!/usr/bin/env python3
"""Reuse existing outputs; prepare an accessible empty VKMS backend without privilege escalation."""
from pathlib import Path
import os,re,subprocess,sys
root=Path(__file__).resolve().parent

def auto(count=4):
    def query():return subprocess.check_output(['xrandr','--query'],text=True)
    if re.search(r'^Virtual-[0-9-]+ connected',query(),re.M):
        subprocess.run([sys.executable,str(root/'vkms/modes.py')],check=True)
        return True
    config=Path('/sys/kernel/config/vkms')
    if config.is_dir() and os.access(config,os.W_OK) and not any(config.iterdir()):
        subprocess.run([sys.executable,str(root/'vkms/topology.py'),str(count)],check=True)
    # Never guess among multiple providers (e.g. EVDI plus VKMS).
    providers=subprocess.check_output(['xrandr','--listproviders'],text=True)
    rows=[(m[1],int(m[2],16)) for m in re.finditer(r'id: (0x[0-9a-f]+) cap: (0x[0-9a-f]+)',providers)]
    sinks=[p for p,cap in rows if cap&2]
    sources=[p for p,cap in rows if cap&1]
    if len(sinks)==1 and len(sources)==1:
        subprocess.run(['xrandr','--setprovideroutputsource',sinks[0],sources[0]],check=True)
    if re.search(r'^Virtual-[0-9-]+ connected',query(),re.M):
        subprocess.run([sys.executable,str(root/'vkms/modes.py')],check=True)
        return True
    print('Virtual displays need initial administrator setup. Open Floatdesk tray → Virtual screen setup.',file=sys.stderr)
    return False

if __name__=='__main__':
    try:auto(int(sys.argv[1]) if len(sys.argv)>1 else 4)
    except (OSError,subprocess.SubprocessError) as error:
        print('Automatic virtual-display preparation:',error,file=sys.stderr)
