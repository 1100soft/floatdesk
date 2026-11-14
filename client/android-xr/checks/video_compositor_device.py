#!/usr/bin/env python3
"""Compile and run the real offscreen compositor check against the installed debug app."""
from pathlib import Path
import os
import base64
import shlex
import re
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')))
javac=None
for home in [os.environ.get('JAVA_HOME',''),'/opt/android-studio/jbr']:
    candidate=Path(home)/'bin/javac'
    if not candidate.is_file(): continue
    version=subprocess.check_output([str(candidate),'-version'],stderr=subprocess.STDOUT,text=True)
    major=re.search(r'javac (\d+)',version)
    if major and int(major.group(1))>=17:
        javac=candidate; break
assert javac is not None, 'Set JAVA_HOME to a JDK version 17 or newer'
platform=sorted((sdk/'platforms').glob('android-*'))[-1]/'android.jar'
d8=sorted((sdk/'build-tools').glob('*/d8'))[-1]
adb=str(sdk/'platform-tools/adb')
gradle_roots=[Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle'))),Path.home()/'.gradle']
stdlib_candidates=[jar for cache in gradle_roots for jar in cache.glob('wrapper/dists/gradle-*/*/gradle-*/lib/kotlin-stdlib-*.jar')]
assert stdlib_candidates, 'Build once first, or set GRADLE_USER_HOME to the Gradle cache'
stdlib=stdlib_candidates[0]
classes=root/'app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
installed=subprocess.check_output([adb,'shell','pm','path','dev.floatdesk.client'],text=True).strip().removeprefix('package:')
assert installed.startswith('/data/app/') and '\n' not in installed, installed
with tempfile.TemporaryDirectory(prefix='floatdesk-compositor-') as temp:
    folder=Path(temp); (folder/'classes').mkdir();(folder/'dex').mkdir()
    pixels=bytes([0,0,255,255])*(2048*1024)+bytes([255,0,0,255])*(2048*512)
    encoded=subprocess.check_output(['ffmpeg','-v','error','-f','rawvideo','-pixel_format','bgra','-video_size','2048x1536','-i','pipe:0','-frames:v','1','-c:v','libx264','-pix_fmt','yuv420p','-threads','2','-preset','ultrafast','-tune','zerolatency','-f','h264','pipe:1'],input=pixels)
    pattern=(bytes([255,255,255,255,0,0,0,255])*1024)*1024+bytes([80,80,80,255])*(2048*512)
    pattern_video=subprocess.check_output(['ffmpeg','-v','error','-f','rawvideo','-pixel_format','bgra','-video_size','2048x1536','-i','pipe:0','-frames:v','1','-c:v','libx264','-pix_fmt','yuv420p','-threads','2','-preset','ultrafast','-tune','zerolatency','-crf','10','-f','h264','pipe:1'],input=pattern)
    pattern_file=folder/'pattern.h264';pattern_file.write_bytes(pattern_video)
    high=bytes([0,255,255,255])*(2048*1024)
    row_top=bytes([0,0,255,255])*1024+bytes([0,255,0,255])*1024
    row_bottom=bytes([0,255,255,255])*1024+bytes([255,0,0,255])*1024
    frames=[]
    for data in (high+row_top*256+row_bottom*256,bytes([0,255,255,255])*(2048*1536)):
        frames.append(subprocess.check_output(['ffmpeg','-v','error','-f','rawvideo','-pixel_format','bgra','-video_size','2048x1536','-i','pipe:0','-frames:v','1','-c:v','libx264','-pix_fmt','yuv420p','-threads','2','-preset','ultrafast','-tune','zerolatency','-f','h264','pipe:1'],input=data))
    # Blank unused atlas space must never bleed into reconstructed screen regions.
    packed=bytearray(1024*768*4)
    for x,y,w,h,color in [(256,192,512,384,bytes([255,255,255,255])),(0,0,128,384,bytes([0,0,255,255])),(768,0,128,384,bytes([0,255,0,255])),(256,0,256,96,bytes([255,0,0,255])),(256,576,256,96,bytes([0,255,255,255]))]:
        for row in range(y,y+h):packed[(row*1024+x)*4:(row*1024+x+w)*4]=color*w
    packed_file=folder/'packed.hevc'
    packed_file.write_bytes(subprocess.check_output(['ffmpeg','-v','error','-f','rawvideo','-pixel_format','bgra','-video_size','1024x768','-i','pipe:0','-frames:v','1','-c:v','libx265','-pix_fmt','yuv420p','-threads','2','-tune','zerolatency','-x265-params','pools=2:frame-threads=1:log-level=error:bframes=0','-preset','ultrafast','-f','hevc','pipe:1'],input=packed))
    native_file=folder/'native.hevc'
    subprocess.run(['gst-launch-1.0','-q','videotestsrc','num-buffers=1','pattern=white','!','video/x-raw,width=5120,height=3200,framerate=1/1','!','videoconvert','!','video/x-raw,format=NV12','!','vah265enc','bitrate=50000','target-usage=6','!','h265parse','!','video/x-h265,stream-format=byte-stream,alignment=au','!','filesink','location='+str(native_file)],check=True)
    screen_fixture=folder/'ScreenFrameFixture.java'
    screen_fixture.write_text('final class ScreenFrameFixture {'+''.join('static final byte[] '+name+'=java.util.Base64.getDecoder().decode("'+base64.b64encode(data).decode()+'");' for name,data in zip(('ALL','THIRD'),frames))+'}')
    fixture=folder/'ViewFrameFixture.java';fixture.write_text('final class ViewFrameFixture {static final byte[] DATA=java.util.Base64.getDecoder().decode("'+base64.b64encode(encoded).decode()+'");}')
    subprocess.run([str(javac),'--release','17','-cp',f'{platform}:{classes}:{stdlib}','-d',str(folder/'classes'),str(root/'checks/VideoCompositorSmoke.java'),str(root/'checks/DecoderIdleSmoke.java'),str(root/'checks/ViewStreamSmoke.java'),str(root/'checks/OutputResolutionSmoke.java'),str(root/'checks/SharpenSmoke.java'),str(root/'checks/ScreenAtlasSmoke.java'),str(root/'checks/PackedViewSmoke.java'),str(root/'checks/BudgetStreamSmoke.java'),str(screen_fixture),str(fixture)],check=True)
    subprocess.run([str(d8),'--lib',str(platform),'--classpath',str(stdlib),'--output',str(folder/'dex'),*[str(file) for file in (folder/'classes').glob('*.class')]],check=True)
    remote='/data/local/tmp/floatdesk-compositor-smoke.dex'
    subprocess.run([adb,'push',str(folder/'dex/classes.dex'),remote],check=True)
    subprocess.run([adb,'push',str(pattern_file),'/data/local/tmp/floatdesk-detail-pattern.h264'],check=True)
    subprocess.run([adb,'push',str(packed_file),'/data/local/tmp/floatdesk-packed.hevc'],check=True)
    subprocess.run([adb,'push',str(native_file),'/data/local/tmp/floatdesk-native.hevc'],check=True)
    try:
        for main in os.environ.get('FLOATDESK_DEVICE_CHECKS','VideoCompositorSmoke,DecoderIdleSmoke,ViewStreamSmoke,OutputResolutionSmoke,SharpenSmoke,ScreenAtlasSmoke,PackedViewSmoke,BudgetStreamSmoke').split(','):
            print('Running '+main,flush=True)
            subprocess.run([adb,'shell',f'CLASSPATH={shlex.quote(remote+":"+installed)} app_process /system/bin {main}'],check=True,timeout=45)
    finally:
        subprocess.run([adb,'shell','rm',remote,'/data/local/tmp/floatdesk-detail-pattern.h264','/data/local/tmp/floatdesk-packed.hevc','/data/local/tmp/floatdesk-native.hevc'],check=True)
