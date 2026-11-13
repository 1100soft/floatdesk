package dev.floatdesk.client.net

import org.json.JSONObject
import dev.floatdesk.client.FocusRect
import dev.floatdesk.client.FocusHover
import dev.floatdesk.client.FocusTarget

internal data class DesktopWindow(val id:Long,val x:Int,val y:Int,val width:Int,val height:Int)

internal data class DesktopOutput(val name:String,val width:Int,val height:Int,val x:Int,val y:Int,val managed:Boolean,val primary:Boolean,val role:Int,val modes:List<Pair<Int,Int>>,val refreshHz:Double=0.0)
internal data class WorkspaceInfo(val screens:List<DesktopOutput>,val available:Int,val modes:List<Pair<Int,Int>>,val memoryBytes:Long,val cpuThreads:Int,val recommendedPixels:Long,val fps:Int,val estimate:String,val setupRequired:Boolean,val limits:Pair<Int,Int> = 32767 to 32767,val windowFocus:Boolean=false,val windows:List<DesktopWindow> = emptyList()) {
    fun bounds():IntArray? {
        if(screens.isEmpty())return null
        val x=screens.minOf{it.x};val y=screens.minOf{it.y}
        return intArrayOf(x,y,screens.maxOf{it.x+it.width}-x,screens.maxOf{it.y+it.height}-y)
    }
    fun hover(u:Float,v:Float):FocusHover? {
        val b=bounds() ?: return null
        val x=b[0]+u*b[2];val y=b[1]+v*b[3]
        val screen=screens.firstOrNull{x>=it.x && x<=it.x+it.width && y>=it.y && y<=it.y+it.height} ?: return null
        fun rect(x:Int,y:Int,w:Int,h:Int)=FocusRect((x-b[0]).toFloat()/b[2],(y-b[1]).toFloat()/b[3],(x+w-b[0]).toFloat()/b[2],(y+h-b[1]).toFloat()/b[3])
        val display=FocusTarget(rect(screen.x,screen.y,screen.width,screen.height),screen.name)
        val window=if(windowFocus)windows.firstOrNull{x>=it.x && x<=it.x+it.width && y>=it.y && y<=it.y+it.height} else null
        return FocusHover(display,window?.let{FocusTarget(rect(it.x,it.y,it.width,it.height),screen.name,it.id)})
    }
    /** Source rectangles may cross screens; map each visible piece through its visual inset. */
    fun visibleRects(rect:FocusRect,spacing:Float):List<FocusRect> {
        val b=bounds() ?: return emptyList()
        return screens.mapNotNull {screen->
            val r=FocusRect((screen.x-b[0]).toFloat()/b[2],(screen.y-b[1]).toFloat()/b[3],(screen.x+screen.width-b[0]).toFloat()/b[2],(screen.y+screen.height-b[1]).toFloat()/b[3])
            val piece=rect.intersect(r) ?: return@mapNotNull null
            val scale=1f-spacing.coerceIn(0f,.15f)
            FocusRect(r.centerU+(piece.left-r.centerU)*scale,r.centerV+(piece.top-r.centerV)*scale,
                r.centerU+(piece.right-r.centerU)*scale,r.centerV+(piece.bottom-r.centerV)*scale)
        }
    }
    companion object {
        fun parse(text:String):WorkspaceInfo? = runCatching {
            val j=JSONObject(text);require(j.getString("type")=="status")
            fun modes(o:JSONObject):List<Pair<Int,Int>> {
                val a=o.optJSONArray("modes") ?: return emptyList()
                return (0 until a.length()).map {val m=a.getJSONArray(it);m.getInt(0) to m.getInt(1)}.filter {it.first>0 && it.second>0}.distinct().sortedBy {it.first.toLong()*it.second}
            }
            val a=j.getJSONArray("screens")
            val screens=(0 until a.length()).map {i->val o=a.getJSONObject(i)
                DesktopOutput(o.getString("name"),o.getInt("width"),o.getInt("height"),o.getInt("x"),o.getInt("y"),o.getBoolean("managed"),o.getBoolean("primary"),o.getInt("role"),modes(o),o.optDouble("refreshHz",0.0)).also {require(it.width>0 && it.height>0)}
            }
            WorkspaceInfo(screens,j.getInt("available"),modes(j),j.getLong("availableMemoryBytes"),j.getInt("cpuThreads"),j.getLong("recommendedPixels"),j.getInt("targetFps"),j.getString("estimate"),j.getBoolean("setupRequired"),j.optJSONArray("desktopLimits")?.let {it.getInt(0) to it.getInt(1)} ?: (32767 to 32767),j.optBoolean("windowFocus",false),j.optJSONArray("windows")?.let {a->
                (0 until a.length()).mapNotNull {i->val w=a.getJSONObject(i);val id=w.getLong("id");val width=w.getInt("width");val height=w.getInt("height")
                    if(id<=0 || width<=0 || height<=0)null else DesktopWindow(id,w.getInt("x"),w.getInt("y"),width,height)}
            } ?: emptyList())
        }.getOrNull()
    }
}
