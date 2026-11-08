#!/usr/bin/env python3
"""Check deliberate air-swipe clutching and discrete one-hand panel gaze activation."""
from pathlib import Path
import subprocess
import sys
import tempfile
folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source='\n'.join((folder/name).read_text().split('\n',1)[1] for name in ['AirSwipe.kt','PanelGazeInput.kt'])
checks='''
internal data class PointerTarget(val monitor:Int,val u:Float,val v:Float,val local:FloatArray)
fun main() {
    val target=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
    for(count in 2..4) {
        val air=AirSwipe()
        fun f(t:Long,x:Float=0f,z:Float=0f,fold:Boolean=true,blocked:Boolean=false)=
            air.update(t,true,blocked,mapOf("LEFT" to AirHand(count,fold,floatArrayOf(x,0f,z))),target)
        check(!f(0).active)
        check(!f(240,z=.1f).active) // Depth alone is not a swipe.
        check(!f(260,x=.02f,z=.1f).active)
        val start=f(280,x=.04f,z=.1f)
        check(start.active && start.frame!!.fingers==count && start.frame.x>.5f)
        check(f(300,x=.05f,z=.1f).target===target)
        check(f(320,x=.05f,z=.1f,fold=false).frame!!.fingers==0)
        f(400); f(650,x=.04f)
        check(f(670,x=.3f).frame!!.fingers==0) // Tracking jump cancels.
        check(!f(1000,x=.31f).active) // Must reopen thumb after a jump.
        f(1020,fold=false); f(1040)
        check(!f(1300,x=.04f,blocked=true).active)
        check(!f(1600,x=.08f).active) // Release required after grab/panel blocking.
    }
    val panel=PanelGazeInput(listOf(PanelButtonArea(0,0f,0f,.45f,1f),PanelButtonArea(1,.55f,0f,.45f,1f)))
    fun p(t:Long,x:Float?=.2f,thumb:Float=1.2f,side:String?="LEFT")=
        panel.update(t,side,x?.let {floatArrayOf(it,.5f)},thumb)
    p(0); p(120); check(panel.focused==0)
    check(p(140,thumb=.6f)==null)
    check(p(200,thumb=.6f)==0)
    check(p(300,thumb=.6f)==null) // One fold, one activation.
    p(320,x=.8f,thumb=.6f); p(460,x=.8f,thumb=.6f)
    check(panel.focused==1 && p(480,x=.8f,thumb=.6f)==null)
    p(500,x=.8f); p(520,x=.8f,thumb=.6f)
    check(p(580,x=.8f,thumb=.6f)==1)
    p(600,x=.8f); p(620,x=null,thumb=.6f)
    check(p(720,x=null,thumb=.6f)==null) // Stale focus cannot click.
    p(900,x=null); check(panel.focused==null)
    p(920,side=null); check(panel.focused==null)
    p(940,thumb=.6f); p(1080,thumb=.6f)
    check(p(1160,thumb=.6f)==null) // Folded thumb on panel entry never arms.
    println("Passed: 2/3/4-finger clutch, planar threshold, target freeze, release/jump/block rearm; panel dwell, sticky focus, fresh thumb fold and stale-gaze rejection")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-air-panel-') as temp:
    file=Path(temp)/'AirPanelChecks.kt';file.write_text(source+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'AirPanelChecksKt'],check=True)
