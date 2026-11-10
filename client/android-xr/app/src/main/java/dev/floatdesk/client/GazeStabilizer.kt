package dev.floatdesk.client

import kotlin.math.PI
import kotlin.math.hypot

/** Speed-adaptive low pass: steady fixations are filtered more than deliberate gaze shifts. */
internal class GazeStabilizer {
    private var lastRaw: PointerTarget? = null
    private var filtered: PointerTarget? = null
    private var output: PointerTarget? = null
    private var pendingJump: PointerTarget? = null
    private var lastMs = 0L
    private var velocityU = 0f
    private var velocityV = 0f

    fun reset() { lastRaw=null; filtered=null; output=null; pendingJump=null; velocityU=0f; velocityV=0f }

    fun update(target: PointerTarget?, nowMs: Long): PointerTarget? {
        if (target == null) { if(nowMs-lastMs>120) reset(); return null }
        val previous=lastRaw
        val before=filtered
        if(previous == null || before == null || target.monitor != previous.monitor || nowMs-lastMs>120) {
            lastRaw=target; filtered=target; output=target; lastMs=nowMs; velocityU=0f; velocityV=0f
            return target
        }
        val distance=hypot(target.u-previous.u,target.v-previous.v)
        if(distance>.12f) {
            val pending=pendingJump
            if(pending == null || hypot(target.u-pending.u,target.v-pending.v)>.04f) {
                pendingJump=target
                return output
            }
        }
        pendingJump=null
        val dt=((nowMs-lastMs)/1000f).coerceIn(.005f,.1f)
        fun alpha(cutoff: Float)=1f/(1f+1f/(2f*PI.toFloat()*cutoff*dt))
        val derivativeAlpha=alpha(1f)
        velocityU+=derivativeAlpha*((target.u-previous.u)/dt-velocityU)
        velocityV+=derivativeAlpha*((target.v-previous.v)/dt-velocityV)
        val a=alpha(1.2f+20f*hypot(velocityU,velocityV))
        val next=target.copy(u=before.u+a*(target.u-before.u),v=before.v+a*(target.v-before.v))
        val shown=output
        if(shown == null || hypot(next.u-shown.u,next.v-shown.v)>.002f) output=next
        lastRaw=target; filtered=next; lastMs=nowMs
        return output
    }
}
