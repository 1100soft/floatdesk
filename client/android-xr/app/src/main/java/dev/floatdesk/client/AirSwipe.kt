package dev.floatdesk.client

internal data class AirHand(val fingers: Int, val thumbFolded: Boolean, val position: FloatArray,
                            val right: FloatArray=floatArrayOf(1f,0f,0f), val up: FloatArray=floatArrayOf(0f,1f,0f))
internal data class TouchpadFrame(val fingers: Int, val x: Float, val y: Float)
internal data class AirSwipeOutput(val frame: TouchpadFrame?, val target: PointerTarget?, val active: Boolean)

/** Deliberate finger-count pose plus motion, not an ordinary open hand moving an aim ray. */
internal class AirSwipe {
    private var side: String? = null
    private var fingers=0
    private var since=0L
    private var origin: FloatArray?=null
    private var previous: FloatArray?=null
    private var active=false
    private var anchor: PointerTarget?=null
    private var mustRelease=false
    private var x=.5f
    private var y=.5f
    private var right=floatArrayOf(1f,0f,0f)
    private var up=floatArrayOf(0f,1f,0f)

    fun update(now: Long,enabled: Boolean,blocked: Boolean,hands: Map<String,AirHand>,target: PointerTarget?): AirSwipeOutput {
        val eligible=hands.filterValues { it.thumbFolded && it.fingers in 2..4 }
        if(!enabled || blocked || eligible.isEmpty()) {
            val end=if(active) TouchpadFrame(0,x,y) else null
            active=false; side=null; origin=null; previous=null; mustRelease=eligible.isNotEmpty() && (!enabled || blocked)
            return AirSwipeOutput(end,target,false)
        }
        if(mustRelease) return AirSwipeOutput(null,target,false)
        val selected=side?.takeIf { it in eligible } ?: eligible.keys.sorted().first()
        val hand=eligible.getValue(selected)
        if(selected!=side || hand.fingers!=fingers) {
            val end=if(active) TouchpadFrame(0,x,y) else null
            side=selected; fingers=hand.fingers; since=now; origin=hand.position.copyOf(); previous=hand.position.copyOf(); active=false; anchor=null
            right=hand.right.copyOf(); up=hand.up.copyOf()
            return AirSwipeOutput(end,target,false)
        }
        val before=previous ?: hand.position
        previous=hand.position.copyOf()
        val jump=kotlin.math.sqrt((0..2).sumOf { val d=hand.position[it]-before[it]; (d*d).toDouble() }).toFloat()
        if(jump>.15f) {
            val end=if(active) TouchpadFrame(0,x,y) else null
            active=false; mustRelease=true
            return AirSwipeOutput(end,target,false)
        }
        val start=origin ?: hand.position
        val delta=FloatArray(3) { hand.position[it]-start[it] }
        val dx=(0..2).sumOf { (delta[it]*right[it]).toDouble() }.toFloat()
        val dy=(0..2).sumOf { (delta[it]*up[it]).toDouble() }.toFloat()
        if(!active && now-since>=220 && kotlin.math.hypot(dx,dy)>=.03f) {
            active=true; anchor=target
        }
        if(!active) return AirSwipeOutput(null,target,false)
        x=(.5f+dx/.25f).coerceIn(.15f,.85f)
        y=(.5f-dy/.15f).coerceIn(.1f,.9f)
        return AirSwipeOutput(TouchpadFrame(fingers,x,y),anchor,true)
    }
}
