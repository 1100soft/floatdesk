package dev.floatdesk.client

/** Gaps are absent geometry, keeping video on the opaque compositor path used by foreground rays. */
internal data class VideoGeometry(val positions:FloatArray,val texCoords:FloatArray,val indices:IntArray)
internal fun videoCoverageIsFull(rects:FloatArray,spacing:Float):Boolean {
    if(spacing>0f || rects.isEmpty()) return false
    val ys=(listOf(0f,1f)+rects.indices.filter {it%4==1 || it%4==3}.map {rects[it]}).distinct().sorted()
    for(j in 0 until ys.lastIndex) {
        val y=(ys[j]+ys[j+1])*.5f;var end=0f
        val spans=(0 until rects.size/4).filter {y>=rects[it*4+1] && y<=rects[it*4+3]}.map {rects[it*4] to rects[it*4+2]}.sortedBy {it.first}
        for((a,b) in spans) {if(a>end+.000001f)return false;end=maxOf(end,b)}
        if(end<.999999f)return false
    }
    return true
}
internal fun clippedVideoGeometry(width:Float,height:Float,mesh:MonitorMesh?,rects:FloatArray,spacing:Float):VideoGeometry {
    val xyz=mesh?.positions ?: floatArrayOf(-width/2,height/2,0f,width/2,height/2,0f,-width/2,-height/2,0f,width/2,-height/2,0f)
    val uv=mesh?.texCoords ?: floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)
    val indices=mesh?.indices ?: intArrayOf(0,2,1,1,2,3)
    if(videoCoverageIsFull(rects,spacing)) return VideoGeometry(xyz,FloatArray(uv.size){if(it%2==1)1f-uv[it] else uv[it]},indices)
    data class Vertex(val x:Float,val y:Float,val z:Float,val u:Float,val v:Float)
    fun vertex(i:Int)=Vertex(xyz[i*3],xyz[i*3+1],xyz[i*3+2],uv[i*2],uv[i*2+1])
    val positions=ArrayList<Float>();val tex=ArrayList<Float>()
    for(r in 0 until rects.size/4) {
        val k=r*4;val cx=(rects[k]+rects[k+2])*.5f;val cy=(rects[k+1]+rects[k+3])*.5f;val scale=1f-spacing
        val bounds=floatArrayOf(cx+(rects[k]-cx)*scale,cy+(rects[k+1]-cy)*scale,cx+(rects[k+2]-cx)*scale,cy+(rects[k+3]-cy)*scale)
        for(t in indices.indices step 3) {
            var polygon=listOf(vertex(indices[t]),vertex(indices[t+1]),vertex(indices[t+2]))
            if(polygon.maxOf {it.u}<bounds[0] || polygon.minOf {it.u}>bounds[2] || polygon.maxOf {it.v}<bounds[1] || polygon.minOf {it.v}>bounds[3])continue
            for(edge in 0..3) {
                if(polygon.isEmpty())break
                fun distance(p:Vertex)=when(edge){0->p.u-bounds[0];1->p.v-bounds[1];2->bounds[2]-p.u;else->bounds[3]-p.v}
                val next=ArrayList<Vertex>();var a=polygon.last();var da=distance(a)
                for(b in polygon) {
                    val db=distance(b)
                    if((da>=0)!=(db>=0)) {
                        val f=da/(da-db)
                        next.add(Vertex(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f,a.z+(b.z-a.z)*f,a.u+(b.u-a.u)*f,a.v+(b.v-a.v)*f))
                    }
                    if(db>=0)next.add(b)
                    a=b;da=db
                }
                polygon=next
            }
            for(i in 1 until polygon.size-1)for(v in listOf(polygon[0],polygon[i],polygon[i+1])) {
                positions.add(v.x);positions.add(v.y);positions.add(v.z);tex.add(v.u);tex.add(1f-v.v)
            }
        }
    }
    return VideoGeometry(positions.toFloatArray(),tex.toFloatArray(),IntArray(positions.size/3){it})
}
