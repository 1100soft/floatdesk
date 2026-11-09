package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
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
import androidx.xr.scenecore.SurfaceEntity

internal enum class MoveHandlePhase { START,UPDATE,END }

/** A small explicit hit target below the image; the monitor body remains non-movable. */
internal class MonitorMoveHandle : AutoCloseable {
    private var entity: SurfaceEntity?=null
    private var component: MovableComponent?=null
    private var listener: ((MoveHandlePhase,Pose)->Unit)?=null
    private var moving=false
    private var generation=0L

    @SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    fun update(session: Session,video: Entity?,monitorPose: Pose,mesh: MonitorMesh?,width: Float,height: Float,
               unitsPerMeter: Float,enabled: Boolean,onMove: (MoveHandlePhase,Pose)->Unit) {
        listener=onMove
        if(video==null) { close(); return }
        val handleWidth=.16f*unitsPerMeter; val handleHeight=.026f*unitsPerMeter
        if(entity==null) {
            val token=++generation
            val handle=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(handleWidth,handleHeight)),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)
            handle.setSurfacePixelDimensions(IntSize2d(256,48))
            handle.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            val canvas=handle.getSurface().lockCanvas(null)
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color=Color.rgb(40,57,70);canvas.drawRoundRect(1f,1f,255f,47f,16f,16f,paint)
            paint.color=Color.rgb(170,230,245);paint.textSize=29f;paint.textAlign=Paint.Align.CENTER
            canvas.drawText("Move",128f,34f,paint)
            handle.getSurface().unlockCanvasAndPost(canvas)
            val move=MovableComponent.createCustomMovable(session,scaleInZ=false,executor=null,entityMoveListener=object: EntityMoveListener {
                override fun onMoveStart(entity: Entity,initialInputRay: Ray,initialPose: Pose,initialScale: Float,initialParent: Entity) {
                    if(token==generation) { moving=true; listener?.invoke(MoveHandlePhase.START,initialPose) }
                }
                override fun onMoveUpdate(entity: Entity,currentInputRay: Ray,currentPose: Pose,currentScale: Float) {
                    if(token==generation && moving) { entity.setPose(currentPose); listener?.invoke(MoveHandlePhase.UPDATE,currentPose) }
                }
                override fun onMoveEnd(entity: Entity,finalInputRay: Ray,finalPose: Pose,finalScale: Float,updatedParent: Entity?) {
                    if(token==generation && moving) { entity.setPose(finalPose); listener?.invoke(MoveHandlePhase.END,finalPose); moving=false }
                }
            })
            move.size=FloatSize3d(handleWidth,handleHeight,.001f*unitsPerMeter)
            check(handle.addComponent(move))
            entity=handle;component=move
        }
        val frame=mesh?.let(::curvedResizeFrame) ?: ResizeFrameGeometry(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,1f),floatArrayOf(0f,1f,0f),width,height)
        val down=frame.height*.5f+.035f*unitsPerMeter+handleHeight*.5f
        val local=FloatArray(3){frame.center[it]-frame.up[it]*down}
        val scale=video.getScale()
        val rotation=Quaternion.fromLookTowards(Vector3(frame.normal[0],frame.normal[1],frame.normal[2]),Vector3(frame.up[0],frame.up[1],frame.up[2]))
        entity?.let {
            it.parent=video.parent;it.setScale(scale);it.setEnabled(enabled)
            if(!moving) it.setPose(monitorPose.compose(Pose(Vector3(local[0]*scale,local[1]*scale,local[2]*scale),rotation)))
        }
    }

    override fun close() {
        generation++;moving=false
        component?.let { entity?.removeComponent(it) }
        entity?.parent=null;entity=null;component=null;listener=null
    }
}
