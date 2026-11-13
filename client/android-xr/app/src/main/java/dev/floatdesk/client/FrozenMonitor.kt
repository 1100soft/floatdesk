package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import dev.floatdesk.client.workspace.MonitorPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** During a grab, display a native opaque mesh rather than a compositor-owned video layer. */
internal class FrozenMonitor private constructor(
    private val live: SurfaceEntity,
    private val model: GltfModel,
    private val entity: GltfModelEntity,
    private val dynamic:Boolean=false,
) : AutoCloseable {
    private val originallyEnabled = live.isEnabled(false)
    private var hidden = false
    private var closed = false

    private var displayedMesh:MonitorMesh?=null
    fun update(placement: MonitorPlacement,mesh:MonitorMesh?=null) {
        if (closed) return
        val p = placement.pose.position
        val q = placement.pose.rotation
        entity.parent = live.parent
        entity.setPose(Pose(Vector3(p[0], p[1], p[2]), Quaternion(q[0], q[1], q[2], q[3])))
        if(dynamic && mesh!=null) {
            entity.setScale(1f)
            if(displayedMesh!==mesh) {updateMeshPatches(entity,mesh,16,10);displayedMesh=mesh}
        } else if(!dynamic) entity.nodes.first().localScale = Vector3(placement.physicalWidthM, placement.physicalHeightM, placement.physicalWidthM)
        // Keep submitting decoded frames to this hidden surface so release reveals current video.
        // Only this compositor layer hides; the frozen native mesh remains visible.
        if (!hidden) { live.setEnabled(false); hidden = true }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (hidden) live.setEnabled(originallyEnabled)
        } finally {
            releaseOwnedGltf(model, entity)
        }
    }

    companion object {
        @SuppressLint("RestrictedApi") // Byte-array loading exists in the pinned SceneCore 1.0 SDK.
        suspend fun create(session: Session, surface: Surface, width: Int, height: Int, mesh: MonitorMesh? = null,dynamic:Boolean=false): FrozenMonitor {
            val live = session.scene.getEntitiesOfType(SurfaceEntity::class.java)
                .firstOrNull { it.getSurface() === surface }
                ?: error("Could not find the monitor's video entity")
            val bitmap = snapshot(surface, width, height)
            val bytes = try {
                withContext(Dispatchers.Default) {
                    val png = ByteArrayOutputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                    if(dynamic && mesh!=null) snapshotGridGlb(png) else frozenFrameGlb(png, mesh,transparent=true)
                }
            } finally { bitmap.recycle() }
            val model = GltfModel.create(session, bytes, "floatdesk-frozen-${System.nanoTime()}")
            try {
                val entity = GltfModelEntity.create(session, model)
                return FrozenMonitor(live, model, entity,dynamic && mesh!=null)
            } catch (failure: Throwable) {
                model.close()
                throw failure
            }
        }

        private suspend fun snapshot(surface: Surface, width: Int, height: Int): Bitmap {
            val ratio = minOf(1f, 1920f / maxOf(width, height).coerceAtLeast(1))
            val bitmap = Bitmap.createBitmap((width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            return suspendCancellableCoroutine { continuation ->
                try {
                    PixelCopy.request(surface, bitmap, { result ->
                        if (!continuation.isActive) bitmap.recycle()
                        else if (result == PixelCopy.SUCCESS) continuation.resume(bitmap)
                        else {
                            bitmap.recycle()
                            continuation.resumeWithException(IllegalStateException("Monitor snapshot failed: $result"))
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (failure: Exception) {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }
    }
}

/** Flat or spherical image mesh with an embedded PNG, normalized for monitor entity scaling. */
internal fun frozenFrameGlb(png: ByteArray, mesh: MonitorMesh? = null, interactionOnly: Boolean = false,transparent:Boolean=false,interactionTriangle:Boolean=false): ByteArray {
    val positions = mesh?.positions?.mapIndexed { index, v -> v / (if (index % 3 == 1) mesh.height else mesh.width) }?.toFloatArray()
        ?: if(interactionTriangle) floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f) else floatArrayOf(-.5f,-.5f,0f, .5f,-.5f,0f, .5f,.5f,0f, -.5f,.5f,0f)
    val uv = mesh?.texCoords ?: if(interactionTriangle) floatArrayOf(0f,0f,1f,0f,0f,1f) else floatArrayOf(0f,1f, 1f,1f, 1f,0f, 0f,0f)
    val indices = mesh?.indices ?: if(interactionTriangle) intArrayOf(0,1,2) else intArrayOf(0,1,2,0,2,3)
    val count = positions.size / 3
    require(count <= 65535)
    val positionBytes = positions.size * 4
    val uvOffset = positionBytes * 2
    val indexOffset = uvOffset + uv.size * 4
    val pngOffset = (indexOffset + indices.size * 2+3)/4*4
    val rawLength = pngOffset + png.size
    val binary = ByteBuffer.allocate((rawLength + 3) / 4 * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in positions) binary.putFloat(v)
    repeat(count) { binary.putFloat(0f); binary.putFloat(0f); binary.putFloat(1f) }
    for (v in uv) binary.putFloat(v)
    for (v in indices) binary.putShort(v.toShort())
    while(binary.position()<pngOffset) binary.put(0.toByte())
    binary.put(png)
    val minimum = (0..2).map { axis -> positions.indices.filter { it % 3 == axis }.minOf { positions[it] } }
    val maximum = (0..2).map { axis -> positions.indices.filter { it % 3 == axis }.maxOf { positions[it] } }
    val color = if (interactionOnly) ",\"baseColorFactor\":[1,1,1,0]" else ""
    val alpha = if (interactionOnly) "BLEND" else if(transparent) "MASK" else "OPAQUE"
    val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],
      "scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
      "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
      "materials":[{"extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorTexture":{"index":0}$color,"metallicFactor":0,"roughnessFactor":1},"alphaMode":"$alpha","doubleSided":true}],
      "textures":[{"source":0,"sampler":0}],"images":[{"bufferView":4,"mimeType":"image/png"}],
      "samplers":[{"magFilter":9729,"minFilter":9729,"wrapS":33071,"wrapT":33071}],
      "buffers":[{"byteLength":$rawLength}],
      "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$positionBytes,"target":34962},{"buffer":0,"byteOffset":$positionBytes,"byteLength":$positionBytes,"target":34962},{"buffer":0,"byteOffset":$uvOffset,"byteLength":${uv.size * 4},"target":34962},{"buffer":0,"byteOffset":$indexOffset,"byteLength":${indices.size * 2},"target":34963},{"buffer":0,"byteOffset":$pngOffset,"byteLength":${png.size}}],
      "accessors":[{"bufferView":0,"componentType":5126,"count":$count,"type":"VEC3","min":$minimum,"max":$maximum},{"bufferView":1,"componentType":5126,"count":$count,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":$count,"type":"VEC2"},{"bufferView":3,"componentType":5123,"count":${indices.size},"type":"SCALAR"}]}""".toByteArray()
    val jsonLength = (json.size + 3) / 4 * 4
    return ByteBuffer.allocate(28 + jsonLength + binary.capacity()).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x46546c67); putInt(2); putInt(capacity())
        putInt(jsonLength); putInt(0x4e4f534a); put(json)
        repeat(jsonLength - json.size) { put(32.toByte()) }
        putInt(binary.capacity()); putInt(0x004e4942); put(binary.array())
    }.array()
}

/** One triangle mesh/material shared by all independently transformed collider nodes. */
internal fun interactionGridGlb(png:ByteArray,columns:Int=12,rows:Int=8):ByteArray {
    val original=frozenFrameGlb(png,interactionOnly=true,interactionTriangle=true)
    val header=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
    val oldLength=header.getInt(12)
    val oldJson=String(original,20,oldLength,Charsets.UTF_8)
    val count=columns*rows*2
    val json=oldJson.replace("\"nodes\":[0]","\"nodes\":["+(0 until count).joinToString(","){(it*2).toString()}+"]")
        .replace("\"nodes\":[{\"mesh\":0}]","\"nodes\":["+(0 until count).joinToString(","){"{\"children\":[${it*2+1}],\"name\":\"patch-$it\"},{\"mesh\":0}"}+"]")
        .trimEnd().toByteArray()
    val length=(json.size+3)/4*4
    val binary=original.copyOfRange(20+oldLength,original.size)
    return ByteBuffer.allocate(20+length+binary.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x46546c67);putInt(2);putInt(capacity());putInt(length);putInt(0x4e4f534a);put(json)
        repeat(length-json.size){put(32.toByte())};put(binary)
    }.array()
}

/** One snapshot texture/material, with independently deformable textured triangles. */
internal fun snapshotGridGlb(png:ByteArray,columns:Int=16,rows:Int=10):ByteArray {
    val original=frozenFrameGlb(png,interactionTriangle=true,transparent=true)
    val oldLength=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
    var json=String(original,20,oldLength,Charsets.UTF_8).replace(Regex("\\s+"),"")
    val originalBinary=original.copyOfRange(28+oldLength,original.size)
    val count=columns*rows*2
    val binary=ByteBuffer.allocate(originalBinary.size+count*24).order(ByteOrder.LITTLE_ENDIAN)
    binary.put(originalBinary)
    for(row in 0 until rows)for(col in 0 until columns)for(part in 0..1) {
        val corners=if(part==0) listOf(col to row,col to row+1,col+1 to row) else listOf(col+1 to row,col to row+1,col+1 to row+1)
        for((x,y) in corners){binary.putFloat(x.toFloat()/columns);binary.putFloat(y.toFloat()/rows)}
    }
    json=json.replace("\"nodes\":[0]","\"nodes\":["+(0 until count).joinToString(","){(it*2).toString()}+"]")
        .replace("\"nodes\":[{\"mesh\":0}]","\"nodes\":["+(0 until count).joinToString(","){"{\"children\":[${it*2+1}]},{\"mesh\":$it}"}+"]")
    val start=json.indexOf("\"meshes\":")
    val end=json.indexOf(",\"materials\":",start)
    val meshes=(0 until count).joinToString(","){"{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":${4+it}},\"indices\":3,\"material\":0}]}"}
    json=json.substring(0,start)+"\"meshes\":["+meshes+"]"+json.substring(end)
    json=json.replace(Regex("\"buffers\":\\[\\{\"byteLength\":\\d+}]"),"\"buffers\":[{\"byteLength\":${binary.capacity()}}]")
    json=json.replace("],\"accessors\":[",",{\"buffer\":0,\"byteOffset\":${originalBinary.size},\"byteLength\":${count*24},\"target\":34962}],\"accessors\":[")
    val accessors=(0 until count).joinToString(","){"{\"bufferView\":5,\"byteOffset\":${it*24},\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"}"}
    json=json.dropLast(2)+","+accessors+"]}"
    val bytes=json.toByteArray();val size=(bytes.size+3)/4*4
    return ByteBuffer.allocate(28+size+binary.capacity()).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x46546c67);putInt(2);putInt(capacity());putInt(size);putInt(0x4e4f534a);put(bytes)
        repeat(size-bytes.size){put(32.toByte())};putInt(binary.capacity());putInt(0x004e4942);put(binary.array())
    }.array()
}
