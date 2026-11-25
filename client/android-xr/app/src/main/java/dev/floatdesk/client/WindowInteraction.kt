package dev.floatdesk.client

import dev.floatdesk.client.net.DesktopOutput
import dev.floatdesk.client.net.DesktopWindow
import dev.floatdesk.client.net.WorkspaceInfo
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.MonitorPlacement
import kotlin.math.abs
import kotlin.math.roundToInt

internal object WindowInteractionPolicy {
    const val SlopFraction=.025f
    const val MotionConfirmMs=100L
    const val SnapFraction=.0125f
    const val AimAcquireMs=150L
    const val GridEdgeFraction=.12f
}
internal data class WindowMove(val id:Long,val x:Int,val y:Int,val width:Int?=null,val height:Int?=null)
internal data class WindowGrabUpdate(val move:WindowMove?=null,val preview:WindowMove?=null)
internal fun snapWindowMove(window:DesktopWindow,x:Int,y:Int,info:WorkspaceInfo):WindowMove {
    val tolerance=(minOf(window.width,window.height)*WindowInteractionPolicy.SnapFraction).coerceAtLeast(1f)
    val xs=info.screens.flatMap{listOf(it.x,it.x+it.width/2,it.x+it.width)}+info.windows.filter{it.id!=window.id}.flatMap{listOf(it.x,it.x+it.width)}
    val ys=info.screens.flatMap{listOf(it.y,it.y+it.height/2,it.y+it.height)}+info.windows.filter{it.id!=window.id}.flatMap{listOf(it.y,it.y+it.height)}
    fun snap(value:Int,size:Int,edges:List<Int>):Int {
        val change=edges.flatMap{listOf(it-value,it-value-size)}.minByOrNull{abs(it)} ?: 0
        return if(abs(change)<=tolerance)value+change else value
    }
    return WindowMove(window.id,snap(x,window.width,xs),snap(y,window.height,ys))
}
/** Shared saved pinch policy for dragging desktop windows; menus open on hover, not on a held pinch. */
internal class WindowGrabController(private val thresholds:()->PinchThresholds){
    private data class Hold(val side:String,val aimSide:String,val window:DesktopWindow,val u:Float,val v:Float,val at:Long,val layout:WorkspaceInfo,var outsideAt:Long?=null,var moved:Boolean=false,var candidate:WindowMove?=null)
    private var hold:Hold?=null
    private val closed=mutableMapOf<String,Boolean>()
    private val pending=mutableMapOf<String,Long>()
    fun cancel(){hold=null;pending.clear()}
    val engaged get()=hold!=null
    val source get()=hold?.window
    val aimSide get()=hold?.aimSide
    val handSide get()=hold?.side
    val preview get()=hold?.candidate
    fun update(now:Long,enabled:Boolean,blocked:Boolean,samples:Map<String,FingerPinch>,hover:Map<String,FocusHover>,info:WorkspaceInfo?):WindowGrabUpdate {
        closed.keys.retainAll(samples.keys)
        val presses=samples.mapValues{(side,sample)->
            val down=thresholds().indexClosed(side,sample.indexMetres)
            val before=closed.put(side,down) ?: down
            down && !before
        }
        if(!enabled || blocked || info==null){hold=null;pending.clear();return WindowGrabUpdate()}
        pending.keys.removeAll{closed[it]!=true || now-(pending[it] ?: 0)>WindowInteractionPolicy.AimAcquireMs}
        presses.filterValues{it}.keys.forEach{pending[it]=now}
        val bounds=info.bounds() ?: return WindowGrabUpdate()
        hold?.let {h->
            if(h.side !in samples){hold=null;return WindowGrabUpdate()}
            val contact=hover[h.aimSide]?.window ?: hover[h.aimSide]?.screen
            val released=closed[h.side]!=true
            if(contact!=null && !released){
                val dx=((contact.contactU-h.u)*bounds[2]).roundToInt()
                val dy=((contact.contactV-h.v)*bounds[3]).roundToInt()
                val slop=minOf(bounds[2],bounds[3])*WindowInteractionPolicy.SlopFraction
                if(abs(dx)>slop || abs(dy)>slop){
                    if(h.outsideAt==null)h.outsideAt=now
                    if(now-h.outsideAt!!>=WindowInteractionPolicy.MotionConfirmMs)h.moved=true
                }else h.outsideAt=null
                if(h.moved)h.candidate=snapWindowDrop(h.window,h.window.x+dx,h.window.y+dy,contact.contactU,contact.contactV,h.layout)
            }
            if(released){hold=null;return WindowGrabUpdate(move=h.candidate)}
            return WindowGrabUpdate(preview=h.candidate)
        }
        for(side in pending.keys.toList()){
            val aiming=hover[side]?.let{side to it} ?: hover.entries.singleOrNull()?.let{it.key to it.value} ?: continue
            val target=aiming.second.window ?: continue
            val window=info.windows.firstOrNull{it.id==target.windowId} ?: continue
            hold=Hold(side,aiming.first,window,target.contactU,target.contactV,now,info);pending.clear();break
        }
        return WindowGrabUpdate()
    }
}

/** Reserve the X11 panel on the edge it occupies, in desktop pixels. */
internal fun windowWorkArea(screen:DesktopOutput,panels:List<DesktopWindow>):IntArray{
    val right=screen.x+screen.width;val bottom=screen.y+screen.height
    var leftInset=screen.x;var topInset=screen.y;var rightInset=right;var bottomInset=bottom
    for(panel in panels.filter{it.panel}){
        val x=maxOf(screen.x,panel.x);val y=maxOf(screen.y,panel.y)
        val r=minOf(right,panel.x+panel.width);val d=minOf(bottom,panel.y+panel.height)
        if(r<=x || d<=y)continue
        if(panel.width>=panel.height){
            val top=y-screen.y;val below=bottom-d
            if(minOf(top,below)>panel.height)continue
            if(top<=below)topInset=maxOf(topInset,d) else bottomInset=minOf(bottomInset,y)
        }else{
            val left=x-screen.x;val after=right-r
            if(minOf(left,after)>panel.width)continue
            if(left<=after)leftInset=maxOf(leftInset,r) else rightInset=minOf(rightInset,x)
        }
    }
    return if(leftInset<rightInset && topInset<bottomInset)
        intArrayOf(leftInset,topInset,rightInset-leftInset,bottomInset-topInset)
    else intArrayOf(screen.x,screen.y,screen.width,screen.height)
}

/** Edge regions select the same half/quarter cells exposed by the visual grid menu. */
internal fun snapWindowDrop(window:DesktopWindow,x:Int,y:Int,u:Float,v:Float,info:WorkspaceInfo):WindowMove {
    val bounds=info.bounds() ?: return WindowMove(window.id,x,y)
    val px=bounds[0]+u*bounds[2];val py=bounds[1]+v*bounds[3]
    val screen=info.screens.firstOrNull{px>=it.x && px<it.x+it.width && py>=it.y && py<it.y+it.height} ?: return snapWindowMove(window,x,y,info)
    val sx=(px-screen.x)/screen.width;val sy=(py-screen.y)/screen.height
    val edge=WindowInteractionPolicy.GridEdgeFraction
    val corner=(sx<.25f || sx>.75f) && (sy<.25f || sy>.75f)
    val col=if(sx<edge || corner && sx<.5f)0 else if(sx>1-edge || corner)1 else -1
    val row=if(sy<edge || corner && sy<.5f)0 else if(sy>1-edge || corner)1 else -1
    if(col<0 && row<0)return snapWindowMove(window,x,y,info)
    val columns=if(col<0)1 else 2;val rows=if(row<0)1 else 2
    val work=windowWorkArea(screen,info.systemWindows)
    return windowGridCell(window.id,work[0],work[1],work[2],work[3],columns,rows,maxOf(0,col),maxOf(0,row))
}
internal fun windowGridCell(id:Long,x:Int,y:Int,width:Int,height:Int,columns:Int,rows:Int,col:Int,row:Int,endCol:Int=col,endRow:Int=row):WindowMove {
    val left=x+width*col/columns;val top=y+height*row/rows
    return WindowMove(id,left,top,x+width*(endCol+1)/columns-left,y+height*(endRow+1)/rows-top)
}

/** A temporary window pane uses the same distance-scaled drag as magnifiers. */
internal class WindowDragRay {
    private data class Anchor(val side:String,val aimSide:String,val desktop:MonitorPlacement,val viewer:Pose,val u:Float,val v:Float,val initial:FloatArray,val pane:MagnifiedWindow)
    private var anchor:Anchor?=null
    private val drag=MagnifierRayDrag()
    var pane:MagnifiedWindow?=null;private set
    fun clear(){anchor=null;pane=null;drag.clear()}
    fun begin(side:String,hand:HandAimPose,hit:PointerTarget,desktop:MonitorPlacement,mesh:MonitorMesh?,viewer:Pose,source:DesktopWindow,bounds:IntArray,units:Float,aimSide:String=side){
        if(anchor!=null)return
        val u=(source.x+source.width*.5f-bounds[0])/bounds[2];val v=(source.y+source.height*.5f-bounds[1])/bounds[3]
        val center=transformPoint(desktop.pose,floatArrayOf((u-.5f)*desktop.physicalWidthM,(.5f-v)*desktop.physicalHeightM,.02f*units))
        val width=source.width.toFloat()/bounds[2]*desktop.physicalWidthM;val height=source.height.toFloat()/bounds[3]*desktop.physicalHeightM
        val pane=MagnifiedWindow(source.id,desktop.pose.copy(position=center),width,height,source.width,source.height,false,units=units,fixed=true,requestedPosition=center)
        val contact=transformPoint(desktop.pose,hit.local)
        val direction=FloatArray(3){contact[it]-hand.position[it]}
        drag.begin(pane,viewer,hand.position,direction,contact,hand.position)
        anchor=Anchor(side,aimSide,desktop,viewer,hit.u,hit.v,project(center,desktop,viewer),pane);this.pane=pane
    }
    private fun project(point:FloatArray,desktop:MonitorPlacement,viewer:Pose):FloatArray{
        val p=viewerInMonitor(point,desktop.pose.position,desktop.pose.rotation)
        val eye=viewerInMonitor(viewer.position,desktop.pose.position,desktop.pose.rotation)
        val denominator=p[2]-eye[2]
        val t=if(kotlin.math.abs(denominator)>.00001f)-eye[2]/denominator else 1f
        return floatArrayOf(eye[0]+(p[0]-eye[0])*t,eye[1]+(p[1]-eye[1])*t,0f)
    }
    fun point(poses:Map<String,HandAimPose>):Pair<String,PointerTarget>?{
        val a=anchor ?: return null;val hand=poses[a.side] ?: return null
        val moved=drag.moveHand(a.viewer,hand.position) ?: a.pane.pose.position
        pane=a.pane.copy(pose=a.pane.pose.copy(position=moved),requestedPosition=moved)
        val p=project(moved,a.desktop,a.viewer)
        val u=a.u+(p[0]-a.initial[0])/a.desktop.physicalWidthM
        val v=a.v-(p[1]-a.initial[1])/a.desktop.physicalHeightM
        return a.aimSide to PointerTarget(0,u,v,floatArrayOf((u-.5f)*a.desktop.physicalWidthM,(.5f-v)*a.desktop.physicalHeightM,0f))
    }
}
