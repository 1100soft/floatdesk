package dev.floatdesk.client

import kotlin.math.hypot

internal data class FingerPinch(val indexMetres: Float, val middleMetres: Float, val position: FloatArray? = null,
                               val right: FloatArray = floatArrayOf(1f,0f,0f), val up: FloatArray = floatArrayOf(0f,1f,0f))
internal data class TouchpadFrame(val fingers: Int, val x: Float, val y: Float)
internal data class MouseClick(val target: PointerTarget, val button: Int)
internal data class MouseGestureOutput(val target: PointerTarget?, val dragging: Boolean = false,
                                      val click: MouseClick? = null, val scroll: TouchpadFrame? = null)

/** Raw finger gestures are independent of which hand supplies the aiming ray. */
internal class MouseGestures {
    private data class Hold(val side: String, val button: Int, val began: Long, val target: PointerTarget)
    private val indexClosed=mutableSetOf<String>()
    private val middleClosed=mutableSetOf<String>()
    private val chordClosed=mutableSetOf<String>()
    private val armed=mutableSetOf<String>()
    private var hold: Hold? = null
    private var scrolling=false
    private var scrollOrigin: FloatArray?=null
    private var scrollPrevious: FloatArray?=null
    private var scrollRight=floatArrayOf(1f,0f,0f)
    private var scrollUp=floatArrayOf(0f,1f,0f)
    private var dragging=false
    private var dragTarget: PointerTarget? = null
    private var lastTarget: PointerTarget? = null
    private var lastTargetAt=0L
    private var lastClick: MouseClick? = null
    private var lastClickAt=0L

    private fun endScroll(): TouchpadFrame? {
        val end=if(scrolling) TouchpadFrame(0,.5f,.5f) else null
        scrolling=false; scrollOrigin=null; scrollPrevious=null
        return end
    }

    fun update(nowMs: Long, enabled: Boolean, blocked: Boolean, samples: Map<String,FingerPinch>,
               target: PointerTarget?): MouseGestureOutput {
        indexClosed.retainAll(samples.keys); middleClosed.retainAll(samples.keys); chordClosed.retainAll(samples.keys); armed.retainAll(samples.keys)
        for((side,sample) in samples) {
            fun gate(set: MutableSet<String>,distance: Float) {
                if(!distance.isFinite() || distance>.025f) set.remove(side)
                else if(distance<.019f) set.add(side)
            }
            gate(chordClosed,maxOf(sample.indexMetres,sample.middleMetres))
            gate(indexClosed,sample.indexMetres)
            gate(middleClosed,sample.middleMetres)
            // A middle pinch is distinct only when the index finger remains open.
            if(sample.indexMetres<.045f) middleClosed.remove(side)
        }
        if(!enabled || blocked) {
            val release=if(dragging) dragTarget else target
            hold=null; dragging=false; armed.clear(); lastClick=null
            return MouseGestureOutput(release,scroll=endScroll())
        }
        if(target != null) { lastTarget=target; lastTargetAt=nowMs }
        for(side in samples.keys) if(side !in indexClosed && side !in middleClosed) armed.add(side)
        var current=hold
        if(current != null) {
            for(side in samples.keys) if(side != current.side && (side in indexClosed || side in middleClosed)) armed.remove(side)
            if(current.side !in samples) {
                val release=if(dragging) dragTarget else target
                hold=null; dragging=false; armed.remove(current.side)
                return MouseGestureOutput(release,scroll=endScroll())
            }
            if(current.button!=2 && !dragging && !scrolling && nowMs-current.began<=150 && current.side in chordClosed) {
                current=current.copy(button=2); hold=current
            }
            val closed=current.side in (when(current.button) { 1->indexClosed; 2->chordClosed; else->middleClosed })
            if(closed) {
                val sample=samples.getValue(current.side)
                if(current.button==3 && !scrolling && sample.position!=null && scrollOrigin!=null) {
                    val delta=FloatArray(3) { sample.position[it]-scrollOrigin!![it] }
                    val dx=(0..2).sumOf { (delta[it]*scrollRight[it]).toDouble() }.toFloat()
                    val dy=(0..2).sumOf { (delta[it]*scrollUp[it]).toDouble() }.toFloat()
                    // Movement, rather than a hold timer, distinguishes scrolling from a click.
                    if(hypot(dx,dy)>=.008f) scrolling=true
                    if(!scrolling) scrollPrevious=sample.position.copyOf()
                }
                if(scrolling) {
                    val position=sample.position
                    val before=scrollPrevious
                    if(position==null || before==null || kotlin.math.sqrt((0..2).sumOf { val d=position[it]-before[it]; (d*d).toDouble() })>.15) {
                        hold=null; armed.remove(current.side)
                        return MouseGestureOutput(current.target,scroll=endScroll())
                    }
                    scrollPrevious=position.copyOf()
                    val delta=FloatArray(3) { position[it]-scrollOrigin!![it] }
                    val dx=(0..2).sumOf { (delta[it]*scrollRight[it]).toDouble() }.toFloat()
                    val dy=(0..2).sumOf { (delta[it]*scrollUp[it]).toDouble() }.toFloat()
                    return MouseGestureOutput(current.target,scroll=TouchpadFrame(2,(.5f+dx/.25f).coerceIn(.15f,.85f),(.5f-dy/.15f).coerceIn(.1f,.9f)))
                }
                if(current.button==1 && !dragging && nowMs-current.began>=350) {
                    dragging=true
                    dragTarget=current.target
                    return MouseGestureOutput(current.target,true)
                }
                if(dragging && target != null) dragTarget=target
                return MouseGestureOutput(if(dragging) target ?: lastTarget else current.target,dragging)
            }
            hold=null
            if(scrolling) return MouseGestureOutput(current.target,scroll=endScroll())
            if(dragging) { dragging=false; return MouseGestureOutput(target ?: lastTarget) }
            if(current.button==3 && nowMs-current.began>350) return MouseGestureOutput(target)
            if(nowMs-current.began<35) return MouseGestureOutput(target)
            var clicked=current.target
            val previous=lastClick
            if(current.button==1 && previous?.button==1 && nowMs-lastClickAt<=400 &&
                previous.target.monitor==clicked.monitor && hypot(previous.target.u-clicked.u,previous.target.v-clicked.v)<.015f)
                clicked=previous.target
            val click=MouseClick(clicked,current.button)
            lastClick=click; lastClickAt=nowMs
            return MouseGestureOutput(clicked,click=click)
        }
        val startTarget=target ?: lastTarget?.takeIf { nowMs-lastTargetAt<=120 }
        for(side in samples.keys.sorted()) {
            if(side !in armed) continue
            val button=when(side) { in chordClosed -> 2; in indexClosed -> 1; in middleClosed -> 3; else -> continue }
            armed.remove(side)
            if(startTarget == null) continue
            hold=Hold(side,button,nowMs,startTarget)
            if(button==3) {
                val sample=samples.getValue(side)
                scrollOrigin=sample.position?.copyOf(); scrollPrevious=sample.position?.copyOf()
                scrollRight=sample.right.copyOf(); scrollUp=sample.up.copyOf()
            }
            return MouseGestureOutput(startTarget)
        }
        return MouseGestureOutput(target)
    }
}
