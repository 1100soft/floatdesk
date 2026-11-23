package dev.floatdesk.client

import dev.floatdesk.client.workspace.FixedMagnifierPlacement
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceFile
import dev.floatdesk.client.workspace.ViewLayers
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
        for((j,p) in placed){
            val dx=(box.width+boxes[j].width)*.5f
            val dy=(box.height+boxes[j].height)*.5f
            if(abs(p[1]-origin[1])<dy-epsilon){
                consider(p[0]-dx,origin[1]);consider(p[0]+dx,origin[1])
            }
            if(abs(p[0]-origin[0])<dx-epsilon){
                consider(origin[0],p[1]-dy);consider(origin[0],p[1]+dy)
            }
        }
        if(best==null){
            // A diagonally isolated rectangle needs alignment on the other axis first.
            for((j,p) in placed){
                val other=boxes[j]
                val dx=(box.width+other.width)*.5f;val dy=(box.height+other.height)*.5f
                val overlapX=minOf(box.width,other.width)*.10f
                val overlapY=minOf(box.height,other.height)*.10f
                val x=origin[0].coerceIn(p[0]-dx+overlapX,p[0]+dx-overlapX)
                val y=origin[1].coerceIn(p[1]-dy+overlapY,p[1]+dy-overlapY)
                consider(p[0]-dx,y);consider(p[0]+dx,y)
                consider(x,p[1]-dy);consider(x,p[1]+dy)
                consider(p[0]-dx,p[1]);consider(p[0]+dx,p[1])
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
internal fun WorkspaceFile.adjustLayout(action:LayoutAdjustment,viewer:Pose,units:Float,contentSizes:Map<String,Pair<Float,Float>> = emptyMap()):WorkspaceFile{
    if(action==LayoutAdjustment.AUTO)return adjustLayout(LayoutAdjustment.ALIGN,viewer,units,contentSizes)
        .adjustLayout(LayoutAdjustment.SNAP,viewer,units,contentSizes).adjustLayout(LayoutAdjustment.FACE,viewer,units,contentSizes)
    val desktop=layouts[activeLayout]?.toList().orEmpty()
    val origin=layoutOrigin ?: desktop.firstOrNull()?.pose ?: return this
    val records=fixedMagnifiers+systemPanels+listOfNotNull(referencePlacement)
    val originQ=origin.rotation
    val inverse=floatArrayOf(-originQ[0],-originQ[1],-originQ[2],originQ[3])
    val boxes=desktop.map{LayoutBox(viewerInMonitor(it.pose.position,origin.position,originQ),it.physicalWidthM,it.physicalHeightM)}+
        records.map{record->
            val fitted=contentSizes[record.id]
            LayoutBox(FloatArray(3){axis->record.pose.position[axis]*units},fitted?.first ?: record.widthM*units,fitted?.second ?: record.heightM*units)
        }
    val mapDepth=ViewLayers.DesktopDistanceM*units
    val snapped=if(action==LayoutAdjustment.SNAP){
        val visible=boxes.indices.filter{mapDepth-boxes[it].center[2]>.001f}
        val mapped=visible.map{i->
            val box=boxes[i]
            val depth=mapDepth-box.center[2]
            LayoutBox(floatArrayOf(box.center[0]/depth,box.center[1]/depth,box.center[2]),box.width/depth,box.height/depth)
        }
        val packed=snapLayoutBoxes(mapped)
        boxes.map{it.center}.toMutableList().also{out->
            visible.forEachIndexed{i,index->
                val point=packed[i];val depth=mapDepth-point[2]
                out[index]=floatArrayOf(point[0]*depth,point[1]*depth,point[2])
            }
        }
    }else boxes.map{it.center}
    fun rotation(p:FloatArray,q:FloatArray)=when(action){
        LayoutAdjustment.ALIGN->axisAlignedRotation(q)
        LayoutAdjustment.FACE,LayoutAdjustment.AUTO->facingMagnifierRotation(p,viewer,originQ)
        LayoutAdjustment.SNAP->q
    }
    val screens=desktop.mapIndexed{i,screen->
        val p=if(action==LayoutAdjustment.SNAP)transformPoint(origin,snapped[i]) else screen.pose.position
        screen.copy(pose=Pose(p,rotation(p,screen.pose.rotation)),freePose=true)
    }.toMutableList()
    fun adjust(i:Int,record:FixedMagnifierPlacement):FixedMagnifierPlacement{
        val local=snapped[desktop.size+i]
        val p=transformPoint(origin,local)
        val q=rotation(p,multiplyQuaternion(originQ,record.pose.rotation))
        return record.copy(pose=Pose(FloatArray(3){local[it]/units},multiplyQuaternion(inverse,q)),freePose=true)
    }
    val fixed=fixedMagnifiers.mapIndexed(::adjust)
    val panels=systemPanels.mapIndexed{i,record->adjust(fixed.size+i,record)}
    return copy(layouts=layouts.toMutableMap().also{it[activeLayout]=screens},desktopHome=screens.firstOrNull()?.pose,
        fixedMagnifiers=fixed,systemPanels=panels,referencePlacement=referencePlacement?.let{adjust(fixed.size+panels.size,it)})
}
