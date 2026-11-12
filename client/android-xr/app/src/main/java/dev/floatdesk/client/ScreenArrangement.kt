package dev.floatdesk.client

import kotlin.math.abs

internal data class DesktopScreen(val x:Int,val y:Int,val width:Int,val height:Int)

/** Snap corresponding edges/centres and adjacent edges in desktop pixels. */
internal fun snapScreen(screen:DesktopScreen,others:List<DesktopScreen>,threshold:Int):DesktopScreen {
    fun axis(start:Int,size:Int,edges:List<Pair<Int,Int>>):Int {
        val candidates=edges.flatMap { (p,n) -> listOf(p,p+n-size,p+n,p-size,p+(n-size)/2) }
        return candidates.minByOrNull {abs(it-start)}?.takeIf {abs(it-start)<=threshold} ?: start
    }
    return screen.copy(
        x=axis(screen.x,screen.width,others.map {it.x to it.width}).coerceIn(0,(16384-screen.width).coerceAtLeast(0)),
        y=axis(screen.y,screen.height,others.map {it.y to it.height}).coerceIn(0,(16384-screen.height).coerceAtLeast(0)),
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
internal fun canMoveScreen(screens:List<DesktopScreen>,index:Int,moved:DesktopScreen,primary:Int):Boolean {
    val previous=connectedScreens(screens,primary).count {!it}
    val next=connectedScreens(screens.mapIndexed {i,r->if(i==index) moved else r},primary).count {!it}
    return next==0 || previous>0 && next<previous
}
