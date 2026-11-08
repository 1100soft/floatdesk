package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.SpatialPointerComponent
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.scenecore.InteractableComponent

/** Transparent material, rather than a hidden entity: the native mesh collider stays active. */
internal class CurvedMonitorInput private constructor(
    val mesh: MonitorMesh,
    val entity: GltfModelEntity,
    private val model: GltfModel,
    private val component: InteractableComponent,
    private val pointer: SpatialPointerComponent,
) : AutoCloseable {
    fun setPointerDimmed(dimmed: Boolean) {
        val icon = if (dimmed) SpatialPointerIcon.NONE else SpatialPointerIcon.DEFAULT
        if (pointer.spatialPointerIcon != icon) pointer.spatialPointerIcon = icon
    }

    override fun close() {
        entity.removeComponent(pointer)
        entity.removeComponent(component)
        entity.parent = null
        model.close()
    }

    companion object {
        @android.annotation.SuppressLint("RestrictedApi")
        suspend fun create(session: Session, parent: Entity, mesh: MonitorMesh,
                           listener: (InputEvent) -> Unit): CurvedMonitorInput {
            val pixel = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")
            val model = GltfModel.create(session, frozenFrameGlb(pixel, mesh, interactionOnly = true), "floatdesk-curved-input-${System.nanoTime()}")
            var entity: GltfModelEntity? = null
            try {
                val created = GltfModelEntity.create(session, model, parent = parent)
                entity = created
                created.nodes.first().localScale = Vector3(mesh.width, mesh.height, mesh.width)
                val component = InteractableComponent.create(session, listener)
                check(created.addComponent(component)) { "Could not attach curved monitor interaction" }
                val pointer = SpatialPointerComponent.create(session)
                check(created.addComponent(pointer))
                return CurvedMonitorInput(mesh, created, model, component, pointer)
            } catch (failure: Throwable) {
                entity?.parent = null
                model.close()
                throw failure
            }
        }
    }
}
