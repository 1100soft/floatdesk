package dev.floatdesk.client

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.*
import java.util.concurrent.atomic.AtomicInteger

/** Four corner marks. Each is the corner of a rounded rectangle, with no fill. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class PaneCornerResize : AutoCloseable {
    private class Corner(val x:Int,val y:Int,val surface:SurfaceEntity,val input:InteractableComponent){
        @Volatile var hovered=false
    }
    private val corners=mutableListOf<Corner>()
    private val aimedHandles=AtomicInteger()
    private var width=0f;private var height=0f;private var startWidth=0f;private var startHeight=0f
    private var active:Corner?=null
    private data class HandResize(val side:String,val frame:dev.floatdesk.client.workspace.Pose,val local:FloatArray,val scale:Float,val gain:Float,val base:Float)
    private var handResize:HandResize?=null
    private var live=false
    private var drawn=false
    private var shownUntil=0L
    private var closed=false
    private var lastFactor=1f
    private var submittedWidth=Float.NaN;private var submittedHeight=Float.NaN;private var submittedEdge=Float.NaN
    private var host:SurfaceEntity?=null
    private var callback:(Boolean,Float)->Unit={_,_->}
    fun update(session:Session,video:SurfaceEntity,width:Float,height:Float,units:Float,distanceM:Float,tracking:GrabTracking,objectAimed:Boolean,onResize:(Boolean,Float)->Unit){
        if(video.isDisposed){cancel();return}
        this.width=width;this.height=height;callback=onResize
        if(host!==video){
            corners.forEach{release(it)};corners.clear();active=null;handResize=null;host=video
            submittedEdge=Float.NaN;drawn=false;live=false
        }
        if(corners.isEmpty())for(y in listOf(-1,1))for(x in listOf(-1,1)){
            val surface=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(.001f,.001f)),parent=video,superSampling=SurfaceEntity.SuperSampling.NONE)
            surface.setSurfacePixelDimensions(IntSize2d(HandlePixels,HandlePixels))
            surface.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            lateinit var corner:Corner
            val input=NativeMonitorInput.create(session) listener@{event->
                if(closed || video.isDisposed || surface.isDisposed)return@listener
                val hovering=event.action==InputEvent.Action.HOVER_ENTER || event.action==InputEvent.Action.HOVER_MOVE
                val leaving=event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL
                if(hovering && !corner.hovered){corner.hovered=true;aimedHandles.incrementAndGet()}
                if(leaving && corner.hovered){corner.hovered=false;aimedHandles.decrementAndGet()}
                if(event.action==InputEvent.Action.DOWN && active==null){active=corner;handResize=null;startWidth=this.width;startHeight=this.height;lastFactor=1f;callback(true,1f)}
                if(active===corner){
                    val end=event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL
                    if(event.action==InputEvent.Action.MOVE || end){
                        fun point(v:Vector3)=session.scene.activitySpace.transformPoseTo(Pose(v),video).translation
                        val o=point(event.origin);val p=point(Vector3(event.origin.x+event.direction.x,event.origin.y+event.direction.y,event.origin.z+event.direction.z))
                        val dz=p.z-o.z
                        val factor=if(kotlin.math.abs(dz)>.00001f){
                            val t=-o.z/dz;val px=o.x+(p.x-o.x)*t;val py=o.y+(p.y-o.y)*t
                            (px*x*startWidth*.5f+py*y*startHeight*.5f)/((startWidth*startWidth+startHeight*startHeight)*.25f)
                        }else 1f
                        if(handResize==null && factor.isFinite() && factor>.01f)lastFactor=factor
                        callback(!end,if(event.action==InputEvent.Action.CANCEL)1f else lastFactor)
                        if(end){active=null;handResize=null}
                    }
                }
            }
            check(surface.addComponent(input));corner=Corner(x,y,surface,input);corners.add(corner)
            val pointer=SpatialPointerComponent.create(session);surface.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
            paint(corner,false)
        }
        active?.let{corner->
            if(handResize==null){
                val side=tracking.pinched.filter{it in tracking.points}.singleOrNull()
                val hand=side?.let{tracking.points[it]}
                if(side!=null && hand!=null){
                    val p=video.getPose();val q=p.rotation
                    val frame=dev.floatdesk.client.workspace.Pose(floatArrayOf(p.translation.x,p.translation.y,p.translation.z),floatArrayOf(q.x,q.y,q.z,q.w))
                    val scale=video.getScale()
                    val target=transformPoint(frame,floatArrayOf(corner.x*startWidth*.5f*scale,corner.y*startHeight*.5f*scale,0f))
                    val viewer=tracking.points["VIEWER"] ?: tracking.points["HEAD"] ?: floatArrayOf(0f,0f,0f)
                    fun range(point:FloatArray)=kotlin.math.sqrt((0..2).sumOf{val d=point[it]-viewer[it];(d*d).toDouble()}).toFloat()
                    handResize=HandResize(side,frame,viewerInMonitor(hand,frame.position,frame.rotation),scale,range(target)/range(hand).coerceAtLeast(.001f),lastFactor)
                }
            }
            handResize?.takeIf{it.side in tracking.pinched}?.let{start->tracking.points[start.side]?.let{point->
                val local=viewerInMonitor(point,start.frame.position,start.frame.rotation)
                val factor=cornerResizeFactor(startWidth,startHeight,corner.x,corner.y,(local[0]-start.local[0])/start.scale*start.gain,(local[1]-start.local[1])/start.scale*start.gain,start.base)
                if(factor.isFinite() && factor>.01f){lastFactor=factor;callback(true,factor)}
            }}
        }
        val edge=resizeHandleEdge(distanceM,units)
        val front=.01f*(units.takeIf{it.isFinite() && it>0f} ?: 1f)
        if(!live){corners.forEach{if(!it.surface.isDisposed)it.surface.setEnabled(true)};live=true}
        val now=SystemClock.uptimeMillis()
        val want=objectAimed || aimedHandles.get()>0 || active!=null
        if(want)shownUntil=now+160
        val show=want || now<shownUntil
        if(show!=drawn){corners.forEach{paint(it,show)};drawn=show}
        for(corner in corners){
            if(edge!=submittedEdge && !corner.surface.isDisposed)corner.surface.shape=SurfaceEntity.Shape.Quad(FloatSize2d(edge,edge))
            if(width!=submittedWidth || height!=submittedHeight || edge!=submittedEdge){
                // Local +Z is this surface's facing normal. One centimetre keeps the mark in front of the image.
                val pose=Pose(Vector3(corner.x*(width-edge)*.5f,corner.y*(height-edge)*.5f,front))
                corner.surface.setPose(pose)
            }
        }
        submittedWidth=width;submittedHeight=height;submittedEdge=edge
    }
    private fun paint(corner:Corner,show:Boolean){
        if(corner.surface.isDisposed)return
        runCatching{
            val canvas=corner.surface.getSurface().lockCanvas(null)
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            if(show)drawRoundedCorner(canvas,corner.x,corner.y)
            corner.surface.getSurface().unlockCanvasAndPost(canvas)
        }
    }
    fun cancel(){
        active=null;handResize=null;lastFactor=1f;shownUntil=0L
        corners.forEach{if(it.hovered){it.hovered=false;aimedHandles.decrementAndGet()}}
        if(drawn){corners.forEach{paint(it,false)};drawn=false}
        if(live){corners.forEach{if(!it.surface.isDisposed)it.surface.setEnabled(false)};live=false}
    }
    private fun release(corner:Corner){
        if(corner.hovered){corner.hovered=false;aimedHandles.decrementAndGet()}
        if(!corner.surface.isDisposed){corner.surface.removeComponent(corner.input);corner.surface.dispose()}
    }
    override fun close(){
        closed=true;active=null;host=null
        corners.forEach{release(it)};corners.clear()
    }

    companion object {
        private const val HandlePixels=128
        private fun drawRoundedCorner(canvas:android.graphics.Canvas,sx:Int,sy:Int){
            val n=canvas.width.toFloat()
            val stroke=n*.09f
            val radius=n*.26f
            val arm=n*.62f
            val px=if(sx>0)n-stroke*.5f else stroke*.5f
            val py=if(sy>0)stroke*.5f else n-stroke*.5f
            val ix=-sx.toFloat();val iy=sy.toFloat()
            val acx=px+ix*radius;val acy=py+iy*radius
            val path=Path()
            path.moveTo(px+ix*(radius+arm),py)
            path.lineTo(px+ix*radius,py)
            val start=Math.toDegrees(kotlin.math.atan2((-sy).toDouble(),0.0)).toFloat()
            val end=Math.toDegrees(kotlin.math.atan2(0.0,sx.toDouble())).toFloat()
            var sweep=end-start
            if(sweep>180f)sweep-=360f
            if(sweep<=-180f)sweep+=360f
            path.arcTo(RectF(acx-radius,acy-radius,acx+radius,acy+radius),start,sweep,false)
            path.lineTo(px,py+iy*(radius+arm))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=Color.CYAN;style=Paint.Style.STROKE;strokeWidth=stroke
                strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
            }
            canvas.drawPath(path,paint)
        }
    }
}
