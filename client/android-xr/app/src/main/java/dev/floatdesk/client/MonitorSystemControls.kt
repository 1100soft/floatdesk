package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.SystemClock
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.FloatSize3d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SpatialPointerComponent
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.scenecore.EntityMoveListener
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.ResizeEvent
import androidx.xr.scenecore.ResizableComponent
import androidx.xr.scenecore.SurfaceEntity

internal enum class SystemMovePhase { START,UPDATE,END }

/** Native move/resize rectangle behind the image. The system owns it throughout a gesture. */
internal class MonitorSystemControls : AutoCloseable {
    private var entity: SurfaceEntity?=null
    private var ownsEntity=true
    private var submitted:NativeNodeState?=null
    private var component: ResizableComponent?=null
    private var movable: MovableComponent?=null
    private var visible=true
    fun setVisible(value:Boolean) {
        if(visible==value)return
        visible=value
        if(!value){if(ownsEntity)entity?.let{NativeJournal.noteEnabled(NativeTag("system-controls",generation=generation.toInt()),"frame",false);it.setEnabled(false)};moving=false;resizeStartWidth=null}
        submitted?.invalidate()
    }
    private var generation=0L
    private var frameWidth=1f
    private var frameMesh:MonitorMesh?=null
    private var curvedFrame:ResizeFrameGeometry?=null
    private var shapeWidth=Float.NaN
    private var shapeHeight=Float.NaN
    private var canonicalWidth=Float.NaN
    private var canonicalHeight=Float.NaN
    private var resizeStartWidth: Float?=null
    private var lastResizeFactor=1f
    private var moving=false
    val isManipulating:Boolean get()=resizeStartWidth!=null || moving
    private var listener: ((ResizeEvent,Float)->Unit)?=null
    private var moveListener: ((SystemMovePhase,Pose)->Unit)?=null

    @SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    fun update(session: Session,video: Entity?,mesh: MonitorMesh?,width: Float,height: Float,viewer: FloatArray?,
               unitsPerMeter: Float,enabled: Boolean,onResize: (ResizeEvent,Float)->Unit,onMove: (SystemMovePhase,Pose)->Unit,allowMove:Boolean=true,minimumWidth:Float=.28f,minimumHeight:Float=.16f) {
        listener=onResize;moveListener=onMove
        if(video==null || (!enabled && (resizeStartWidth!=null || moving))) { close();return }
        // Do not feed the app's changing geometry back into an active native controller.
        if(resizeStartWidth!=null || moving) {submitted?.invalidate();return}
        val frame=if(!allowMove)ResizeFrameGeometry(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,1f),floatArrayOf(0f,1f,0f),width,height) else if(mesh!=null) {
            if(frameMesh!==mesh || curvedFrame==null) {curvedFrame=curvedResizeFrame(mesh);frameMesh=mesh}
            curvedFrame!!
        } else flatResizeFrame(width,height,viewer)
        frameWidth=frame.width
        val padding=if(allowMove).02f*unitsPerMeter else 0f
        val nextWidth=frame.width+2f*padding;val nextHeight=frame.height+2f*padding
        // Head jitter moves the viewer-dependent frame by fractions of a millimetre; ignore that.
        val geometryChanged=!shapeWidth.isFinite() || !shapeHeight.isFinite() ||
            kotlin.math.abs(shapeWidth-nextWidth)>maxOf(.00001f,kotlin.math.abs(nextWidth)*.002f) ||
            kotlin.math.abs(shapeHeight-nextHeight)>maxOf(.00001f,kotlin.math.abs(nextHeight)*.002f)
        val boundsChanged=geometryChanged || canonicalWidth!=width || canonicalHeight!=height
        if(entity==null) {
            val token=++generation
            ownsEntity=allowMove || video !is SurfaceEntity
            val root=if(!ownsEntity)video as SurfaceEntity else NativeJournal.native(NativeTag("system-controls",generation=generation.toInt()),"create","target=frame"){SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(frame.width+2f*padding,frame.height+2f*padding)),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)}
            if(ownsEntity){
            val pointer=SpatialPointerComponent.create(session)
            NativeJournal.native(NativeTag("system-controls",generation=generation.toInt()),"pointer-attach","target=frame"){check(root.addComponent(pointer))};pointer.spatialPointerIcon=SpatialPointerIcon.NONE
            NativeJournal.native(NativeTag("system-controls",generation=generation.toInt()),"texture-size","target=frame size=256x256"){root.setSurfacePixelDimensions(IntSize2d(256,256))}
            root.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            clear(root)
            }
            val resize=ResizableComponent.create(session,resizeEventListener=java.util.function.Consumer { event ->
                if(visible && token==generation && !moving) {
                    submitted?.invalidate()
                    val startEvent=event.resizeState==ResizeEvent.ResizeState.START
                    val end=event.resizeState==ResizeEvent.ResizeState.END
                    if(startEvent) {submitted?.invalidate();resizeStartWidth=event.newSize.width.takeIf {it.isFinite() && it>0f} ?: frameWidth;lastResizeFactor=1f}
                    val start=resizeStartWidth
                    if(start!=null && start>0f) {
                        val proposed=event.newSize.width/start
                        val valid=proposed.isFinite() && proposed>0f
                        if(valid)lastResizeFactor=proposed
                        try {
                            if(valid || startEvent || end) listener?.invoke(event,lastResizeFactor)
                        } finally {
                            if(end) {resizeStartWidth=null;shapeWidth=Float.NaN}
                        }
                    }
                }
            })
            resize.affordanceSize=FloatSize3d(nextWidth,nextHeight,.001f)
            resize.isFixedAspectRatioEnabled=true;resize.isAutoHideContentWhileResizingEnabled=false
            resize.isAlwaysShowOverlayEnabled=true
            NativeJournal.native(NativeTag("system-controls",generation=generation.toInt()),"collider-attach","target=${if(ownsEntity)"frame" else "video"} kind=resize"){check(root.addComponent(resize))}
            if(allowMove){
            val move=MovableComponent.createSystemMovable(session,scaleInZ=false)
            move.addMoveListener(object: EntityMoveListener {
                override fun onMoveStart(entity: Entity,initialInputRay: Ray,initialPose: Pose,initialScale: Float,initialParent: Entity) {
                    if(visible && token==generation && resizeStartWidth==null) { submitted?.invalidate();moving=true;moveListener?.invoke(SystemMovePhase.START,initialPose) }
                }
                override fun onMoveUpdate(entity: Entity,currentInputRay: Ray,currentPose: Pose,currentScale: Float) {
                    if(visible && token==generation)submitted?.invalidate()
                    if(visible && token==generation && moving) { moveListener?.invoke(SystemMovePhase.UPDATE,currentPose) }
                }
                override fun onMoveEnd(entity: Entity,finalInputRay: Ray,finalPose: Pose,finalScale: Float,updatedParent: Entity?) {
                    if(visible && token==generation)submitted?.invalidate()
                    if(visible && token==generation && moving) { moveListener?.invoke(SystemMovePhase.END,finalPose);moving=false }
                }
            })
            NativeJournal.native(NativeTag("system-controls",generation=generation.toInt()),"collider-attach","target=frame kind=move"){check(root.addComponent(move))};movable=move
            }
            entity=root;submitted=NativeNodeState(root,NativeTag("system-controls",generation=generation.toInt()),"frame");component=resize
        }
        val scale=video.getScale()
        val offset=Vector3(frame.center[0]*scale,frame.center[1]*scale,frame.center[2]*scale)
        val normal=Vector3(frame.normal[0],frame.normal[1],frame.normal[2]);val up=Vector3(frame.up[0],frame.up[1],frame.up[2])
        entity?.takeIf{ownsEntity}?.let {
            submitted!!.parent(video.parent);submitted!!.pose(video.getPose().compose(Pose(offset,Quaternion.fromLookTowards(normal,up))))
            submitted!!.scale(scale);submitted!!.enabled(enabled && visible)
            if(geometryChanged) {
                val shape=SurfaceEntity.Shape.Quad(FloatSize2d(nextWidth,nextHeight))
                journalShape(NativeTag("system-controls",generation=generation.toInt()),shape)
                it.shape=shape
                // A reshaped surface may come back with an undefined buffer drawn over the desktop.
                clear(it)
                reshapes++
                val now=SystemClock.uptimeMillis()
                if(now-reshapeLogAt>=5000){Log.i("Floatdesk","system frame reshapes=$reshapes size=${nextWidth}x$nextHeight");reshapeLogAt=now}
            }
        }
        if(boundsChanged) component?.let {
            val x=frame.width/width;val y=frame.height/height
            it.minimumEntitySize=FloatSize3d(minimumWidth*x,minimumHeight*y,.001f)
            // A two-hand grab can exceed the old 2.4 x 1.5 bounds. Never set a
            // native maximum below the current frame; grow the next gesture's range.
            it.maximumEntitySize=FloatSize3d(maxOf(10f*unitsPerMeter,frame.width*16f),maxOf(10f*unitsPerMeter,frame.height*16f),.001f)
            it.affordanceSize=FloatSize3d(nextWidth,nextHeight,.001f)
        }
        if(geometryChanged) movable?.size=FloatSize3d(nextWidth,nextHeight,.001f)
        if(geometryChanged) {shapeWidth=nextWidth;shapeHeight=nextHeight};canonicalWidth=width;canonicalHeight=height
    }

    private var reshapes=0
    private var reshapeLogAt=0L

    private fun clear(surface:SurfaceEntity) {
        runCatching {
            val canvas=surface.getSurface().lockCanvas(null)
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            surface.getSurface().unlockCanvasAndPost(canvas)
        }.onFailure{Log.w("Floatdesk","system frame clear failed",it)}
    }

    @SuppressLint("RestrictedApi")
    override fun close() {
        generation++;moving=false;submitted=null
        frameMesh=null;curvedFrame=null
        if(entity!=null)NativeJournal.record(NativeTag("system-controls",generation=generation.toInt()),"collider-detach","target=${if(ownsEntity)"frame" else "video"}")
        component?.let { entity?.takeUnless{it.isDisposed}?.removeComponent(it) };movable?.let { entity?.takeUnless{it.isDisposed}?.removeComponent(it) }
        if(ownsEntity)entity?.let{NativeJournal.native(NativeTag("system-controls",generation=generation.toInt()),"dispose","target=frame"){it.dispose()}}
        entity=null;component=null;movable=null;listener=null;moveListener=null;resizeStartWidth=null
        shapeWidth=Float.NaN;shapeHeight=Float.NaN;canonicalWidth=Float.NaN;canonicalHeight=Float.NaN
    }
}
