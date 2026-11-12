#!/usr/bin/env python3
"""Publish advertised modes without activating or moving outputs. Never guess provider IDs."""
import argparse
import re
import shlex
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--providers',action='store_true')
parser.add_argument('--link',nargs=2,metavar=('VKMS_PROVIDER','PRIMARY_PROVIDER'))
args=parser.parse_args()
if args.providers:
    raise SystemExit(subprocess.run(['xrandr','--listproviders']).returncode)
if args.link:
    for value in args.link:
        int(value,0)  # Require explicit numeric provider IDs.
    subprocess.run(['xrandr','--setprovideroutputsource',*args.link],check=True)
query=subprocess.check_output(['xrandr','--query'],text=True)
outputs=re.findall(r'^(Virtual-[0-9-]+) connected',query,re.M)
if not outputs:
    raise SystemExit('No Virtual-* outputs: inspect --providers and explicitly --link the VKMS and primary provider IDs')
registered=set(re.findall(r'^\s+(\S+)\s+\d',query,re.M))
added=0
for width,height in [(1280,720),(1920,1080),(2560,1600)]:
    for hz in [.1,1,10,30,60]:
        for command in [['cvt','-r',str(width),str(height),str(hz)],['cvt',str(width),str(height),str(hz)]]:
            result=subprocess.run(command,capture_output=True,text=True)
            line=next((s for s in result.stdout.splitlines() if s.startswith('Modeline ')),None)
            if result.returncode==0 and line:
                fields=shlex.split(line)[2:]
                if float(fields[0])>0:
                    break
        else:
            continue
        name=f'{width}x{height}_{hz:g}'
        if name not in registered:
            created=subprocess.run(['xrandr','--newmode',name,*fields],capture_output=True)
            if created.returncode:
                continue
            registered.add(name)
        for output in outputs:
            if subprocess.run(['xrandr','--addmode',output,name],capture_output=True).returncode==0:
                added+=1
print(f'Published modes on {len(outputs)} virtual outputs ({added} associations); output activation and positions unchanged')
