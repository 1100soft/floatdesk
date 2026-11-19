package dev.floatdesk.client

import android.graphics.Color
import android.graphics.Paint
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.*

/** Four visible corner targets; no backing plane, edge margins or move component. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class PaneCornerResize : AutoCloseable {
    private data class Corner(val x:Int,val y:Int,val surface:SurfaceEntity,val input:InteractableComponent)
    private val corners=mutableListOf<Corner>()
    private var width=0f;private var height=0f;private var startWidth=0f;private var startHeight=0f
    private var active:Corner?=null
    private data class HandResize(val side:String,val frame:dev.floatdesk.client.workspace.Pose,val local:FloatArray,val scale:Float,val gain:Float,val base:Float)
    private var handResize:HandResize?=null
    private var visible=true
    private var closed=false
    private var lastFactor=1f
    private var submittedWidth=Float.NaN;private var submittedHeight=Float.NaN;private var submittedEdge=Float.NaN
    private var callback:(Boolean,Float)->Unit={_,_->}
    fun update(session:Session,video:SurfaceEntity,width:Float,height:Float,units:Float,tracking:GrabTracking,onResize:(Boolean,Float)->Unit){
        this.width=width;this.height=height;callback=onResize
        if(corners.isEmpty())for(y in listOf(-1,1))for(x in listOf(-1,1)){
            val surface=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(.001f,.001f)),parent=video,superSampling=SurfaceEntity.SuperSampling.NONE)
            surface.setSurfacePixelDimensions(IntSize2d(32,32))
            val canvas=surface.getSurface().lockCanvas(null)
            canvas.drawColor(Color.rgb(25,53,67))
            val ink=Paint().apply{color=Color.CYAN;strokeWidth=3f}
            val cx=if(x<0)6f else 26f;val cy=if(y>0)6f else 26f
            canvas.drawLine(cx,cy,cx-x*13f,cy,ink);canvas.drawLine(cx,cy,cx,cy+y*13f,ink)
            surface.getSurface().unlockCanvasAndPost(canvas)
            lateinit var corner:Corner
            val input=NativeMonitorInput.create(session) listener@{event->
                if(closed || video.isDisposed || surface.isDisposed)return@listener
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
        val edge=minOf(.014f*units,minOf(width,height)*.5f)
        if(!visible){corners.forEach{it.surface.setEnabled(true)};visible=true}
        for(corner in corners){
            if(edge!=submittedEdge)corner.surface.shape=SurfaceEntity.Shape.Quad(FloatSize2d(edge,edge))
            if(width!=submittedWidth || height!=submittedHeight || edge!=submittedEdge)corner.surface.setPose(Pose(Vector3(corner.x*width*.5f,corner.y*height*.5f,.001f*units)))
        }
        submittedWidth=width;submittedHeight=height;submittedEdge=edge
    }
    fun cancel(){active=null;handResize=null;lastFactor=1f;if(visible){corners.forEach{it.surface.setEnabled(false)};visible=false}}
    override fun close(){closed=true;active=null;corners.forEach{if(!it.surface.isDisposed){it.surface.removeComponent(it.input);it.surface.dispose()}};corners.clear()}
}
