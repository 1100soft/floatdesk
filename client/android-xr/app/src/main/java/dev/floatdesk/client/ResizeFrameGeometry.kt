package dev.floatdesk.client

internal data class ResizeFrameGeometry(val center: FloatArray,val normal: FloatArray,val up: FloatArray,
                                       val width: Float,val height: Float)

/** Put the frame behind the sphere's tangent plane, with corners on the image's viewing rays. */
internal fun curvedResizeFrame(mesh: MonitorMesh): ResizeFrameGeometry {
    val offset=mesh.radius*.003f
    val n=mesh.normal;val xAxis=mesh.tangentX;val yAxis=mesh.tangentY
    val base=FloatArray(3){mesh.eye[it]-n[it]*(mesh.radius+offset)}
    fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf {(a[it]*b[it]).toDouble()}.toFloat()
    var minX=Float.POSITIVE_INFINITY;var maxX=Float.NEGATIVE_INFINITY
    var minY=Float.POSITIVE_INFINITY;var maxY=Float.NEGATIVE_INFINITY
    // A wraparound desktop crosses the tangent-plane horizon. Perspective projection
    // there has an asymptote; use a finite orthographic backing frame for that case.
    val wraparound=(0 until mesh.positions.size/3).any {v ->
        val i=v*3
        dot(FloatArray(3){mesh.positions[i+it]-mesh.eye[it]},n)>-mesh.radius*.05f
    }
    // A curved outline can extend beyond its four corners. Include its complete perimeter.
    for(row in 0..mesh.rows) for(col in 0..mesh.columns) {
        if(!wraparound && row!=0 && row!=mesh.rows && col!=0 && col!=mesh.columns)continue
        if(mesh.focus==null && ((row!=0 && row!=mesh.rows) || (col!=0 && col!=mesh.columns)))continue
        val i=(row*(mesh.columns+1)+col)*3
        val ray=FloatArray(3){mesh.positions[i+it]-mesh.eye[it]}
        val t=if(wraparound) 1f else -(mesh.radius+offset)/dot(ray,n)
        val p=FloatArray(3){mesh.eye[it]+ray[it]*t-base[it]}
        val px=dot(p,xAxis);val py=dot(p,yAxis)
        minX=minOf(minX,px);maxX=maxOf(maxX,px);minY=minOf(minY,py);maxY=maxOf(maxY,py)
    }
    return ResizeFrameGeometry(FloatArray(3){base[it]+xAxis[it]*(minX+maxX)*.5f+yAxis[it]*(minY+maxY)*.5f},n,yAxis,maxX-minX,maxY-minY)
}

/** Parallel backing plane, perspective-corrected to the flat image's corner rays. */
internal fun flatResizeFrame(width: Float,height: Float,eye: FloatArray?): ResizeFrameGeometry {
    val offset=minOf(width,height)*.01f
    val factor=if(eye!=null && eye[2]>.1f) (eye[2]+offset)/eye[2] else 1f
    val center=floatArrayOf((eye?.get(0) ?: 0f)*(1f-factor),(eye?.get(1) ?: 0f)*(1f-factor),-offset)
    return ResizeFrameGeometry(center,floatArrayOf(0f,0f,1f),floatArrayOf(0f,1f,0f),width*factor,height*factor)
}

/** One aspect-preserving size for preview and release; no fixed workspace-size ceiling. */
internal fun resizeDimensions(width:Float,height:Float,factor:Float):Pair<Float,Float>? {
    if(!width.isFinite() || !height.isFinite() || !factor.isFinite() || width<=0f || height<=0f || factor<=0f)return null
    val scale=maxOf(factor,.28f/width,.16f/height)
    val w=width*scale;val h=height*scale
    return if(w.isFinite() && h.isFinite()) w to h else null
}

/**
 * Corner-handle length in the surface's local units. 1.4 cm at arm's length (0.40 m), growing in
 * proportion to the viewing distance so the handle keeps that angle. Never larger than half the surface.
 */
internal fun resizeHandleEdge(distanceM: Float, localPerMeter: Float, width: Float, height: Float): Float {
    val scale = localPerMeter.takeIf { it.isFinite() && it > 0f } ?: 1f
    val distance = distanceM.takeIf { it.isFinite() && it >= .05f } ?: .40f
    val edge = .014f * scale * distance / .40f
    val cap = minOf(width, height) * .5f
    return if (cap.isFinite() && cap > 0f) minOf(edge, cap) else edge
}

/** Aspect-preserving resize at a visible corner, measured in that pane's frozen axes. */
internal fun cornerResizeFactor(width:Float,height:Float,x:Int,y:Int,dx:Float,dy:Float,base:Float=1f):Float {
    val radius=(width*width+height*height)*.25f
    return if(radius>0f)base+(dx*x*width*.5f+dy*y*height*.5f)/radius else base
}
