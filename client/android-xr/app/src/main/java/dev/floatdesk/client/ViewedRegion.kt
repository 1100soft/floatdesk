package dev.floatdesk.client

/** Conservative view-cell coverage, shared by flat/curved geometry; no changes to hit testing. */
internal fun viewedRegion(width:Float,height:Float,mesh:MonitorMesh?,horizontal:Float,vertical:Float,toView:(FloatArray)->FloatArray):FocusRect? {
    val columns=16;val rows=12
    val points=Array((columns+1)*(rows+1)){i->
        val flat=floatArrayOf((i%(columns+1)).toFloat()/columns*width-width*.5f,height*.5f-(i/(columns+1)).toFloat()/rows*height,0f)
        toView(mesh?.project(flat) ?: flat)
    }
    var left=1f;var top=1f;var right=0f;var bottom=0f
    val tx=kotlin.math.tan(horizontal.coerceIn(.1f,1.4f))*1.12f
    val ty=kotlin.math.tan(vertical.coerceIn(.1f,1.4f))*1.12f
    for(y in 0 until rows)for(x in 0 until columns){
        val corners=listOf(points[y*(columns+1)+x],points[y*(columns+1)+x+1],points[(y+1)*(columns+1)+x],points[(y+1)*(columns+1)+x+1])
        if(corners.all{it[2]>=-.01f})continue
        val front=corners.filter{it[2]<-.01f}
        // Keep cells crossing the eye plane rather than accidentally dropping a visible edge.
        val overlaps=front.size<4 || (front.minOf{it[0]/-it[2]}<=tx && front.maxOf{it[0]/-it[2]}>=-tx && front.minOf{it[1]/-it[2]}<=ty && front.maxOf{it[1]/-it[2]}>=-ty)
        if(overlaps){left=minOf(left,x.toFloat()/columns);right=maxOf(right,(x+1f)/columns);top=minOf(top,y.toFloat()/rows);bottom=maxOf(bottom,(y+1f)/rows)}
    }
    return if(right>left && bottom>top)FocusRect(left,top,right,bottom) else null
}
