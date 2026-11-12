#!/usr/bin/env python3
"""A slow synthetic KScreen operation must not stall control heartbeats. Uses private Xvfb only."""
from pathlib import Path
import json,os,signal,socket,struct,subprocess,tempfile,time
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='floatdesk-display-worker-') as temp:
    folder=Path(temp);state=folder/'active';bin=folder/'bin';bin.mkdir()
    (bin/'xrandr').write_text('''#!/usr/bin/env python3
import os
from pathlib import Path
print('screen connected primary 800x600+0+0\\n   800x600 60.00*')
print('Virtual-1-1 connected'+(' 800x600+800+0' if Path(os.environ['FD_TEST_STATE']).exists() else '')+'\\n   800x600_low 0.10'+('*' if Path(os.environ['FD_TEST_STATE']).exists() else ''))
''')
    (bin/'kscreen-doctor').write_text('''#!/usr/bin/env python3
import sys,json,os,time
from pathlib import Path
state=Path(os.environ['FD_TEST_STATE'])
if '-j' in sys.argv:
 def row(name,x,active,priority,rate):return {'name':name,'enabled':active,'priority':priority,'pos':{'x':x,'y':0},'size':{'width':800,'height':600},'modes':[{'id':'low','size':{'width':800,'height':600},'refreshRate':rate}]}
 print(json.dumps({'outputs':[row('screen',0,True,1,60),row('Virtual-1-1',800,state.exists(),2,.1)]}))
else:
 time.sleep(2)
 if any('disable' in a for a in sys.argv):state.unlink(missing_ok=True)
 else:state.touch()
''')
    for p in bin.iterdir():p.chmod(0o755)
    x=subprocess.Popen(['Xvfb','-displayfd','1','-screen','0','1600x600x24','-nolisten','tcp'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
    number=x.stdout.readline().strip();assert number.isdigit()
    env=dict(os.environ,DISPLAY=':'+number,PATH=str(bin)+':'+os.environ['PATH'],FD_TEST_STATE=str(state))
    control=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);control.bind(('127.0.0.1',0));port=control.getsockname()[1];control.close()
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',0));s.settimeout(.2)
    log=open(folder/'host.log','w+')
    host=subprocess.Popen([str(root/'target/release/floatdesk'),'serve','--existing','--listen',f'127.0.0.1:{port}','--encoder','x264enc','--fps','10'],env=env,stdout=log,stderr=log)
    def send(kind,payload):s.sendto(b'FDC1'+struct.pack('<BBHII',1,kind,0,len(payload),0)+payload,('127.0.0.1',port))
    try:
        ready=time.monotonic()+25
        while True:
            send(1,b'\x04test'+struct.pack('<H',49990))
            try:hello,_=s.recvfrom(65535)
            except socket.timeout:
                assert time.monotonic()<ready,'Host did not start'
                continue
            if hello[5]==2:break
            assert time.monotonic()<ready,'Host did not send hello' 
        while True:
            try:s.recvfrom(65535)
            except socket.timeout:break
        start=time.monotonic()
        command=json.dumps({'type':'command','requestId':100,'action':1,'name':'','width':800,'height':600}).encode()
        send(15,command);heartbeat=None;completed=None;metadata=None;retried=False
        while time.monotonic()-start<8:
            try:b,_=s.recvfrom(65535)
            except socket.timeout:continue
            if b[5]==2 and heartbeat is None:heartbeat=time.monotonic()-start
            if not retried and time.monotonic()-start>.3:send(15,command);retried=True
            if b[5]==12 and struct.unpack_from('<I',b,16)[0]==100:
                assert b[20],b;completed=time.monotonic()-start
            if b[5]==15:
                m=json.loads(b[16:])
                if m['requestId']==100:metadata=m
            if completed and metadata:break
        assert heartbeat is not None and heartbeat<1.6,heartbeat
        assert completed and completed>=2,completed
        assert len(metadata['screens'])==2 and abs(metadata['screens'][1]['refreshHz']-.1)<.000001,metadata
        print(f'PASS: control heartbeat at {heartbeat:.2f}s during slow low-rate operation ({completed:.2f}s); lowest rate retained in workspace metadata')
    finally:
        host.send_signal(signal.SIGTERM)
        try:host.wait(timeout=12)
        finally:x.terminate();x.wait(timeout=5);s.close();log.close()
