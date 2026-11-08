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
) : AutoCloseable {
    private val originallyEnabled = live.isEnabled(false)
    private var hidden = false
    private var closed = false

    fun update(placement: MonitorPlacement) {
        if (closed) return
        val p = placement.pose.position
        val q = placement.pose.rotation
        entity.parent = live.parent
        entity.setPose(Pose(Vector3(p[0], p[1], p[2]), Quaternion(q[0], q[1], q[2], q[3])))
        entity.nodes.first().localScale = Vector3(placement.physicalWidthM, placement.physicalHeightM, placement.physicalWidthM)
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
            entity.parent = null
            model.close()
        }
    }

    companion object {
        @SuppressLint("RestrictedApi") // Byte-array loading exists in the pinned SceneCore 1.0 SDK.
        suspend fun create(session: Session, surface: Surface, width: Int, height: Int, mesh: MonitorMesh? = null): FrozenMonitor {
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
                    frozenFrameGlb(png, mesh)
                }
            } finally { bitmap.recycle() }
            val model = GltfModel.create(session, bytes, "floatdesk-frozen-${System.nanoTime()}")
            try {
                val entity = GltfModelEntity.create(session, model)
                return FrozenMonitor(live, model, entity)
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
internal fun frozenFrameGlb(png: ByteArray, mesh: MonitorMesh? = null): ByteArray {
    val positions = mesh?.positions?.mapIndexed { index, v -> v / (if (index % 3 == 1) mesh.height else mesh.width) }?.toFloatArray()
        ?: floatArrayOf(-.5f,-.5f,0f, .5f,-.5f,0f, .5f,.5f,0f, -.5f,.5f,0f)
    val uv = mesh?.texCoords ?: floatArrayOf(0f,1f, 1f,1f, 1f,0f, 0f,0f)
    val indices = mesh?.indices ?: intArrayOf(0,1,2,0,2,3)
    val count = positions.size / 3
    require(count <= 65535)
    val positionBytes = positions.size * 4
    val uvOffset = positionBytes * 2
    val indexOffset = uvOffset + uv.size * 4
    val pngOffset = indexOffset + indices.size * 2
    val rawLength = pngOffset + png.size
    val binary = ByteBuffer.allocate((rawLength + 3) / 4 * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in positions) binary.putFloat(v)
    repeat(count) { binary.putFloat(0f); binary.putFloat(0f); binary.putFloat(1f) }
    for (v in uv) binary.putFloat(v)
    for (v in indices) binary.putShort(v.toShort())
    binary.put(png)
    val minimum = (0..2).map { axis -> positions.indices.filter { it % 3 == axis }.minOf { positions[it] } }
    val maximum = (0..2).map { axis -> positions.indices.filter { it % 3 == axis }.maxOf { positions[it] } }
    val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],
      "scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
      "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
      "materials":[{"extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1},"alphaMode":"OPAQUE","doubleSided":true}],
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
