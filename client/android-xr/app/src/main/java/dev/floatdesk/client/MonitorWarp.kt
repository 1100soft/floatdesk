package dev.floatdesk.client

import android.annotation.SuppressLint
import android.view.Surface
import androidx.xr.compose.subspace.layout.SpatialInputEvent
import androidx.xr.scenecore.PixelDensity
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import dev.floatdesk.client.workspace.MonitorPlacement
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Keep input enabled independently of video, with matching geometry for image and ray bounds. */
internal class MonitorWarp : AutoCloseable {
    var mesh: MonitorMesh? = null
        private set
    private var input: SurfaceEntity? = null
    private var live: SurfaceEntity? = null
    private var flatWidth = 0f
    private var flatHeight = 0f
    private var inputAlpha = 1f
    private var appliedShape: SurfaceEntity.Shape? = null

    @SuppressLint("RestrictedApi")
    fun attach(session: Session, inputSurface: Surface, placement: MonitorPlacement): Surface {
        close()
        val target = session.scene.getEntitiesOfType(SurfaceEntity::class.java)
            .firstOrNull { it.getSurface() === inputSurface }
            ?: error("Could not find monitor input entity")
        val video = SurfaceEntity.create(session,
            shape = SurfaceEntity.Shape.Quad(FloatSize2d(placement.physicalWidthM, placement.physicalHeightM)))
        video.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
        input = target
        live = video
        inputAlpha = target.getAlpha()
        // Alpha affects rendering; unlike setEnabled(false), it keeps input components active.
        target.setAlpha(0f)
        flatWidth = placement.physicalWidthM; flatHeight = placement.physicalHeightM
        syncPose()
        return video.getSurface()
    }

    private fun syncPose() {
        val target = input ?: return
        val video = live ?: return
        video.parent = target.parent
        video.setPose(target.getPose())
        video.setScale(target.getScale())
    }

    fun hit(session: Session, event: SpatialInputEvent, density: PixelDensity, placement: MonitorPlacement): FloatArray? {
        val target = input ?: return null
        fun meters(v: Vector3) = Vector3(density.convertPixelsToMeters(v.x),density.convertPixelsToMeters(v.y),density.convertPixelsToMeters(v.z))
        val origin = session.scene.activitySpace.transformPoseTo(Pose(meters(event.origin)),target).translation
        val direction = session.scene.activitySpace.transformVectorTo(meters(event.direction),target)
        return monitorRayHit(floatArrayOf(origin.x,origin.y,origin.z),floatArrayOf(direction.x,direction.y,direction.z),
            placement.physicalWidthM,placement.physicalHeightM,mesh)
    }

    @SuppressLint("RestrictedApi") // CustomMesh is available in the pinned SceneCore 1.0 runtime.
    fun update(session: Session, root: Entity, eye: FloatArray?, placement: MonitorPlacement,
               enabled: Boolean, held: Boolean) {
        val target = live ?: return
        syncPose()
        // A captured shape must remain rigid while either contact is held.
        if (held) return
        val localEye = eye?.let {
            root.transformPoseTo(Pose(Vector3(it[0], it[1], it[2])), target).translation
        }
        // Freeze the last valid curvature during tracking gaps; never switch shape on a stale pose.
        if (enabled && (localEye == null || localEye.z <= .10f || localEye.length <= .15f)) return
        val usable = enabled
        if (!usable) {
            if (mesh != null || flatWidth != placement.physicalWidthM || flatHeight != placement.physicalHeightM) {
                val shape = SurfaceEntity.Shape.Quad(FloatSize2d(placement.physicalWidthM, placement.physicalHeightM))
                target.shape = shape
                input?.shape = shape
                appliedShape = shape
                flatWidth = placement.physicalWidthM; flatHeight = placement.physicalHeightM
                mesh = null
            }
            return
        }
        val viewer = floatArrayOf(localEye!!.x, localEye.y, localEye.z)
        val previous = mesh
        val changed = previous == null || previous.width != placement.physicalWidthM || previous.height != placement.physicalHeightM ||
            viewer.indices.any { kotlin.math.abs(viewer[it] - previous.eye[it]) > 0.001f }
        if (changed) {
            val next = MonitorMesh(placement.physicalWidthM, placement.physicalHeightM, viewer)
            val shape = SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(
                FloatBuffer.wrap(next.positions), FloatBuffer.wrap(next.videoTexCoords), IntBuffer.wrap(next.indices)))
            target.shape = shape
            input?.shape = shape
            appliedShape = shape
            mesh = next
        } else if (input?.shape !== appliedShape) {
            appliedShape?.let { input?.shape = it }
        }
    }

    override fun close() {
        live?.parent = null
        live = null
        input?.let { target -> runCatching { target.setAlpha(inputAlpha) } }
        input = null
        mesh = null
        appliedShape = null
    }
}
