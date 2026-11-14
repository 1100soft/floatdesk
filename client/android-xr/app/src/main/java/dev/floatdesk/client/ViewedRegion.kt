package dev.floatdesk.client

/** Clip the actual rendered triangles to the head frustum, interpolating source UV at cuts. */
internal fun viewedRegion(width:Float,height:Float,mesh:MonitorMesh?,horizontal:Float,vertical:Float,toView:(FloatArray)->FloatArray):FocusRect? {
    val tx=kotlin.math.tan(horizontal.coerceIn(.1f,1.4f))*1.05f
    val ty=kotlin.math.tan(vertical.coerceIn(.1f,1.4f))*1.05f
    val positions=mesh?.positions ?: floatArrayOf(-width/2,height/2,0f,-width/2,-height/2,0f,width/2,height/2,0f,width/2,-height/2,0f)
    val uv=mesh?.texCoords ?: floatArrayOf(0f,0f,0f,1f,1f,0f,1f,1f)
    val indices=mesh?.indices ?: intArrayOf(0,1,2,2,1,3)
    val vertices=Array(positions.size/3){i->val p=toView(positions.copyOfRange(i*3,i*3+3));floatArrayOf(p[0],p[1],p[2],uv[i*2],uv[i*2+1])}
    fun distance(v:FloatArray,plane:Int)=when(plane){0->-v[2]-.01f;1->-v[2]*tx-v[0];2->-v[2]*tx+v[0];3->-v[2]*ty-v[1];else->-v[2]*ty+v[1]}
    var left=1f;var top=1f;var right=0f;var bottom=0f
    for(i in indices.indices step 3){
        var polygon=listOf(vertices[indices[i]],vertices[indices[i+1]],vertices[indices[i+2]])
        if(polygon.any{v->v.any{!it.isFinite()}})continue
        for(plane in 0..4){
            if(polygon.isEmpty())break
            if(polygon.all{distance(it,plane)>=0})continue
            if(polygon.all{distance(it,plane)<0}){polygon=emptyList();break}
            val clipped=ArrayList<FloatArray>(5)
            var before=polygon.last();var dBefore=distance(before,plane)
            for(after in polygon){
                val dAfter=distance(after,plane)
                if((dBefore>=0)!=(dAfter>=0)){
                    val t=(dBefore/(dBefore-dAfter)).coerceIn(0f,1f)
                    clipped.add(FloatArray(5){before[it]+t*(after[it]-before[it])})
                }
                if(dAfter>=0)clipped.add(after)
                before=after;dBefore=dAfter
            }
            polygon=clipped
        }
        for(v in polygon){left=minOf(left,v[3]);right=maxOf(right,v[3]);top=minOf(top,v[4]);bottom=maxOf(bottom,v[4])}
    }
    return if(right>left && bottom>top)FocusRect(left.coerceIn(0f,1f),top.coerceIn(0f,1f),right.coerceIn(0f,1f),bottom.coerceIn(0f,1f)) else null
}
