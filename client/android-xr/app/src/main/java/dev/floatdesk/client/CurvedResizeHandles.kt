package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize3d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.ResizeEvent
import androidx.xr.scenecore.ResizableComponent

/** A resize frame behind the curved image, whose corners project to its visible corners. */
internal class CurvedResizeHandles : AutoCloseable {
    private var entity: Entity?=null
    private var component: ResizableComponent?=null
    private var generation=0L
    private var frameWidth=1f
    private var resizeStartWidth: Float?=null
    private var listener: ((ResizeEvent,Float)->Unit)?=null

    fun update(session: Session,video: Entity?,mesh: MonitorMesh?,enabled: Boolean,onResize: (ResizeEvent,Float)->Unit) {
        listener=onResize
        if(video==null || mesh==null) { close(); return }
        val frame=curvedResizeFrame(mesh)
        frameWidth=frame.width
        if(entity==null) {
            val token=++generation
            val root=Entity.create(session,name="Curved monitor resize handles",parent=video.parent)
            val resize=ResizableComponent.create(session,resizeEventListener=java.util.function.Consumer { event ->
                if(token==generation) {
                    if(event.resizeState==ResizeEvent.ResizeState.START) resizeStartWidth=event.newSize.width.takeIf { it.isFinite() && it>0f } ?: frameWidth
                    val start=resizeStartWidth
                    if(start!=null && start>0f) listener?.invoke(event,event.newSize.width/start)
                    if(event.resizeState==ResizeEvent.ResizeState.END) resizeStartWidth=null
                }
            })
            resize.affordanceSize=FloatSize3d(frame.width,frame.height,.001f)
            resize.isFixedAspectRatioEnabled=true
            resize.isAutoHideContentWhileResizingEnabled=false
            check(root.addComponent(resize))
            entity=root; component=resize
        }
        val scale=video.getScale()
        val offset=Vector3(frame.center[0]*scale,frame.center[1]*scale,frame.center[2]*scale)
        val normal=Vector3(frame.normal[0],frame.normal[1],frame.normal[2])
        val up=Vector3(frame.up[0],frame.up[1],frame.up[2])
        entity?.let {
            it.parent=video.parent
            it.setPose(video.getPose().compose(Pose(offset,Quaternion.fromLookTowards(normal,up))))
            it.setScale(scale); it.setEnabled(enabled)
        }
        component?.let {
            val x=frame.width/mesh.width; val y=frame.height/mesh.height
            it.minimumEntitySize=FloatSize3d(.28f*x,.16f*y,.001f)
            it.maximumEntitySize=FloatSize3d(2.4f*x,1.5f*y,.001f)
            it.affordanceSize=FloatSize3d(frame.width,frame.height,.001f)
        }
    }

    override fun close() {
        generation++
        component?.let { entity?.removeComponent(it) }
        entity?.parent=null
        entity=null; component=null; listener=null; resizeStartWidth=null
    }
}
