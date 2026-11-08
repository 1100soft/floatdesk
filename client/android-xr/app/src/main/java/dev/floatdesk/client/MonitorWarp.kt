package dev.floatdesk.client

import android.annotation.SuppressLint
import android.view.Surface
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

/** Changes the existing video geometry without replacing the decoder surface or its input target. */
internal class MonitorWarp {
    var mesh: MonitorMesh? = null
        private set
    private var live: SurfaceEntity? = null
    private var applied: SurfaceEntity.Shape? = null

    @SuppressLint("RestrictedApi") // CustomMesh is available in the pinned SceneCore 1.0 runtime.
    fun update(session: Session, surface: Surface, root: Entity, eye: FloatArray?, placement: MonitorPlacement,
               enabled: Boolean, held: Boolean) {
        val target = live?.takeUnless { it.isDisposed }?.takeIf { it.getSurface() === surface }
            ?: session.scene.getEntitiesOfType(SurfaceEntity::class.java).firstOrNull { it.getSurface() === surface }
            ?: return
        if (target !== live) { live = target; mesh = null; applied = null }
        // A captured shape must remain rigid while either contact is held.
        if (held) return
        val localEye = eye?.let {
            root.transformPoseTo(Pose(Vector3(it[0], it[1], it[2])), target).translation
        }
        val usable = enabled && localEye != null && localEye.z > 0.10f && localEye.length > 0.15f
        if (!usable) {
            if (mesh != null) {
                target.shape = SurfaceEntity.Shape.Quad(FloatSize2d(placement.physicalWidthM, placement.physicalHeightM))
                mesh = null; applied = null
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
                FloatBuffer.wrap(next.positions), FloatBuffer.wrap(next.texCoords), IntBuffer.wrap(next.indices)))
            target.shape = shape
            mesh = next; applied = shape
        } else if (target.shape !== applied) {
            // Compose restores its Quad when the layout size changes. Reapply our geometry afterward.
            applied?.let { target.shape = it }
        }
    }
}
