package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    var mesh: MonitorMesh?,
    private var width:Float,private var height:Float,
    val entity: GltfModelEntity,
    private val component: InteractableComponent,
    private val pointer: SpatialPointerComponent,
    private val columns:Int,private val rows:Int,
    private val tag:NativeTag,
) : AutoCloseable {
    private var closed = false

    fun update(next:MonitorMesh) = updateGeometry(next,next.width,next.height)

    fun updateGeometry(next:MonitorMesh?,width:Float,height:Float) {
        if(closed || mesh===next && this.width==width && this.height==height)return
        updateMeshPatches(entity,next,columns,rows,width,height)
        mesh=next;this.width=width;this.height=height
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun close() {
        if (closed) return
        closed = true
        if(!entity.isDisposed)NativeJournal.native(tag,"collider-detach","target=collider"){entity.removeComponent(pointer);entity.removeComponent(component)}
        if(!entity.isDisposed)NativeJournal.native(tag,"dispose","target=collider"){entity.dispose()}
    }

    /** Detach an immutable pane collider without retiring its borrowed native material. */
    @android.annotation.SuppressLint("RestrictedApi")
    fun detach(){
        if(closed)return
        closed=true
        if(!entity.isDisposed){
            NativeJournal.record(tag,"collider-detach","target=collider retained=true")
            entity.removeComponent(pointer)
            entity.removeComponent(component)
            NativeJournal.noteEnabled(tag,"collider",false)
            entity.setEnabled(false)
            entity.parent=null
        }
    }

    companion object {
        // One immutable input asset per grid/session. Menu slots instance it; they
        // never repeatedly create/free its shared texture and material objects.
        private val assets=java.util.WeakHashMap<Session,MutableMap<Pair<Int,Int>,kotlinx.coroutines.CompletableDeferred<GltfModel>>>()
        @android.annotation.SuppressLint("RestrictedApi")
        private suspend fun inputModel(session:Session,columns:Int,rows:Int):GltfModel {
            var load=false
            val key=columns to rows
            val result=synchronized(assets){
                val entries=assets.getOrPut(session){
                    session.lifecycleOwner.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver{_,event->
                        if(event==androidx.lifecycle.Lifecycle.Event.ON_DESTROY)android.os.Handler(android.os.Looper.getMainLooper()).post{synchronized(assets){assets.remove(session)}}
                    })
                    mutableMapOf()
                }
                entries.getOrPut(key){load=true;kotlinx.coroutines.CompletableDeferred()}
            }
            if(load)session.lifecycleOwner.lifecycleScope.launch { try{
                val pixel=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")
                result.complete(NativeJournal.native(NativeTag("input-model"),"gltf-load","grid=${columns}x$rows"){GltfModel.create(session,interactionGridGlb(pixel,columns,rows),"floatdesk-input-$columns-$rows-${System.nanoTime()}")})
            }catch(error:Throwable){
                result.completeExceptionally(error)
                synchronized(assets){assets[session]?.remove(key)}
            }}
            return result.await()
        }

        @android.annotation.SuppressLint("RestrictedApi")
        suspend fun create(session: Session, parent: Entity, mesh: MonitorMesh?,
                           columns:Int=12,rows:Int=8,width:Float=requireNotNull(mesh).width,height:Float=requireNotNull(mesh).height,tag:NativeTag=NativeTag("input"),listener: (InputEvent) -> Unit): CurvedMonitorInput {
            if(parent.isDisposed)throw java.util.concurrent.CancellationException("Input owner disposed")
            val model=inputModel(session,columns,rows)
            if(parent.isDisposed)throw java.util.concurrent.CancellationException("Input owner disposed during load")
            var entity: GltfModelEntity? = null
            try {
                val created = NativeJournal.native(tag,"create","target=collider grid=${columns}x$rows"){GltfModelEntity.create(session, model, parent = parent)}
                entity = created
                updateMeshPatches(created,mesh,columns,rows,width,height)
                val component = NativeMonitorInput.create(session, listener)
                NativeJournal.native(tag,"collider-attach","target=collider"){check(created.addComponent(component)) { "Could not attach curved monitor interaction" }}
                val pointer=SpatialPointerComponent.create(session)
                check(created.addComponent(pointer)) { "Could not set curved monitor pointer" }
                pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                return CurvedMonitorInput(mesh,width,height, created, component,pointer,columns,rows,tag)
            } catch (failure: Throwable) {
                entity?.takeUnless{it.isDisposed}?.let{NativeJournal.native(tag,"dispose","target=collider failed=true"){it.dispose()}}
                throw failure
            }
        }
    }
}

internal fun updateMeshPatches(entity:GltfModelEntity,mesh:MonitorMesh?,columns:Int=12,rows:Int=8,width:Float=requireNotNull(mesh).width,height:Float=requireNotNull(mesh).height) {
    val patches=interactionPatches(width,height,mesh,columns,rows)
    check(entity.nodes.size==patches.size*2)
    patches.forEachIndexed {i,p->
        val q=p.rotation;val child=p.childRotation
        entity.nodes[i*2].localPose=Pose(Vector3(p.center[0],p.center[1],p.center[2]),Quaternion(q[0],q[1],q[2],q[3]))
        entity.nodes[i*2].localScale=Vector3(p.scale[0],p.scale[1],1f)
        entity.nodes[i*2+1].localPose=Pose(Vector3(0f,0f,0f),Quaternion(child[0],child[1],child[2],child[3]))
    }
}
