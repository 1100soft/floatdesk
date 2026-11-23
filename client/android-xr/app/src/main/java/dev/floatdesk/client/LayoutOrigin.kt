package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose

internal data class LayoutOriginGuide(
    val viewer: Pose,
    val rotation: FloatArray,
    val near: FloatArray,
    val far: FloatArray,
    val nearRange: Float,
    val farRange: Float,
    val spherical: Boolean,
) {
    val gripRadius get() = nearRange * .022f

    /** Preserve all view angles while pulling the visible guides inside the desktop layer. */
    fun foreground(desktopRange:Float?,units:Float):LayoutOriginGuide {
        if(desktopRange==null)return this
        val outerRange=farRange*(if(spherical)1.03f else 2.03f)
        val factor=((desktopRange-.018f*units)/outerRange).coerceIn(.05f,1f)
        if(factor==1f)return this
        fun scale(point:FloatArray)=FloatArray(3){viewer.position[it]+(point[it]-viewer.position[it])*factor}
        return copy(near=scale(near),far=scale(far),nearRange=nearRange*factor,farRange=farRange*factor)
    }

}

internal fun layoutOriginGuide(origin: Pose, viewer: Pose, near: Float, far: Float, spherical: Boolean): LayoutOriginGuide? {
    val ray = subtract(origin.position, viewer.position)
    if (dot(ray, ray) < 1e-8f) return null
    val direction = normalize3(ray)
    val forward = rotateVector(viewer.rotation, 0f, 0f, -1f)
    val normal = if (spherical) direction else forward
    val alignment = dot(direction, normal)
    if (alignment < .05f) return null
    val nearRange = near / alignment
    val farRange = far / alignment
    fun onRay(range: Float) = FloatArray(3) { viewer.position[it] + direction[it] * range }
    val rotation = if (spherical) facingMagnifierRotation(onRay(nearRange), viewer, origin.rotation) else viewer.rotation
    return LayoutOriginGuide(viewer, rotation, onRay(nearRange), onRay(farRange), nearRange, farRange, spherical)
}

/** The grip is projected onto the near layer. Move the actual origin at its original hand-to-eye gain. */
internal class LayoutOriginGrab {
    private data class Hold(val side: String, val time: Long, val origin: Pose, val hand: FloatArray, val gain: Float)
    private var hold: Hold? = null
    val active get() = hold != null

    fun begin(side: String, now: Long, origin: Pose, viewer: Pose, hand: FloatArray, units: Float) {
        if (active) return
        val range = distance(hand, viewer.position)
        if (range < .05f * units) return
        hold = Hold(side, now, origin, hand.copyOf(), distance(origin.position, viewer.position) / range)
    }

    fun advance(viewer: Pose, hands: Map<String, FloatArray>, pinched: Set<String>, now: Long, free: Boolean): Pose? {
        val start = hold ?: return null
        if (now - start.time >= 120 && start.side !in pinched) {
            clear()
            return null
        }
        val hand = hands[start.side] ?: return null
        val displaced = FloatArray(3) { start.origin.position[it] + (hand[it] - start.hand[it]) * start.gain }
        val ray = subtract(displaced, viewer.position)
        val range = distance(displaced, viewer.position)
        if (range < .0001f) return null
        val gain = if (free) 1f else distance(start.origin.position, viewer.position) / range
        val target = FloatArray(3) { viewer.position[it] + ray[it] * gain }
        return start.origin.copy(position = target).takeIf { target.all(Float::isFinite) }
    }

    fun end(side: String) {
        if (hold?.side == side) clear()
    }

    fun clear() { hold = null }
}
