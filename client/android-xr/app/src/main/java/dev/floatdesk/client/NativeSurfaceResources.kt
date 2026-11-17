package dev.floatdesk.client

import androidx.xr.scenecore.SurfaceEntity

/** Native upload storage is shared by desktop and magnifier paths, validated before JNI. */
@android.annotation.SuppressLint("RestrictedApi")
internal fun nativeVideoMesh(geometry:VideoGeometry):SurfaceEntity.Shape{
    val vertices=geometry.positions.size/3
    require(vertices>=3 && geometry.positions.size%3==0 && geometry.texCoords.size==vertices*2)
    require(geometry.indices.size>=3 && geometry.indices.size%3==0 && geometry.indices.all{it in 0 until vertices})
    require(geometry.positions.all{it.isFinite()} && geometry.texCoords.all{it.isFinite()})
    fun floats(values:FloatArray)=java.nio.ByteBuffer.allocateDirect(values.size*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply{put(values);rewind()}
    // Submit independent triangles. Avoid the SDK's indexed native upload/lifetime
    // path as a mitigation for the observed native mesh-upload faults.
    val positions=FloatArray(geometry.indices.size*3){i->geometry.positions[geometry.indices[i/3]*3+i%3]}
    val texCoords=FloatArray(geometry.indices.size*2){i->geometry.texCoords[geometry.indices[i/2]*2+i%2]}
    return SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(floats(positions),floats(texCoords),null))
}

@android.annotation.SuppressLint("RestrictedApi")
internal class RetainedSurfaceShapes(maxEntries:Int=512,maxBytes:Long=64L*1024*1024){
    private val history=BoundedHistory<SurfaceEntity.Shape>(maxEntries,maxBytes){shape->
        fun bytes(mesh:SurfaceEntity.Shape.TriangleMesh)=4L*(mesh.positions.capacity().toLong()+mesh.texCoords.capacity()+(mesh.indices?.capacity() ?: 0))
        if(shape is SurfaceEntity.Shape.CustomMesh)bytes(shape.leftEye)+(shape.rightEye?.takeUnless{it===shape.leftEye}?.let{bytes(it)} ?: 0) else 0L
    }
    fun retain(shape:SurfaceEntity.Shape)=history.retain(shape)
}
