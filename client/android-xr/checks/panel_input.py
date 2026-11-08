#!/usr/bin/env python3
"""Check deliberate discrete one-hand panel gaze activation."""
from pathlib import Path
import subprocess
import sys
import tempfile
folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source='\n'.join((folder/name).read_text().split('\n',1)[1] for name in ['PanelGazeInput.kt','AimProjection.kt'])
checks='''
internal data class PointerTarget(val monitor:Int,val u:Float,val v:Float,val local:FloatArray)
fun main() {
    val target=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
    val beam=projectedAimBeam(floatArrayOf(-.4f,.5f,0f),floatArrayOf(.8f,.5f,0f),floatArrayOf(.8f,.5f)) { point ->
        if(point[0] in 0f..1f) floatArrayOf(point[0],point[1]) else null
    }
    check(beam.isNotEmpty() && beam[0] in 0f..0.00002f) // No missing piece at the screen edge.
    check(beam[beam.size-2]==.8f && beam[beam.size-1]==.5f)
    val narrow=projectedAimBeam(floatArrayOf(-10f,.5f,0f),floatArrayOf(.01f,.5f,0f),floatArrayOf(.01f,.5f)) { point ->
        if(point[0] in 0f..1f) floatArrayOf(point[0],point[1]) else null
    }
    check(narrow[0] in 0f..0.0001f) // Even when only the last coarse sample is visible.
    val panel=PanelGazeInput(listOf(PanelButtonArea(0,0f,0f,.45f,1f),PanelButtonArea(1,.55f,0f,.45f,1f)))
    fun p(t:Long,x:Float?=.2f,thumb:Float=.07f,side:String?="LEFT")=
        panel.update(t,side,x?.let {floatArrayOf(it,.5f)},thumb)
    p(0); p(120); check(panel.focused==0)
    check(p(140,thumb=.015f)==null)
    check(p(200,thumb=.015f)==0)
    check(p(300,thumb=.015f)==null) // One fold, one activation.
    p(320,x=.8f,thumb=.015f); p(460,x=.8f,thumb=.015f)
    check(panel.focused==1 && p(480,x=.8f,thumb=.015f)==null)
    p(500,x=.8f); p(520,x=.8f,thumb=.015f)
    check(p(580,x=.8f,thumb=.015f)==1)
    p(600,x=.8f); p(620,x=null,thumb=.015f)
    check(p(720,x=null,thumb=.015f)==null) // Stale focus cannot click.
    p(900,x=null); check(panel.focused==null)
    p(920,side=null); check(panel.focused==null)
    p(940,thumb=.015f); p(1080,thumb=.015f)
    check(p(1160,thumb=.015f)==null) // Folded thumb on panel entry never arms.
    println("Passed: panel dwell, sticky focus, fresh middle pinch and stale-gaze rejection")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-air-panel-') as temp:
    file=Path(temp)/'AirPanelChecks.kt';file.write_text(source+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'AirPanelChecksKt'],check=True)
