#!/usr/bin/env python3
"""Run hand identity/discontinuity guards against stable, overlapping and corrupt input."""
from pathlib import Path
import subprocess
import sys
import tempfile
folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
imports=[];sources=[]
for name in ['HandTrackingQuality.kt','MonitorMesh.kt','PointerControl.kt','PinchPolicy.kt','MouseGestures.kt']:
    lines=(folder/name).read_text().splitlines()
    imports += [line for line in lines if line.startswith('import ')]
    sources.append('\n'.join(line for line in lines if not line.startswith(('package ','import '))))
main=(folder/'MainActivity.kt').read_text()
callback=next(line.split('onPalmAction = ',1)[1].strip().removesuffix(',') for line in main.splitlines() if 'onPalmAction = { action-> if(actionEpoch==' in line)
a=main.index('            if(handInputAllowed && !allowed) palmInteractionEpoch++')
b=main.index('            for(side in quality.rejected)',a)
transition=main[a:b]
sources.append("""
private class PanelCallbackHarness {
    var handInputAllowed=true
    var palmInteractionEpoch=0
    val actions=mutableListOf<Int>()
    fun palmAction(action:Int) { actions.add(action) }
    fun callback(): (Int)->Unit {
        val actionEpoch=palmInteractionEpoch
        return """+callback+"""
    }
    fun update(allowed:Boolean) {
"""+transition+"""
    }
}
""")
checks='''
fun main() {
    fun hand(x:Float)=HandTrackingSample(floatArrayOf(x,0f,0f),floatArrayOf(x,.05f,0f),floatArrayOf(x+.03f,.1f,0f),
        floatArrayOf(x,.17f,0f),floatArrayOf(x+.01f,.18f,0f),floatArrayOf(0f,0f,0f,1f))
    val stable=HandTrackingQuality()
    for(t in 0L..1000L step 20) {
        val result=stable.update(t,mapOf("LEFT" to hand(-.2f+t*.00002f),"RIGHT" to hand(.2f+t*.00002f)))
        check(result.rejected.isEmpty() && !result.overlapping) // No universal palm-entry delay.
    }
    val reacquired=HandTrackingQuality()
    reacquired.update(0,mapOf("LEFT" to hand(0f)))
    reacquired.update(100,emptyMap())
    check(reacquired.update(1000,mapOf("LEFT" to hand(.5f))).rejected.isEmpty())
    val duplicate=HandTrackingQuality()
    duplicate.update(0,mapOf("LEFT" to hand(0f),"RIGHT" to hand(.3f)))
    val bad=duplicate.update(20,mapOf("LEFT" to hand(0f),"RIGHT" to hand(.025f)))
    check(bad.overlapping && bad.rejected==setOf("LEFT","RIGHT"))
    val pointer=PointerControl()
    for(side in listOf("LEFT","RIGHT")) pointer.hover(0,side,floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f),.8f,.5f,20)
    for(side in bad.rejected) pointer.rejectHand(side)
    check(pointer.hands(setOf("LEFT","RIGHT"),20).isEmpty()) // Fresh native hover cannot preserve rejected identities.
    var recovered=bad
    for(t in 40L..400L step 20) recovered=duplicate.update(t,mapOf("LEFT" to hand(0f),"RIGHT" to hand(.3f)))
    check(recovered.rejected.isEmpty())
    val clone=HandTrackingQuality()
    for(t in 0L..200L step 20) clone.update(t,mapOf("RIGHT" to hand(.2f)))
    for(t in 220L..1000L step 20) {
        val result=clone.update(t,mapOf("RIGHT" to hand(.2f),"LEFT" to hand(.225f)))
        check(result.overlapping && result.continuousOwner=="RIGHT" && result.rejected==setOf("LEFT"))
    }
    val disappeared=clone.update(1020,mapOf("RIGHT" to hand(.2f)))
    check("RIGHT" !in disappeared.rejected) // Ghost disappearing is not a real identity switch.
    val unknown=HandTrackingQuality().update(0,mapOf("RIGHT" to hand(.2f),"LEFT" to hand(.225f)))
    check(unknown.continuousOwner==null && unknown.rejected==setOf("LEFT","RIGHT"))
    val establishedPair=HandTrackingQuality()
    for(t in 0L..200L step 20) establishedPair.update(t,mapOf("LEFT" to hand(0f),"RIGHT" to hand(.3f)))
    val merged=establishedPair.update(220,mapOf("LEFT" to hand(0f),"RIGHT" to hand(.025f)))
    check(merged.continuousOwner==null && merged.rejected==setOf("LEFT","RIGHT"))
    val routing=HandTrackingQuality()
    val gestures=MouseGestures()
    val target=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
    for(t in 0L..200L step 20) routing.update(t,mapOf("RIGHT" to hand(.2f)))
    gestures.update(200,true,false,mapOf("RIGHT" to FingerPinch(.07f,.07f)),target)
    for(t in listOf(220L,240L,260L)) {
        val decision=routing.update(t,mapOf("RIGHT" to hand(.2f),"LEFT" to hand(.225f)))
        val raw=mapOf("RIGHT" to FingerPinch(if(t==240L) .009f else .07f,.07f),"LEFT" to FingerPinch(.009f,.07f))
        val output=gestures.update(t,true,decision.overlapping && decision.continuousOwner==null,
            raw.filterKeys { it !in decision.rejected },target)
        if(t==260L) check(output.click?.button==1) else check(output.click==null)
    }
    val switched=HandTrackingQuality()
    switched.update(0,mapOf("LEFT" to hand(0f)))
    check(switched.update(20,mapOf("RIGHT" to hand(.02f))).rejected==setOf("LEFT","RIGHT"))
    val jumping=HandTrackingQuality()
    jumping.update(0,mapOf("RIGHT" to hand(0f)))
    check("RIGHT" in jumping.update(20,mapOf("RIGHT" to hand(.2f))).rejected)
    var jumpEnd=jumping.update(40,mapOf("RIGHT" to hand(.2f)))
    for(t in 60L..400L step 20) jumpEnd=jumping.update(t,mapOf("RIGHT" to hand(.2f)))
    check(jumpEnd.rejected.isEmpty())
    val wobble=HandTrackingQuality()
    wobble.update(0,mapOf("RIGHT" to hand(0f)))
    wobble.update(20,mapOf("RIGHT" to hand(.03f)))
    check("RIGHT" in wobble.update(40,mapOf("RIGHT" to hand(-.03f))).rejected)
    val corrupt=HandTrackingQuality()
    check("RIGHT" in corrupt.update(0,mapOf("RIGHT" to hand(0f).copy(wrist=floatArrayOf(Float.NaN,0f,0f)))).rejected)
    val rotated=HandTrackingQuality()
    rotated.update(0,mapOf("RIGHT" to hand(0f)))
    check("RIGHT" in rotated.update(20,mapOf("RIGHT" to hand(0f).copy(rotation=floatArrayOf(1f,0f,0f,0f)))).rejected)
    val ui=PanelCallbackHarness()
    val oldClick=ui.callback();oldClick(1);check(ui.actions==listOf(1))
    ui.update(false);oldClick(2);check(ui.actions==listOf(1))
    ui.update(true);oldClick(3);check(ui.actions==listOf(1)) // A delayed native click from the old panel is still invalid.
    val newClick=ui.callback();newClick(4);check(ui.actions==listOf(1,4))
    ui.update(false);ui.update(false);newClick(5);check(ui.actions==listOf(1,4))
    ui.update(true);ui.callback()(6);check(ui.actions==listOf(1,4,6))
    println("Passed: actual panel callback/epoch transitions reject delayed clicks through bad tracking and recovery")
    println("Passed: stable first-frame input, duplicate identities, identity switches, pose jumps/wobble/invalid joints, bounded recovery and native hover rejection")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-hand-quality-') as temp:
    file=Path(temp)/'HandQualityCheck.kt';file.write_text('\n'.join(sorted(set(imports)))+'\n'+'\n'.join(sources)+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'HandQualityCheckKt'],check=True)
