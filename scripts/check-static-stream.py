#!/usr/bin/env python3
"""Exercise idle and recovery video on a private large Xvfb desktop; capture no real desktop data."""
import os,subprocess,socket,struct,time,signal,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
probe=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);probe.bind(("127.0.0.1",0));control_port=probe.getsockname()[1];probe.close()
r,w=os.pipe();x=subprocess.Popen(['Xvfb','-displayfd',str(w),'-screen','0','5120x3200x24','-nolisten','tcp'],pass_fds=(w,),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);os.close(w);d=os.read(r,20).decode().strip();os.close(r)
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',0));s.settimeout(.2)
v=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);v.bind(('127.0.0.1',0));video_port=v.getsockname()[1];v.settimeout(.2)
log=tempfile.TemporaryFile(mode='w+t');env=dict(os.environ,DISPLAY=':'+d)
p=subprocess.Popen([str(root/'target/release/floatdesk'),'serve','--existing','--listen',f'127.0.0.1:{control_port}','--fps','60','--encoder','x264enc','--duration','12'],env=env,stdout=log,stderr=log)
def send(k,data):s.sendto(b'FDC1'+struct.pack('<BBHII',1,k,0,len(data),0)+data,('127.0.0.1',control_port))
try:
 time.sleep(.7);send(1,b'\x04test'+struct.pack('<H',video_port));start=time.monotonic();marks=0;packets=0;first=None
 while time.monotonic()-start<4:
  try:b,_=v.recvfrom(2048)
  except socket.timeout:continue
  packets+=1
  if b[1]&128:marks+=1;first=first or time.monotonic()-start
 assert marks>0,(packets,marks)
 print('PASS static 5120x3200 desktop with no input: complete access units',marks,'first_ms',round(first*1000))
 send(4,b'\xff');start=time.monotonic()
 while time.monotonic()-start<3:
  try:b,_=v.recvfrom(2048)
  except socket.timeout:continue
  if b[1]&128:break
 else:raise AssertionError('Recovery frame did not arrive on idle desktop')
 print('PASS requested recovery frame on idle desktop ms',round((time.monotonic()-start)*1000))
finally:
 p.send_signal(signal.SIGINT);p.wait(timeout=12);x.terminate();x.wait(timeout=5);log.close();s.close();v.close()
