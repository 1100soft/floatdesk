#!/usr/bin/env python3
"""Bounded detail TCP/JPEG path on private Xvfb; never reads the user's desktop."""
import os,socket,struct,subprocess,tempfile,time,json,signal
from io import BytesIO
from PIL import Image
from pathlib import Path
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='floatdesk-detail-x11-') as temp:
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
   host=subprocess.Popen([str(root/'target/release/floatdesk'),'serve','--existing','--listen',f'127.0.0.1:{port}','--fps','10','--encoder','x264enc','--duration','15'],env=env,stdout=log,stderr=log)
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as control:
    control.bind(('127.0.0.1',0));control.settimeout(.2)
    hello=b'\x06detail'+struct.pack('<H',49800)
    deadline=time.monotonic()+5;session=None
    while time.monotonic()<deadline:
     control.sendto(b'FDC1'+struct.pack('<BBHII',1,1,0,len(hello),0)+hello,('127.0.0.1',port))
     try:data=control.recv(65535)
     except socket.timeout:continue
     if data[5]==2:session=struct.unpack_from('<Q',data,16)[0];break
    assert session
    def exact(stream,n):
     out=b''
     while len(out)<n:
      block=stream.recv(n-len(out));assert block,'Detail connection closed';out+=block
     return out
    def request(rect,session_id=session):
     with socket.create_connection(('127.0.0.1',port),2) as stream:
      stream.settimeout(2);payload=json.dumps(dict(sessionId=session_id,rect=rect)).encode();stream.sendall(struct.pack('>I',len(payload))+payload)
      meta=json.loads(exact(stream,struct.unpack('>I',exact(stream,4))[0]));jpeg=exact(stream,meta['bytes'])
      assert meta['width']==1536 and meta['height']==1024 and len(jpeg)<4*1024*1024 and jpeg[:2]==b'\xff\xd8' and jpeg[-2:]==b'\xff\xd9'
      return meta,jpeg
    subprocess.run(["xsetroot","-solid","red"],env=env,check=True)
    first,jpeg=request([.1,.1,.5,.6]);assert Image.open(BytesIO(jpeg)).getpixel((768,512))[0]>200;time.sleep(.15)
    subprocess.run(["xsetroot","-solid","blue"],env=env,check=True)
    second,jpeg=request([.5,.2,.9,.7]);assert first['rect']!=second['rect'];assert Image.open(BytesIO(jpeg)).getpixel((768,512))[2]>200
    assert second['desktop']==[0,0,1920,1200]
    time.sleep(.15)
    try:request([0,0,1,1],session+1)
    except (AssertionError,ConnectionError):pass
    else:raise AssertionError('Expired session accepted')
    time.sleep(.15);request([.2,.2,.4,.4])
    print('PASS bound-client/session detail path, reusable crop capture, bounded JPEG, geometry association, recovery after invalid session')
  finally:
   if host and host.poll() is None:host.send_signal(signal.SIGTERM);host.wait(timeout=5)
   x.terminate();x.wait(timeout=5)
