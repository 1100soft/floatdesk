#!/usr/bin/env python3
"""View-only H.264 atlas and baseline handoff on private Xvfb; never reads the user's desktop."""
import os,socket,struct,subprocess,tempfile,time,json,signal
from pathlib import Path
root=Path(__file__).resolve().parents[1]
width,height=map(int,os.environ.get("FLOATDESK_CHECK_SIZE","1920x1200").split("x"))
with tempfile.TemporaryDirectory(prefix='floatdesk-view-x11-') as temp:
 with open(temp+'/display','w+') as display,open(temp+'/log','w+') as log:
  x=subprocess.Popen(['Xvfb','-displayfd',str(display.fileno()),'-screen','0',f'{width}x{height}x24','-nolisten','tcp','-ac'],pass_fds=(display.fileno(),),stdout=log,stderr=log)
  host=None
  try:
   for _ in range(100):
    display.seek(0);number=display.read().strip()
    if number:break
    time.sleep(.05)
   assert number
   env=dict(os.environ,DISPLAY=':'+number,FLOATDESK_SYNTHETIC_DISPLAY='1');env.pop('XAUTHORITY',None)
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
   host=subprocess.Popen([str(root/'target/release/floatdesk'),'serve','--existing','--listen',f'127.0.0.1:{port}','--fps',os.environ.get('FLOATDESK_CHECK_FPS','10'),'--encoder',os.environ.get('FLOATDESK_CHECK_ENCODER','x264enc'),'--duration','40'],env=env,stdout=log,stderr=log)
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
    def request(stream,rect,session_id=session,center=None,**settings):
     payload=json.dumps(dict(mode='view-h264-v1',sessionId=session_id,rect=rect,center=center,**settings)).encode();stream.sendall(struct.pack('>I',len(payload))+payload)
     meta=json.loads(exact(stream,struct.unpack('>I',exact(stream,4))[0]));unit=exact(stream,meta['bytes'])
     assert meta['type']=='view-h264-v1' and meta['width']==2048 and meta['height']==1536 and len(unit)<8*1024*1024
     outer,inner=meta['rect'],meta['inner'];assert outer[0]<=inner[0]<inner[2]<=outer[2] and outer[1]<=inner[1]<inner[3]<=outer[3]
     return meta,unit
    def packets():
     count=0
     while True:
      try:video.recv(2048);count+=1
      except BlockingIOError:return count
    def control_json(kind,timeout=3):
     deadline=time.monotonic()+timeout
     while time.monotonic()<deadline:
      try:data=control.recv(65535)
      except socket.timeout:continue
      if data[5]==15:
       value=json.loads(data[16:])
       if value.get('type')==kind:return value
     raise AssertionError('Missing control message '+kind)
    send(15,json.dumps(dict(type='encoderSettings',quality=1,bitrateMbps=14)).encode())
    ack=control_json('encoderAck');assert ack['quality']==1 and ack['bitrate']==14000000
    telemetry=control_json('resourceStats')
    assert telemetry['cpuPct']>=0 and telemetry['rssMiB']>0 and telemetry['fps']>=0
    units=[];regions=[]
    with socket.create_connection(('127.0.0.1',port),2) as stream:
     stream.settimeout(2)
     for index in range(8):
      subprocess.run(['xsetroot','-solid','red' if index%2==0 else 'blue'],env=env,check=True)
      send(4,b'\xff')
      meta,unit=request(stream,[.1,.1,.5,.6] if index%2==0 else [.5,.2,.9,.7],center=[1.5,-.5] if index==1 else [-1e100,1e100] if index==2 else None);
      if index==1:assert meta['inner'][2]==meta['rect'][2] and meta['inner'][1]==meta['rect'][1], 'Off-edge gaze recentered'
      if index==2:assert meta['inner'][0]==meta['rect'][0] and meta['inner'][3]==meta['rect'][3], 'Extended gaze overflowed'
      units.append(unit);regions.append(meta['rect'])
      assert meta['frame']==index+1 and meta['desktop']==[0,0,width,height]
      count=packets()
      if index>=3:assert count==0,'Full-desktop RTP continued during view-only streaming'
    assert regions[0]!=regions[1]
    for quality in (1,2):
     with socket.create_connection(('127.0.0.1',port),2) as stream:
      stream.settimeout(3)
      meta,_=request(stream,[.1,.1,.9,.9],quality=quality,bitrateMbps=14,fraction=.25)
      assert abs((meta['inner'][2]-meta['inner'][0])/(meta['rect'][2]-meta['rect'][0])-.25)<.002
    with socket.create_connection(('127.0.0.1',port),2) as stream:
     stream.settimeout(3)
     meta,_=request(stream,[.2,.2,.6,.6],quality=0,bitrateMbps=14,fraction=1.0,center=[100,-100])
     assert all(abs(a-b)<.002 for a,b in zip(meta['inner'],meta['rect'])), '100% dense crop was capped'
    # Packed HEVC preserves native density for every requested coverage, including 100%.
    packed_rect=json.loads(os.environ.get("FLOATDESK_PACKED_RECT","[0.1,0.1,0.9,0.9]"))
    packed_units=[];packed_frames=int(os.environ.get("FLOATDESK_PACKED_FRAMES","4"));packed_start=time.monotonic()
    with socket.create_connection(('127.0.0.1',port),2) as stream:
     stream.settimeout(5)
     for packed_index in range(packed_frames):
      fraction=(.4,.7,.99,1.0)[packed_index%4]
      payload=json.dumps(dict(mode='view-tiles-v2',sessionId=session,rect=packed_rect,center=[.5,.5],fraction=fraction)).encode()
      stream.sendall(struct.pack('>I',len(payload))+payload)
      meta=json.loads(exact(stream,struct.unpack('>I',exact(stream,4))[0]));unit=exact(stream,meta['bytes']);packed_units.append(unit)
      assert meta['type']=='view-tiles-v2' and meta['codec']=='video/hevc'
      assert meta['width']<=width and meta['height']<=height
      assert meta['width']>=round(width*(packed_rect[2]-packed_rect[0])) and meta['height']>=round(height*(packed_rect[3]-packed_rect[1]))
      outer,inner=meta['rect'],meta['inner']
      assert abs((inner[2]-inner[0])/(outer[2]-outer[0])-fraction)<.002
      area=0
      for tile_index,(source,dest) in enumerate(meta['tiles']):
       assert all(0<=v<=1 for v in source+dest)
       density=1 if tile_index==0 else min(1,4096/width,2160/height)
       assert abs((source[2]-source[0])*width*density/meta['width']-(dest[2]-dest[0]))<.002
       assert abs((source[3]-source[1])*height*density/meta['height']-(dest[3]-dest[1]))<.002
       area+=(source[2]-source[0])*(source[3]-source[1])
      assert abs(area-(outer[2]-outer[0])*(outer[3]-outer[1]))<.00001
      assert 1<=len(meta['tiles'])<=5
      if fraction==1:assert len(meta['tiles'])==1
     packed_elapsed=time.monotonic()-packed_start
     packed_size=(meta['width'],meta['height'])
     # One persistent session must shrink/grow its atlas when zoom crosses a bucket.
     for resized_rect in ([.3,.3,.4,.4],[0,0,1,1]):
      payload=json.dumps(dict(mode='view-tiles-v2',sessionId=session,rect=resized_rect,fraction=1)).encode()
      stream.sendall(struct.pack('>I',len(payload))+payload)
      resized=json.loads(exact(stream,struct.unpack('>I',exact(stream,4))[0]));unit=exact(stream,resized['bytes'])
      dimensions=json.loads(subprocess.check_output(['ffprobe','-v','error','-f','hevc','-show_entries','stream=width,height','-of','json','pipe:0'],input=unit))['streams'][0]
      assert (dimensions['width'],dimensions['height'])==(resized['width'],resized['height'])
      assert len(resized['tiles'])==1
    elapsed=packed_elapsed
    print(f'Packed native probe: {packed_frames} frames at {width}x{height}, {packed_frames/elapsed:.1f} responses/s including startup; host '+next(line.strip() for line in Path(f'/proc/{host.pid}/status').read_text().splitlines() if line.startswith('VmHWM:')),flush=True)
    packed_file=Path(temp)/"packed-decoded.rgb"
    with packed_file.open("wb") as packed_output:subprocess.run(['ffmpeg','-v','error','-f','hevc','-i','pipe:0','-frames:v',str(packed_frames),'-f','rawvideo','-pix_fmt','rgb24','pipe:1'],input=b''.join(packed_units),stdout=packed_output,check=True)
    assert packed_file.stat().st_size==packed_size[0]*packed_size[1]*3*packed_frames
    print('PASS packed HEVC native density, changing 40/70/99/100% coverage, tile bounds/partition and decoded frames')
    send(15,json.dumps(dict(type='encoderSettings',quality=0,bitrateMbps=0)).encode())
    assert control_json('encoderAck')['quality']==0
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
    print('PASS actual view-only H.264 atlas, high/low capture pixels, per-frame crops, baseline pause/resume, off-view idle lease/expiry and session rejection, encoder presets/bitrate, dense fraction and telemetry')
  finally:
   if host and host.poll() is None:host.send_signal(signal.SIGTERM);host.wait(timeout=5)
   x.terminate();x.wait(timeout=5)
