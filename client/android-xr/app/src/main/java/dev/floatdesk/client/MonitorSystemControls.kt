package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.FloatSize3d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.EntityMoveListener
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.ResizeEvent
import androidx.xr.scenecore.ResizableComponent
import androidx.xr.scenecore.SurfaceEntity

internal enum class SystemMovePhase { START,UPDATE,END }

/** Native move/resize rectangle behind the image. The system owns it throughout a gesture. */
internal class MonitorSystemControls : AutoCloseable {
    private var entity: SurfaceEntity?=null
    private var component: ResizableComponent?=null
    private var movable: MovableComponent?=null
    private var generation=0L
    private var frameWidth=1f
    private var resizeStartWidth: Float?=null
    private var moving=false
    private var listener: ((ResizeEvent,Float)->Unit)?=null
    private var moveListener: ((SystemMovePhase,Pose)->Unit)?=null

    @SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    fun update(session: Session,video: Entity?,mesh: MonitorMesh?,width: Float,height: Float,viewer: FloatArray?,
               unitsPerMeter: Float,enabled: Boolean,onResize: (ResizeEvent,Float)->Unit,onMove: (SystemMovePhase,Pose)->Unit) {
        listener=onResize;moveListener=onMove
        if(video==null || (!enabled && (resizeStartWidth!=null || moving))) { close();return }
        // Do not feed the app's changing geometry back into an active native controller.
        if(resizeStartWidth!=null || moving) return
        val frame=mesh?.let(::curvedResizeFrame) ?: flatResizeFrame(width,height,viewer)
        frameWidth=frame.width
        val padding=.02f*unitsPerMeter
        if(entity==null) {
            val token=++generation
            val root=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(frame.width+2f*padding,frame.height+2f*padding)),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)
            root.setSurfacePixelDimensions(IntSize2d(256,256))
            root.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            val canvas=root.getSurface().lockCanvas(null)
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            root.getSurface().unlockCanvasAndPost(canvas)
            val resize=ResizableComponent.create(session,resizeEventListener=java.util.function.Consumer { event ->
                if(token==generation && !moving) {
                    if(event.resizeState==ResizeEvent.ResizeState.START) resizeStartWidth=event.newSize.width.takeIf { it.isFinite() && it>0f } ?: frameWidth
                    val start=resizeStartWidth
                    if(start!=null && start>0f) listener?.invoke(event,event.newSize.width/start)
                    if(event.resizeState==ResizeEvent.ResizeState.END) resizeStartWidth=null
                }
            })
            resize.affordanceSize=FloatSize3d(frame.width,frame.height,.001f)
            resize.isFixedAspectRatioEnabled=true;resize.isAutoHideContentWhileResizingEnabled=false
            resize.isAlwaysShowOverlayEnabled=true
            check(root.addComponent(resize))
            val move=MovableComponent.createSystemMovable(session,scaleInZ=false)
            move.addMoveListener(object: EntityMoveListener {
                override fun onMoveStart(entity: Entity,initialInputRay: Ray,initialPose: Pose,initialScale: Float,initialParent: Entity) {
                    if(token==generation && resizeStartWidth==null) { moving=true;moveListener?.invoke(SystemMovePhase.START,initialPose) }
                }
                override fun onMoveUpdate(entity: Entity,currentInputRay: Ray,currentPose: Pose,currentScale: Float) {
                    if(token==generation && moving) { moveListener?.invoke(SystemMovePhase.UPDATE,currentPose) }
                }
                override fun onMoveEnd(entity: Entity,finalInputRay: Ray,finalPose: Pose,finalScale: Float,updatedParent: Entity?) {
                    if(token==generation && moving) { moveListener?.invoke(SystemMovePhase.END,finalPose);moving=false }
                }
            })
            check(root.addComponent(move))
            entity=root;component=resize;movable=move
        }
        val scale=video.getScale()
        val offset=Vector3(frame.center[0]*scale,frame.center[1]*scale,frame.center[2]*scale)
        val normal=Vector3(frame.normal[0],frame.normal[1],frame.normal[2]);val up=Vector3(frame.up[0],frame.up[1],frame.up[2])
        entity?.let {
            it.parent=video.parent;it.setPose(video.getPose().compose(Pose(offset,Quaternion.fromLookTowards(normal,up))))
            it.setScale(scale);it.setEnabled(enabled)
            it.shape=SurfaceEntity.Shape.Quad(FloatSize2d(frame.width+2f*padding,frame.height+2f*padding))
        }
        component?.let {
            val x=frame.width/width;val y=frame.height/height
            it.minimumEntitySize=FloatSize3d(.28f*x,.16f*y,.001f)
            it.maximumEntitySize=FloatSize3d(2.4f*x,1.5f*y,.001f)
            it.affordanceSize=FloatSize3d(frame.width,frame.height,.001f)
        }
        movable?.size=FloatSize3d(frame.width+2f*padding,frame.height+2f*padding,.001f)
    }

    override fun close() {
        generation++;moving=false
        component?.let { entity?.removeComponent(it) };movable?.let { entity?.removeComponent(it) }
        entity?.parent=null
        entity=null;component=null;movable=null;listener=null;moveListener=null;resizeStartWidth=null
    }
}
