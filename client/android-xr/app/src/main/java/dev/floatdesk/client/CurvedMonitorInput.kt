package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.SpatialPointerComponent
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.scenecore.InteractableComponent

/** Shared transparent triangles; update node transforms without replacing native models/materials. */
internal class CurvedMonitorInput private constructor(
    var mesh: MonitorMesh,
    val entity: GltfModelEntity,
    private val model: GltfModel,
    private val component: InteractableComponent,
    private val pointer: SpatialPointerComponent,
) : AutoCloseable {
    private var closed = false

    fun update(next:MonitorMesh) {
        if(closed || mesh===next)return
        updateMeshPatches(entity,next)
        mesh=next
    }

    override fun close() {
        if (closed) return
        closed = true
        entity.removeComponent(pointer)
        entity.removeComponent(component)
        releaseOwnedGltf(model, entity)
    }

    companion object {
        @android.annotation.SuppressLint("RestrictedApi")
        suspend fun create(session: Session, parent: Entity, mesh: MonitorMesh,
                           listener: (InputEvent) -> Unit): CurvedMonitorInput {
            val pixel = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")
            val model = GltfModel.create(session, interactionGridGlb(pixel), "floatdesk-curved-input-${System.nanoTime()}")
            var entity: GltfModelEntity? = null
            try {
                val created = GltfModelEntity.create(session, model, parent = parent)
                entity = created
                updateMeshPatches(created,mesh)
                val component = NativeMonitorInput.create(session, listener)
                check(created.addComponent(component)) { "Could not attach curved monitor interaction" }
                val pointer=SpatialPointerComponent.create(session)
                check(created.addComponent(pointer)) { "Could not set curved monitor pointer" }
                pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                return CurvedMonitorInput(mesh, created, model, component,pointer)
            } catch (failure: Throwable) {
                releaseOwnedGltf(model, entity)
                throw failure
            }
        }
    }
}

internal fun updateMeshPatches(entity:GltfModelEntity,mesh:MonitorMesh,columns:Int=12,rows:Int=8) {
    val patches=interactionPatches(mesh,columns,rows)
    check(entity.nodes.size==patches.size*2)
    patches.forEachIndexed {i,p->
        val q=p.rotation;val child=p.childRotation
        entity.nodes[i*2].localPose=Pose(Vector3(p.center[0],p.center[1],p.center[2]),Quaternion(q[0],q[1],q[2],q[3]))
        entity.nodes[i*2].localScale=Vector3(p.scale[0],p.scale[1],1f)
        entity.nodes[i*2+1].localPose=Pose(Vector3(0f,0f,0f),Quaternion(child[0],child[1],child[2],child[3]))
    }
}
