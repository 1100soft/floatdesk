package dev.floatdesk.client

import android.annotation.SuppressLint
import android.view.Surface
import android.util.Log
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.InputEvent
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

/** Deform the original Compose-owned video surface; preserve its proven input and handle ownership. */
internal class MonitorWarp : AutoCloseable {
    var mesh: MonitorMesh? = null
        private set
    private var input: SurfaceEntity? = null
    private var live: SurfaceEntity? = null
    private var flatWidth = 0f
    private var flatHeight = 0f
    private var appliedShape: SurfaceEntity.Shape? = null
    private var interaction: InteractableComponent? = null
    private val sequences = mutableSetOf<String>()

    @SuppressLint("RestrictedApi")
    fun attach(session: Session, inputSurface: Surface, placement: MonitorPlacement, density: PixelDensity? = null, onEvent: ((SpatialInputEvent) -> Unit)? = null): Surface {
        close()
        val target = session.scene.getEntitiesOfType(SurfaceEntity::class.java)
            .firstOrNull { it.getSurface() === inputSurface }
            ?: error("Could not find monitor input entity")
        target.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
        input = target
        live = target
        flatWidth = placement.physicalWidthM; flatHeight = placement.physicalHeightM
        if (density != null && onEvent != null) {
            val component = InteractableComponent.create(session) { event ->
                val info = event.hitInfoList.firstOrNull()
                val side = event.pointerType.toString()
                if (event.action == InputEvent.Action.DOWN) {
                    if (info?.inputEntity !== target) return@create
                    sequences.add(side)
                }
                if (side !in sequences && (side != "DEFAULT" || sequences.isEmpty())) return@create
                val hit = info?.hitPosition
                val inputScale = target.getScale(Space.ACTIVITY)
                fun onSurface(p: FloatArray): Boolean {
                    val flat = if (mesh == null) p else mesh?.unproject(p) ?: return false
                    val depthError = if (mesh == null) kotlin.math.abs(p[2]) else {
                        val sphere = mesh!!
                        kotlin.math.abs(kotlin.math.sqrt((0..2).sumOf { val d=p[it]-sphere.eye[it]; (d*d).toDouble() }).toFloat()-sphere.radius)
                    }
                    return depthError < .015f && kotlin.math.abs(flat[0]) <= flatWidth*.5f+.001f && kotlin.math.abs(flat[1]) <= flatHeight*.5f+.001f
                }
                // Hit position includes the activity root's scale; HitInfo.transform does not.
                val local = if (info != null && hit != null)
                    eventMatrixHit(floatArrayOf(hit.x,hit.y,hit.z),info.transform.data,
                        flatWidth,flatHeight,mesh,inputScale) else null
                if (event.action == InputEvent.Action.DOWN) {
                    Log.i("Floatdesk","native event contact=${local?.contentToString()} inputScale=$inputScale hit=$hit matrix=${info?.transform?.data?.contentToString()}")
                }
                val legacy = if (local == null && hit != null) session.scene.activitySpace.transformPoseTo(Pose(hit),target).translation
                    .takeIf { onSurface(floatArrayOf(it.x,it.y,it.z)) } else null
                fun pixels(v: Vector3) = Vector3(density.convertMetersToPixels(v.x),density.convertMetersToPixels(v.y),density.convertMetersToPixels(v.z))
                onEvent(SpatialInputEvent(source=event.source,pointerType=event.pointerType,action=event.action,timestamp=event.timestamp,
                    hitPosition=local?.let { pixels(Vector3(it[0],it[1],it[2])) } ?: legacy?.let { pixels(it) },origin=pixels(event.origin),direction=pixels(event.direction)))
                if (event.action == InputEvent.Action.UP || event.action == InputEvent.Action.CANCEL) {
                    if (side == "DEFAULT") sequences.clear() else sequences.remove(side)
                }
            }
            check(target.addComponent(component)) { "Could not attach native monitor input" }
            interaction = component
        }
        return inputSurface
    }

    @SuppressLint("RestrictedApi") // CustomMesh is available in the pinned SceneCore 1.0 runtime.
    fun update(session: Session, root: Entity, eye: FloatArray?, placement: MonitorPlacement,
               enabled: Boolean, held: Boolean) {
        val target = live ?: return
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
            appliedShape = shape
            mesh = next
        } else if (input?.shape !== appliedShape) {
            appliedShape?.let { input?.shape = it }
        }
    }

    override fun close() {
        // Compose owns the entity and surface. Never detach, disable or hide them here.
        interaction?.let { component -> runCatching { input?.removeComponent(component) } }
        interaction = null; sequences.clear()
        live = null
        input = null
        mesh = null
        appliedShape = null
    }
}
