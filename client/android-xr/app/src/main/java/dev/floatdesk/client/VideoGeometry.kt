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
/** A convex silhouette extruded from the viewing eye. Points are in desktop local space. */
internal data class VideoCutout(val planes:List<List<Float>>,val textureSpace:Boolean=false)

internal fun textureCutout(left:Float,top:Float,right:Float,bottom:Float)=VideoCutout(
    listOf(listOf(1f,0f,0f,-left),listOf(0f,1f,0f,-top),
        listOf(-1f,0f,0f,right),listOf(0f,-1f,0f,bottom)),textureSpace=true)


internal fun foregroundCutout(eye:FloatArray,outline:List<FloatArray>):VideoCutout? {
    if(outline.size<3)return null
    fun cross(a:FloatArray,b:FloatArray)=floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    fun dot(a:FloatArray,b:FloatArray)=a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
    fun unit(v:FloatArray):FloatArray?{
        val length=kotlin.math.sqrt(dot(v,v))
        return if(length>.000001f && length.isFinite())FloatArray(3){v[it]/length}else null
    }
    val rays=outline.map{point->FloatArray(3){point[it]-eye[it]}}
    val forward=unit(FloatArray(3){axis->rays.sumOf{it[axis].toDouble()}.toFloat()}) ?: return null
    val side=unit(cross(if(kotlin.math.abs(forward[1])<.9f)floatArrayOf(0f,1f,0f) else floatArrayOf(1f,0f,0f),forward)) ?: return null
    val up=cross(forward,side)
    val points=rays.map{ray->
        val z=dot(forward,ray)
        if(z<=.00001f)return null
        dot(side,ray)/z to dot(up,ray)/z
    }.distinct().sortedWith(compareBy({it.first},{it.second}))
    fun chain(sequence:List<Pair<Float,Float>>):List<Pair<Float,Float>> {
        val result=mutableListOf<Pair<Float,Float>>()
        for(p in sequence){
            while(result.size>=2){
                val a=result[result.lastIndex-1];val b=result.last()
                if((b.first-a.first)*(p.second-a.second)>(b.second-a.second)*(p.first-a.first))break
                result.removeAt(result.lastIndex)
            }
            result+=p
        }
        return result.dropLast(1)
    }
    val hull=chain(points)+chain(points.asReversed())
    if(hull.size<3)return null
    fun ray(p:Pair<Float,Float>)=FloatArray(3){forward[it]+side[it]*p.first+up[it]*p.second}
    val planes=hull.indices.map{index->
        val n=unit(cross(ray(hull[index]),ray(hull[(index+1)%hull.size]))) ?: return null
        listOf(n[0],n[1],n[2],-dot(n,eye))
    }
    return VideoCutout(planes)
}

internal fun clippedVideoGeometry(width:Float,height:Float,mesh:MonitorMesh?,rects:FloatArray,spacing:Float,cutouts:List<VideoCutout> = emptyList()):VideoGeometry {
    val xyz=mesh?.positions ?: floatArrayOf(-width/2,height/2,0f,width/2,height/2,0f,-width/2,-height/2,0f,width/2,-height/2,0f)
    val uv=mesh?.texCoords ?: floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)
    val indices=mesh?.indices ?: intArrayOf(0,2,1,1,2,3)
    if(cutouts.isEmpty() && videoCoverageIsFull(rects,spacing)) return VideoGeometry(xyz,FloatArray(uv.size){if(it%2==1)1f-uv[it] else uv[it]},indices)
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
            fun distance(v:Vertex,plane:List<Float>,texture:Boolean)=if(texture)plane[0]*v.u+plane[1]*v.v+plane[3] else plane[0]*v.x+plane[1]*v.y+plane[2]*v.z+plane[3]
            fun split(vertices:List<Vertex>,plane:List<Float>,texture:Boolean):Pair<List<Vertex>,List<Vertex>> {
                val inside=mutableListOf<Vertex>();val outside=mutableListOf<Vertex>()
                var a=vertices.last();var da=distance(a,plane,texture)
                for(b in vertices){
                    val db=distance(b,plane,texture)
                    if((da>=0f)!=(db>=0f)){
                        val f=da/(da-db)
                        val edge=Vertex(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f,a.z+(b.z-a.z)*f,a.u+(b.u-a.u)*f,a.v+(b.v-a.v)*f)
                        inside+=edge;outside+=edge
                    }
                    if(db>=0f)inside+=b else outside+=b
                    a=b;da=db
                }
                return inside to outside
            }
            var pieces=listOf(polygon).filter{it.size>=3}
            for(mask in cutouts){
                pieces=pieces.flatMap{piece->
                    if(mask.planes.any{plane->piece.all{distance(it,plane,mask.textureSpace)<0f}})return@flatMap listOf(piece)
                    if(mask.planes.all{plane->piece.all{distance(it,plane,mask.textureSpace)>=0f}})return@flatMap emptyList()
                    var inside=piece
                    val outside=mutableListOf<List<Vertex>>()
                    // If a plane rejects the whole polygon, the original needs no splits.
                    for(plane in mask.planes){
                        if(inside.size<3)break
                        val (next,rest)=split(inside,plane,mask.textureSpace)
                        if(rest.size>=3)outside+=rest
                        inside=next
                    }
                    if(inside.size<3)listOf(piece) else outside
                }
            }
            for(piece in pieces)for(i in 1 until piece.size-1)for(v in listOf(piece[0],piece[i],piece[i+1])){
                positions.add(v.x);positions.add(v.y);positions.add(v.z);tex.add(v.u);tex.add(1f-v.v)
            }
        }
    }
    return VideoGeometry(positions.toFloatArray(),tex.toFloatArray(),IntArray(positions.size/3){it})
}
