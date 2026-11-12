package dev.floatdesk.client

import kotlin.math.abs

internal data class DesktopScreen(val x:Int,val y:Int,val width:Int,val height:Int)

/** Snap corresponding edges/centres and adjacent edges in desktop pixels. */
internal fun snapScreen(screen:DesktopScreen,others:List<DesktopScreen>,threshold:Int):DesktopScreen {
    fun axis(start:Int,size:Int,edges:List<Pair<Int,Int>>):Int {
        val candidates=edges.flatMap { (p,n) -> listOf(p,p+n-size,p+n,p-size,p+(n-size)/2,p+n/2,p+n/2-size,p-size/2,p+n-size/2) }
        return candidates.minByOrNull {abs(it-start)}?.takeIf {abs(it-start)<=threshold} ?: start
    }
    return screen.copy(
        x=axis(screen.x,screen.width,others.map {it.x to it.width}).coerceIn(-32767,32767),
        y=axis(screen.y,screen.height,others.map {it.y to it.height}).coerceIn(-32767,32767),
    )
}

internal fun connectedScreens(screens:List<DesktopScreen>,primary:Int):List<Boolean> {
    val reached=MutableList(screens.size) {false};if(screens.isEmpty()) return reached
    val pending=java.util.ArrayDeque<Int>();val root=primary.coerceIn(0,screens.lastIndex);reached[root]=true;pending.add(root)
    while(pending.isNotEmpty()) {
        val a=screens[pending.removeFirst()]
        screens.forEachIndexed {i,b->
            val dx=minOf(a.x+a.width,b.x+b.width)-maxOf(a.x,b.x)
            val dy=minOf(a.y+a.height,b.y+b.height)-maxOf(a.y,b.y)
            if(!reached[i] && ((dx>0 && dy>=0) || (dy>0 && dx>=0))) {reached[i]=true;pending.add(i)}
        }
    }
    return reached
}
internal fun canMoveScreen(screens:List<DesktopScreen>,index:Int,moved:DesktopScreen,primary:Int,limits:Pair<Int,Int> = 32767 to 32767):Boolean {
    val proposed=screens.mapIndexed {i,r->if(i==index) moved else r}
    if(overlappingScreens(proposed).any {it}) return false
    if(proposed.maxOf {it.x+it.width}-proposed.minOf {it.x}<1 ||
       proposed.maxOf {it.x+it.width}-minOf(0,proposed.minOf {it.x})>limits.first ||
       proposed.maxOf {it.y+it.height}-minOf(0,proposed.minOf {it.y})>limits.second) return false
    val previous=connectedScreens(screens,primary).count {!it}
    val next=connectedScreens(screens.mapIndexed {i,r->if(i==index) moved else r},primary).count {!it}
    return next==0 || previous>0 && next<previous
}

internal fun overlappingScreens(screens:List<DesktopScreen>):List<Boolean> = screens.mapIndexed {i,a->
    screens.withIndex().any {(j,b)->i!=j && minOf(a.x+a.width,b.x+b.width)>maxOf(a.x,b.x) && minOf(a.y+a.height,b.y+b.height)>maxOf(a.y,b.y)}
}
internal data class ArrangementViewport(val scale:Float,val offsetX:Float,val offsetY:Float)
/** Fit the logical union with room on all sides; keep this transform fixed during a drag. */
internal fun arrangementViewport(screens:List<DesktopScreen>,width:Float,height:Float):ArrangementViewport {
    if(screens.isEmpty()) return ArrangementViewport(1f,width/2,height/2)
    val left=screens.minOf {it.x};val top=screens.minOf {it.y}
    val right=screens.maxOf {it.x+it.width};val bottom=screens.maxOf {it.y+it.height}
    val scale=minOf(width/(right-left+screens.maxOf {it.width}*2f),height/(bottom-top+screens.maxOf {it.height}*2f)).coerceAtLeast(.001f)
    return ArrangementViewport(scale,(width-(left+right)*scale)/2,(height-(top+bottom)*scale)/2)
}

/** Preview stays live; release discards the final 100 ms of pinch-opening aim motion. */
internal class ArrangementDropHistory(private val releaseWindowMs:Long=100) {
    private val samples=java.util.ArrayDeque<Pair<Long,DesktopScreen>>()
    fun begin(screen:DesktopScreen,at:Long) {samples.clear();record(screen,at)}
    fun record(screen:DesktopScreen,at:Long) {
        val time=maxOf(at,samples.peekLast()?.first ?: at)
        samples.addLast(time to screen)
        while(samples.size>64 || samples.size>2 && samples.elementAt(1).first<time-500) samples.removeFirst()
    }
    fun finish(at:Long):DesktopScreen? = samples.lastOrNull {it.first<=at-releaseWindowMs}?.second ?: samples.peekFirst()?.second
    fun clear()=samples.clear()
}
