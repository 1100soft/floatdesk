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
internal data class FocusTarget(val rect:FocusRect,val screen:String,val windowId:Long?=null)
internal data class FocusHover(val screen:FocusTarget,val window:FocusTarget?=null)
internal data class FocusResult(val requestId:Int,val ok:Boolean,val rect:FocusRect?,val message:String="")

/** Capture the highlighted target at contact, select on release; no hold timer or chord veto. */
internal class FocusGestures(private val thresholds:()->PinchThresholds={PinchThresholds.Default}) {
    private data class Hold(val side:String,val index:Boolean,val target:FocusTarget)
    private val previous=mutableMapOf<String,Int>()
    private var hold:Hold?=null
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
        if(!enabled || blocked) {hold=null;return null}
        hold?.let {current->
            val sample=samples[current.side]
            if(sample==null) {hold=null;return null}
            val closed=if(current.index)policy.indexClosed(current.side,sample.indexMetres) else policy.middleClosed(current.side,sample.middleMetres)
            if(closed)return null
            hold=null;return current.target
        }
        for((side,state) in edges) {
            val pressed=state.second
            if(pressed==0)continue
            val target=hover[side] ?: hover.values.singleOrNull() ?: continue
            val index=pressed and 1 != 0
            hold=Hold(side,index,if(index)target.window ?: target.screen else target.screen)
            break
        }
        return null
    }
}
