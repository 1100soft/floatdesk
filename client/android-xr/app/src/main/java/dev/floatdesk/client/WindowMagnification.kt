package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose
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
    fun pin(){if(active!=null){pinned=true;departed=0;candidate=null}}
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
internal data class MagnifiedWindow(val id:Long,val pose:Pose,val width:Float,val height:Float,val sourceWidth:Int,val sourceHeight:Int,val spherical:Boolean=false,val mesh:MonitorMesh?=null,val units:Float=1f)
internal fun magnifiedWindow(id:Long,width:Int,height:Int,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spherical:Boolean=false):MagnifiedWindow{
    val source=deskLayout(listOf(Triple("Window",width,height)))
    val fitted=fitDesktopInView(source,viewer,horizontal,vertical,units,spherical,centralView=true,distanceM=MagnifierDistanceM).single()
    val window=MagnifiedWindow(id,fitted.pose,fitted.physicalWidthM,fitted.physicalHeightM,width,height,spherical,units=units)
    return magnifierGeometry(window,viewer,units)
}
/** Same exponential-map projection as the desktop, with this panel's own fixed radial distance. */
internal fun magnifierGeometry(window:MagnifiedWindow,viewer:Pose,units:Float,proposed:FloatArray=window.pose.position,gazeDirection:FloatArray?=null):MagnifiedWindow{
    val radius=MagnifierDistanceM*units
    val delta=FloatArray(3){proposed[it]-viewer.position[it]}
    val length=sqrt(delta.sumOf{(it*it).toDouble()}).toFloat()
    val fallback=rotateVector(viewer.rotation,0f,0f,-1f)
    val center=FloatArray(3){viewer.position[it]+radius*(if(length>.00001f)delta[it]/length else fallback[it])}
    val pose=Pose(center,viewer.rotation.copyOf())
    val scale=units/window.units
    val width=window.width*scale;val height=window.height*scale
    val mesh=if(window.spherical){
        val eye=viewerInMonitor(viewer.position,pose.position,pose.rotation)
        val reference=MonitorMesh(width,height,eye,columns=32,rows=20,focus=floatArrayOf(0f,0f))
        val ray=gazeDirection ?: rotateVector(viewer.rotation,0f,0f,-1f)
        val endpoint=viewerInMonitor(FloatArray(3){viewer.position[it]+ray[it]},pose.position,pose.rotation)
        val focus=sphericalViewFocus(reference,FloatArray(3){endpoint[it]-eye[it]}) ?: floatArrayOf(0f,0f)
        MonitorMesh(width,height,eye,columns=32,rows=20,focus=focus)
    }else null
    return window.copy(pose=pose,width=width,height=height,mesh=mesh,units=units)
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
