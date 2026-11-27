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

/** Four corner squares. The cyan mark is only the outer quadrant of each square. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class PaneCornerResize : AutoCloseable {
    private class Corner(val x:Int,val y:Int,val surface:SurfaceEntity,val input:InteractableComponent){
        @Volatile var hovered=false
    }
    private val corners=mutableListOf<Corner>()
    var journalKey="corners"
    private var hostGeneration=0
    private fun tag()=NativeTag(journalKey,generation=hostGeneration)
    private val aimedHandles=AtomicInteger()
    private var width=0f;private var height=0f;private var startWidth=0f;private var startHeight=0f
    private var active:Corner?=null
    private data class HandResize(val side:String,val frame:dev.floatdesk.client.workspace.Pose,val local:FloatArray,val scale:Float,val gain:Float,val base:Float)
    private var handResize:HandResize?=null
    private var live=false
    private var drawnShow=false
    private var drawnThick=false
    private var drawnHeld=false
    private var shownUntil=0L
    private var closed=false
    private var lastFactor=1f
    private var grabProjection=Float.NaN
    private var grabPlane=0f
    private var grabScale=1f
    private var planeZ=0f
    private var submittedWidth=Float.NaN;private var submittedHeight=Float.NaN;private var submittedEdge=Float.NaN;private var submittedFront=Float.NaN
    private var frontSign=1f
    private var objectAimed=false
    private var host:SurfaceEntity?=null
    private var callback:(Boolean,Float)->Unit={_,_->}
    fun update(session:Session,video:SurfaceEntity,width:Float,height:Float,units:Float,distanceM:Float,tracking:GrabTracking,objectAimed:Boolean,onResize:(Boolean,Float)->Unit){
        if(video.isDisposed){cancel();return}
        this.width=width;this.height=height;this.objectAimed=objectAimed;callback=onResize
        if(host!==video){
            corners.forEach{release(it)};corners.clear();active=null;handResize=null;host=video;hostGeneration++
            submittedEdge=Float.NaN;submittedFront=Float.NaN;drawnShow=false;drawnThick=false;drawnHeld=false;live=false
        }
        val wantCorners=objectAimed || active!=null
        if(corners.isEmpty() && !wantCorners)return
        if(corners.size<4 && wantCorners && tracking.tryCornerAllocation(SystemClock.uptimeMillis())){
            val x=if(corners.size%2==0)-1 else 1
            val y=if(corners.size<2)-1 else 1
            val surface=NativeJournal.native(tag(),"create","target=corner:$x,$y"){SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(.001f,.001f)),parent=video,superSampling=SurfaceEntity.SuperSampling.NONE)}
            NativeJournal.native(tag(),"texture-size","target=corner:$x,$y size=${HandlePixels}x$HandlePixels"){surface.setSurfacePixelDimensions(IntSize2d(HandlePixels,HandlePixels))}
            surface.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            lateinit var corner:Corner
            val input=NativeMonitorInput.create(session) listener@{event->
                if(closed || video.isDisposed || surface.isDisposed)return@listener
                val hovering=event.action==InputEvent.Action.HOVER_ENTER || event.action==InputEvent.Action.HOVER_MOVE
                val leaving=event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL
                val wasHeld=active!=null
                if(hovering && !corner.hovered){corner.hovered=true;aimedHandles.incrementAndGet()}
                if(leaving && corner.hovered){corner.hovered=false;aimedHandles.decrementAndGet()}
                if(event.action==InputEvent.Action.DOWN && active==null){
                    active=corner;handResize=null;startWidth=this.width;startHeight=this.height;lastFactor=1f
                    grabScale=video.getScale()
                    grabPlane=planeZ;grabProjection=cornerProjection(session,video,event,grabPlane,x,y) ?: Float.NaN
                    callback(true,1f)
                }
                if(active===corner){
                    val end=event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL
                    if(event.action==InputEvent.Action.MOVE || end){
                        val radius=(startWidth*startWidth+startHeight*startHeight)*.25f
                        val hit=cornerProjection(session,video,event,grabPlane,x,y)
                        if(hit!=null && radius>0f && handResize==null){
                            if(grabProjection.isNaN())grabProjection=hit
                            else {
                                val factor=anchoredCornerFactor(hit,grabProjection,radius)
                                if(factor.isFinite() && factor>.01f)lastFactor=factor
                            }
                        }
                        callback(!end,if(event.action==InputEvent.Action.CANCEL)1f else lastFactor)
                        if(end){active=null;handResize=null;grabProjection=Float.NaN}
                    }
                }
                if(corner.hovered || active!=null || wasHeld)present()
            }
            NativeJournal.native(tag(),"collider-attach","target=corner:$x,$y"){check(surface.addComponent(input))};corner=Corner(x,y,surface,input);corners.add(corner)
            val pointer=SpatialPointerComponent.create(session);surface.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
            paint(corner,drawnShow,drawnThick,drawnHeld)
            live=false;submittedWidth=Float.NaN;submittedEdge=Float.NaN
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
        val eye=tracking.points["VIEWER"] ?: tracking.points["HEAD"]
        val videoPose=video.getPose();val videoRotation=videoPose.rotation
        val eyeZ=if(eye!=null)viewerInMonitor(eye,floatArrayOf(videoPose.translation.x,videoPose.translation.y,videoPose.translation.z),floatArrayOf(videoRotation.x,videoRotation.y,videoRotation.z,videoRotation.w))[2] else 1f
        if(eyeZ.isFinite() && kotlin.math.abs(eyeZ)>.001f)frontSign=if(eyeZ>0f)1f else -1f
        // One centimetre toward the viewer, so the square wins the ray where it crosses the object.
        val front=.01f*(units.takeIf{it.isFinite() && it>0f} ?: 1f)*frontSign
        planeZ=front
        if(!live){NativeJournal.noteEnabled(tag(),"corners",true);corners.forEach{if(!it.surface.isDisposed)it.surface.setEnabled(true)};live=true}
        present()
        for(corner in corners){
            if(edge!=submittedEdge && !corner.surface.isDisposed){
                val shape=SurfaceEntity.Shape.Quad(FloatSize2d(edge,edge))
                journalShape(tag(),shape)
                corner.surface.shape=shape
            }
            if(width!=submittedWidth || height!=submittedHeight || edge!=submittedEdge || front!=submittedFront){
                corner.surface.setPose(Pose(Vector3(corner.x*width*.5f,corner.y*height*.5f,front)))
            }
        }
        submittedWidth=width;submittedHeight=height;submittedEdge=edge;submittedFront=front
    }
    private fun present(){
        val now=SystemClock.uptimeMillis()
        val want=objectAimed || aimedHandles.get()>0 || active!=null
        if(want)shownUntil=now+160
        val show=want || now<shownUntil
        val thick=aimedHandles.get()>0 || active!=null
        val held=active!=null
        if(show!=drawnShow || thick!=drawnThick || held!=drawnHeld){
            corners.forEach{paint(it,show,thick,held)}
            drawnShow=show;drawnThick=thick;drawnHeld=held
        }
    }
    private fun paint(corner:Corner,show:Boolean,thick:Boolean,held:Boolean){
        if(corner.surface.isDisposed)return
        runCatching{
            val canvas=corner.surface.getSurface().lockCanvas(null)
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            if(show)drawRoundedCorner(canvas,corner.x,corner.y,thick,held)
            corner.surface.getSurface().unlockCanvasAndPost(canvas)
        }
    }
    fun cancel(){
        active=null;handResize=null;lastFactor=1f;grabProjection=Float.NaN;shownUntil=0L
        corners.forEach{if(it.hovered){it.hovered=false;aimedHandles.decrementAndGet()}}
        if(drawnShow || drawnThick || drawnHeld){corners.forEach{paint(it,false,false,false)};drawnShow=false;drawnThick=false;drawnHeld=false}
        if(live){NativeJournal.noteEnabled(tag(),"corners",false);corners.forEach{if(!it.surface.isDisposed)it.surface.setEnabled(false)};live=false}
    }
    private fun release(corner:Corner){
        if(corner.hovered){corner.hovered=false;aimedHandles.decrementAndGet()}
        if(!corner.surface.isDisposed)NativeJournal.native(tag(),"dispose","target=corner:${corner.x},${corner.y}"){corner.surface.removeComponent(corner.input);corner.surface.dispose()}
    }
    override fun close(){
        closed=true;active=null;host=null
        corners.forEach{release(it)};corners.clear()
    }

    private fun cornerProjection(session:Session,video:SurfaceEntity,event:InputEvent,plane:Float,x:Int,y:Int):Float?{
        fun point(v:Vector3)=session.scene.activitySpace.transformPoseTo(Pose(v),video).translation
        val o=point(event.origin);val p=point(Vector3(event.origin.x+event.direction.x,event.origin.y+event.direction.y,event.origin.z+event.direction.z))
        val dz=p.z-o.z
        if(kotlin.math.abs(dz)<=.00001f)return null
        val t=(plane-o.z)/dz
        val px=o.x+(p.x-o.x)*t;val py=o.y+(p.y-o.y)*t
        val projection=(px*x*startWidth*.5f+py*y*startHeight*.5f)*video.getScale()/grabScale
        return projection.takeIf{it.isFinite()}
    }

    companion object {
        private const val HandlePixels=128
        private fun drawRoundedCorner(canvas:android.graphics.Canvas,sx:Int,sy:Int,thick:Boolean,glow:Boolean){
            val n=canvas.width.toFloat()
            val stroke=n*.05f
            // The bitmap centre is the object corner. The mark occupies the outer quadrant only,
            // out to its midpoint, so four marks on a point form a rounded square of side n/2.
            val reach=n*.25f-stroke*.5f
            val radius=reach*.5f
            val ou=sx.toFloat();val ov=-sy.toFloat()
            val cx=n*.5f;val cy=n*.5f
            fun at(pu:Float,pv:Float)=floatArrayOf(cx+ou*pu,cy+ov*pv)
            val start=at(0f,reach);val arcStart=at(reach-radius,reach);val arcEnd=at(reach,reach-radius);val end=at(reach,0f)
            val centre=at(reach-radius,reach-radius)
            val path=Path()
            path.moveTo(start[0],start[1]);path.lineTo(arcStart[0],arcStart[1])
            val startAngle=Math.toDegrees(kotlin.math.atan2(ov.toDouble(),0.0)).toFloat()
            val endAngle=Math.toDegrees(kotlin.math.atan2(0.0,ou.toDouble())).toFloat()
            var sweep=endAngle-startAngle
            if(sweep>180f)sweep-=360f
            if(sweep<=-180f)sweep+=360f
            path.arcTo(RectF(centre[0]-radius,centre[1]-radius,centre[0]+radius,centre[1]+radius),startAngle,sweep,false)
            path.lineTo(end[0],end[1])
            fun ink(color:Int,width:Float)=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                this.color=color;style=Paint.Style.STROKE;strokeWidth=width
                strokeCap=Paint.Cap.BUTT;strokeJoin=Paint.Join.ROUND
            }
            val weight=if(thick || glow)stroke*1.85f else stroke
            if(glow){
                // Wide faint rings. They stack to a soft white cloud beside the stroke, then the cyan covers the mark.
                val steps=18
                val spread=n*.42f
                for(i in steps downTo 1){
                    val halo=ink(Color.argb(22,255,255,255),weight+spread*(i/steps.toFloat()))
                    halo.strokeCap=Paint.Cap.ROUND
                    halo.strokeJoin=Paint.Join.ROUND
                    canvas.drawPath(path,halo)
                }
            }
            canvas.drawPath(path,ink(Color.CYAN,weight))
        }
    }
}
