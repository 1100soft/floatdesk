package dev.floatdesk.client

internal data class PointerTarget(val monitor: Int, val u: Float, val v: Float, val local: FloatArray)
internal data class HandAimPose(val position: FloatArray,val rotation: FloatArray)
internal enum class PointerMode { OFF, GAZE, HAND, PREVIEW, GRAB }
internal data class PointerChoice(val mode: PointerMode, val target: PointerTarget? = null)

/** Hover is observational: it never arms a pinch or changes the monitor pose. */
internal class PointerControl {
    private data class Motion(val before: PointerTarget,val at: Long,val beforeAt: Long)
    private val dimensions=mutableMapOf<Int,Pair<Float,Float>>()
    private val motion=mutableMapOf<String,Motion>()
    private val hovering = mutableMapOf<String, PointerTarget>()
    private val departedAt = mutableMapOf<String, Long>()
    private val lastHoverAt = mutableMapOf<String, Long>()
    private val handPoses=mutableMapOf<String,HandAimPose>()
    private val hoverPoses=mutableMapOf<String,HandAimPose>()
    private var resumeAfterGrab = 0L
    val meshes = mutableMapOf<Int, MonitorMesh?>()

    fun hover(monitor: Int, side: String, point: FloatArray?, flat: FloatArray?, width: Float, height: Float, nowMs: Long = 0) {
        if (side !in listOf("LEFT", "RIGHT")) return
        if (point == null || flat == null) {
            // Native mesh replacement can briefly end/restart hover. Avoid a gaze takeover
            // during that handoff; actual hand tracking loss is still removed immediately.
            if (hovering[side]?.monitor == monitor) departedAt[side] = nowMs
            return
        }
        val u = flat[0]/width+.5f; val v = .5f-flat[1]/height
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) {
            if (hovering[side]?.monitor == monitor) hovering.remove(side)
            return
        }
        dimensions[monitor]=width to height
        val next=PointerTarget(monitor,u,v,point.copyOf())
        val previous=hovering[side]
        val previousAt=lastHoverAt[side]
        if(previous!=null && previous.monitor==monitor && previousAt!=null && nowMs>previousAt &&
            (previous.u!=u || previous.v!=v)) motion[side]=Motion(previous,nowMs,previousAt)
        else motion.remove(side)
        hovering[side] = next
        handPoses[side]?.let { hoverPoses[side]=it }
        lastHoverAt[side] = nowMs
        departedAt.remove(side)
    }

    fun rejectHand(side: String) {
        motion.remove(side)
        hovering.remove(side);departedAt.remove(side);lastHoverAt.remove(side);handPoses.remove(side);hoverPoses.remove(side)
    }

    fun clearMonitor(monitor: Int) {
        motion.entries.removeAll { it.value.before.monitor==monitor }
        hovering.entries.removeAll { it.value.monitor == monitor }
        dimensions.remove(monitor)
        meshes.remove(monitor)
    }

    fun clear() { dimensions.clear(); motion.clear(); hovering.clear(); departedAt.clear(); lastHoverAt.clear(); handPoses.clear(); hoverPoses.clear() }

    fun hands(tracked: Set<String>, nowMs: Long = 0, poses: Map<String,HandAimPose> = emptyMap()): Map<String, PointerTarget> {
        // The OS aim ray can remain valid through a brief missing joint sample. Fresh
        // native hover is authoritative; once both streams stop, discard the stale target.
        hovering.keys.removeAll { it !in tracked && nowMs-(lastHoverAt[it] ?: 0) >= 80 }
        handPoses.clear(); handPoses.putAll(poses)
        fun stationary(side: String): Boolean {
            val current=poses[side] ?: return false
            val anchor=hoverPoses[side] ?: return false
            val movement=kotlin.math.sqrt((0..2).sumOf { val d=current.position[it]-anchor.position[it]; (d*d).toDouble() })
            val rotation=kotlin.math.abs((0..3).sumOf { (current.rotation[it]*anchor.rotation[it]).toDouble() })
            return side in tracked && movement<.015 && rotation>kotlin.math.cos(Math.toRadians(2.5))
        }
        // A stationary tracked hand has no expiry. An SDK hover-exit caused by idle fading
        // cannot erase a still-valid contact; real motion/rotation away still releases it.
        hovering.keys.removeAll { departedAt[it]?.let { left -> nowMs-left >= 80 && !stationary(it) } == true }
        departedAt.keys.retainAll(hovering.keys)
        lastHoverAt.keys.retainAll(hovering.keys)
        hoverPoses.keys.retainAll(hovering.keys)
        return hovering.toMap()
    }

    /** Bounded visual-only prediction; cursor and grab targets always use measured contacts. */
    fun visualHands(contacts: Map<String,PointerTarget>,now: Long): Map<String,PointerTarget> = contacts.mapValues { (side,hit) ->
        val sample=motion[side]
        val age=sample?.let { now-it.at } ?: Long.MAX_VALUE
        val dt=sample?.let { it.at-it.beforeAt } ?: 0
        if(sample==null || age !in 0..50 || dt !in 4..100 || sample.before.monitor!=hit.monitor) hit else {
            val du=hit.u-sample.before.u;val dv=hit.v-sample.before.v
            if(kotlin.math.hypot(du,dv)>.15f) hit else {
                val lead=(age+8).coerceAtMost(30).toFloat()/dt
                val u=(hit.u+(du*lead).coerceIn(-.035f,.035f)).coerceIn(0f,1f)
                val v=(hit.v+(dv*lead).coerceIn(-.035f,.035f)).coerceIn(0f,1f)
                val mesh=meshes[hit.monitor]
                // Width/height come from the same geometry used by native hit localization.
                val size=dimensions[hit.monitor]
                if(size==null) hit else {
                    val flat=floatArrayOf((u-.5f)*size.first,(.5f-v)*size.second,0f)
                    hit.copy(u=u,v=v,local=mesh?.project(flat) ?: flat)
                }
            }
        }
    }

    fun choose(enabled: Boolean, gaze: PointerTarget?, hands: Map<String, PointerTarget>, grabbing: Boolean, nowMs: Long = 0): PointerChoice {
        if (grabbing) resumeAfterGrab = nowMs+120
        // Restoring the live surface restarts its native hover sequence. Let both hands
        // repopulate before allowing gaze to take over after releasing a monitor.
        return when {
            grabbing || nowMs < resumeAfterGrab -> PointerChoice(PointerMode.GRAB)
            hands.size >= 2 -> PointerChoice(PointerMode.PREVIEW)
            hands.size == 1 -> PointerChoice(PointerMode.HAND,hands.values.single())
            enabled -> PointerChoice(PointerMode.GAZE,gaze)
            else -> PointerChoice(PointerMode.OFF)
        }
    }
}
