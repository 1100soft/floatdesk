#!/usr/bin/env python3
"""Exercise the production arrangement snap rules without XR dependencies."""
from pathlib import Path
import subprocess
import sys
import tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'ScreenArrangement.kt').read_text().split('\n',1)[1]+'\n'+(root/'media/ScreenPresentation.kt').read_text().split('\n',1)[1]
code=source+'''
fun main() {
 val drop=ArrangementDropHistory()
 val a=DesktopScreen(100,0,100,100);val b=a.copy(x=150);val jitter=b.copy(x=158,y=8)
 drop.begin(a,0);drop.record(b,200);drop.record(b,300);drop.record(jitter,380)
 check(drop.finish(400)==b)
 check(drop.finish(600)==jitter) // A deliberate held position eventually becomes the drop.
 drop.begin(a,1000);drop.record(b,1200);check(drop.finish(1400)==b)
 drop.clear();check(drop.finish(1500)==null)
 val full=ScreenPresentation()
 check(kotlin.math.abs(full.desktopPoint(.2f,.3f)!!.first-.2f)<.00001f)
 val layout=ScreenPresentation.from(listOf(100 to 100,100 to 100),listOf(-100 to 50,0 to 150),0f)
 check(layout.desktopPoint(.25f,.25f)==(.25f to .25f))
 check(layout.desktopPoint(.75f,.25f)==null)
 check(layout.desktopPoint(.25f,.75f)==null)
 val inset=layout.copy(spacing=.1f)
 check(inset.desktopPoint(.01f,.25f)==null)
 val mapped=inset.desktopPoint(.1375f,.25f)!!
 check(kotlin.math.abs(mapped.first-.125f)<.00001f)
 check(kotlin.math.abs(mapped.second-.25f)<.00001f)
 check(inset.desktopPoint(Float.NaN,.5f)==null)
 val chain=listOf(DesktopScreen(0,0,100,100),DesktopScreen(100,0,100,100),DesktopScreen(200,0,100,100))
 check(connectedScreens(chain,2).all {it})
 check(!canMoveScreen(chain,1,DesktopScreen(100,100,100,100),0))
 check(!canMoveScreen(chain,2,DesktopScreen(201,0,100,100),0))
 check(canMoveScreen(chain,2,DesktopScreen(0,100,100,100),0))
 check(!canMoveScreen(chain,2,DesktopScreen(190,0,100,100),0))
 check(!canMoveScreen(chain,2,DesktopScreen(0,0,100,100),0))
 check(canMoveScreen(chain,2,DesktopScreen(-100,0,100,100),0))
 check(canMoveScreen(chain,2,DesktopScreen(0,-100,100,100),0))
 val view=arrangementViewport(listOf(DesktopScreen(2000,3000,100,100)),600f,220f)
 check(kotlin.math.abs(view.offsetX+2050*view.scale-300)<.001f)
 check(kotlin.math.abs(view.offsetY+3050*view.scale-110)<.001f)
 check(view.offsetX+1900*view.scale>=0 && view.offsetY+2900*view.scale>=0)
 check(snapScreen(DesktopScreen(100,48,100,100),listOf(DesktopScreen(0,0,100,100)),4).y==50)
 check(snapScreen(DesktopScreen(100,-48,100,100),listOf(DesktopScreen(0,0,100,100)),4).y == -50)
 val first=DesktopScreen(0,0,1920,1080)
 check(snapScreen(DesktopScreen(1955,31,1920,1080),listOf(first),64)==DesktopScreen(1920,0,1920,1080))
 check(snapScreen(DesktopScreen(22,1110,1920,1080),listOf(first),64)==DesktopScreen(0,1080,1920,1080))
 check(snapScreen(DesktopScreen(200,310,1280,720),listOf(first),64).y==360)
 check(snapScreen(DesktopScreen(2250,400,1920,1080),listOf(first),64)==DesktopScreen(2250,400,1920,1080))
 check(snapScreen(DesktopScreen(-20,-40,1920,1080),emptyList(),64)==DesktopScreen(-20,-40,1920,1080))
 check(snapScreen(DesktopScreen(20000,20000,1920,1080),emptyList(),64)==DesktopScreen(20000,20000,1920,1080))
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-arrangement-') as temp:
 file=Path(temp)/'ArrangementCheck.kt';file.write_text(code)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
 subprocess.run(['java','-cp',temp+':'+str(stdlib),'ArrangementCheckKt'],check=True)
print('Passed: edge/centre/adjacency snapping, free movement outside snap range and desktop bounds')
