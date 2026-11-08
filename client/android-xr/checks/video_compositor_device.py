#!/usr/bin/env python3
"""Compile and run the real offscreen compositor check against the installed debug app."""
from pathlib import Path
import os
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
    subprocess.run([str(javac),'--release','17','-cp',f'{platform}:{classes}','-d',str(folder/'classes'),str(root/'checks/VideoCompositorSmoke.java')],check=True)
    subprocess.run([str(d8),'--lib',str(platform),'--output',str(folder/'dex'),str(folder/'classes/VideoCompositorSmoke.class')],check=True)
    remote='/data/local/tmp/floatdesk-compositor-smoke.dex'
    subprocess.run([adb,'push',str(folder/'dex/classes.dex'),remote],check=True)
    try:
        subprocess.run([adb,'shell',f'CLASSPATH={shlex.quote(remote+":"+installed)} app_process /system/bin VideoCompositorSmoke'],check=True)
    finally:
        subprocess.run([adb,'shell','rm',remote],check=True)
