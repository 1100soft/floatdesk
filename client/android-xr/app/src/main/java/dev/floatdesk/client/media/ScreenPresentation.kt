package dev.floatdesk.client.media

/** Normalized top-left source rectangles; spacing scales content, never desktop geometry. */
internal data class ScreenPresentation(val rects:FloatArray=floatArrayOf(0f,0f,1f,1f),val spacing:Float=0f) {
    fun desktopPoint(u:Float,v:Float):Pair<Float,Float>? {
        if(!u.isFinite() || !v.isFinite()) return null
        val scale=1f-spacing.coerceIn(0f,.15f)
        for(i in 0 until rects.size/4) {
            val n=i*4;val cx=(rects[n]+rects[n+2])*.5f;val cy=(rects[n+1]+rects[n+3])*.5f
            val x=cx+(u-cx)/scale;val y=cy+(v-cy)/scale
            if(x>=rects[n] && x<=rects[n+2] && y>=rects[n+1] && y<=rects[n+3]) return x.coerceIn(0f,1f) to y.coerceIn(0f,1f)
        }
        return null
    }
    companion object {
        fun from(sizes:List<Pair<Int,Int>>,positions:List<Pair<Int,Int>>,spacing:Float):ScreenPresentation {
            if(sizes.isEmpty() || sizes.size!=positions.size) return ScreenPresentation(spacing=spacing)
            val left=positions.minOf {it.first};val top=positions.minOf {it.second}
            val width=sizes.indices.maxOf {positions[it].first+sizes[it].first}-left
            val height=sizes.indices.maxOf {positions[it].second+sizes[it].second}-top
            if(width<=0 || height<=0) return ScreenPresentation(spacing=spacing)
            val rects=sizes.indices.flatMap {i->
                val (x,y)=positions[i];val (w,h)=sizes[i]
                listOf((x-left).toFloat()/width,(y-top).toFloat()/height,(x+w-left).toFloat()/width,(y+h-top).toFloat()/height)
            }.toFloatArray()
            return ScreenPresentation(rects,spacing.coerceIn(0f,.15f))
        }
    }
}
