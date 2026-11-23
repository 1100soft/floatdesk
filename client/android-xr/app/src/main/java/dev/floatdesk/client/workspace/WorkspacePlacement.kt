package dev.floatdesk.client.workspace

import dev.floatdesk.client.viewHalfAngles
import dev.floatdesk.client.focusViewHalfAngles
import dev.floatdesk.client.viewerInMonitor

/** UI panel compositing plus ordered physical planes; all values are in meters. */
internal object ViewLayers {
    // The nearest layer defaults to the headset's ~0.9 m accommodation distance. Layers step evenly
    // in diopters between the user's nearest and farthest distances so neighbouring planes keep
    // their depth order.
    const val DefaultNearestM=.9f
    const val DefaultFarthestM=1.2f
    const val MinNearestM=.6f
    const val MaxNearestM=4.7f
    const val MinSpanM=.3f
    const val MaxFarthestM=5f
    /** The palm menu is touched directly, so it stays at arm's length whatever the layers are. */
    const val PalmMenuDistanceM=.40f
    private const val Steps=4
    @Volatile var nearestM=DefaultNearestM; private set
    @Volatile var farthestM=DefaultFarthestM; private set
    fun clampNearest(value:Float)=(if(value.isFinite())value else DefaultNearestM).coerceIn(MinNearestM,MaxNearestM)
    fun clampFarthest(nearest:Float,value:Float)=(if(value.isFinite())value else DefaultFarthestM).coerceIn(clampNearest(nearest)+MinSpanM,MaxFarthestM)
    fun configure(nearest:Float,farthest:Float){val near=clampNearest(nearest);nearestM=near;farthestM=clampFarthest(near,farthest)}
    /** Layer [step] from 0 (nearest) to 4 (farthest), evenly spaced in diopters. */
    fun layer(step:Int,nearest:Float=nearestM,farthest:Float=farthestM):Float {
        val near=1f/nearest;val far=1f/farthest
        return 1f/(near+(far-near)*step.coerceIn(0,Steps)/Steps)
    }
    val DiagnosticsDistanceM get()=layer(0)
    val ControlsDistanceM get()=layer(1)
    val ShellDistanceM get()=layer(2)
    val MagnifierDistanceM get()=layer(3)
    val DesktopDistanceM get()=layer(4)
    /** Size factor that keeps a fixed-size object on layer [step] at its default apparent size. */
    fun sizeScale(step:Int)=layer(step)/layer(step,DefaultNearestM,DefaultFarthestM)
    fun popupDistance(owner:Float,level:Int=0):Float {
        val near=if(owner>ShellDistanceM)ShellDistanceM else DiagnosticsDistanceM
        return near+(owner-near)*Math.pow(.5,level.coerceAtMost(8)+1.0).toFloat()
    }
}

/** Without a restored room anchor, keep the saved arrangement relative to the startup viewer. */
internal fun WorkspaceFile.restoreViewer(viewer:Pose,unitsPerMeter:Float=referenceUnitsPerMeter ?: 1f):WorkspaceFile {
    val prior=viewerReference ?: Pose()
    if(anchorUuid!=null)return this
    val scale=referenceUnitsPerMeter?.let{unitsPerMeter/it}?.takeIf{it.isFinite() && it>0f} ?: 1f
    fun multiply(a:FloatArray,b:FloatArray)=floatArrayOf(a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2])
    val q=multiply(viewer.rotation,floatArrayOf(-prior.rotation[0],-prior.rotation[1],-prior.rotation[2],prior.rotation[3]))
    fun transform(p:Pose):Pose {
        val v=FloatArray(3){(p.position[it]-prior.position[it])*scale}
        val t=floatArrayOf(2*(q[1]*v[2]-q[2]*v[1]),2*(q[2]*v[0]-q[0]*v[2]),2*(q[0]*v[1]-q[1]*v[0]))
        val rotated=floatArrayOf(v[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],v[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2],v[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0])
        return Pose(FloatArray(3){viewer.position[it]+rotated[it]},multiply(q,p.rotation))
    }
    return copy(viewerReference=viewer,referenceUnitsPerMeter=unitsPerMeter,layoutOrigin=layoutOrigin?.let(::transform),
        layouts=layouts.mapValues {(_,screens)->screens.map {
            it.copy(pose=transform(it.pose),physicalWidthM=it.physicalWidthM*scale,physicalHeightM=it.physicalHeightM*scale)
        }.toMutableList()}.toMutableMap(),control=control.copy(pose=transform(control.pose)))
}

private fun inverseRotation(q:FloatArray)=floatArrayOf(-q[0],-q[1],-q[2],q[3])

private fun composeLayoutRotation(a:FloatArray,b:FloatArray)=floatArrayOf(
    a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],
    a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],
    a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],
    a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2])

private fun originTransform(from:Pose,to:Pose):(Pose)->Pose {
    val delta=composeLayoutRotation(to.rotation,inverseRotation(from.rotation))
    val inverseDelta=inverseRotation(delta)
    return {pose->
        val rotated=viewerInMonitor(pose.position,from.position,inverseDelta)
        Pose(FloatArray(3){to.position[it]+rotated[it]},composeLayoutRotation(delta,pose.rotation))
    }
}

/** Reset and explicit Follow carry the environment along with its origin. */
internal fun WorkspaceFile.moveLayoutOrigin(target:Pose):WorkspaceFile {
    val origin=layoutOrigin ?: layouts[activeLayout]?.firstOrNull()?.pose ?: return this
    val transform=originTransform(origin,target)
    val moved=layouts.mapValues{(_,screens)->screens.map{it.copy(pose=transform(it.pose))}.toMutableList()}.toMutableMap()
    return copy(layoutOrigin=target,layouts=moved,control=control.copy(pose=transform(control.pose)))
}

/** Dragging the axes changes coordinates while world-space object poses stay put. */
internal fun WorkspaceFile.rebaseLayoutOrigin(target:Pose,units:Float):WorkspaceFile {
    val origin=layoutOrigin ?: layouts[activeLayout]?.firstOrNull()?.pose ?: return this
    fun rebase(record:FixedMagnifierPlacement)=rebaseSupportingPlacement(record,origin,target,units)
    return copy(layoutOrigin=target,fixedMagnifiers=fixedMagnifiers.map(::rebase),
        systemPanels=systemPanels.map(::rebase),referencePlacement=referencePlacement?.let(::rebase))
}

internal fun rebaseSupportingPlacement(record:FixedMagnifierPlacement,old:Pose,next:Pose,units:Float):FixedMagnifierPlacement {
    val toWorld=originTransform(Pose(),old)
    val toLocal=originTransform(next,Pose())
    val scaled=record.pose.copy(position=FloatArray(3){record.pose.position[it]*units})
    val local=toLocal(toWorld(scaled))
    return record.copy(pose=local.copy(position=FloatArray(3){local.position[it]/units}))
}

/** Fit the complete desktop into a symmetric, inset portion of the current camera view. */
internal fun fitDesktopInView(screens:List<MonitorPlacement>,viewer:Pose,horizontalHalfAngle:Float,
                             verticalHalfAngle:Float,unitsPerMeter:Float,spherical:Boolean,centralView:Boolean=false,distanceM:Float=ViewLayers.DesktopDistanceM):MutableList<MonitorPlacement> {
    if(screens.isEmpty())return mutableListOf()
    val units=unitsPerMeter.takeIf{it.isFinite() && it>0f} ?: 1f
    val distance=distanceM*units
    val bounds=if(centralView)focusViewHalfAngles(horizontalHalfAngle,verticalHalfAngle)else viewHalfAngles(horizontalHalfAngle,verticalHalfAngle)
    val horizontal=bounds.first;val vertical=bounds.second
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
        physicalWidthM=width,physicalHeightM=height,viewCenterU=u,viewCenterV=v,freePose=false)
}


/**
 * Hold the desktop at a fixed spherical focus radius or flat-plane depth. Scaling about the eye
 * preserves angular location/size (for explicit placement); a rigid push along the view ray keeps
 * physical size, so head motion changes parallax but never depth.
 */
internal fun fixedDesktopDepth(screen:MonitorPlacement,viewer:Pose,unitsPerMeter:Float,spherical:Boolean=true,preserveAngularSize:Boolean=true,distanceM:Float=ViewLayers.DesktopDistanceM):MonitorPlacement {
    val units=unitsPerMeter.takeIf{it.isFinite() && it>0f} ?: 1f
    val x=(screen.viewCenterU-.5f)*screen.physicalWidthM
    val y=(.5f-screen.viewCenterV)*screen.physicalHeightM
    val q=screen.pose.rotation
    val tx=-2f*q[2]*y;val ty=2f*q[2]*x;val tz=2f*(q[0]*y-q[1]*x)
    val offset=floatArrayOf(x+q[3]*tx+q[1]*tz-q[2]*ty,y+q[3]*ty+q[2]*tx-q[0]*tz,q[3]*tz+q[0]*ty-q[1]*tx)
    val d=FloatArray(3){screen.pose.position[it]+offset[it]-viewer.position[it]}
    val radial=kotlin.math.sqrt(d.sumOf{(it*it).toDouble()}).toFloat()
    // A tilted, enlarged plane can enter the HUD even with its focus point far away.
    val normal=floatArrayOf(2f*(q[0]*q[2]+q[3]*q[1]),2f*(q[1]*q[2]-q[3]*q[0]),1f-2f*(q[0]*q[0]+q[1]*q[1]))
    val distance=if(spherical)radial else kotlin.math.abs((0..2).sumOf{(d[it]*normal[it]).toDouble()}).toFloat()
    if(!distance.isFinite() || distance<.05f*units)return screen
    val target=distanceM*units
    if(kotlin.math.abs(distance-target)<.002f*units)return screen
    if(!preserveAngularSize) {
        val axis=if(spherical)FloatArray(3){d[it]/radial} else {
            val facing=if((0..2).sumOf{(d[it]*normal[it]).toDouble()}<0.0)-1f else 1f
            FloatArray(3){normal[it]*facing}
        }
        return screen.copy(pose=Pose(FloatArray(3){screen.pose.position[it]+axis[it]*(target-distance)},screen.pose.rotation))
    }
    val scale=target/distance
    return screen.copy(pose=Pose(FloatArray(3){viewer.position[it]+(screen.pose.position[it]-viewer.position[it])*scale},screen.pose.rotation),
        physicalWidthM=screen.physicalWidthM*scale,physicalHeightM=screen.physicalHeightM*scale)
}

/**
 * Move every saved object onto the layers for new viewing distances without changing its direction
 * or apparent size. Magnifier records use the layout origin, so their offsets follow the workspace's
 * scale about the eye and their sizes follow their own layer.
 */
internal fun WorkspaceFile.withViewingDistances(nearest:Float,farthest:Float,viewer:Pose?,unitsPerMeter:Float):WorkspaceFile {
    val near=ViewLayers.clampNearest(nearest);val far=ViewLayers.clampFarthest(near,farthest)
    fun ratio(step:Int)=ViewLayers.layer(step,near,far)/ViewLayers.layer(step,nearestDistanceM,farthestDistanceM)
    val before=layouts[activeLayout]?.firstOrNull()
    val nextLayouts=if(viewer==null)layouts else layouts.mapValues{(_,screens)->
        screens.map{fixedDesktopDepth(it,viewer,unitsPerMeter,true,true,ViewLayers.layer(4,near,far))}.toMutableList()
    }.toMutableMap()
    val after=nextLayouts[activeLayout]?.firstOrNull()
    val desktopScale=if(before!=null && after!=null && before.physicalWidthM>0f)after.physicalWidthM/before.physicalWidthM else 1f
    fun rescale(records:List<FixedMagnifierPlacement>,size:Float)=records.map{
        it.copy(pose=Pose(FloatArray(3){i->it.pose.position[i]*desktopScale},it.pose.rotation),widthM=it.widthM*size,heightM=it.heightM*size)
    }
    return copy(layouts=nextLayouts,fixedMagnifiers=rescale(fixedMagnifiers,ratio(3)),systemPanels=rescale(systemPanels,ratio(2)),
        referencePlacement=rescale(listOfNotNull(referencePlacement),ratio(3)).firstOrNull(),
        nearestDistanceM=near,farthestDistanceM=far)
}
