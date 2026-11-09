package dev.floatdesk.client

internal data class ResizeFrameGeometry(val center: FloatArray,val normal: FloatArray,val up: FloatArray,
                                       val width: Float,val height: Float)

/** Put the frame behind the sphere's tangent plane, with corners on the image's viewing rays. */
internal fun curvedResizeFrame(mesh: MonitorMesh): ResizeFrameGeometry {
    val offset=mesh.radius*.003f
    val center=FloatArray(3) { -mesh.normal[it]*offset }
    fun dot(a: FloatArray,b: FloatArray)=(0..2).sumOf { (a[it]*b[it]).toDouble() }.toFloat()
    var halfWidth=0f; var halfHeight=0f
    for(x in listOf(-.5f,.5f)) for(y in listOf(-.5f,.5f)) {
        val corner=mesh.project(floatArrayOf(x*mesh.width,y*mesh.height,0f))
        val t=(mesh.radius+offset)/(mesh.radius-dot(corner,mesh.normal))
        val onPlane=FloatArray(3) { mesh.eye[it]+(corner[it]-mesh.eye[it])*t-center[it] }
        halfWidth=maxOf(halfWidth,kotlin.math.abs(dot(onPlane,mesh.tangentX)))
        halfHeight=maxOf(halfHeight,kotlin.math.abs(dot(onPlane,mesh.tangentY)))
    }
    return ResizeFrameGeometry(center,mesh.normal,mesh.tangentY,halfWidth*2f,halfHeight*2f)
}

/** Parallel backing plane, perspective-corrected to the flat image's corner rays. */
internal fun flatResizeFrame(width: Float,height: Float,eye: FloatArray?): ResizeFrameGeometry {
    val offset=minOf(width,height)*.01f
    val factor=if(eye!=null && eye[2]>.1f) (eye[2]+offset)/eye[2] else 1f
    val center=floatArrayOf((eye?.get(0) ?: 0f)*(1f-factor),(eye?.get(1) ?: 0f)*(1f-factor),-offset)
    return ResizeFrameGeometry(center,floatArrayOf(0f,0f,1f),floatArrayOf(0f,1f,0f),width*factor,height*factor)
}
