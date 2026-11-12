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

/** Remember the pre-loss viewer; rebase only after a stable, substantially different recovery. */
internal class TrackingRecovery {
    private var position:FloatArray?=null
    private var rotation:FloatArray?=null
    private var lostAt:Long?=null
    fun update(now:Long,reliable:Boolean,p:FloatArray?,q:FloatArray?,unitsPerMeter:Float):Pair<FloatArray,FloatArray>? {
        if(!reliable || p==null || q==null) {
            if(position!=null && lostAt==null) lostAt=now
            return null
        }
        val before=position;val orientation=rotation;val lost=lostAt
        position=p.copyOf();rotation=q.copyOf();lostAt=null
        if(before==null || orientation==null || lost==null || now-lost<250 || unitsPerMeter<=0f) return null
        val distance=kotlin.math.sqrt((0..2).sumOf {val d=(p[it]-before[it])/unitsPerMeter;(d*d).toDouble()})
        val cosine=kotlin.math.abs((0..3).sumOf {(q[it]*orientation[it]).toDouble()}).coerceIn(0.0,1.0)
        return if(distance>.25 || 2*kotlin.math.acos(cosine)>.5) before to orientation else null
    }
}
