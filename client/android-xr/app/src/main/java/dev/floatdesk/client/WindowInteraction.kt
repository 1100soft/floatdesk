package dev.floatdesk.client

import dev.floatdesk.client.net.DesktopWindow
import dev.floatdesk.client.net.WorkspaceInfo
import kotlin.math.abs
import kotlin.math.roundToInt

internal object WindowInteractionPolicy {
    const val ContextHoldMs=700L
    const val SlopFraction=.004f
    const val SnapFraction=.0125f
    const val AimAcquireMs=150L
    const val GridEdgeFraction=.12f
}
internal data class WindowMove(val id:Long,val x:Int,val y:Int,val width:Int?=null,val height:Int?=null)
internal data class WindowGrabUpdate(val move:WindowMove?=null,val menu:Long?=null,val preview:WindowMove?=null)
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
/** Shared saved pinch policy; a stationary hold and a drag are mutually exclusive. */
internal class WindowGrabController(private val thresholds:()->PinchThresholds){
    private data class Hold(val side:String,val aimSide:String,val window:DesktopWindow,val u:Float,val v:Float,val at:Long,var moved:Boolean=false,var menu:Boolean=false,var candidate:WindowMove?=null)
    private var hold:Hold?=null
    private val closed=mutableMapOf<String,Boolean>()
    private val pending=mutableMapOf<String,Long>()
    val engaged get()=hold!=null
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
            if(contact!=null && !h.menu && !released){
                val dx=((contact.contactU-h.u)*bounds[2]).roundToInt()
                val dy=((contact.contactV-h.v)*bounds[3]).roundToInt()
                val slop=minOf(h.window.width,h.window.height)*WindowInteractionPolicy.SlopFraction
                if(abs(dx)>slop || abs(dy)>slop)h.moved=true
                if(h.moved)h.candidate=snapWindowDrop(h.window,h.window.x+dx,h.window.y+dy,contact.contactU,contact.contactV,info)
            }
            if(released){hold=null;return WindowGrabUpdate(move=h.candidate)}
            if(!h.moved && !h.menu && now-h.at>=WindowInteractionPolicy.ContextHoldMs){h.menu=true;return WindowGrabUpdate(menu=h.window.id)}
            return WindowGrabUpdate(preview=h.candidate)
        }
        for(side in pending.keys.toList()){
            val aiming=hover[side]?.let{side to it} ?: hover.entries.singleOrNull()?.let{it.key to it.value} ?: continue
            val target=aiming.second.window ?: continue
            val window=info.windows.firstOrNull{it.id==target.windowId} ?: continue
            hold=Hold(side,aiming.first,window,target.contactU,target.contactV,now);pending.clear();break
        }
        return WindowGrabUpdate()
    }
}

/** Edge regions select the same half/quarter cells exposed by the visual grid menu. */
internal fun snapWindowDrop(window:DesktopWindow,x:Int,y:Int,u:Float,v:Float,info:WorkspaceInfo):WindowMove {
    val bounds=info.bounds() ?: return WindowMove(window.id,x,y)
    val px=bounds[0]+u*bounds[2];val py=bounds[1]+v*bounds[3]
    val screen=info.screens.firstOrNull{px>=it.x && px<it.x+it.width && py>=it.y && py<it.y+it.height} ?: return snapWindowMove(window,x,y,info)
    val sx=(px-screen.x)/screen.width;val sy=(py-screen.y)/screen.height
    val edge=WindowInteractionPolicy.GridEdgeFraction
    val col=if(sx<edge)0 else if(sx>1-edge)1 else -1
    val row=if(sy<edge)0 else if(sy>1-edge)1 else -1
    if(col<0 && row<0)return snapWindowMove(window,x,y,info)
    val columns=if(col<0)1 else 2;val rows=if(row<0)1 else 2
    return windowGridCell(window.id,screen.x,screen.y,screen.width,screen.height,columns,rows,maxOf(0,col),maxOf(0,row))
}
internal fun windowGridCell(id:Long,x:Int,y:Int,width:Int,height:Int,columns:Int,rows:Int,col:Int,row:Int):WindowMove {
    val left=x+width*col/columns;val top=y+height*row/rows
    return WindowMove(id,left,top,x+width*(col+1)/columns-left,y+height*(row+1)/rows-top)
}
