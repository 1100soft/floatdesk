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
