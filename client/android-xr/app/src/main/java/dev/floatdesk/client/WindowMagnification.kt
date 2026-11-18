package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.ViewLayers
import dev.floatdesk.client.workspace.FixedMagnifierPlacement
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.net.WorkspaceInfo
import dev.floatdesk.client.net.DesktopWindow
import dev.floatdesk.client.workspace.deskLayout
import dev.floatdesk.client.workspace.fitDesktopInView
import kotlin.math.*

/** Only valid, sustained gaze can switch/dismiss; blinking never starts departure. */
internal class WindowMagnification(private val dwellMs:Long=600,private val travelMs:Long=900){
    var active:Long?=null;private set
    var pinned=false;private set
    private var candidate:Long?=null;private var since=0L;private var entered=0L
    private var readyAt=0L;private var departed=0L
    fun refresh(now:Long){entered=now;readyAt=0;departed=0;candidate=null}
    fun togglePin(){if(active!=null){pinned=!pinned;departed=0;candidate=null}}
    fun dismiss(now:Long){active=null;pinned=false;candidate=null;readyAt=0;departed=0}
    fun update(now:Long,enabled:Boolean,blocked:Boolean,target:Long?,inside:Boolean,ready:Boolean,hold:Boolean=false,validGaze:Boolean=true,retain:Boolean=false):Long?{
        if(!enabled || blocked){dismiss(now);return null}
        if(active!=null && !ready){if(now-entered>4000)dismiss(now);return active}
        if(active!=null && readyAt==0L)readyAt=now
        if(pinned || hold || !validGaze || active!=null && now-readyAt<travelMs){departed=0;candidate=null;return active}
        if(active!=null && inside){departed=0;candidate=null;return active}
        if(target!=null && target!=active){
            if(candidate!=target){candidate=target;since=now}
            if(now-since>=dwellMs){active=target;entered=now;readyAt=0;departed=0;candidate=null;return active}
        }else candidate=null
        if(active!=null){
            if(departed==0L)departed=now
            if(!retain && now-departed>=1000)dismiss(now)
        }
        return active
    }
}
/** Native UP can omit HitInfo; preserve the last contact, but never an exited/cancelled tap. */
internal class NativeSurfaceTap {
    private val down=mutableMapOf<String,Pair<Float,Float>>()
    private val contacts=mutableMapOf<String,Pair<Float,Float>>()
    fun clear(){down.clear();contacts.clear()}
    fun update(side:String,action:String,point:Pair<Float,Float>?):Pair<Float,Float>? {
        if(point!=null)contacts[side]=point
        when(action){
            "DOWN" -> if(point!=null)down[side]=point
            "UP" -> down.remove(side)?.let{start->
                val end=point ?: contacts[side] ?: start
                if(hypot((end.first-start.first).toDouble(),(end.second-start.second).toDouble())<.08)return end
            }
            "CANCEL","HOVER_EXIT" -> {down.remove(side);contacts.remove(side)}
        }
        return null
    }
}
internal const val MagnifierDistanceM=ViewLayers.MagnifierDistanceM
internal data class MagnifiedWindow(val id:Long,val pose:Pose,val width:Float,val height:Float,val sourceWidth:Int,val sourceHeight:Int,val spherical:Boolean=false,val mesh:MonitorMesh?=null,val units:Float=1f,val headOffset:FloatArray=floatArrayOf(0f,0f,-1f),val sizeScale:Float=1f,val fixed:Boolean=false,val desktopRotation:FloatArray?=null,val requestedPosition:FloatArray?=null,val headRotation:FloatArray?=null,val layerDistanceM:Float=MagnifierDistanceM)
internal fun magnifiedWindow(id:Long,width:Int,height:Int,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spherical:Boolean=false,previous:MagnifiedWindow?=null,layerDistanceM:Float=MagnifierDistanceM):MagnifiedWindow{
    val source=deskLayout(listOf(Triple("Window",width,height)))
    val fitted=fitDesktopInView(source,viewer,horizontal,vertical,units,spherical,centralView=true,distanceM=layerDistanceM).single()
    val scale=previous?.sizeScale ?: 1f
    val window=MagnifiedWindow(id,fitted.pose,fitted.physicalWidthM*scale,fitted.physicalHeightM*scale,width,height,spherical,
        units=units,headOffset=previous?.headOffset ?: floatArrayOf(0f,0f,-1f),sizeScale=scale,headRotation=previous?.headRotation,layerDistanceM=layerDistanceM)
    return magnifierFrame(window,window,viewer,units,true)
}
/** Same exponential-map projection as the desktop, with this panel's own fixed radial distance. */
internal fun magnifierGeometry(window:MagnifiedWindow,viewer:Pose,units:Float,proposed:FloatArray?=null,updateMesh:Boolean=true):MagnifiedWindow{
    // Perception-space matrices can jitter by a few ULPs. Do not turn that into native mesh uploads.
    val stableUnits=if(abs(units/window.units-1f)<.001f)window.units else units
    val radius=window.layerDistanceM*stableUnits
    val offset=if(proposed==null)window.headOffset else {
        val local=viewerInMonitor(proposed,viewer.position,viewer.rotation)
        val length=sqrt(local.sumOf{(it*it).toDouble()}).toFloat()
        if(length>.00001f)FloatArray(3){local[it]/length} else window.headOffset
    }
    val center=transformPoint(viewer,FloatArray(3){offset[it]*radius})
    val facing=if(window.spherical || proposed!=null)facingMagnifierRotation(center,viewer,viewer.rotation) else null
    val headRotation=if(proposed!=null) {
        val q=viewer.rotation
        multiplyQuaternion(floatArrayOf(-q[0],-q[1],-q[2],q[3]),facing!!)
    } else window.headRotation ?: floatArrayOf(0f,0f,0f,1f)
    val pose=Pose(center,if(window.spherical)facing!! else multiplyQuaternion(viewer.rotation,headRotation))
    val scale=stableUnits/window.units
    val width=window.width*scale;val height=window.height*scale
    val mesh=projectMagnifier(window,viewer,pose,width,height,updateMesh,.000001f,.00001f)
    return window.copy(pose=pose,width=width,height=height,mesh=mesh,units=stableUnits,headOffset=offset,headRotation=headRotation)

}
/** Projection mode changes are immediate even when ordinary mesh updates are deferred. */
internal fun magnifierFrame(window:MagnifiedWindow, previous:MagnifiedWindow, viewer:Pose, units:Float, updateGeometry:Boolean):MagnifiedWindow {
    val changed=window.spherical!=previous.spherical || window.width!=previous.width || window.height!=previous.height
    val rebuild=updateGeometry || changed || window.spherical && previous.mesh==null
    val source=window.copy(mesh=if(changed)null else previous.mesh)
    return if(window.fixed)fixedMagnifierGeometry(source,viewer,updateMesh=rebuild)
        else magnifierGeometry(source,viewer,units,updateMesh=rebuild)
}

/** A fixed 1.5 degree tolerance around the actual rendered boundary, independent of window size. */
internal fun magnifierContainsGaze(window:MagnifiedWindow,origin:FloatArray,direction:FloatArray):Boolean{
    if(magnifierPoint(window,origin,direction)!=null)return true
    val o=viewerInMonitor(origin,window.pose.position,window.pose.rotation)
    val end=viewerInMonitor(FloatArray(3){origin[it]+direction[it]},window.pose.position,window.pose.rotation)
    fun unit(v:FloatArray):FloatArray{val n=sqrt(v.sumOf{(it*it).toDouble()}).toFloat();return FloatArray(3){v[it]/n.coerceAtLeast(.000001f)}}
    fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf{(a[it]*b[it]).toDouble()}.toFloat()
    val ray=unit(FloatArray(3){end[it]-o[it]})
    val border=window.mesh?.outline ?: listOf(floatArrayOf(-window.width/2,window.height/2,0f),floatArrayOf(window.width/2,window.height/2,0f),floatArrayOf(window.width/2,-window.height/2,0f),floatArrayOf(-window.width/2,-window.height/2,0f))
    val points=border.map{p->unit(FloatArray(3){p[it]-o[it]})}
    val cosine=cos(Math.toRadians(1.5)).toFloat()
    return points.indices.any{i->
        val a=points[i];val b=points[(i+1)%points.size];val edge=FloatArray(3){b[it]-a[it]}
        val t=(dot(FloatArray(3){ray[it]-a[it]},edge)/dot(edge,edge).coerceAtLeast(.00000001f)).coerceIn(0f,1f)
        dot(ray,unit(FloatArray(3){a[it]+edge[it]*t}))>=cosine
    }
}
internal fun magnifierPoint(window:MagnifiedWindow,origin:FloatArray,direction:FloatArray,margin:Float=0f):Pair<Float,Float>?{
    val o=viewerInMonitor(origin,window.pose.position,window.pose.rotation)
    val end=viewerInMonitor(FloatArray(3){origin[it]+direction[it]},window.pose.position,window.pose.rotation)
    val d=FloatArray(3){end[it]-o[it]};if(abs(d[2])<1e-6f)return null
    if(window.mesh!=null){
        val hit=monitorRayHit(o,d,window.width,window.height,window.mesh) ?: return null
        val flat=window.mesh.unproject(hit) ?: return null
        return flat[0]/window.width+.5f to (.5f-flat[1]/window.height)
    }
    val t=-o[2]/d[2];if(t<=0f)return null
    val u=(o[0]+t*d[0])/window.width+.5f;val v=.5f-(o[1]+t*d[1])/window.height
    return (u to v).takeIf{u in -margin..1f+margin && v in -margin..1f+margin}
}

/** Resize belongs to this activation only; opening another window creates sizeScale=1 again. */
internal fun resizedMagnifier(window:MagnifiedWindow,factor:Float):MagnifiedWindow {
    if(!factor.isFinite() || factor<=0f)return window
    return window.copy(width=window.width*factor,height=window.height*factor,sizeScale=window.sizeScale*factor,mesh=null)
}
internal fun magnifierPinOutline(window:MagnifiedWindow):List<FloatArray> {
    val pad=.005f*window.units
    return window.mesh?.let {mesh->MonitorMesh(window.width+2*pad,window.height+2*pad,mesh.eye,columns=32,rows=20,focus=mesh.focus,center=mesh.center).outline} ?: listOf(
        floatArrayOf(-window.width/2-pad,window.height/2+pad,0f),floatArrayOf(window.width/2+pad,window.height/2+pad,0f),
        floatArrayOf(window.width/2+pad,-window.height/2-pad,0f),floatArrayOf(-window.width/2-pad,-window.height/2-pad,0f))
}

/** Keep a large, fully visible and unobscured window in its desktop position. */
internal fun inPlaceWindowRect(info:WorkspaceInfo,id:Long,desktop:MonitorPlacement,mesh:MonitorMesh?,viewer:Pose,horizontal:Float,vertical:Float,units:Float,spacing:Float):FocusRect? {
    val index=info.windows.indexOfFirst{it.id==id};if(index<0)return null
    val window=info.windows[index];val bounds=info.bounds() ?: return null
    fun overlap(a:DesktopWindow,b:DesktopWindow)=a.x<b.x+b.width && b.x<a.x+a.width && a.y<b.y+b.height && b.y<a.y+a.height
    if(info.windows.take(index).any{overlap(it,window)})return null
    val rect=FocusRect((window.x-bounds[0]).toFloat()/bounds[2],(window.y-bounds[1]).toFloat()/bounds[3],(window.x+window.width-bounds[0]).toFloat()/bounds[2],(window.y+window.height-bounds[1]).toFloat()/bounds[3])
    if(rect.left<0f || rect.top<0f || rect.right>1f || rect.bottom>1f)return null
    val covered=info.screens.sumOf{s->(minOf(s.x+s.width,window.x+window.width)-maxOf(s.x,window.x)).coerceAtLeast(0).toLong()*(minOf(s.y+s.height,window.y+window.height)-maxOf(s.y,window.y)).coerceAtLeast(0)}
    if(covered<window.width.toLong()*window.height)return null
    var left=Float.POSITIVE_INFINITY;var right=Float.NEGATIVE_INFINITY;var top=left;var bottom=right
    for(piece in info.visibleRects(rect,spacing))for(edge in 0..3)for(step in 0..8){
        val t=step/8f
        val u=when(edge){0->piece.left+t*(piece.right-piece.left);1->piece.right;2->piece.right-t*(piece.right-piece.left);else->piece.left}
        val v=when(edge){0->piece.top;1->piece.top+t*(piece.bottom-piece.top);2->piece.bottom;else->piece.bottom-t*(piece.bottom-piece.top)}
        val flat=floatArrayOf((u-.5f)*desktop.physicalWidthM,(.5f-v)*desktop.physicalHeightM,0f)
        val point=transformPoint(desktop.pose,mesh?.project(flat,onTriangles=true) ?: flat)
        val camera=viewerInMonitor(point,viewer.position,viewer.rotation)
        if(camera[2]>=-.01f)return null
        val x=atan2(camera[0],-camera[2]);val y=atan2(camera[1],-camera[2])
        if(abs(x)>horizontal*.95f || abs(y)>vertical*.95f)return null
        left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
    }
    val standard=magnifiedWindow(id,window.width,window.height,viewer,horizontal,vertical,units,mesh!=null)
    val desired=standard.mesh?.outline ?: listOf(floatArrayOf(-standard.width/2,standard.height/2,0f),floatArrayOf(standard.width/2,-standard.height/2,0f))
    val angles=desired.map{viewerInMonitor(transformPoint(standard.pose,it),viewer.position,viewer.rotation)}
    val width=angles.maxOf{atan2(it[0],-it[2])}-angles.minOf{atan2(it[0],-it[2])}
    val height=angles.maxOf{atan2(it[1],-it[2])}-angles.minOf{atan2(it[1],-it[2])}
    return rect.takeIf{right-left>=width*.99f && bottom-top>=height*.99f}
}


internal fun saveFixedMagnifier(id:String,window:MagnifiedWindow,desktop:MonitorPlacement,units:Float):FixedMagnifierPlacement {
    val local=viewerInMonitor(window.pose.position,desktop.pose.position,desktop.pose.rotation)
    val q=desktop.pose.rotation
    val rotation=multiplyQuaternion(floatArrayOf(-q[0],-q[1],-q[2],q[3]),window.pose.rotation)
    return FixedMagnifierPlacement(id,Pose(FloatArray(3){local[it]/units},rotation),window.width/units,window.height/units)
}
internal fun fixedMagnifier(record:FixedMagnifierPlacement,desktop:MonitorPlacement,source:DesktopWindow?,viewer:Pose,units:Float,spherical:Boolean,previous:MagnifiedWindow?=null,layerDistanceM:Float=MagnifierDistanceM):MagnifiedWindow{
    val pose=Pose(transformPoint(desktop.pose,FloatArray(3){record.pose.position[it]*units}),multiplyQuaternion(desktop.pose.rotation,record.pose.rotation))
    val window=MagnifiedWindow(source?.id ?: 0,pose,record.widthM*units,record.heightM*units,source?.width ?: 1280,source?.height ?: 800,
        spherical,units=units,fixed=true,desktopRotation=desktop.pose.rotation,requestedPosition=pose.position,layerDistanceM=layerDistanceM)
    return magnifierFrame(window,previous ?: window,viewer,units,true)
}

internal fun fixedMagnifierGeometry(window:MagnifiedWindow,viewer:Pose,proposed:FloatArray?=null,updateMesh:Boolean=true):MagnifiedWindow{
    val requested=proposed ?: window.requestedPosition ?: window.pose.position
    fun unit(v:FloatArray):FloatArray{val n=sqrt(v.sumOf{(it*it).toDouble()}).toFloat().coerceAtLeast(.00001f);return FloatArray(3){v[it]/n}}
    val normal=unit(FloatArray(3){viewer.position[it]-requested[it]})
    val center=FloatArray(3){viewer.position[it]-normal[it]*window.layerDistanceM*window.units}
    val rotation=if(window.spherical || proposed!=null)facingMagnifierRotation(center,viewer,window.desktopRotation ?: window.pose.rotation) else window.pose.rotation
    val pose=Pose(center,rotation)
    val mesh=projectMagnifier(window,viewer,pose,window.width,window.height,updateMesh,.003f,.003f)
    return window.copy(pose=pose,mesh=mesh,requestedPosition=requested)
}
internal fun magnifierContentPoint(window:MagnifiedWindow,point:Pair<Float,Float>):Pair<Float,Float>{
    val source=window.sourceWidth.toFloat()/window.sourceHeight.coerceAtLeast(1);val pane=window.width/window.height
    val x=minOf(1f,source/pane);val y=minOf(1f,pane/source)
    return (((point.first-.5f)/x+.5f).coerceIn(0f,1f) to ((point.second-.5f)/y+.5f).coerceIn(0f,1f))
}

internal fun surfaceRayDistance(pose:Pose,width:Float,height:Float,mesh:MonitorMesh?,origin:FloatArray,direction:FloatArray):Float?{
    val o=viewerInMonitor(origin,pose.position,pose.rotation)
    val end=viewerInMonitor(FloatArray(3){origin[it]+direction[it]},pose.position,pose.rotation)
    val hit=monitorRayHit(o,FloatArray(3){end[it]-o[it]},width,height,mesh) ?: return null
    return sqrt((0..2).sumOf{val d=hit[it]-o[it];(d*d).toDouble()}).toFloat()
}

/** Preserve every video triangle and add an exterior frame ring; content is never inset/covered. */
internal fun framedMagnifierGeometry(window:MagnifiedWindow):VideoGeometry {
    val mesh=window.mesh ?: return clippedVideoGeometry(window.width+.01f*window.units,window.height+.01f*window.units,null,floatArrayOf(0f,0f,1f,1f),0f)
    val pad=.005f*window.units;val width=window.width;val height=window.height
    val expanded=MonitorMesh(width+2*pad,height+2*pad,mesh.eye,columns=mesh.columns,rows=mesh.rows,focus=mesh.focus,center=mesh.center,planar=mesh.planar)
    val perimeter=buildList{for(c in 0..mesh.columns)add(c);for(r in 1..mesh.rows)add(r*(mesh.columns+1)+mesh.columns);for(c in mesh.columns-1 downTo 0)add(mesh.rows*(mesh.columns+1)+c);for(r in mesh.rows-1 downTo 1)add(r*(mesh.columns+1))}
    val positions=mesh.positions.toMutableList();val uv=ArrayList<Float>();val indices=mesh.indices.toMutableList()
    fun tex(u:Float,v:Float){uv.add((pad+u*width)/(width+2*pad));uv.add(1f-(pad+v*height)/(height+2*pad))}
    for(i in 0 until mesh.positions.size/3)tex(mesh.texCoords[i*2],mesh.texCoords[i*2+1])
    val base=mesh.positions.size/3
    for(i in perimeter){val u=mesh.texCoords[i*2];val v=mesh.texCoords[i*2+1]
        val x=(u-.5f)*width+if(u==0f)-pad else if(u==1f)pad else 0f
        val y=(.5f-v)*height+if(v==0f)pad else if(v==1f)-pad else 0f
        positions.addAll(expanded.project(floatArrayOf(x,y,0f)).toList());tex(x/width+.5f,.5f-y/height)
    }
    for(j in perimeter.indices){val next=(j+1)%perimeter.size;val a=perimeter[j];val b=perimeter[next];val c=base+j;val d=base+next;indices.addAll(listOf(a,b,c,c,b,d))}
    return VideoGeometry(positions.toFloatArray(),uv.toFloatArray(),indices.toIntArray())
}

/** Orthonormal column basis, including rotations beyond 180 degrees. */
internal fun basisRotation(x:FloatArray,y:FloatArray,z:FloatArray):FloatArray{
    val trace=x[0]+y[1]+z[2]
    return if(trace>0f){val s=sqrt(trace+1f)*2;floatArrayOf((y[2]-z[1])/s,(z[0]-x[2])/s,(x[1]-y[0])/s,s/4)}
    else if(x[0]>y[1] && x[0]>z[2]){val s=sqrt(1+x[0]-y[1]-z[2])*2;floatArrayOf(s/4,(y[0]+x[1])/s,(z[0]+x[2])/s,(y[2]-z[1])/s)}
    else if(y[1]>z[2]){val s=sqrt(1+y[1]-x[0]-z[2])*2;floatArrayOf((y[0]+x[1])/s,s/4,(z[1]+y[2])/s,(z[0]-x[2])/s)}
    else{val s=sqrt(1+z[2]-x[0]-y[1])*2;floatArrayOf((z[0]+x[2])/s,(z[1]+y[2])/s,s/4,(x[1]-y[0])/s)}
}

internal fun facingMagnifierRotation(center:FloatArray,viewer:Pose,horizontalRotation:FloatArray):FloatArray{
    val normal=normalize3(FloatArray(3){viewer.position[it]-center[it]})
    var vertical=rotateVector(horizontalRotation,0f,1f,0f)
    if(abs((0..2).sumOf{(vertical[it]*normal[it]).toDouble()})>.999)vertical=rotateVector(horizontalRotation,0f,0f,1f)
    val right=normalize3(cross3(vertical,normal));val up=normalize3(cross3(normal,right))
    return basisRotation(right,up,normal)
}


/** Captured native DOWN/MOVE/UP rays drag the actual pane without a Movable affordance. */
internal class MagnifierRayDrag{
    private var start:MagnifiedWindow?=null
    private var from=floatArrayOf();private var offset=floatArrayOf();private var rayDistance=0f
    private var dragging=false
    private var handStart:FloatArray?=null
    private var contactStart=floatArrayOf()
    var gain=1f;private set
    fun clear(){start=null;handStart=null;dragging=false}
    fun begin(window:MagnifiedWindow,viewer:Pose,origin:FloatArray,direction:FloatArray,contact:FloatArray,hand:FloatArray?=null){
        start=window;dragging=false;offset=FloatArray(3){window.pose.position[it]-viewer.position[it]}
        val d=normalize3(direction);rayDistance=(0..2).sumOf{((contact[it]-origin[it])*d[it]).toDouble()}.toFloat().coerceAtLeast(.001f)
        // Establish identity from the captured ray itself. Native hit positions and ray origins
        // can have different offsets/scales; comparing a hit-based DOWN with a ray-based MOVE
        // turns a stationary pinch into an immediate drag.
        from=normalize3(FloatArray(3){origin[it]+d[it]*rayDistance-viewer.position[it]})
        handStart=null;gain=1f
        if(hand!=null && hand.all{it.isFinite()}){
            val handRange=sqrt((0..2).sumOf{val x=hand[it]-viewer.position[it];(x*x).toDouble()}).toFloat()
            val targetRange=sqrt((0..2).sumOf{val x=contact[it]-viewer.position[it];(x*x).toDouble()}).toFloat()
            if(handRange>.00001f && targetRange>.00001f){handStart=hand.copyOf();contactStart=contact.copyOf();gain=targetRange/handRange;from=normalize3(FloatArray(3){contact[it]-viewer.position[it]})}
        }
    }
    fun move(viewer:Pose,origin:FloatArray,direction:FloatArray):FloatArray?{
        val window=start ?: return null
        val d=normalize3(direction);val to=normalize3(FloatArray(3){origin[it]+d[it]*rayDistance-viewer.position[it]})
        return rotateTo(viewer,to)
    }
    fun moveHand(viewer:Pose,hand:FloatArray):FloatArray?{
        val window=start ?: return null;val first=handStart ?: return null
        if(!hand.all{it.isFinite()})return null
        val delta=FloatArray(3){hand[it]-first[it]}
        val movement=sqrt(delta.sumOf{(it*it).toDouble()}).toFloat()
        // Intent is measured at the hand, so increased gain cannot turn pinch jitter into a drag.
        if(!dragging && movement<.008f*window.units)return null
        dragging=true
        val to=normalize3(FloatArray(3){contactStart[it]+delta[it]*gain-viewer.position[it]})
        return rotateTo(viewer,to)
    }
    private fun rotateTo(viewer:Pose,to:FloatArray):FloatArray?{
        val window=start ?: return null
        val dot=(0..2).sumOf{(from[it]*to[it]).toDouble()}.toFloat().coerceIn(-1f,1f)
        if(!dragging && sqrt(maxOf(0f,2f-2f*dot))*MagnifierDistanceM<.008f)return null
        dragging=true
        val q=quaternionBetween(from,to)
        val moved=rotateVector(q,offset[0],offset[1],offset[2])
        return FloatArray(3){viewer.position[it]+moved[it]}.takeIf{it.all{v->v.isFinite()} && window.units>0f}
    }
}

/** Placement changes independently; expensive geometry is shared, cached and budgeted by callers. */
private fun projectMagnifier(window:MagnifiedWindow,viewer:Pose,pose:Pose,width:Float,height:Float,update:Boolean,eyeTolerance:Float,focusTolerance:Float):MonitorMesh?{
    val eye=viewerInMonitor(viewer.position,pose.position,pose.rotation)
    val old=window.mesh
    if(!update && old!=null && old.planar==!window.spherical && old.width==width && old.height==height)return old
    val same=old!=null && old.planar==!window.spherical && old.width==width && old.height==height && eye.indices.all{abs(eye[it]-old.eye[it])<eyeTolerance}
    if(!window.spherical) {
        if(same)return old
        return MonitorMesh(width,height,eye,columns=32,rows=20,focus=floatArrayOf(0f,0f),planar=true)
    }
    if(!update && same)return old
    val reference=if(same)old!! else MonitorMesh(width,height,eye,columns=32,rows=20,focus=floatArrayOf(0f,0f))
    val direction=rotateVector(viewer.rotation,0f,0f,-1f)
    val end=viewerInMonitor(FloatArray(3){viewer.position[it]+direction[it]},pose.position,pose.rotation)
    val focus=sphericalViewFocus(reference,FloatArray(3){end[it]-eye[it]}) ?: floatArrayOf(0f,0f)
    if(same && focus.indices.all{abs(focus[it]-(old!!.focus?.get(it) ?: 0f))<focusTolerance})return old
    return MonitorMesh(width,height,eye,columns=32,rows=20,focus=focus)
}

/** A popup keeps its desktop-pixel offset/scale beside its owner; it never refits the owner. */
internal fun stickyMagnifier(owner:MagnifiedWindow,source:DesktopWindow,popup:DesktopWindow,viewer:Pose,level:Int=0):MagnifiedWindow {
    val distance=ViewLayers.popupDistance(owner.layerDistanceM,level)
    val ratio=distance/owner.layerDistanceM
    val fitX=minOf(1f,(owner.sourceWidth.toFloat()/owner.sourceHeight)/(owner.width/owner.height))
    val fitY=minOf(1f,(owner.width/owner.height)/(owner.sourceWidth.toFloat()/owner.sourceHeight))
    val sx=owner.width*fitX/source.width;val sy=owner.height*fitY/source.height
    val flat=floatArrayOf((popup.x+popup.width*.5f-source.x-source.width*.5f)*sx,-(popup.y+popup.height*.5f-source.y-source.height*.5f)*sy,0f)
    val position=transformPoint(owner.pose,owner.mesh?.project(flat) ?: flat)
    val window=MagnifiedWindow(popup.id,Pose(position,owner.pose.rotation),popup.width*sx*ratio,popup.height*sy*ratio,popup.width,popup.height,owner.spherical,
        units=owner.units,fixed=true,desktopRotation=owner.pose.rotation,requestedPosition=position,layerDistanceM=distance)
    return magnifierFrame(window,window,viewer,owner.units,true)
}
