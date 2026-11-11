#!/usr/bin/env python3
"""Exercise the production arrangement snap rules without XR dependencies."""
from pathlib import Path
import subprocess
import sys
import tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'ScreenArrangement.kt').read_text().split('\n',1)[1]
code=source+'''
fun main() {
 val first=DesktopScreen(0,0,1920,1080)
 check(snapScreen(DesktopScreen(1955,31,1920,1080),listOf(first),64)==DesktopScreen(1920,0,1920,1080))
 check(snapScreen(DesktopScreen(22,1110,1920,1080),listOf(first),64)==DesktopScreen(0,1080,1920,1080))
 check(snapScreen(DesktopScreen(200,310,1280,720),listOf(first),64).y==360)
 check(snapScreen(DesktopScreen(2250,400,1920,1080),listOf(first),64)==DesktopScreen(2250,400,1920,1080))
 check(snapScreen(DesktopScreen(-20,-40,1920,1080),emptyList(),64)==first)
 check(snapScreen(DesktopScreen(20000,20000,1920,1080),emptyList(),64)==DesktopScreen(14464,15304,1920,1080))
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-arrangement-') as temp:
 file=Path(temp)/'ArrangementCheck.kt';file.write_text(code)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
 subprocess.run(['java','-cp',temp+':'+str(stdlib),'ArrangementCheckKt'],check=True)
print('Passed: edge/centre/adjacency snapping, free movement outside snap range and desktop bounds')
