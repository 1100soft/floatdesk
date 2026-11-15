package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose
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
internal data class MagnifiedWindow(val id:Long,val pose:Pose,val width:Float,val height:Float,val sourceWidth:Int,val sourceHeight:Int,val spherical:Boolean=false,val mesh:MonitorMesh?=null)
internal fun magnifiedWindow(id:Long,width:Int,height:Int,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spherical:Boolean=false):MagnifiedWindow{
    val angles=focusViewHalfAngles(horizontal,vertical);val distance=.85f*units
    val aspect=width.toFloat()/height.coerceAtLeast(1);val w=minOf(2*distance*tan(angles.first),2*distance*tan(angles.second)*aspect)
    val p=transformPoint(viewer,floatArrayOf(0f,0f,-distance))
    return MagnifiedWindow(id,Pose(p,viewer.rotation.copyOf()),w,w/aspect,width,height,spherical,if(spherical)MonitorMesh(w,w/aspect,floatArrayOf(0f,0f,distance),columns=32,rows=20,focus=floatArrayOf(0f,0f)) else null)
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
