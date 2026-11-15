package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose
import kotlin.math.*

/** Identity lock includes travel grace, departure hysteresis and a re-entry cooldown. */
internal class WindowMagnification(private val dwellMs:Long=600,private val travelMs:Long=900,private val cooldownMs:Long=1800){
    var active:Long?=null;private set
    private var candidate:Long?=null;private var since=0L;private var entered=0L
    private var readyAt=0L;private var departed=0L;private var coolUntil=0L
    fun dismiss(now:Long){active=null;candidate=null;readyAt=0;departed=0;coolUntil=now+cooldownMs}
    fun update(now:Long,enabled:Boolean,blocked:Boolean,target:Long?,inside:Boolean,ready:Boolean,hold:Boolean=false):Long?{
        if(!enabled){active=null;candidate=null;coolUntil=0;return null}
        if(blocked){if(active!=null)dismiss(now);candidate=null;return active}
        if(active!=null){
            if(!ready){if(now-entered>4000)dismiss(now);return active}
            if(readyAt==0L)readyAt=now
            if(now-readyAt<travelMs || hold){departed=0;return active}
            if(inside){departed=0;return active}
            if(departed==0L)departed=now
            if(now-departed>=180)dismiss(now)
            return active
        }
        if(now<coolUntil || target==null){candidate=null;return null}
        if(candidate!=target){candidate=target;since=now;return null}
        if(now-since>=dwellMs){active=target;entered=now;readyAt=0;departed=0;candidate=null}
        return active
    }
}
internal data class MagnifiedWindow(val id:Long,val pose:Pose,val width:Float,val height:Float,val sourceWidth:Int,val sourceHeight:Int)
internal fun magnifiedWindow(id:Long,width:Int,height:Int,viewer:Pose,horizontal:Float,vertical:Float,units:Float):MagnifiedWindow{
    val angles=focusViewHalfAngles(horizontal,vertical);val distance=.85f*units
    val aspect=width.toFloat()/height.coerceAtLeast(1);val w=minOf(2*distance*tan(angles.first),2*distance*tan(angles.second)*aspect)
    val p=transformPoint(viewer,floatArrayOf(0f,0f,-distance))
    return MagnifiedWindow(id,Pose(p,viewer.rotation.copyOf()),w,w/aspect,width,height)
}
internal fun magnifierPoint(window:MagnifiedWindow,origin:FloatArray,direction:FloatArray,margin:Float=0f):Pair<Float,Float>?{
    val o=viewerInMonitor(origin,window.pose.position,window.pose.rotation)
    val end=viewerInMonitor(FloatArray(3){origin[it]+direction[it]},window.pose.position,window.pose.rotation)
    val d=FloatArray(3){end[it]-o[it]};if(abs(d[2])<1e-6f)return null
    val t=-o[2]/d[2];if(t<=0f)return null
    val u=(o[0]+t*d[0])/window.width+.5f;val v=.5f-(o[1]+t*d[1])/window.height
    return (u to v).takeIf{u in -margin..1f+margin && v in -margin..1f+margin}
}
