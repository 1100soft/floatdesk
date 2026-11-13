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

/** Pinch edges share mouse thresholds, without clicks, drags or hold timers. */
internal class FocusGestures {
    private val ready=mutableSetOf<String>()
    fun update(enabled:Boolean,blocked:Boolean,samples:Map<String,FingerPinch>,hover:Map<String,FocusHover>):FocusTarget? {
        ready.removeAll{it.substringBefore(':') !in samples}
        for((side,sample) in samples) {
            if(!indexPinchClosed(side,sample.indexMetres))ready.add("$side:1")
            if(!sample.middleMetres.isFinite() || sample.middleMetres>mouseMiddleContactMetres(side))ready.add("$side:3")
        }
        if(!enabled || blocked) {ready.clear();return null}
        for((side,sample) in samples) {
            val index=indexPinchClosed(side,sample.indexMetres)
            val middle=sample.middleMetres.isFinite() && sample.middleMetres<=mouseMiddleContactMetres(side)
            if(index==middle)continue
            val key="$side:${if(index)1 else 3}"
            if(!ready.remove(key))continue
            val target=hover[side] ?: hover.values.singleOrNull() ?: continue
            return if(middle)target.screen else target.window ?: target.screen
        }
        return null
    }
}
