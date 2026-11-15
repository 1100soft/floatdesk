package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.net.WorkspaceInfo
import dev.floatdesk.client.net.DesktopWindow
import dev.floatdesk.client.workspace.deskLayout
import dev.floatdesk.client.workspace.fitDesktopInView
import kotlin.math.*

/** Only valid, sustained gaze can switch/dismiss; blinking never starts departure. */
internal class WindowMagnification(private val dwellMs:Long=600,private val travelMs:Long=900){
    var active:Long?=null;private set
    var pinned=false;private set
    private var candidate:Long?=null;private var since=0L;private var entered=0L
    private var readyAt=0L;private var departed=0L
    fun refresh(now:Long){entered=now;readyAt=0;departed=0;candidate=null}
    fun togglePin(){if(active!=null){pinned=!pinned;departed=0;candidate=null}}
    fun dismiss(now:Long){active=null;pinned=false;candidate=null;readyAt=0;departed=0}
    fun update(now:Long,enabled:Boolean,blocked:Boolean,target:Long?,inside:Boolean,ready:Boolean,hold:Boolean=false,validGaze:Boolean=true):Long?{
        if(!enabled || blocked){dismiss(now);return null}
        if(active!=null && !ready){if(now-entered>4000)dismiss(now);return active}
        if(active!=null && readyAt==0L)readyAt=now
        if(pinned || hold || !validGaze || active!=null && now-readyAt<travelMs){departed=0;candidate=null;return active}
        if(active!=null && inside){departed=0;candidate=null;return active}
        if(target!=null && target!=active){
            if(candidate!=target){candidate=target;since=now}
            if(now-since>=dwellMs){active=target;entered=now;readyAt=0;departed=0;candidate=null;return active}
        }else candidate=null
        if(active!=null){
            if(departed==0L)departed=now
            if(now-departed>=1000)dismiss(now)
        }
        return active
    }
}
/** Native UP can omit HitInfo; preserve the last contact, but never an exited/cancelled tap. */
internal class NativeSurfaceTap {
    private val down=mutableMapOf<String,Pair<Float,Float>>()
    private val contacts=mutableMapOf<String,Pair<Float,Float>>()
    fun clear(){down.clear();contacts.clear()}
    fun update(side:String,action:String,point:Pair<Float,Float>?):Pair<Float,Float>? {
        if(point!=null)contacts[side]=point
        when(action){
            "DOWN" -> if(point!=null)down[side]=point
            "UP" -> down.remove(side)?.let{start->
                val end=point ?: contacts[side] ?: start
                if(hypot((end.first-start.first).toDouble(),(end.second-start.second).toDouble())<.08)return end
            }
            "CANCEL","HOVER_EXIT" -> {down.remove(side);contacts.remove(side)}
        }
        return null
    }
}
internal const val MagnifierDistanceM=.85f
internal data class MagnifiedWindow(val id:Long,val pose:Pose,val width:Float,val height:Float,val sourceWidth:Int,val sourceHeight:Int,val spherical:Boolean=false,val mesh:MonitorMesh?=null,val units:Float=1f,val headOffset:FloatArray=floatArrayOf(0f,0f,-1f),val sizeScale:Float=1f)
internal fun magnifiedWindow(id:Long,width:Int,height:Int,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spherical:Boolean=false):MagnifiedWindow{
    val source=deskLayout(listOf(Triple("Window",width,height)))
    val fitted=fitDesktopInView(source,viewer,horizontal,vertical,units,spherical,centralView=true,distanceM=MagnifierDistanceM).single()
    val window=MagnifiedWindow(id,fitted.pose,fitted.physicalWidthM,fitted.physicalHeightM,width,height,spherical,units=units)
    return magnifierGeometry(window,viewer,units)
}
/** Same exponential-map projection as the desktop, with this panel's own fixed radial distance. */
internal fun magnifierGeometry(window:MagnifiedWindow,viewer:Pose,units:Float,proposed:FloatArray?=null):MagnifiedWindow{
    // Perception-space matrices can jitter by a few ULPs. Do not turn that into native mesh uploads.
    val stableUnits=if(abs(units/window.units-1f)<.001f)window.units else units
    val radius=MagnifierDistanceM*stableUnits
    val offset=if(proposed==null)window.headOffset else {
        val local=viewerInMonitor(proposed,viewer.position,viewer.rotation)
        val length=sqrt(local.sumOf{(it*it).toDouble()}).toFloat()
        if(length>.00001f)FloatArray(3){local[it]/length} else window.headOffset
    }
    val center=transformPoint(viewer,FloatArray(3){offset[it]*radius})
    val pose=Pose(center,viewer.rotation.copyOf())
    val scale=stableUnits/window.units
    val width=window.width*scale;val height=window.height*scale
    val eye=FloatArray(3){-offset[it]*radius}
    val old=window.mesh
    val mesh=if(!window.spherical)null else if(old!=null && old.width==width && old.height==height && old.eye.indices.all{abs(old.eye[it]-eye[it])<.000001f})old else {
        val reference=MonitorMesh(width,height,eye,columns=32,rows=20,focus=floatArrayOf(0f,0f))
        val focus=sphericalViewFocus(reference,floatArrayOf(0f,0f,-1f)) ?: floatArrayOf(0f,0f)
        MonitorMesh(width,height,eye,columns=32,rows=20,focus=focus)
    }
    return window.copy(pose=pose,width=width,height=height,mesh=mesh,units=stableUnits,headOffset=offset)

}
/** A fixed 1.5 degree tolerance around the actual rendered boundary, independent of window size. */
internal fun magnifierContainsGaze(window:MagnifiedWindow,origin:FloatArray,direction:FloatArray):Boolean{
    if(magnifierPoint(window,origin,direction)!=null)return true
    val o=viewerInMonitor(origin,window.pose.position,window.pose.rotation)
    val end=viewerInMonitor(FloatArray(3){origin[it]+direction[it]},window.pose.position,window.pose.rotation)
    fun unit(v:FloatArray):FloatArray{val n=sqrt(v.sumOf{(it*it).toDouble()}).toFloat();return FloatArray(3){v[it]/n.coerceAtLeast(.000001f)}}
    fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf{(a[it]*b[it]).toDouble()}.toFloat()
    val ray=unit(FloatArray(3){end[it]-o[it]})
    val border=window.mesh?.outline ?: listOf(floatArrayOf(-window.width/2,window.height/2,0f),floatArrayOf(window.width/2,window.height/2,0f),floatArrayOf(window.width/2,-window.height/2,0f),floatArrayOf(-window.width/2,-window.height/2,0f))
    val points=border.map{p->unit(FloatArray(3){p[it]-o[it]})}
    val cosine=cos(Math.toRadians(1.5)).toFloat()
    return points.indices.any{i->
        val a=points[i];val b=points[(i+1)%points.size];val edge=FloatArray(3){b[it]-a[it]}
        val t=(dot(FloatArray(3){ray[it]-a[it]},edge)/dot(edge,edge).coerceAtLeast(.00000001f)).coerceIn(0f,1f)
        dot(ray,unit(FloatArray(3){a[it]+edge[it]*t}))>=cosine
    }
}
internal fun magnifierPoint(window:MagnifiedWindow,origin:FloatArray,direction:FloatArray,margin:Float=0f):Pair<Float,Float>?{
    val o=viewerInMonitor(origin,window.pose.position,window.pose.rotation)
    val end=viewerInMonitor(FloatArray(3){origin[it]+direction[it]},window.pose.position,window.pose.rotation)
    val d=FloatArray(3){end[it]-o[it]};if(abs(d[2])<1e-6f)return null
    if(window.mesh!=null){
        val hit=monitorRayHit(o,d,window.width,window.height,window.mesh) ?: return null
        val flat=window.mesh.unproject(hit) ?: return null
        return flat[0]/window.width+.5f to (.5f-flat[1]/window.height)
    }
    val t=-o[2]/d[2];if(t<=0f)return null
    val u=(o[0]+t*d[0])/window.width+.5f;val v=.5f-(o[1]+t*d[1])/window.height
    return (u to v).takeIf{u in -margin..1f+margin && v in -margin..1f+margin}
}

/** Resize belongs to this activation only; opening another window creates sizeScale=1 again. */
internal fun resizedMagnifier(window:MagnifiedWindow,factor:Float):MagnifiedWindow {
    if(!factor.isFinite() || factor<=0f)return window
    return window.copy(width=window.width*factor,height=window.height*factor,sizeScale=window.sizeScale*factor,mesh=null)
}
internal fun magnifierPinOutline(window:MagnifiedWindow):List<FloatArray> {
    val pad=.005f*window.units
    return window.mesh?.let {mesh->MonitorMesh(window.width+2*pad,window.height+2*pad,mesh.eye,columns=32,rows=20,focus=mesh.focus,center=mesh.center).outline} ?: listOf(
        floatArrayOf(-window.width/2-pad,window.height/2+pad,0f),floatArrayOf(window.width/2+pad,window.height/2+pad,0f),
        floatArrayOf(window.width/2+pad,-window.height/2-pad,0f),floatArrayOf(-window.width/2-pad,-window.height/2-pad,0f))
}

/** Keep a large, fully visible and unobscured window in its desktop position. */
internal fun inPlaceWindowRect(info:WorkspaceInfo,id:Long,desktop:MonitorPlacement,mesh:MonitorMesh?,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spacing:Float):FocusRect? {
    val index=info.windows.indexOfFirst{it.id==id};if(index<0)return null
    val window=info.windows[index];val bounds=info.bounds() ?: return null
    fun overlap(a:DesktopWindow,b:DesktopWindow)=a.x<b.x+b.width && b.x<a.x+a.width && a.y<b.y+b.height && b.y<a.y+a.height
    if(info.windows.take(index).any{overlap(it,window)})return null
    val rect=FocusRect((window.x-bounds[0]).toFloat()/bounds[2],(window.y-bounds[1]).toFloat()/bounds[3],(window.x+window.width-bounds[0]).toFloat()/bounds[2],(window.y+window.height-bounds[1]).toFloat()/bounds[3])
    if(rect.left<0f || rect.top<0f || rect.right>1f || rect.bottom>1f)return null
    val covered=info.screens.sumOf{s->(minOf(s.x+s.width,window.x+window.width)-maxOf(s.x,window.x)).coerceAtLeast(0).toLong()*(minOf(s.y+s.height,window.y+window.height)-maxOf(s.y,window.y)).coerceAtLeast(0)}
    if(covered<window.width.toLong()*window.height)return null
    var left=Float.POSITIVE_INFINITY;var right=Float.NEGATIVE_INFINITY;var top=left;var bottom=right
    for(piece in info.visibleRects(rect,spacing))for(edge in 0..3)for(step in 0..8){
        val t=step/8f
        val u=when(edge){0->piece.left+t*(piece.right-piece.left);1->piece.right;2->piece.right-t*(piece.right-piece.left);else->piece.left}
        val v=when(edge){0->piece.top;1->piece.top+t*(piece.bottom-piece.top);2->piece.bottom;else->piece.bottom-t*(piece.bottom-piece.top)}
        val flat=floatArrayOf((u-.5f)*desktop.physicalWidthM,(.5f-v)*desktop.physicalHeightM,0f)
        val point=transformPoint(desktop.pose,mesh?.project(flat,onTriangles=true) ?: flat)
        val camera=viewerInMonitor(point,viewer.position,viewer.rotation)
        if(camera[2]>=-.01f)return null
        val x=atan2(camera[0],-camera[2]);val y=atan2(camera[1],-camera[2])
        if(abs(x)>horizontal*.95f || abs(y)>vertical*.95f)return null
        left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
    }
    val standard=magnifiedWindow(id,window.width,window.height,viewer,horizontal,vertical,units,mesh!=null)
    val desired=standard.mesh?.outline ?: listOf(floatArrayOf(-standard.width/2,standard.height/2,0f),floatArrayOf(standard.width/2,-standard.height/2,0f))
    val angles=desired.map{viewerInMonitor(transformPoint(standard.pose,it),viewer.position,viewer.rotation)}
    val width=angles.maxOf{atan2(it[0],-it[2])}-angles.minOf{atan2(it[0],-it[2])}
    val height=angles.maxOf{atan2(it[1],-it[2])}-angles.minOf{atan2(it[1],-it[2])}
    return rect.takeIf{right-left>=width*.99f && bottom-top>=height*.99f}
}
