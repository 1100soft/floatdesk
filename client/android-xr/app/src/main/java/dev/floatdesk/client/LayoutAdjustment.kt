package dev.floatdesk.client

import dev.floatdesk.client.workspace.FixedMagnifierPlacement
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceFile
import dev.floatdesk.client.workspace.ViewLayers
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal enum class LayoutAdjustment { ALIGN, SNAP, FACE, AUTO }

internal enum class SupportSide { LEFT, RIGHT, TOP, BOTTOM }

/** A support object seated around the desktop: [aspect] is its content width over height, [distanceM] its default radial layer. */
internal data class SupportSeat(val id:String,val side:SupportSide,val panel:Boolean,val aspect:Float,val distanceM:Float)

/** Panel placement keys end with the edge the panel occupies on its output. */
internal fun panelSide(id:String):SupportSide?=when(id.substringAfterLast(':')){
    "left"->SupportSide.LEFT
    "right"->SupportSide.RIGHT
    "top"->SupportSide.TOP
    "bottom"->SupportSide.BOTTOM
    else->null
}

/** Magnifiers sit below the desktop, the reference to its left and each panel on its own edge. */
internal fun WorkspaceFile.supportSeats(shown:Set<String>,aspects:Map<String,Float> = emptyMap()):List<SupportSeat>{
    val desktop=layouts[activeLayout]?.firstOrNull() ?: return emptyList()
    fun aspect(record:FixedMagnifierPlacement,fallback:Float)=aspects[record.id]?.takeIf{it.isFinite() && it>0f}
        ?: fallback.takeIf{it.isFinite() && it>0f} ?: 1f
    return buildList{
        for(record in systemPanels)if(record.id in shown){
            val a=aspect(record,record.widthM/record.heightM)
            add(SupportSeat(record.id,panelSide(record.id) ?: if(a<1f)SupportSide.RIGHT else SupportSide.BOTTOM,true,a,ViewLayers.ShellDistanceM))
        }
        for(record in fixedMagnifiers)if(record.id in shown)
            add(SupportSeat(record.id,SupportSide.BOTTOM,false,aspect(record,record.widthM/record.heightM),ViewLayers.MagnifierDistanceM))
        if(desktopReference)referencePlacement?.takeIf{it.id in shown}?.let{record->
            add(SupportSeat(record.id,SupportSide.LEFT,false,aspect(record,desktop.pixelWidth.toFloat()/desktop.pixelHeight),ViewLayers.MagnifierDistanceM))
        }
    }
}

/**
 * Seat support objects edge to edge around the desktop in the viewer's projection, panels nearest on their side.
 * With [resize] each takes the desktop's apparent size; a vertical panel its apparent height, a horizontal one its width.
 * The desktop itself is not moved.
 */
internal fun WorkspaceFile.seatSupportObjects(viewer:Pose,units:Float,seats:List<SupportSeat>,resize:Boolean):WorkspaceFile{
    if(seats.isEmpty() || !units.isFinite() || units<=0f || viewer.position.any{!it.isFinite()} || viewer.rotation.any{!it.isFinite()})return this
    val desktop=layouts[activeLayout]?.firstOrNull() ?: return this
    val origin=layoutOrigin ?: desktop.pose
    val originQ=origin.rotation
    val inverse=floatArrayOf(-originQ[0],-originQ[1],-originQ[2],originQ[3])
    val center=viewerInMonitor(desktop.pose.position,viewer.position,viewer.rotation)
    val range=sqrt(center.sumOf{(it*it).toDouble()}).toFloat()
    if(!range.isFinite() || range<.0001f || -center[2]<=.0001f)return this
    val yaw=atan2(center[0],-center[2])
    val pitch=atan2(center[1],sqrt(center[0]*center[0]+center[2]*center[2]))
    // Curved surfaces subtend arc over radius; flat viewer-facing ones subtend twice the half-size arctangent.
    fun angle(size:Float,distance:Float)=if(sphericalWarp)size/distance else 2f*atan(size*.5f/distance)
    fun extent(angle:Float,distance:Float)=if(sphericalWarp)angle*distance else 2f*distance*tan(angle.coerceAtMost(2.8f)*.5f)
    val deskW=angle(desktop.physicalWidthM,range);val deskH=angle(desktop.physicalHeightM,range)
    fun outer(b:FocusRect,side:SupportSide)=when(side){
        SupportSide.LEFT->b.left;SupportSide.RIGHT->b.right;SupportSide.TOP->b.top;SupportSide.BOTTOM->b.bottom
    }
    fun inner(b:FocusRect,side:SupportSide)=when(side){
        SupportSide.LEFT->b.right;SupportSide.RIGHT->b.left;SupportSide.TOP->b.bottom;SupportSide.BOTTOM->b.top
    }
    fun FocusRect.finite()=left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite()
    // Contact is measured on the projected bounds the cursor map uses. A side whose bounds diverge keeps angular seating.
    val desktopBounds=projectedBounds("desktop",viewer,desktop.pose,desktop.physicalWidthM,desktop.physicalHeightM,sphericalWarp,units,desktop)
        ?.takeIf{it.finite()}
    val edge=SupportSide.entries.associateWith{side->desktopBounds?.let{outer(it,side)}}.toMutableMap()
    val records=(fixedMagnifiers+systemPanels+listOfNotNull(referencePlacement)).associateBy{it.id}
    val reach=mutableMapOf<SupportSide,Float>()
    val placed=mutableMapOf<String,FixedMagnifierPlacement>()
    for(seat in seats.filter{it.panel}+seats.filterNot{it.panel}){
        val record=records[seat.id] ?: continue
        if(seat.distanceM<=0f || !seat.aspect.isFinite() || seat.aspect<=0f)continue
        val (w,h)=when{
            !resize->angle(record.widthM,seat.distanceM) to angle(record.heightM,seat.distanceM)
            seat.panel && seat.aspect<1f->deskH*seat.aspect to deskH
            seat.panel->deskW to deskW/seat.aspect
            seat.aspect>=deskW/deskH->deskW to deskW/seat.aspect
            else->deskH*seat.aspect to deskH
        }
        if(!w.isFinite() || !h.isFinite() || w<=0f || h<=0f)continue
        val offset=reach[seat.side] ?: 0f
        val (startYaw,startPitch)=when(seat.side){
            SupportSide.LEFT->yaw-deskW*.5f-offset-w*.5f to pitch
            SupportSide.RIGHT->yaw+deskW*.5f+offset+w*.5f to pitch
            SupportSide.TOP->yaw to pitch+deskH*.5f+offset+h*.5f
            SupportSide.BOTTOM->yaw to pitch-deskH*.5f-offset-h*.5f
        }
        reach[seat.side]=offset+if(seat.side==SupportSide.LEFT || seat.side==SupportSide.RIGHT)w else h
        val width=if(resize)extent(w,seat.distanceM) else record.widthM
        val height=if(resize)extent(h,seat.distanceM) else record.heightM
        val distance=seat.distanceM*units
        fun at(y:Float,p:Float)=transformPoint(viewer,floatArrayOf(sin(y)*cos(p)*distance,sin(p)*distance,-cos(y)*cos(p)*distance))
        fun pose(y:Float,p:Float)=at(y,p).let{Pose(it,facingMagnifierRotation(it,viewer,originQ))}
        var y=startYaw;var p=startPitch
        val target=edge[seat.side]
        var reached:Float?=null
        if(target!=null)for(step in 0..8){
            val b=projectedBounds(seat.id,viewer,pose(y,p),width*units,height*units,sphericalWarp,units)?.takeIf{it.finite()}
            if(b==null){y=startYaw;p=startPitch;reached=null;break}
            reached=outer(b,seat.side)
            val error=atan(target)-atan(inner(b,seat.side))
            if(abs(error)<.00002f || step==8)break
            if(seat.side==SupportSide.LEFT || seat.side==SupportSide.RIGHT)y+=error else p-=error
        }
        edge[seat.side]=reached
        val world=pose(y,p)
        val local=viewerInMonitor(world.position,origin.position,originQ)
        placed[seat.id]=record.copy(pose=Pose(FloatArray(3){local[it]/units},multiplyQuaternion(inverse,world.rotation)),
            widthM=width,heightM=height,freePose=true)
    }
    if(placed.isEmpty())return this
    return copy(cursorMapReference=viewer,fixedMagnifiers=fixedMagnifiers.map{placed[it.id] ?: it},
        systemPanels=systemPanels.map{placed[it.id] ?: it},referencePlacement=referencePlacement?.let{placed[it.id] ?: it})
}

/** Projected image bounds in the viewer's tangent plane, as the cursor map measures them. */
private fun projectedBounds(key:String,viewer:Pose,pose:Pose,width:Float,height:Float,spherical:Boolean,units:Float,screen:MonitorPlacement?=null):FocusRect?{
    val mesh=if(!spherical)null else if(screen!=null){
        val eye=viewerInMonitor(viewer.position,pose.position,pose.rotation)
        val anchor=monitorProjectionCenter(width,height,screen.viewCenterU,screen.viewCenterV)
        if(anchor.all{it.isFinite()} && distance(eye,anchor)>.0001f)
            desktopSphericalMesh(width,height,eye,anchor,ViewLayers.DesktopDistanceM*units) else null
    }else projectMagnifier(MagnifiedWindow(0,pose,width,height,1,1,spherical=true),viewer,pose,width,height,true,.003f,.003f)
    return cursorPatches(key,pose,width,height,mesh,FocusRect(0f,0f,1f,1f),viewer,projectBounds=true).firstOrNull()?.viewBounds
}

internal data class LayoutBox(val center:FloatArray,val width:Float,val height:Float)

/** Seat each rectangle against the desktop or an earlier rectangle in nearest-first order. */
internal fun snapLayoutBoxes(boxes:List<LayoutBox>):List<FloatArray>{
    if(boxes.isEmpty())return emptyList()
    val anchor=boxes[0].center
    val ordered=(1 until boxes.size).sortedBy{i->
        val dx=boxes[i].center[0]-anchor[0];val dy=boxes[i].center[1]-anchor[1]
        dx*dx+dy*dy
    }
    val placed=linkedMapOf(0 to anchor.copyOf())
    val epsilon=.00001f
    for(i in ordered){
        val box=boxes[i];val origin=box.center
        fun clear(x:Float,y:Float)=placed.none{(j,p)->
            abs(p[0]-x)<(box.width+boxes[j].width)*.5f-epsilon &&
                abs(p[1]-y)<(box.height+boxes[j].height)*.5f-epsilon
        }
        var best:FloatArray?=null
        var bestDistance=Float.POSITIVE_INFINITY
        fun consider(x:Float,y:Float){
            if(!clear(x,y))return
            val dx=x-origin[0];val dy=y-origin[1]
            val distance=dx*dx+dy*dy
            if(distance<bestDistance){bestDistance=distance;best=floatArrayOf(x,y,origin[2])}
        }
        // Each earlier box contributes four contact segments. On each segment,
        // the closest clear point is the orthogonal projection or a blocker endpoint.
        for((j,p) in placed){
            for(axis in 0..1){
                val along=1-axis
                fun halfSpan(index:Int,dimension:Int):Float {
                    val other=boxes[index]
                    return if(dimension==0)(box.width+other.width)*.5f else (box.height+other.height)*.5f
                }
                val span=halfSpan(j,along)
                val low=p[along]-span+epsilon
                val high=p[along]+span-epsilon
                if(low>high)continue
                for(side in listOf(-1,1)){
                    val edge=p[axis]+side*halfSpan(j,axis)
                    fun candidate(value:Float){
                        if(axis==0)consider(edge,value.coerceIn(low,high))
                        else consider(value.coerceIn(low,high),edge)
                    }
                    candidate(origin[along])
                    for((blocker,center) in placed){
                        if(abs(edge-center[axis])>=halfSpan(blocker,axis)-epsilon)continue
                        val blocked=halfSpan(blocker,along)
                        candidate(center[along]-blocked)
                        candidate(center[along]+blocked)
                    }
                }
            }
        }
        if(best==null){
            val (j,p)=placed.maxBy{(index,center)->center[0]+boxes[index].width*.5f}
            best=floatArrayOf(p[0]+(boxes[j].width+box.width)*.5f,p[1],origin[2])
        }
        placed[i]=requireNotNull(best)
    }
    return boxes.indices.map(placed::getValue)
}

private fun axisAlignedRotation(rotation:FloatArray):FloatArray {
    val normal=rotateVector(rotation,0f,0f,1f)
    val quarter=Math.PI.toFloat()*.5f
    val yaw=round(atan2(normal[0],normal[2])/quarter)*quarter
    return floatArrayOf(0f,sin(yaw*.5f),0f,cos(yaw*.5f))
}

/** Auto lays out in the reference axes, then faces each resulting surface toward the viewer. */
internal fun WorkspaceFile.adjustLayout(action:LayoutAdjustment,viewer:Pose,units:Float,contentSizes:Map<String,Pair<Float,Float>> = emptyMap(),visibleRecords:Set<String>?=null,seats:List<SupportSeat> = emptyList()):WorkspaceFile{
    if(action==LayoutAdjustment.AUTO){
        // Packing projects onto the viewer's view plane, so objects behind or beside the viewer are first seated at their default side.
        val basis=layoutOrigin ?: layouts[activeLayout]?.firstOrNull()?.pose
        val records=(fixedMagnifiers+systemPanels+listOfNotNull(referencePlacement)).associateBy{it.id}
        val outside=if(basis==null || !units.isFinite() || units<=0f)emptyList() else seats.filter{seat->
            val record=records[seat.id]
            record!=null && (visibleRecords==null || seat.id in visibleRecords) && run{
                val v=viewerInMonitor(transformPoint(basis,FloatArray(3){record.pose.position[it]*units}),viewer.position,viewer.rotation)
                -v[2]<=sqrt(v[0]*v[0]+v[1]*v[1])*.18f
            }
        }
        return seatSupportObjects(viewer,units,outside,resize=false)
            .adjustLayout(LayoutAdjustment.ALIGN,viewer,units,contentSizes,visibleRecords)
            .adjustLayout(LayoutAdjustment.FACE,viewer,units,contentSizes,visibleRecords).adjustLayout(LayoutAdjustment.SNAP,viewer,units,contentSizes,visibleRecords)
    }
    if(!units.isFinite() || units<=0f || viewer.position.any{!it.isFinite()} || viewer.rotation.any{!it.isFinite()})return this
    val desktop=layouts[activeLayout]?.toList().orEmpty()
    val origin=layoutOrigin ?: desktop.firstOrNull()?.pose ?: return this
    val records=fixedMagnifiers+systemPanels+listOfNotNull(referencePlacement)
    val originQ=origin.rotation
    val inverse=floatArrayOf(-originQ[0],-originQ[1],-originQ[2],originQ[3])
    val world=desktop.map{it.pose.position}+records.map{transformPoint(origin,FloatArray(3){axis->it.pose.position[axis]*units})}
    val boxes=desktop.mapIndexed{i,item->LayoutBox(viewerInMonitor(world[i],viewer.position,viewer.rotation),item.physicalWidthM,item.physicalHeightM)}+
        records.mapIndexed{i,record->
            val fitted=contentSizes[record.id]
            LayoutBox(viewerInMonitor(world[desktop.size+i],viewer.position,viewer.rotation),
                fitted?.first ?: record.widthM*units,fitted?.second ?: record.heightM*units)
        }
    val snapped=world.toMutableList()
    if(action==LayoutAdjustment.SNAP){
        val visible=boxes.indices.filter{i->
            val box=boxes[i]
            box.center.all{it.isFinite()} && -box.center[2]>.001f &&
            box.width.isFinite() && box.height.isFinite() && box.width>0f && box.height>0f &&
            (i<desktop.size || visibleRecords==null || records[i-desktop.size].id in visibleRecords)}
        // The desktop must be in front of the reference before it can anchor a pack.
        if(visible.firstOrNull()!=0 || desktop.isEmpty())return this
        val angular=visible.map{i->
            val box=boxes[i];val depth=-box.center[2]
            LayoutBox(floatArrayOf(box.center[0]/depth,box.center[1]/depth,box.center[2]),box.width/depth,box.height/depth)
        }
        fun worldAt(point:FloatArray):FloatArray{
            val depth=-point[2]
            return transformPoint(viewer,floatArrayOf(point[0]*depth,point[1]*depth,point[2]))
        }
        // Use the same projected image bounds for packing and the cursor map.
        val cached=mutableMapOf<Int,Pair<FloatArray,LayoutBox>>()
        fun footprint(slot:Int,point:FloatArray):LayoutBox{
            cached[slot]?.takeIf{it.first.contentEquals(point)}?.let{return it.second}
            val index=visible[slot]
            val record=records.getOrNull(index-desktop.size)
            val box=boxes[index]
            val pose=Pose(worldAt(point),if(record==null)desktop[index].pose.rotation
                else multiplyQuaternion(originQ,record.pose.rotation))
            val bounds=projectedBounds(record?.id ?: "desktop",viewer,pose,box.width,box.height,sphericalWarp,units,
                if(record==null)desktop[index] else null) ?: return angular[slot].copy(center=point)
            val footprint=LayoutBox(floatArrayOf((bounds.left+bounds.right)*.5f,-(bounds.top+bounds.bottom)*.5f,point[2]),
                bounds.right-bounds.left,bounds.bottom-bounds.top)
            cached[slot]=point to footprint
            return footprint
        }
        val anchors=angular.map{it.center}
        var points=anchors
        for(iteration in 0 until 12){
            val actual=points.mapIndexed(::footprint)
            val packed=snapLayoutBoxes(actual.mapIndexed{i,box->
                box.copy(center=FloatArray(3){axis->anchors[i][axis]+box.center[axis]-points[i][axis]})
            })
            val moved=packed.mapIndexed{i,point->FloatArray(3){axis->point[axis]+points[i][axis]-actual[i].center[axis]}}
            val settled=moved.indices.all{i->distance(moved[i],points[i])<.00001f}
            points=moved
            if(settled)break
        }
        // If the nearest edge changes during iteration, settle any residual
        // intersection against the actual footprints in nearest-first order.
        val settled=points.toMutableList()
        val occupied=mutableListOf(footprint(0,settled[0]))
        val order=(1 until anchors.size).sortedBy{i->
            val x=anchors[i][0]-anchors[0][0];val y=anchors[i][1]-anchors[0][1]
            x*x+y*y
        }
        fun overlaps(box:LayoutBox)=occupied.any{
            abs(it.center[0]-box.center[0])<(it.width+box.width)*.5f-.00001f &&
                abs(it.center[1]-box.center[1])<(it.height+box.height)*.5f-.00001f
        }
        for(i in order){
            val start=settled[i]
            val original=footprint(i,start)
            if(overlaps(original)){
                var best=Float.POSITIVE_INFINITY
                for(axis in 0..1)for(sign in listOf(-1,1)){
                    fun at(delta:Float)=start.copyOf().also{it[axis]+=sign*delta}
                    var low=0f
                    var high=.001f
                    while(high<best && overlaps(footprint(i,at(high)))){
                        low=high
                        high*=2f
                    }
                    if(!high.isFinite() || high>=best)continue
                    repeat(18){
                        val mid=(low+high)*.5f
                        if(overlaps(footprint(i,at(mid))))low=mid else high=mid
                    }
                    if(high<best){best=high;settled[i]=at(high)}
                }
            }
            occupied+=footprint(i,settled[i])
        }
        visible.forEachIndexed{i,index->snapped[index]=worldAt(settled[i])}
    }
    fun rotation(p:FloatArray,q:FloatArray)=when(action){
        LayoutAdjustment.ALIGN->axisAlignedRotation(q)
        LayoutAdjustment.FACE,LayoutAdjustment.AUTO->facingMagnifierRotation(p,viewer,originQ)
        LayoutAdjustment.SNAP->q
    }
    val screens=desktop.mapIndexed{i,screen->
        val p=snapped[i]
        screen.copy(pose=Pose(p,rotation(p,screen.pose.rotation)),freePose=true)
    }.toMutableList()
    fun adjust(i:Int,record:FixedMagnifierPlacement):FixedMagnifierPlacement{
        if(visibleRecords!=null && record.id !in visibleRecords)return record
        val p=snapped[desktop.size+i]
        val local=viewerInMonitor(p,origin.position,originQ)
        val q=rotation(p,multiplyQuaternion(originQ,record.pose.rotation))
        return record.copy(pose=Pose(FloatArray(3){local[it]/units},multiplyQuaternion(inverse,q)),freePose=true)
    }
    val fixed=fixedMagnifiers.mapIndexed(::adjust)
    val panels=systemPanels.mapIndexed{i,record->adjust(fixed.size+i,record)}
    return copy(cursorMapReference=if(action==LayoutAdjustment.SNAP)viewer else cursorMapReference,
        layouts=layouts.toMutableMap().also{it[activeLayout]=screens},desktopHome=screens.firstOrNull()?.pose,
        fixedMagnifiers=fixed,systemPanels=panels,referencePlacement=referencePlacement?.let{adjust(fixed.size+panels.size,it)})
}
