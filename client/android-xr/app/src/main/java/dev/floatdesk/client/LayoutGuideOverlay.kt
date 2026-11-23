package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi
import androidx.xr.scenecore.SurfaceEntity
import kotlin.math.abs
import kotlin.math.tan

/** One transparent foreground media layer, with no input components or per-stroke native nodes. */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class LayoutGuideOverlay(private val session:Session):AutoCloseable {
    private var node:NativeNodeState?=null
    private var surface:SurfaceEntity?=null
    private var lastGuide:LayoutOriginGuide?=null
    private var acceptsInput=false
    private var drawnAt=0L
    private var drawnWidth=0f
    private var drawnHeight=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()

    fun update(parent:Entity,guide:LayoutOriginGuide?,angles:FloatArray,units:Float,desktopRange:Float?) {
        if(guide==null){
            node?.enabled(false)
            lastGuide=null
            return
        }
        val depth=minOf(guide.nearRange*.72f,(desktopRange ?: guide.nearRange)*.70f).coerceAtLeast(.14f*units)
        val halfWidth=tan(angles[0].coerceIn(.35f,1.05f))
        val halfHeight=tan(angles[1].coerceIn(.35f,1.05f))
        val owner=surface ?: SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(1f,1f)),
            superSampling=SurfaceEntity.SuperSampling.NONE,mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT).also{
            it.setEnabled(false)
            acceptsInput=!SurfaceInput.disableCollider(it)
            it.setSurfacePixelDimensions(IntSize2d(960,720))
            surface=it
            node=NativeNodeState(it)
        }
        if(acceptsInput)return
        val viewer=guide.viewer
        val position=transformPoint(viewer,floatArrayOf(0f,0f,-depth))
        val q=viewer.rotation
        val now=android.os.SystemClock.uptimeMillis()
        val previous=lastGuide
        if(previous==null || now-drawnAt>=32 &&
            (halfWidth!=drawnWidth || halfHeight!=drawnHeight || guide.spherical!=previous.spherical ||
            abs(guide.farRange-previous.farRange)>.001f*units || distance(guide.near,previous.near)>.001f*units ||
            distance(viewer.position,previous.viewer.position)>.001f*units ||
            q.indices.any{abs(q[it]-previous.viewer.rotation[it])>.001f} ||
            guide.rotation.indices.any{abs(guide.rotation[it]-previous.rotation[it])>.001f})){
            if(draw(owner,guide,halfWidth,halfHeight)){
                // A newer head pose paired with the previous canvas moves the axes twice.
                // Keep the media node at the pose used to project its currently posted frame.
                node?.apply{
                    this.parent(parent)
                    pose(Pose(Vector3(position[0],position[1],position[2]),Quaternion(q[0],q[1],q[2],q[3])))
                    scale(Vector3(2f*halfWidth*depth,2f*halfHeight*depth,1f))
                }
                if(previous==null)android.util.Log.i("Floatdesk","Layout guide foreground frame posted (spherical=${guide.spherical})")
                lastGuide=guide
                drawnWidth=halfWidth;drawnHeight=halfHeight
                drawnAt=now
            }
        }
        node?.enabled(lastGuide!=null)
    }

    private fun draw(owner:SurfaceEntity,guide:LayoutOriginGuide,halfWidth:Float,halfHeight:Float):Boolean{
        val surface=owner.getSurface()
        if(!surface.isValid)return false
        val canvas=surface.lockCanvas(null)
        try{
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            val w=canvas.width.toFloat()
            val h=canvas.height.toFloat()
            val projection=LayoutGuideProjection(guide,halfWidth,halfHeight)
            val cyan=Color.rgb(88,241,255)
            val violet=Color.rgb(200,125,255)
            fun pixel(p:Pair<Float,Float>)=(p.first.coerceIn(-3f,4f)*w) to (p.second.coerceIn(-3f,4f)*h)
            for(layer in 1 downTo 0)for(axis in 0..1){
                path.rewind()
                var connected=false
                for(uv in projection.axis(layer,axis)){
                    if(uv==null){connected=false;continue}
                    val (x,y)=pixel(uv)
                    if(connected)path.lineTo(x,y)else path.moveTo(x,y)
                    connected=true
                }
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=if(layer==0)1.8f else 4f
                paint.color=if(layer==0)cyan else violet
                canvas.drawPath(path,paint)
            }
            val near=projection.point(guide.near)?.let(::pixel) ?: return true
            val far=projection.point(guide.far)?.let(::pixel) ?: return true
            val radius=.022f*h/(2f*halfHeight)
            if(kotlin.math.hypot(far.first-near.first,far.second-near.second)>1f){
                paint.shader=LinearGradient(near.first,near.second,far.first,far.second,cyan,violet,Shader.TileMode.CLAMP)
                paint.strokeWidth=2.5f
                canvas.drawLine(near.first,near.second,far.first,far.second,paint)
                paint.shader=null
            }
            // The cyan marker is a separate 3D sphere. Keep the far marker readable
            // when its projection coincides with the near marker.
            paint.style=Paint.Style.STROKE
            paint.color=violet
            paint.strokeWidth=2f
            canvas.drawCircle(far.first,far.second,radius*1.34f,paint)
        }finally{
            surface.unlockCanvasAndPost(canvas)
        }
        return true
    }

    override fun close(){
        surface?.takeUnless{it.isDisposed}?.let{it.setEnabled(false);it.parent=null}
        surface=null
        node=null
    }
}
