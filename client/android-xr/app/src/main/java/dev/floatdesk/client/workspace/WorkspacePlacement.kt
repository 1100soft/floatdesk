package dev.floatdesk.client.workspace

import dev.floatdesk.client.focusViewHalfAngles

/** UI panel compositing plus ordered physical planes; all values are in meters. */
internal object ViewLayers {
    const val DesktopDistanceM=1.2f
    const val ControlsDistanceM=.40f
    const val DiagnosticsDistanceM=.35f
}

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
                             verticalHalfAngle:Float,unitsPerMeter:Float,spherical:Boolean,centralView:Boolean=false):MutableList<MonitorPlacement> {
    if(screens.isEmpty())return mutableListOf()
    val units=unitsPerMeter.takeIf{it.isFinite() && it>0f} ?: 1f
    val distance=ViewLayers.DesktopDistanceM*units
    fun angle(value:Float,fallback:Float)=((value.takeIf{it.isFinite() && it>.05f} ?: fallback)*.85f).coerceAtMost(1.2f)
    val focused=focusViewHalfAngles(horizontalHalfAngle,verticalHalfAngle)
    val horizontal=if(centralView)focused.first else angle(horizontalHalfAngle,Math.toRadians(35.0).toFloat())
    val vertical=if(centralView)focused.second else angle(verticalHalfAngle,Math.toRadians(30.0).toFloat())
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

/** Reframe a displayed source region using Reset's fit policy, preserving the whole desktop. */
internal fun focusDesktopRegion(screen:MonitorPlacement,rect:FloatArray,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spherical:Boolean):MonitorPlacement {
    require(rect.size==4 && rect.all{it.isFinite()} && rect[2]>rect[0] && rect[3]>rect[1])
    val u=(rect[0]+rect[2])*.5f;val v=(rect[1]+rect[3])*.5f
    val region=screen.copy(pose=Pose(),physicalWidthM=screen.physicalWidthM*(rect[2]-rect[0]),physicalHeightM=screen.physicalHeightM*(rect[3]-rect[1]))
    val fitted=fitDesktopInView(listOf(region),viewer,horizontal,vertical,units,spherical,centralView=true).single()
    val width=fitted.physicalWidthM/(rect[2]-rect[0]);val height=fitted.physicalHeightM/(rect[3]-rect[1])
    val x=(u-.5f)*width;val y=(.5f-v)*height
    val q=viewer.rotation
    val tx=-2f*q[2]*y;val ty=2f*q[2]*x;val tz=2f*(q[0]*y-q[1]*x)
    val offset=floatArrayOf(x+q[3]*tx+q[1]*tz-q[2]*ty,y+q[3]*ty+q[2]*tx-q[0]*tz,q[3]*tz+q[0]*ty-q[1]*tx)
    return screen.copy(pose=Pose(FloatArray(3){fitted.pose.position[it]-offset[it]},q.copyOf()),
        physicalWidthM=width,physicalHeightM=height,viewCenterU=u,viewCenterV=v)
}


/** Preserve angular location/size while keeping the desktop's focus point at a fixed radius. */
internal fun fixedDesktopDepth(screen:MonitorPlacement,viewer:Pose,unitsPerMeter:Float):MonitorPlacement {
    val units=unitsPerMeter.takeIf{it.isFinite() && it>0f} ?: 1f
    val x=(screen.viewCenterU-.5f)*screen.physicalWidthM
    val y=(.5f-screen.viewCenterV)*screen.physicalHeightM
    val q=screen.pose.rotation
    val tx=-2f*q[2]*y;val ty=2f*q[2]*x;val tz=2f*(q[0]*y-q[1]*x)
    val offset=floatArrayOf(x+q[3]*tx+q[1]*tz-q[2]*ty,y+q[3]*ty+q[2]*tx-q[0]*tz,q[3]*tz+q[0]*ty-q[1]*tx)
    val d=FloatArray(3){screen.pose.position[it]+offset[it]-viewer.position[it]}
    val distance=kotlin.math.sqrt(d.sumOf{(it*it).toDouble()}).toFloat()
    if(!distance.isFinite() || distance<.05f*units)return screen
    val target=ViewLayers.DesktopDistanceM*units
    if(kotlin.math.abs(distance-target)<.002f*units)return screen
    val scale=target/distance
    return screen.copy(pose=Pose(FloatArray(3){viewer.position[it]+(screen.pose.position[it]-viewer.position[it])*scale},screen.pose.rotation),
        physicalWidthM=screen.physicalWidthM*scale,physicalHeightM=screen.physicalHeightM*scale)
}
