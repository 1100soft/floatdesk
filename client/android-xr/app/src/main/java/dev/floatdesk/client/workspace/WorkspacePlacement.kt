package dev.floatdesk.client.workspace

/** Without a restored room anchor, keep the saved arrangement relative to the startup viewer. */
internal fun WorkspaceFile.restoreViewer(viewer:Pose):WorkspaceFile {
    val prior=viewerReference ?: Pose()
    if(anchorUuid!=null)return this
    fun multiply(a:FloatArray,b:FloatArray)=floatArrayOf(a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2])
    val q=multiply(viewer.rotation,floatArrayOf(-prior.rotation[0],-prior.rotation[1],-prior.rotation[2],prior.rotation[3]))
    fun transform(p:Pose):Pose {
        val v=FloatArray(3){p.position[it]-prior.position[it]}
        val t=floatArrayOf(2*(q[1]*v[2]-q[2]*v[1]),2*(q[2]*v[0]-q[0]*v[2]),2*(q[0]*v[1]-q[1]*v[0]))
        val rotated=floatArrayOf(v[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],v[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2],v[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0])
        return Pose(FloatArray(3){viewer.position[it]+rotated[it]},multiply(q,p.rotation))
    }
    return copy(viewerReference=viewer,layouts=layouts.mapValues {(_,screens)->screens.map {it.copy(pose=transform(it.pose))}.toMutableList()}.toMutableMap(),control=control.copy(pose=transform(control.pose)))
}

/** Fit the complete desktop into a symmetric, inset portion of the current camera view. */
internal fun fitDesktopInView(screens:List<MonitorPlacement>,viewer:Pose,horizontalHalfAngle:Float,
                             verticalHalfAngle:Float,unitsPerMeter:Float,spherical:Boolean):MutableList<MonitorPlacement> {
    if(screens.isEmpty())return mutableListOf()
    val units=unitsPerMeter.takeIf{it.isFinite() && it>0f} ?: 1f
    val distance=1.2f*units
    fun angle(value:Float,fallback:Float)=((value.takeIf{it.isFinite() && it>.05f} ?: fallback)*.85f).coerceAtMost(1.2f)
    val horizontal=angle(horizontalHalfAngle,Math.toRadians(35.0).toFloat())
    val vertical=angle(verticalHalfAngle,Math.toRadians(30.0).toFloat())
    val left=screens.minOf{it.pose.position[0]-it.physicalWidthM*.5f}
    val right=screens.maxOf{it.pose.position[0]+it.physicalWidthM*.5f}
    val bottom=screens.minOf{it.pose.position[1]-it.physicalHeightM*.5f}
    val top=screens.maxOf{it.pose.position[1]+it.physicalHeightM*.5f}
    val width=right-left;val height=top-bottom
    var scale=minOf(2f*distance*kotlin.math.tan(horizontal)/width,2f*distance*kotlin.math.tan(vertical)/height)
    if(spherical) {
        // Check the corners, not just the central horizontal/vertical arcs.
        var low=0f;var high=scale
        repeat(28) {
            val candidate=(low+high)*.5f
            val x=width*candidate/(2f*distance);val y=height*candidate/(2f*distance)
            val a=kotlin.math.sqrt(x*x+y*y)
            val sinc=if(a<1e-6f)1f else kotlin.math.sin(a)/a
            val fits=a<1.45f && kotlin.math.atan2(sinc*x,kotlin.math.cos(a))<=horizontal && kotlin.math.atan2(sinc*y,kotlin.math.cos(a))<=vertical
            if(fits)low=candidate else high=candidate
        }
        scale=low
    }
    val q=viewer.rotation
    fun rotate(v:FloatArray):FloatArray {
        val t=floatArrayOf(2*(q[1]*v[2]-q[2]*v[1]),2*(q[2]*v[0]-q[0]*v[2]),2*(q[0]*v[1]-q[1]*v[0]))
        return floatArrayOf(v[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],v[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2],v[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0])
    }
    return screens.map {screen ->
        val offset=rotate(floatArrayOf((screen.pose.position[0]-(left+right)*.5f)*scale,(screen.pose.position[1]-(bottom+top)*.5f)*scale,-distance))
        screen.copy(pose=Pose(FloatArray(3){viewer.position[it]+offset[it]},q.copyOf()),
            physicalWidthM=screen.physicalWidthM*scale,physicalHeightM=screen.physicalHeightM*scale)
    }.toMutableList()
}
