#!/usr/bin/env python3
"""Install a per-user Floatdesk launcher; optionally launch the tray at login."""
from pathlib import Path
import argparse,os,sys
parser=argparse.ArgumentParser();parser.add_argument('--autostart',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
# This interpreter must provide PySide6; the launcher does not depend on login-shell PATH.
import PySide6
entry='[Desktop Entry]\nType=Application\nName=Floatdesk\nComment=XR desktop streaming and virtual displays\nIcon=video-display\nTerminal=false\nExec="'+sys.executable+'" "'+str(root/'scripts/floatdesk-tray.py')+'" --show\nCategories=Utility;\n'
folder=Path(os.environ.get('XDG_DATA_HOME',Path.home()/'.local/share'))/'applications';folder.mkdir(parents=True,exist_ok=True);(folder/'floatdesk.desktop').write_text(entry)
if args.autostart:
 folder=Path(os.environ.get('XDG_CONFIG_HOME',Path.home()/'.config'))/'autostart';folder.mkdir(parents=True,exist_ok=True);(folder/'floatdesk.desktop').write_text(entry.replace(' --show',''))
print('Installed Floatdesk launcher'+(' and login tray' if args.autostart else ''))
