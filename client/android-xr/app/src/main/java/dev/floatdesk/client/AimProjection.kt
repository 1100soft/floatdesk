package dev.floatdesk.client

/** Project a physical beam and resolve every visible/invisible crossing to the monitor edge. */
internal fun projectedAimBeam(start: FloatArray,end: FloatArray,contact: FloatArray,
                              project: (FloatArray) -> FloatArray?): FloatArray {
    fun at(t: Float): FloatArray? = if(t==1f) contact else project(FloatArray(3) { start[it]+(end[it]-start[it])*t })
    fun boundary(a: Float,b: Float,visibleAtA: Boolean): FloatArray {
        var left=a; var right=b
        var visible=if(visibleAtA) at(a)!! else at(b)!!
        repeat(14) {
            val mid=(left+right)*.5f; val uv=at(mid)
            if(uv!=null) visible=uv
            if((uv!=null)==visibleAtA) left=mid else right=mid
        }
        return visible
    }
    val segments=mutableListOf<Float>()
    var before=at(0f)
    for(step in 1..8) {
        val a=(step-1)/8f; val b=step/8f
        val next=at(b)
        val from=before ?: if(next!=null) boundary(a,b,false) else null
        val to=next ?: if(before!=null) boundary(a,b,true) else null
        if(from!=null && to!=null) segments.addAll(listOf(from[0],from[1],to[0],to[1]))
        before=next
    }
    return segments.toFloatArray()
}
