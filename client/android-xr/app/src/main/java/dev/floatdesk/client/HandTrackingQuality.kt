package dev.floatdesk.client

internal data class HandTrackingSample(val wrist: FloatArray,val palm: FloatArray,val thumb: FloatArray,
                                       val index: FloatArray,val middle: FloatArray,val rotation: FloatArray)
internal data class HandTrackingDecision(val rejected: Set<String>,val overlapping: Boolean,val reasons: Map<String,String>,val continuousOwner: String? = null)

/** Reject duplicate identities and discontinuities, without changing the palm gesture itself. */
internal class HandTrackingQuality {
    private data class History(val sample: HandTrackingSample,val at: Long,val velocity: FloatArray?,val since: Long)
    private val history=mutableMapOf<String,History>()
    private var overlapOwner: String?=null
    private val ghosts=mutableSetOf<String>()
    private val recoverUntil=mutableMapOf<String,Long>()

    fun update(now: Long,samples: Map<String,HandTrackingSample>): HandTrackingDecision {
        val reasons=mutableMapOf<String,String>()
        val prior=history.toMap()
        ghosts.removeAll { side->now-(prior[side]?.at ?: Long.MIN_VALUE/2)>200 }
        fun gap(a: FloatArray,b: FloatArray)=kotlin.math.sqrt((0..2).sumOf { val d=a[it]-b[it];(d*d).toDouble() }).toFloat()
        for((side,sample) in samples) {
            val finite=listOf(sample.wrist,sample.palm,sample.thumb,sample.index,sample.middle,sample.rotation).all { it.all { value->value.isFinite() } }
            if(!finite) {
                recoverUntil[side]=now+200;history.remove(side);reasons[side]="invalid joints";continue
            }
            // A reacquired hand starts a new motion history; the missing interval
            // is not evidence that the newly tracked pose is a jump.
            val before=history[side]?.takeIf { now-it.at<=150 }
            val elapsed=before?.let { now-it.at } ?: 0L
            val dt=elapsed.coerceIn(1,100)/1000f
            val velocity=before?.let { FloatArray(3){axis->(sample.wrist[axis]-it.sample.wrist[axis])/dt} }
            if(before!=null) {
                val cosine=kotlin.math.abs((0..3).sumOf { (sample.rotation[it]*before.sample.rotation[it]).toDouble() }).coerceIn(0.0,1.0)
                val angle=2*kotlin.math.acos(cosine)
                val predicted=before.velocity?.let { v->FloatArray(3){before.sample.wrist[it]+v[it]*dt} }
                val unstable=gap(sample.wrist,before.sample.wrist)>.025f+3f*dt ||
                    angle>.35f+8f*dt || (predicted!=null && gap(sample.wrist,predicted)>.015f+dt)
                if(unstable) { recoverUntil[side]=now+200;reasons[side]="pose discontinuity" }
            }
            history[side]=History(sample,now,velocity,before?.since ?: now)
        }
        fun sameHand(a: HandTrackingSample,b: HandTrackingSample)=gap(a.wrist,b.wrist)<.075f && gap(a.palm,b.palm)<.07f &&
            (gap(a.thumb,b.thumb)+gap(a.index,b.index)+gap(a.middle,b.middle))/3f<.06f
        val left=samples["LEFT"];val right=samples["RIGHT"]
        val overlap=left!=null && right!=null && sameHand(left,right)
        if(overlap) {
            if(overlapOwner==null) {
                // Preserve identity only if exactly one hand was continuously tracked
                // before the other appeared. Never select an owner from two established hands.
                val established=listOf("LEFT","RIGHT").filter { side ->
                    val old=prior[side]
                    old!=null && now-old.at<=100 && now-old.since>=200 && side !in ghosts
                }
                overlapOwner=established.singleOrNull()?.takeIf { side ->
                    val old=prior.getValue(side);val sample=samples.getValue(side)
                    gap(old.sample.wrist,sample.wrist)<.06f && now>=(recoverUntil[side] ?: 0)
                }
            }
            for(side in listOf("LEFT","RIGHT")) if(side!=overlapOwner) {
                recoverUntil[side]=now+200;reasons[side]="overlapping hand identities"
                if(overlapOwner!=null) ghosts.add(side)
            }
        } else overlapOwner=null
        for((side,sample) in samples) {
            val other=if(side=="LEFT") "RIGHT" else "LEFT"
            val recent=history[other]
            if(other !in samples && other !in ghosts && recent!=null && now-recent.at<=150 && sameHand(sample,recent.sample)) {
                recoverUntil[side]=now+200;recoverUntil[other]=now+200
                reasons[side]="hand identity switch"
            }
        }
        val rejected=recoverUntil.filterValues { now<it }.keys.toSet()
        for(side in rejected) if(side !in reasons) reasons[side]="tracking recovery"
        return HandTrackingDecision(rejected,overlap,reasons,overlapOwner?.takeIf { it !in rejected })
    }
}
