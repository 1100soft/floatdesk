#!/usr/bin/env python3
"""Private helper stubs: hidden tray is idle, visible probes cannot connect to IBus."""
from pathlib import Path
import importlib.util,json,os,tempfile,time,sys
os.environ['QT_QPA_PLATFORM']='offscreen'
os.environ['QT_IM_MODULE']='none'
sys.dont_write_bytecode=True
from PySide6.QtWidgets import QApplication
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='floatdesk-tray-check-') as temp:
    folder=Path(temp);log=folder/'calls';bin=folder/'bin';bin.mkdir()
    (bin/'kscreen-doctor').write_text('''#!/usr/bin/env python3
import json,os
with open(os.environ['FD_TRAY_LOG'],'a') as f:f.write(json.dumps({'im':os.environ.get('QT_IM_MODULE')})+'\\n')
print(json.dumps({'outputs':[{'name':'test','enabled':True,'size':{'width':800,'height':600},'pos':{'x':0,'y':0},'modes':[]}]}))
''')
    (bin/'systemctl').write_text('#!/bin/sh\necho active\n')
    for p in bin.iterdir():p.chmod(0o755)
    os.environ['PATH']=str(bin)+':'+os.environ['PATH'];os.environ['FD_TRAY_LOG']=str(log)
    # A caller explicitly requesting IBus must still be overridden for child helpers.
    spec=importlib.util.spec_from_file_location('floatdesk_tray',root/'scripts/floatdesk-tray.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    app=QApplication([]);window=mod.Window(None)
    def spin(seconds):
        end=time.monotonic()+seconds
        while time.monotonic()<end:app.processEvents();time.sleep(.005)
    def calls():return [json.loads(l) for l in log.read_text().splitlines()] if log.exists() else []
    try:
        assert not window.timer.isActive() and window.timer.interval()==15000
        window.refresh();spin(.15);assert not calls(),'Hidden tray launched a helper'
        os.environ['QT_IM_MODULE']='ibus'
        window.show();spin(.4)
        assert calls() and all(c['im']=='none' for c in calls()),calls()
        assert window.table.rowCount()==1,'Display results not rendered'
        window.timer.setInterval(50);spin(.4);assert len(calls())>1,'Visible refresh stopped working'
        window.hide();spin(.2);count=len(calls());window.refresh();spin(.2)
        assert not window.timer.isActive() and len(calls())==count,'Hidden timer kept probing'
        print('PASS hidden tray launches no probes, visible status updates, and every Qt helper has IBus disabled')
    finally:window.shutdown();window.close()
