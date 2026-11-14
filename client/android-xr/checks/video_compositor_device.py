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
classes=root/'app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
installed=subprocess.check_output([adb,'shell','pm','path','dev.floatdesk.client'],text=True).strip().removeprefix('package:')
assert installed.startswith('/data/app/') and '\n' not in installed, installed
with tempfile.TemporaryDirectory(prefix='floatdesk-compositor-') as temp:
    folder=Path(temp); (folder/'classes').mkdir();(folder/'dex').mkdir()
    pixels=bytes([0,0,255,255])*(2048*1024)+bytes([255,0,0,255])*(2048*512)
    encoded=subprocess.check_output(['ffmpeg','-v','error','-f','rawvideo','-pixel_format','bgra','-video_size','2048x1536','-i','pipe:0','-frames:v','1','-c:v','libx264','-pix_fmt','yuv420p','-threads','2','-preset','ultrafast','-tune','zerolatency','-f','h264','pipe:1'],input=pixels)
    fixture=folder/'ViewFrameFixture.java';fixture.write_text('final class ViewFrameFixture {static final byte[] DATA=java.util.Base64.getDecoder().decode("'+base64.b64encode(encoded).decode()+'");}')
    subprocess.run([str(javac),'--release','17','-cp',f'{platform}:{classes}','-d',str(folder/'classes'),str(root/'checks/VideoCompositorSmoke.java'),str(root/'checks/DecoderIdleSmoke.java'),str(root/'checks/ViewStreamSmoke.java'),str(fixture)],check=True)
    subprocess.run([str(d8),'--lib',str(platform),'--output',str(folder/'dex'),*[str(file) for file in (folder/'classes').glob('*.class')]],check=True)
    remote='/data/local/tmp/floatdesk-compositor-smoke.dex'
    subprocess.run([adb,'push',str(folder/'dex/classes.dex'),remote],check=True)
    try:
        for main in ['VideoCompositorSmoke','DecoderIdleSmoke','ViewStreamSmoke']:
            subprocess.run([adb,'shell',f'CLASSPATH={shlex.quote(remote+":"+installed)} app_process /system/bin {main}'],check=True)
    finally:
        subprocess.run([adb,'shell','rm',remote],check=True)
