package dev.floatdesk.client

internal data class ResizeFrameGeometry(val center: FloatArray,val normal: FloatArray,val up: FloatArray,
                                       val width: Float,val height: Float)

/** Put the frame behind the sphere's tangent plane, with corners on the image's viewing rays. */
internal fun curvedResizeFrame(mesh: MonitorMesh): ResizeFrameGeometry {
    val offset=mesh.radius*.003f
    val normal=mesh.frameNormal;val right=mesh.frameTangentX;val up=mesh.frameTangentY
    val tangent=FloatArray(3) { mesh.eye[it]-normal[it]*(mesh.radius+offset) }
    fun dot(a: FloatArray,b: FloatArray)=(0..2).sumOf { (a[it]*b[it]).toDouble() }.toFloat()
    var minX=Float.POSITIVE_INFINITY;var maxX=Float.NEGATIVE_INFINITY
    var minY=Float.POSITIVE_INFINITY;var maxY=Float.NEGATIVE_INFINITY
    for(x in listOf(-.5f,.5f)) for(y in listOf(-.5f,.5f)) {
        val corner=mesh.project(floatArrayOf(x*mesh.width,y*mesh.height,0f))
        val direction=FloatArray(3) { corner[it]-mesh.eye[it] }
        val t=-(mesh.radius+offset)/dot(direction,normal)
        val onPlane=FloatArray(3) { mesh.eye[it]+direction[it]*t-tangent[it] }
        val px=dot(onPlane,right);val py=dot(onPlane,up)
        minX=minOf(minX,px);maxX=maxOf(maxX,px);minY=minOf(minY,py);maxY=maxOf(maxY,py)
    }
    val center=FloatArray(3) { tangent[it]+right[it]*(minX+maxX)*.5f+up[it]*(minY+maxY)*.5f }
    return ResizeFrameGeometry(center,normal,up,maxX-minX,maxY-minY)
}

/** Parallel backing plane, perspective-corrected to the flat image's corner rays. */
internal fun flatResizeFrame(width: Float,height: Float,eye: FloatArray?): ResizeFrameGeometry {
    val offset=minOf(width,height)*.01f
    val factor=if(eye!=null && eye[2]>.1f) (eye[2]+offset)/eye[2] else 1f
    val center=floatArrayOf((eye?.get(0) ?: 0f)*(1f-factor),(eye?.get(1) ?: 0f)*(1f-factor),-offset)
    return ResizeFrameGeometry(center,floatArrayOf(0f,0f,1f),floatArrayOf(0f,1f,0f),width*factor,height*factor)
}
