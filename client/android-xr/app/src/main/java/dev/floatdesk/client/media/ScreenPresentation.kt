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
    /** Extend the nearest screen's affine spacing map past its edge for gaze-quality targeting. */
    fun extendedDesktopPoint(u:Float,v:Float):Pair<Float,Float>? {
        if(!u.isFinite() || !v.isFinite())return null
        val scale=1f-spacing.coerceIn(0f,.15f)
        var best=Float.POSITIVE_INFINITY;var result:Pair<Float,Float>?=null
        for(i in 0 until rects.size/4){
            val n=i*4;val cx=(rects[n]+rects[n+2])*.5f;val cy=(rects[n+1]+rects[n+3])*.5f
            val l=cx+(rects[n]-cx)*scale;val r=cx+(rects[n+2]-cx)*scale
            val t=cy+(rects[n+1]-cy)*scale;val b=cy+(rects[n+3]-cy)*scale
            val dx=u-u.coerceIn(l,r);val dy=v-v.coerceIn(t,b);val distance=dx*dx+dy*dy
            if(distance<best){best=distance;result=(cx+(u-cx)/scale) to (cy+(v-cy)/scale)}
        }
        return result
    }

    /** Invert visible spacing for a region, clipping against actual screens (including holes). */
    fun desktopBounds(view:dev.floatdesk.client.FocusRect):dev.floatdesk.client.FocusRect? {
        val pieces=mutableListOf<dev.floatdesk.client.FocusRect>()
        val scale=1f-spacing.coerceIn(0f,.15f)
        for(i in 0 until rects.size/4){
            val n=i*4;val cx=(rects[n]+rects[n+2])*.5f;val cy=(rects[n+1]+rects[n+3])*.5f
            val displayed=dev.floatdesk.client.FocusRect(cx+(rects[n]-cx)*scale,cy+(rects[n+1]-cy)*scale,cx+(rects[n+2]-cx)*scale,cy+(rects[n+3]-cy)*scale)
            val hit=displayed.intersect(view) ?: continue
            pieces.add(dev.floatdesk.client.FocusRect(cx+(hit.left-cx)/scale,cy+(hit.top-cy)/scale,cx+(hit.right-cx)/scale,cy+(hit.bottom-cy)/scale))
        }
        return if(pieces.isEmpty())null else dev.floatdesk.client.FocusRect(pieces.minOf{it.left}.coerceIn(0f,1f),pieces.minOf{it.top}.coerceIn(0f,1f),pieces.maxOf{it.right}.coerceIn(0f,1f),pieces.maxOf{it.bottom}.coerceIn(0f,1f))
    }
    fun displayBounds(source:dev.floatdesk.client.FocusRect):dev.floatdesk.client.FocusRect? {
        val pieces=mutableListOf<dev.floatdesk.client.FocusRect>()
        val scale=1f-spacing.coerceIn(0f,.15f)
        for(i in 0 until rects.size/4){
            val n=i*4;val r=dev.floatdesk.client.FocusRect(rects[n],rects[n+1],rects[n+2],rects[n+3])
            val hit=r.intersect(source) ?: continue
            pieces.add(dev.floatdesk.client.FocusRect(r.centerU+(hit.left-r.centerU)*scale,r.centerV+(hit.top-r.centerV)*scale,r.centerU+(hit.right-r.centerU)*scale,r.centerV+(hit.bottom-r.centerV)*scale))
        }
        return if(pieces.isEmpty())null else dev.floatdesk.client.FocusRect(pieces.minOf{it.left},pieces.minOf{it.top},pieces.maxOf{it.right},pieces.maxOf{it.bottom})
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
