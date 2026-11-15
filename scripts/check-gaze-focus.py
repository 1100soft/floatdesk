#!/usr/bin/env python3
"""Check gaze focus against isolated KWin/Xvfb; never touches the current desktop."""
import os,subprocess,tempfile,time,signal
from pathlib import Path
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='floatdesk-gaze-kwin-') as tmp:
 p=Path(tmp);(p/'runtime').mkdir(mode=0o700);(p/'config').mkdir();(p/'config/kwinrc').write_text('[Compositing]\nEnabled=false\n[Windows]\nFocusPolicy=ClickToFocus\nAutoRaise=false\nFocusStealingPreventionLevel=1\n')
 wm=None
 with (p/'display').open('w+') as display,(p/'kwin.log').open('w+') as log:
  x=subprocess.Popen(['Xvfb','-displayfd',str(display.fileno()),'-screen','0','1280x800x24','-nolisten','tcp','-ac'],pass_fds=(display.fileno(),),stdout=log,stderr=log)
  try:
   for _ in range(100):
    display.seek(0);number=display.read().strip()
    if number:break
    time.sleep(.05)
   assert number,'Xvfb did not start'
   env=dict(os.environ,DISPLAY=':'+number,XDG_CONFIG_HOME=str(p/'config'),XDG_RUNTIME_DIR=str(p/'runtime'),KWIN_COMPOSE='N',QT_QPA_PLATFORM='xcb',FLOATDESK_SYNTHETIC_DISPLAY='1');env.pop('XAUTHORITY',None)
   wm=subprocess.Popen(['/usr/bin/dbus-run-session','--','kwin_x11','--replace'],env=env,stdout=log,stderr=log,start_new_session=True)
   for _ in range(100):
    reply=subprocess.run(['xprop','-root','_NET_SUPPORTING_WM_CHECK'],env=env,capture_output=True,text=True)
    if 'window id' in reply.stdout:break
    if wm.poll() is not None:raise RuntimeError('KWin exited')
    time.sleep(.05)
   else:raise RuntimeError('KWin did not manage private display')
   result=subprocess.run(['cargo','test','--offline','focus_without_raise_private_kwin','--','--ignored','--nocapture'],env=env,cwd=root,capture_output=True,text=True,timeout=25)
   print(result.stdout);print(result.stderr[-2000:]);assert result.returncode==0
  except Exception:
   log.seek(0);print(log.read()[-4000:]);raise
  finally:
   if wm and wm.poll() is None:
    os.killpg(wm.pid,signal.SIGTERM)
    try:wm.wait(timeout=5)
    except subprocess.TimeoutExpired:os.killpg(wm.pid,signal.SIGKILL);wm.wait()
   x.terminate();x.wait(timeout=5)
