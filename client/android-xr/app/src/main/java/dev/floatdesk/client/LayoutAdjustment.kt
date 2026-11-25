package dev.floatdesk.client

import dev.floatdesk.client.workspace.FixedMagnifierPlacement
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceFile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

internal enum class LayoutAdjustment { ALIGN, SNAP, FACE, AUTO }

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
internal fun WorkspaceFile.adjustLayout(action:LayoutAdjustment,viewer:Pose,units:Float,contentSizes:Map<String,Pair<Float,Float>> = emptyMap(),visibleRecords:Set<String>?=null):WorkspaceFile{
    if(action==LayoutAdjustment.AUTO)return adjustLayout(LayoutAdjustment.ALIGN,viewer,units,contentSizes,visibleRecords)
        .adjustLayout(LayoutAdjustment.FACE,viewer,units,contentSizes,visibleRecords).adjustLayout(LayoutAdjustment.SNAP,viewer,units,contentSizes,visibleRecords)
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
        val visible=boxes.indices.filter{i->-boxes[i].center[2]>.001f &&
            (i<desktop.size || visibleRecords==null || records[i-desktop.size].id in visibleRecords)}
        // The desktop must be in front of the reference before it can anchor a pack.
        if(visible.firstOrNull()!=0 || desktop.isEmpty())return this
        val panels=systemPanels.map{it.id}.toSet()
        val angular=visible.map{i->
            val box=boxes[i];val depth=-box.center[2]
            LayoutBox(floatArrayOf(box.center[0]/depth,box.center[1]/depth,box.center[2]),box.width/depth,box.height/depth)
        }
        fun worldAt(point:FloatArray):FloatArray{
            val depth=-point[2]
            return transformPoint(viewer,floatArrayOf(point[0]*depth,point[1]*depth,point[2]))
        }
        // Tilt shifts the center of a panel's projected bounds as well as its size.
        // Re-evaluate both at the proposed placement so Snap and cursor routing
        // agree even for narrow panels near the edge of the view.
        fun footprint(slot:Int,point:FloatArray):LayoutBox{
            val index=visible[slot]
            val record=records.getOrNull(index-desktop.size)
            if(record==null || record.id !in panels)return angular[slot].copy(center=point)
            val box=boxes[index]
            val pose=Pose(worldAt(point),multiplyQuaternion(originQ,record.pose.rotation))
            val mesh=if(sphericalWarp)projectMagnifier(
                MagnifiedWindow(0,pose,box.width,box.height,1,1,spherical=true),
                viewer,pose,box.width,box.height,true,.003f,.003f) else null
            val bounds=cursorPatches(record.id,pose,box.width,box.height,mesh,FocusRect(0f,0f,1f,1f),
                viewer,projectBounds=true).firstOrNull()?.viewBounds ?: return angular[slot].copy(center=point)
            return LayoutBox(floatArrayOf((bounds.left+bounds.right)*.5f,-(bounds.top+bounds.bottom)*.5f,point[2]),
                bounds.right-bounds.left,bounds.bottom-bounds.top)
        }
        val anchors=angular.map{it.center}
        var points=anchors
        for(iteration in 0 until if(panels.isEmpty())1 else 12){
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
