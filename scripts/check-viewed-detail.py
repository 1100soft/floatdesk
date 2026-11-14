#!/usr/bin/env python3
"""View-only H.264 atlas and baseline handoff on private Xvfb; never reads the user's desktop."""
import os,socket,struct,subprocess,tempfile,time,json,signal
from pathlib import Path
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='floatdesk-view-x11-') as temp:
 with open(temp+'/display','w+') as display,open(temp+'/log','w+') as log:
  x=subprocess.Popen(['Xvfb','-displayfd',str(display.fileno()),'-screen','0','1920x1200x24','-nolisten','tcp','-ac'],pass_fds=(display.fileno(),),stdout=log,stderr=log)
  host=None
  try:
   for _ in range(100):
    display.seek(0);number=display.read().strip()
    if number:break
    time.sleep(.05)
   assert number
   env=dict(os.environ,DISPLAY=':'+number,FLOATDESK_SYNTHETIC_DISPLAY='1');env.pop('XAUTHORITY',None)
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
   host=subprocess.Popen([str(root/'target/release/floatdesk'),'serve','--existing','--listen',f'127.0.0.1:{port}','--fps','10','--encoder','x264enc','--duration','20'],env=env,stdout=log,stderr=log)
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as control,socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as video:
    control.bind(('127.0.0.1',0));control.settimeout(.2);video.bind(('127.0.0.1',0));video.setblocking(False)
    def send(kind,payload):control.sendto(b'FDC1'+struct.pack('<BBHII',1,kind,0,len(payload),0)+payload,('127.0.0.1',port))
    hello=b'\x06detail'+struct.pack('<H',video.getsockname()[1]);deadline=time.monotonic()+5;session=None
    while time.monotonic()<deadline:
     send(1,hello)
     try:data=control.recv(65535)
     except socket.timeout:continue
     if data[5]==2:session=struct.unpack_from('<Q',data,16)[0];break
    assert session
    def exact(stream,n):
     out=b''
     while len(out)<n:
      block=stream.recv(n-len(out));assert block,'View connection closed';out+=block
     return out
    def request(stream,rect,session_id=session):
     payload=json.dumps(dict(mode='view-h264-v1',sessionId=session_id,rect=rect)).encode();stream.sendall(struct.pack('>I',len(payload))+payload)
     meta=json.loads(exact(stream,struct.unpack('>I',exact(stream,4))[0]));unit=exact(stream,meta['bytes'])
     assert meta['type']=='view-h264-v1' and meta['width']==2048 and meta['height']==1536 and len(unit)<8*1024*1024
     outer,inner=meta['rect'],meta['inner'];assert outer[0]<=inner[0]<inner[2]<=outer[2] and outer[1]<=inner[1]<inner[3]<=outer[3]
     return meta,unit
    def packets():
     count=0
     while True:
      try:video.recv(2048);count+=1
      except BlockingIOError:return count
    units=[];regions=[]
    with socket.create_connection(('127.0.0.1',port),2) as stream:
     stream.settimeout(2)
     for index in range(8):
      subprocess.run(['xsetroot','-solid','red' if index%2==0 else 'blue'],env=env,check=True)
      send(4,b'\xff')
      meta,unit=request(stream,[.1,.1,.5,.6] if index%2==0 else [.5,.2,.9,.7]);units.append(unit);regions.append(meta['rect'])
      assert meta['frame']==index+1 and meta['desktop']==[0,0,1920,1200]
      count=packets()
      if index>=3:assert count==0,'Full-desktop RTP continued during view-only streaming'
    assert regions[0]!=regions[1]
    decoded=subprocess.check_output(['ffmpeg','-v','error','-f','h264','-i','pipe:0','-frames:v','2','-f','rawvideo','-pix_fmt','rgb24','pipe:1'],input=b''.join(units[:2]))
    stride=2048*1536*3
    assert len(decoded)==2*stride
    for y in [512,1280]:
     at=(y*2048+1024)*3
     assert decoded[at]>200 and decoded[at+2]<30
     assert decoded[stride+at+2]>200 and decoded[stride+at]<30
    deadline=time.monotonic()+2;resumed=False
    while time.monotonic()<deadline:
     if packets():resumed=True;break
     time.sleep(.02)
    assert resumed,'Normal video did not resume after view socket closed'
    for index in range(6):
     send(15,b'{"type":"viewIdle"}');send(4,b'\xff');subprocess.run(['xsetroot','-solid','green' if index%2==0 else 'yellow'],env=env,check=True);time.sleep(.1)
     count=packets()
     if index>=2:assert count==0,'Off-view idle lease did not pause full-desktop encoding'
    deadline=time.monotonic()+2
    while time.monotonic()<deadline:
     if packets():break
     time.sleep(.03)
    else:raise AssertionError('Baseline did not resume after the idle lease expired')
    with socket.create_connection(('127.0.0.1',port),2) as stream:
     stream.settimeout(2)
     try:request(stream,[0,0,1,1],session+1)
     except (AssertionError,ConnectionError):pass
     else:raise AssertionError('Expired session accepted')
    print('PASS actual view-only H.264 atlas, high/low capture pixels, per-frame crops, baseline pause/resume, off-view idle lease/expiry and session rejection')
  finally:
   if host and host.poll() is None:host.send_signal(signal.SIGTERM);host.wait(timeout=5)
   x.terminate();x.wait(timeout=5)
