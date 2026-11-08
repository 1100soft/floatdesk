package dev.floatdesk.client

/** Quarantine tracking gaps and discontinuities; never replay movement accumulated during a gap. */
internal class TrackingGuard {
    private var previous: FloatArray? = null
    private var rotation: FloatArray? = null
    private var last = 0L
    private var stableSince: Long? = null

    fun update(now: Long, position: FloatArray?, orientation: FloatArray?, unitsPerMeter: Float): Boolean {
        if (position == null || orientation == null || !position.all { it.isFinite() } || !orientation.all { it.isFinite() }) {
            previous = null; rotation = null; stableSince = null; last = now
            return false
        }
        val before = previous
        val q = rotation
        val elapsed = (now-last).coerceAtLeast(1)
        val distance = if (before == null) 0f else kotlin.math.sqrt((0..2).sumOf { val d=position[it]-before[it]; (d*d).toDouble() }).toFloat()/unitsPerMeter
        val cosine = if (q == null) 1f else kotlin.math.abs((0..3).sumOf { (q[it]*orientation[it]).toDouble() }).toFloat().coerceIn(0f,1f)
        val angle = 2f*kotlin.math.acos(cosine)
        val discontinuity = before == null || elapsed > 100 || distance > .03f+4f*elapsed/1000f || angle > .15f+6f*elapsed/1000f
        previous = position.copyOf(); rotation = orientation.copyOf(); last = now
        if (discontinuity || stableSince == null) stableSince = now
        return !discontinuity && now-stableSince!! >= 300
    }
}
