package dev.floatdesk.client

/** Logical desktop coordinates, independent of stream downscaling and visual screen spacing. */
internal data class FocusRect(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    val centerU get()=(left+right)*.5f
    val centerV get()=(top+bottom)*.5f
    fun contains(u:Float,v:Float)=u>=left && u<=right && v>=top && v<=bottom
    fun intersect(other:FocusRect):FocusRect? {
        val r=FocusRect(maxOf(left,other.left),maxOf(top,other.top),minOf(right,other.right),minOf(bottom,other.bottom))
        return r.takeIf{it.right>it.left && it.bottom>it.top}
    }
}
internal data class FocusTarget(val rect:FocusRect,val screen:String,val windowId:Long?=null,val contactU:Float=rect.centerU,val contactV:Float=rect.centerV,val resetDesktop:Boolean=false)
internal data class FocusHover(val screen:FocusTarget,val window:FocusTarget?=null)
internal data class FocusResult(val requestId:Int,val ok:Boolean,val rect:FocusRect?,val message:String="")

/** One targeting context: hands win, system gaze is the fallback; mouse routing stays separate. */
internal fun focusAimContacts(hands:Map<String,PointerTarget>,gaze:PointerTarget?):Map<String,PointerTarget> =
    if(hands.isNotEmpty())hands else gaze?.let{mapOf("GAZE" to it)} ?: emptyMap()

/** Capture on contact, select on release. A deliberate overlapping two-finger contact wins. */
internal class FocusGestures(private val thresholds:()->PinchThresholds={PinchThresholds.Default}) {
    private data class Hold(val side:String,val fingers:Int,val target:FocusTarget)
    private val previous=mutableMapOf<String,Int>()
    private var hold:Hold?=null
    private var chordRelease:String?=null
    fun update(enabled:Boolean,blocked:Boolean,samples:Map<String,FingerPinch>,hover:Map<String,FocusHover>):FocusTarget? {
        previous.keys.retainAll(samples.keys)
        val policy=thresholds()
        val edges=samples.mapValues{(side,sample)->
            val bits=(if(policy.indexClosed(side,sample.indexMetres))1 else 0) or
                (if(policy.middleClosed(side,sample.middleMetres))2 else 0)
            val before=previous.put(side,bits) ?: bits
            bits to (bits and before.inv())
        }
        // Observe opens while blocked, rather than clearing a latch that requires another wide opening.
        if(!enabled || blocked) {hold=null;chordRelease=null;return null}
        chordRelease?.let{side->
            if((edges[side]?.first ?: 0)==0)chordRelease=null
            return null
        }
        hold?.let {current->
            val sample=samples[current.side]
            if(sample==null) {hold=null;return null}
            val bits=edges.getValue(current.side).first
            // Upgrade only when the second finger newly closes while the owned finger is closed.
            // A finger already resting closed before this gesture must not turn it into Reset.
            if(current.fingers!=3 && bits==3 && edges.getValue(current.side).second and current.fingers.inv()!=0) {
                hold=current.copy(fingers=3,target=current.target.copy(resetDesktop=true));return null
            }
            if(bits and current.fingers==current.fingers)return null
            hold=null
            if(current.fingers==3)chordRelease=current.side.takeIf{bits!=0}
            return current.target
        }
        for((side,state) in edges) {
            val pressed=state.second
            if(pressed==0)continue
            val target=hover[side] ?: hover.values.singleOrNull() ?: continue
            val index=pressed and 1 != 0
            val fingers=if(pressed==3)3 else if(index)1 else 2
            val captured=if(index)target.window ?: target.screen else target.screen
            hold=Hold(side,fingers,if(fingers==3)captured.copy(resetDesktop=true) else captured)
            break
        }
        return null
    }
}
