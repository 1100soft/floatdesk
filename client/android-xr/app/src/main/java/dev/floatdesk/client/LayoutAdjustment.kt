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

/** Pack logical rectangles by minimizing their enclosing area while keeping edges connected. */
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
        var bestArea=Float.POSITIVE_INFINITY
        var bestContact=Float.NEGATIVE_INFINITY
        var bestTravel=Float.POSITIVE_INFINITY
        var best:FloatArray?=null
        val left=placed.minOf{(i,p)->p[0]-boxes[i].width*.5f}
        val right=placed.maxOf{(i,p)->p[0]+boxes[i].width*.5f}
        val bottom=placed.minOf{(i,p)->p[1]-boxes[i].height*.5f}
        val top=placed.maxOf{(i,p)->p[1]+boxes[i].height*.5f}
        for(i in remaining){
            val box=boxes[i]
            for((j,p) in placed){
                val other=boxes[j]
                val xSpan=(box.width+other.width)*.5f
                val ySpan=(box.height+other.height)*.5f
                val xAligned=placed.flatMap{(k,c)->
                    val half=boxes[k].width*.5f
                    listOf(c[0]-half+box.width*.5f,c[0]+half-box.width*.5f)
                }
                val yAligned=placed.flatMap{(k,c)->
                    val half=boxes[k].height*.5f
                    listOf(c[1]-half+box.height*.5f,c[1]+half-box.height*.5f)
                }
                fun consider(cx:Float,cy:Float) {
                    if(overlaps(i,cx,cy))return
                    val bx=box.width*.5f;val by=box.height*.5f
                    val area=(maxOf(right,cx+bx)-minOf(left,cx-bx))*(maxOf(top,cy+by)-minOf(bottom,cy-by))
                    var contact=0f
                    for((k,c) in placed){
                        val kx=boxes[k].width*.5f;val ky=boxes[k].height*.5f
                        if(abs(abs(c[0]-cx)-kx-bx)<.0001f)
                            contact+=(minOf(c[1]+ky,cy+by)-maxOf(c[1]-ky,cy-by)).coerceAtLeast(0f)
                        if(abs(abs(c[1]-cy)-ky-by)<.0001f)
                            contact+=(minOf(c[0]+kx,cx+bx)-maxOf(c[0]-kx,cx-bx)).coerceAtLeast(0f)
                    }
                    if(contact<=.00001f)return
                    val dx=cx-box.center[0];val dy=cy-box.center[1]
                    val travel=dx*dx+dy*dy
                    if(area<bestArea-.00001f || abs(area-bestArea)<.00001f &&
                        (contact>bestContact+.00001f || abs(contact-bestContact)<.00001f && travel<bestTravel)){
                        bestArea=area;bestContact=contact;bestTravel=travel
                        bestIndex=i;best=floatArrayOf(cx,cy,box.center[2])
                    }
                }
                for(sign in listOf(-1f,1f)){
                    for(y in yAligned+listOf(p[1],box.center[1]))if(abs(y-p[1])<ySpan)
                        consider(p[0]+sign*xSpan,y)
                    for(x in xAligned+listOf(p[0],box.center[0]))if(abs(x-p[0])<xSpan)
                        consider(x,p[1]+sign*ySpan)
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
