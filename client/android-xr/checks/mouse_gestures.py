#!/usr/bin/env python3
"""Run actual gaze filtering and mouse gesture state machines without Android."""
from pathlib import Path
import subprocess
import sys
import tempfile

folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
sources=[]
imports=[]
for name in ['MonitorMesh.kt','PointerControl.kt','GazeStabilizer.kt','MouseGestures.kt']:
    lines=(folder/name).read_text().splitlines()[1:]
    imports.extend(line for line in lines if line.startswith('import '))
    sources.append('\n'.join(line for line in lines if not line.startswith('import ')))
checks='''
fun main() {
    val target=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
    val open=FingerPinch(.07f,.07f); val index=FingerPinch(.009f,.07f); val middle=FingerPinch(.07f,.011f)
    val gestures=MouseGestures()
    fun frame(t: Long,sample: FingerPinch,point: PointerTarget?=target,enabled: Boolean=true,blocked: Boolean=false)=
        gestures.update(t,enabled,blocked,mapOf("RIGHT" to sample),point)
    frame(0,open)
    for(t in 1L..30L) {
        val relaxed=frame(t*20,FingerPinch(.021f,.021f,position=floatArrayOf(t*.001f,0f,0f)))
        check(!relaxed.dragging && relaxed.click==null && relaxed.scroll==null)
    }
    frame(0,open)
    check(frame(20,index).click == null)
    val first=frame(100,open).click!!
    check(first.button==1 && first.target.u==.5f)
    frame(170,index,target.copy(u=.505f))
    val second=frame(250,open,target.copy(u=.505f)).click!!
    check(second.target.u==first.target.u) // Two pinches retain one double-click location.
    frame(500,index)
    val down=frame(860,index,target.copy(u=.6f))
    check(down.dragging && down.target!!.u==.5f) // Button goes down at the original target.
    check(frame(880,index,target.copy(u=.7f)).target!!.u==.7f)
    check(!frame(920,open,target.copy(u=.7f)).dragging)
    frame(1000,middle)
    check(frame(1100,open).click!!.button==3)
    frame(1200,index)
    check(frame(1600,index).dragging)
    check(!frame(1620,index,enabled=false).dragging)
    check(frame(1800,index).click == null) // Held gesture cannot rearm on enable.
    frame(1820,open)
    frame(1840,index)
    val blocked=frame(1900,open,blocked=true)
    check(blocked.click == null && !blocked.dragging)
    frame(2000,open)
    frame(2020,index)
    check(frame(2400,index).dragging)
    val lost=gestures.update(2420,true,false,emptyMap(),target.copy(u=.9f))
    check(!lost.dragging && lost.click==null && lost.target!!.u==.5f)
    // Clicking hand is RIGHT; target can come from the other hand (or gaze).
    val cross=MouseGestures()
    cross.update(0,true,false,mapOf("RIGHT" to open),target.copy(u=.2f))
    cross.update(20,true,false,mapOf("RIGHT" to index),target.copy(u=.2f))
    check(cross.update(100,true,false,mapOf("RIGHT" to open),target.copy(u=.8f)).click!!.target.u==.2f)

    val buffered=PinchFrameBuffer()
    buffered.push(0,mapOf("RIGHT" to open))
    buffered.push(5,mapOf("RIGHT" to index))
    buffered.push(10,mapOf("RIGHT" to open))
    val burst=MouseGestures()
    check(buffered.drain(15).map { burst.update(it.at,true,false,it.samples,target) }.count { it.click!=null }==1)
    check(buffered.drain(16).isEmpty())
    buffered.push(20,mapOf("RIGHT" to index))
    check(buffered.drain(200).single().samples.isEmpty())
    // Losing the other hand must not reset an active clicking hand.
    val isolated=MouseGestures()
    isolated.update(0,true,false,mapOf("LEFT" to open,"RIGHT" to open),target)
    isolated.update(10,true,false,mapOf("RIGHT" to index),target)
    check(isolated.update(20,true,false,mapOf("RIGHT" to open),target).click!!.button==1)
    val leftContact=MouseGestures()
    leftContact.update(0,true,false,mapOf("LEFT" to open),target)
    leftContact.update(10,true,false,mapOf("LEFT" to FingerPinch(.015f,.07f)),target)
    check(leftContact.update(20,true,false,mapOf("LEFT" to open),target).click!!.button==1)
    for((side,threshold) in listOf("RIGHT" to .0115f,"LEFT" to .0155f)) {
        val binary=MouseGestures()
        val apart=FingerPinch(threshold+.0001f,.07f)
        val touching=FingerPinch(threshold,.07f)
        binary.update(0,true,false,mapOf(side to apart),target)
        binary.update(10,true,false,mapOf(side to touching),target)
        check(binary.update(20,true,false,mapOf(side to apart),target).click!!.button==1)
        binary.update(30,true,false,mapOf(side to touching),target)
        check(binary.update(40,true,false,mapOf(side to apart),target).click!!.button==1)
    }
    val narrowRelease=MouseGestures()
    narrowRelease.update(0,true,false,mapOf("RIGHT" to open),target)
    narrowRelease.update(10,true,false,mapOf("RIGHT" to index),target)
    check(narrowRelease.update(400,true,false,mapOf("RIGHT" to index),target).dragging)
    check(!narrowRelease.update(410,true,false,mapOf("RIGHT" to FingerPinch(.0151f,.07f)),target).dragging)
    // A relaxed index does not have to be held 45 mm away from the thumb.
    for(side in listOf("LEFT","RIGHT")) {
        val relaxedIndex=MouseGestures()
        val apart=FingerPinch(.033f,.025f)
        val touching=FingerPinch(.033f,.01f,position=floatArrayOf(0f,0f,0f))
        relaxedIndex.update(0,true,false,mapOf(side to apart),target)
        relaxedIndex.update(10,true,false,mapOf(side to touching),target)
        check(relaxedIndex.update(100,true,false,mapOf(side to apart),target).click!!.button==3)
        relaxedIndex.update(110,true,false,mapOf(side to touching),target)
        check(relaxedIndex.update(1000,true,false,mapOf(side to apart),target).click!!.button==3)
        relaxedIndex.update(1010,true,false,mapOf(side to touching),target)
        check(relaxedIndex.update(1020,true,false,mapOf(side to touching.copy(position=floatArrayOf(.02f,0f,0f))),target).scroll!!.fingers==2)
        val released=relaxedIndex.update(1030,true,false,mapOf(side to apart),target)
        check(released.scroll!!.fingers==0 && released.click==null)
        relaxedIndex.update(1040,true,false,mapOf(side to touching),target)
        check(relaxedIndex.update(1050,true,false,mapOf(side to apart),target).click!!.button==3)
    }
    for(side in listOf("LEFT","RIGHT")) {
        val repeats=MouseGestures()
        val threshold=mouseMiddleContactMetres(side)
        val apart=FingerPinch(.033f,threshold+.0001f)
        val touching=apart.copy(middleMetres=threshold)
        repeats.update(0,true,false,mapOf(side to apart),target)
        for(n in 0L..9L) {
            repeats.update(n*20+1,true,false,mapOf(side to touching),target)
            check(repeats.update(n*20+10,true,false,mapOf(side to apart),target).click!!.button==3)
        }
    }
    // Repeated combined pinches need only release ONE participating finger.
    for(side in listOf("LEFT","RIGHT")) for(releaseIndex in listOf(false,true)) {
        val independent=MouseGestures()
        val i=mouseIndexContactMetres(side);val m=mouseMiddleContactMetres(side)
        val both=FingerPinch(i-.0001f,m-.0001f)
        val partial=if(releaseIndex) both.copy(indexMetres=i+.0001f) else both.copy(middleMetres=m+.0001f)
        independent.update(0,true,false,mapOf(side to open),target)
        for(n in 0L..9L) {
            independent.update(n*20+1,true,false,mapOf(side to both),target)
            check(independent.update(n*20+10,true,false,mapOf(side to partial),target).click!!.button==2)
        }
    }
    val quick=MouseGestures()
    quick.update(0,true,false,mapOf("RIGHT" to open),target)
    quick.update(10,true,false,mapOf("RIGHT" to index),target)
    check(quick.update(20,true,false,mapOf("RIGHT" to open),target).click!!.button==1)
    quick.update(30,true,false,mapOf("RIGHT" to index),target)
    check(quick.update(390,true,false,mapOf("RIGHT" to index),target).dragging)
    check(!quick.update(400,true,false,mapOf("RIGHT" to open),null).dragging)
    val chord=MouseGestures()
    chord.update(0,true,false,mapOf("RIGHT" to open),target)
    chord.update(20,true,false,mapOf("RIGHT" to index),target)
    chord.update(60,true,false,mapOf("RIGHT" to FingerPinch(.009f,.011f)),target)
    check(chord.update(120,true,false,mapOf("RIGHT" to open),target).click!!.button==2)
    chord.update(200,true,false,mapOf("RIGHT" to FingerPinch(.009f,.011f)),target)
    check(!chord.update(600,true,false,mapOf("RIGHT" to FingerPinch(.009f,.011f)),target).dragging)
    check(chord.update(650,true,false,mapOf("RIGHT" to open),target).click!!.button==2)
    val scroll=MouseGestures()
    fun sf(t:Long,pinch:FingerPinch,blocked:Boolean=false)=scroll.update(t,true,blocked,mapOf("LEFT" to pinch),target.copy(u=.8f))
    sf(0,open)
    sf(20,middle.copy(position=floatArrayOf(0f,0f,0f)))
    check(sf(30,middle.copy(position=floatArrayOf(.003f,0f,0f))).scroll==null)
    val moved=sf(40,middle.copy(position=floatArrayOf(.025f,.015f,0f)))
    check(moved.scroll!!.fingers==2 && moved.target!!.u==.8f) // Starts after 20 ms, with no hold delay.
    check(kotlin.math.abs(moved.scroll.x-.6f)<.0001f && kotlin.math.abs(moved.scroll.y-.4f)<.0001f)
    check(sf(60,middle.copy(position=floatArrayOf(.025f,.015f,.05f))).scroll!!.y==moved.scroll.y)
    val end=sf(80,open)
    check(end.scroll!!.fingers==0 && end.click==null && !end.dragging)
    sf(100,middle.copy(position=floatArrayOf(0f,0f,0f)))
    check(sf(180,open).click!!.button==3) // Still short pinch remains right-click.
    sf(200,middle.copy(position=floatArrayOf(0f,0f,0f)))
    check(sf(600,middle.copy(position=floatArrayOf(0f,0f,0f))).scroll==null)
    check(sf(620,open).click!!.button==3) // Duration alone never discards a stationary right-click.
    sf(700,middle.copy(position=floatArrayOf(0f,0f,0f)))
    sf(720,middle.copy(position=floatArrayOf(.02f,0f,0f)))
    val cancelled=sf(740,middle.copy(position=floatArrayOf(.02f,0f,0f)),true)
    check(cancelled.scroll!!.fingers==0 && cancelled.click==null)
    check(sf(800,middle.copy(position=floatArrayOf(.02f,0f,0f))).scroll==null)
    sf(820,open)
    sf(840,middle.copy(position=floatArrayOf(0f,0f,0f)))
    sf(860,middle.copy(position=floatArrayOf(.02f,0f,0f)))
    check(sf(880,middle.copy(position=floatArrayOf(.3f,0f,0f))).scroll!!.fingers==0)
    check(sf(900,open).click==null)
    for(sample in listOf(FingerPinch(.016f,.07f),FingerPinch(.07f,.0195f))) {
        val contact=MouseGestures()
        contact.update(0,true,false,mapOf("LEFT" to open),target)
        for(t in 1L..30L) {
            val output=contact.update(t*20,true,false,mapOf("LEFT" to sample.copy(position=floatArrayOf(t*.002f,0f,0f))),target)
            check(output.click==null && !output.dragging && output.scroll==null)
        }
        check(contact.update(650,true,false,mapOf("LEFT" to open),target).click==null)
    }
    val filter=GazeStabilizer()
    filter.update(target,0)
    var maximum=0f
    for(i in 1..100) {
        val raw=target.copy(u=.5f+if(i%2==0) .006f else -.006f)
        val shown=filter.update(raw,i*20L)!!
        maximum=maxOf(maximum,kotlin.math.abs(shown.u-.5f))
    }
    check(maximum<.002f) // Stationary jitter is reduced, not merely delayed.
    val before=filter.update(target,2020)!!
    val jump=filter.update(target.copy(u=.9f),2040)!!
    check(jump.u==before.u) // Reject one isolated large outlier.
    val shifted=filter.update(target.copy(u=.9f),2060)!!
    check(shifted.u>.8f) // A deliberate sustained gaze shift remains responsive.
    check(filter.update(null,2200)==null)
    check(filter.update(target.copy(monitor=1,u=.1f),2220)!!.u==.1f)
    println("Passed: gaze jitter reduction/outlier rejection/fast shifts/reset, click/double pinch/right-click/drag, cross-hand target, disabled/preview/tracking-loss cancellation")
}
'''
lib=Path(sys.argv[1]); stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-mouse-') as temp:
    file=Path(temp)/'MouseGestureChecks.kt'
    file.write_text('\n'.join(sorted(set(imports)))+'\n'+'\n'.join(sources)+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'MouseGestureChecksKt'],check=True)
