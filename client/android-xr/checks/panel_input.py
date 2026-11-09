#!/usr/bin/env python3
"""Check deliberate discrete one-hand panel gaze activation."""
from pathlib import Path
import subprocess
import sys
import tempfile
folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source='\n'.join((folder/name).read_text().split('\n',1)[1] for name in ['PanelGazeInput.kt','EyeTrackingLifecycle.kt'])
checks='''
internal data class PointerTarget(val monitor:Int,val u:Float,val v:Float,val local:FloatArray)
fun main() {
    val target=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
    val lifecycle=EyeTrackingLifecycle()
    check(lifecycle.update(0,true,true,false,false,false).configureFine==true)
    check(!lifecycle.update(20,true,true,true,false,false).refreshResources)
    check(lifecycle.update(40,true,true,true,false,false).refreshResources)
    // Panel visibility and desktop eye-mouse routing do not control sensor readiness.
    for(t in 60L..3000L step 20) {
        val action=lifecycle.update(t,true,true,true,true,true)
        check(action.configureFine==null && !action.clearResources && !action.refreshResources)
    }
    val stalled=lifecycle.update(4000,true,true,true,true,false)
    check(stalled.refreshResources && stalled.configureFine==null)
    val modeLost=lifecycle.update(5000,true,true,false,true,false)
    check(modeLost.configureFine==true && modeLost.clearResources)
    check(lifecycle.update(5020,true,true,false,false,false).configureFine==null) // Bounded configuration retry.
    check(lifecycle.update(5100,true,true,true,false,false).refreshResources)
    check(lifecycle.update(5200,false,true,true,true,true).clearResources)
    val resume=lifecycle.update(6000,true,true,true,true,true)
    check(resume.clearResources && resume.refreshResources) // Reacquire handles after resume.
    val revoked=lifecycle.update(7000,true,false,true,true,true)
    check(revoked.configureFine==false && revoked.clearResources && !revoked.refreshResources)
    check(lifecycle.update(7020,true,false,false,false,false).configureFine==null)
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
    println("Passed: warm eye lifecycle, stalled-resource/mode recovery, resume and permission handling; panel dwell, sticky focus, fresh middle pinch and stale-gaze rejection")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-air-panel-') as temp:
    file=Path(temp)/'AirPanelChecks.kt';file.write_text(source+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'AirPanelChecksKt'],check=True)
