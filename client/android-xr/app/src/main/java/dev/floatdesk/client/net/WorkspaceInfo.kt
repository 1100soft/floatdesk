package dev.floatdesk.client.net

import org.json.JSONObject

internal data class DesktopOutput(val name:String,val width:Int,val height:Int,val x:Int,val y:Int,val managed:Boolean,val primary:Boolean,val role:Int,val modes:List<Pair<Int,Int>>,val refreshHz:Double=0.0)
internal data class WorkspaceInfo(val screens:List<DesktopOutput>,val available:Int,val modes:List<Pair<Int,Int>>,val memoryBytes:Long,val cpuThreads:Int,val recommendedPixels:Long,val fps:Int,val estimate:String,val setupRequired:Boolean,val limits:Pair<Int,Int> = 32767 to 32767) {
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
            WorkspaceInfo(screens,j.getInt("available"),modes(j),j.getLong("availableMemoryBytes"),j.getInt("cpuThreads"),j.getLong("recommendedPixels"),j.getInt("targetFps"),j.getString("estimate"),j.getBoolean("setupRequired"),j.optJSONArray("desktopLimits")?.let {it.getInt(0) to it.getInt(1)} ?: (32767 to 32767))
        }.getOrNull()
    }
}
