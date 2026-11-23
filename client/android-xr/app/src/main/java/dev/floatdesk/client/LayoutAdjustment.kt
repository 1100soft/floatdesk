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

/** Attach each rectangle to the nearest available edge, retaining its own size and depth. */
internal fun snapLayoutBoxes(boxes:List<LayoutBox>):List<FloatArray>{
    if(boxes.isEmpty())return emptyList()
    val placed=mutableMapOf(0 to boxes[0].center.copyOf())
    val remaining=(1 until boxes.size).toMutableSet()
    fun overlaps(i:Int,x:Float,y:Float)=placed.any{(j,p)->
        abs(p[0]-x)<(boxes[i].width+boxes[j].width)*.5f-.0001f &&
            abs(p[1]-y)<(boxes[i].height+boxes[j].height)*.5f-.0001f
    }
    while(remaining.isNotEmpty()){
        var bestIndex=-1
        var bestCost=Float.POSITIVE_INFINITY
        var best:FloatArray?=null
        for(i in remaining){
            val box=boxes[i]
            for((j,p) in placed){
                val other=boxes[j]
                val xSpan=(box.width+other.width)*.5f
                val ySpan=(box.height+other.height)*.5f
                for(sign in listOf(-1f,1f)){
                    val y=box.center[1].coerceIn(p[1]-ySpan*.85f,p[1]+ySpan*.85f)
                    val x=box.center[0].coerceIn(p[0]-xSpan*.85f,p[0]+xSpan*.85f)
                    for((cx,cy) in listOf((p[0]+sign*xSpan) to y,x to (p[1]+sign*ySpan))){
                        if(overlaps(i,cx,cy))continue
                        val dx=cx-box.center[0]
                        val dy=cy-box.center[1]
                        val cost=dx*dx+dy*dy
                        if(cost<bestCost){bestCost=cost;bestIndex=i;best=floatArrayOf(cx,cy,box.center[2])}
                    }
                }
            }
        }
        if(best==null){
            bestIndex=remaining.first()
            val (j,p)=placed.maxBy{(index,center)->center[0]+boxes[index].width*.5f}
            val box=boxes[bestIndex]
            best=floatArrayOf(p[0]+(boxes[j].width+box.width)*.5f,p[1],box.center[2])
        }
        placed[bestIndex]=best
        remaining.remove(bestIndex)
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
internal fun WorkspaceFile.adjustLayout(action:LayoutAdjustment,viewer:Pose,units:Float):WorkspaceFile{
    if(action==LayoutAdjustment.AUTO)return adjustLayout(LayoutAdjustment.ALIGN,viewer,units)
        .adjustLayout(LayoutAdjustment.SNAP,viewer,units).adjustLayout(LayoutAdjustment.FACE,viewer,units)
    val desktop=layouts[activeLayout]?.toList().orEmpty()
    val origin=layoutOrigin ?: desktop.firstOrNull()?.pose ?: return this
    val records=fixedMagnifiers+systemPanels+listOfNotNull(referencePlacement)
    val originQ=origin.rotation
    val inverse=floatArrayOf(-originQ[0],-originQ[1],-originQ[2],originQ[3])
    val boxes=desktop.map{LayoutBox(viewerInMonitor(it.pose.position,origin.position,originQ),it.physicalWidthM,it.physicalHeightM)}+
        records.map{LayoutBox(FloatArray(3){axis->it.pose.position[axis]*units},it.widthM*units,it.heightM*units)}
    val snapped=if(action==LayoutAdjustment.SNAP)snapLayoutBoxes(boxes) else boxes.map{it.center}
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
