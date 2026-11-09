package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize3d
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.ResizeEvent
import androidx.xr.scenecore.ResizableComponent

/** Resize affordances on an empty entity: no invisible planar video body can intercept input. */
internal class CurvedResizeHandles : AutoCloseable {
    private var entity: Entity?=null
    private var component: ResizableComponent?=null
    private var generation=0L
    private var listener: ((ResizeEvent)->Unit)?=null

    fun update(session: Session,video: Entity?,width: Float,height: Float,enabled: Boolean,onResize: (ResizeEvent)->Unit) {
        listener=onResize
        if(video==null) { close(); return }
        if(entity==null) {
            val token=++generation
            val root=Entity.create(session,name="Curved monitor resize handles",pose=video.getPose(),parent=video.parent)
            val resize=ResizableComponent.create(session,minimumSize=FloatSize3d(.28f,.16f,.001f),
                maximumSize=FloatSize3d(2.4f,1.5f,.001f),resizeEventListener=java.util.function.Consumer { if(token==generation) listener?.invoke(it) })
            resize.isFixedAspectRatioEnabled=true
            resize.isAutoHideContentWhileResizingEnabled=false
            check(root.addComponent(resize))
            entity=root; component=resize
        }
        entity?.let {
            it.parent=video.parent; it.setPose(video.getPose()); it.setScale(video.getScale()); it.setEnabled(enabled)
        }
        component?.affordanceSize=FloatSize3d(width,height,.001f)
    }

    override fun close() {
        generation++
        component?.let { entity?.removeComponent(it) }
        entity?.parent=null
        entity=null; component=null; listener=null
    }
}
