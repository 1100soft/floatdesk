package dev.floatdesk.client

import dev.floatdesk.client.workspace.Pose
import kotlin.math.abs

/** Shared render/debug/transport representation of the current view-plane map. */
internal data class CursorPatch(val key:String,val points:List<Pair<Float,Float>>,val source:FocusRect,val depth:Float,val front:Boolean){
    val viewBounds=FocusRect(points.minOf{it.first},points.minOf{it.second},points.maxOf{it.first},points.maxOf{it.second})
}
internal data class CursorTopology(val patches:List<CursorPatch> = emptyList(),val scale:Pair<Float,Float> = .001f to .001f){
    fun json():String {
        fun number(v:Float)=kotlin.math.round(v*10000f)/10000f
        val rows=patches.map{p->org.json.JSONObject().put("key",p.key).put("points",org.json.JSONArray(p.points.map{org.json.JSONArray(listOf(number(it.first),number(it.second)))}))
            .put("source",org.json.JSONArray(listOf(p.source.left,p.source.top,p.source.right,p.source.bottom)))
            .put("depth",p.depth)}
        return org.json.JSONObject().put("type","cursorMap").put("scale",org.json.JSONArray(listOf(scale.first,scale.second))).put("surfaces",org.json.JSONArray(rows)).toString()
    }
}
/** Logical content rectangles. Orientation/curvature never skew a cursor space. */
internal fun cursorPatches(key:String,pose:Pose,width:Float,height:Float,mesh:MonitorMesh?,source:FocusRect,viewer:Pose,
    region:FocusRect=FocusRect(0f,0f,1f,1f),front:Boolean=false,divisions:Int=4):List<CursorPatch>{
    val center=viewerInMonitor(pose.position,viewer.position,viewer.rotation)
    val depth=-center[2]
    if(depth<=.001f || center.any{!it.isFinite()} || width<=0 || height<=0)return emptyList()
    val left=(center[0]+(region.left-.5f)*width)/depth
    val right=(center[0]+(region.right-.5f)*width)/depth
    val top=(-center[1]+(region.top-.5f)*height)/depth
    val bottom=(-center[1]+(region.bottom-.5f)*height)/depth
    return listOf(CursorPatch(key,listOf(left to top,right to top,right to bottom,left to bottom),source,depth,front))
}
internal fun cursorMapScale(desktop:List<CursorPatch>):Pair<Float,Float>{
    if(desktop.isEmpty())return .001f to .001f
    val widths=desktop.mapNotNull{p->val w=p.source.right-p.source.left;(abs(p.points[1].first-p.points[0].first)/w).takeIf{it.isFinite() && it>0}}
    val heights=desktop.mapNotNull{p->val h=p.source.bottom-p.source.top;(abs(p.points[3].second-p.points[0].second)/h).takeIf{it.isFinite() && it>0}}
    return (widths.sorted().let{it.getOrNull(it.size/2)} ?: .001f) to (heights.sorted().let{it.getOrNull(it.size/2)} ?: .001f)
}

internal data class CursorWire(val back:String,val a:Pair<Float,Float>,val b:Pair<Float,Float>)
private fun patchTriangle(patch:CursorPatch,point:Pair<Float,Float>,ai:Int,bi:Int,ci:Int):Pair<Float,Float>?{
    val a=patch.points[ai];val b=patch.points[bi];val c=patch.points[ci]
    val det=(b.second-c.second)*(a.first-c.first)+(c.first-b.first)*(a.second-c.second)
    if(abs(det)<1e-10f)return null
    val u=((b.second-c.second)*(point.first-c.first)+(c.first-b.first)*(point.second-c.second))/det
    val v=((c.second-a.second)*(point.first-c.first)+(a.first-c.first)*(point.second-c.second))/det
    if(u < -.0001f || v < -.0001f || u+v > 1.0001f)return null
    fun x(i:Int)=if(i==0 || i==3)patch.source.left else patch.source.right
    fun y(i:Int)=if(i<2)patch.source.top else patch.source.bottom
    return u*x(ai)+v*x(bi)+(1-u-v)*x(ci) to u*y(ai)+v*y(bi)+(1-u-v)*y(ci)
}
internal fun CursorPatch.sourceAt(point:Pair<Float,Float>):Pair<Float,Float>? {
    if(!viewBounds.contains(point.first,point.second))return null
    return patchTriangle(this,point,0,1,2) ?: patchTriangle(this,point,0,2,3)
}
/** The same logical shadow is visible on every intersected layer behind its owner. */
internal fun CursorTopology.wires():List<CursorWire> = buildList {
    for(front in patches.filter{it.front})for(back in patches.filter{it.key!=front.key && it.depth>front.depth}){
        val f=front.viewBounds;val b=back.viewBounds
        fun edge(x0:Float,y0:Float,x1:Float,y1:Float){
            val a=back.sourceAt(x0 to y0) ?: return;val end=back.sourceAt(x1 to y1) ?: return
            if(x0!=x1 || y0!=y1)add(CursorWire(back.key,a,end))
        }
        val left=maxOf(f.left,b.left);val right=minOf(f.right,b.right)
        val top=maxOf(f.top,b.top);val bottom=minOf(f.bottom,b.bottom)
        if(left<right && top<bottom){
            edge(left,top,right,top);edge(right,top,right,bottom)
            edge(right,bottom,left,bottom);edge(left,bottom,left,top)
        }
    }
}
/** Express the whole virtual workspace in desktop pixels, including extra-desktop space. */
internal fun CursorTopology.logicalPixels():CursorTopology {
    val base=patches.firstOrNull{!it.front} ?: return this
    val b=base.viewBounds
    val sx=(base.source.right-base.source.left)/(b.right-b.left)
    val sy=(base.source.bottom-base.source.top)/(b.bottom-b.top)
    if(!sx.isFinite() || !sy.isFinite() || sx<=0f || sy<=0f)return CursorTopology()
    return CursorTopology(patches.map{p->p.copy(points=p.points.map{(x,y)->
        base.source.left+(x-b.left)*sx to base.source.top+(y-b.top)*sy
    })},1f to 1f)
}
internal fun CursorTopology.debugSegments():FloatArray=patches.flatMap{p->(0..3).flatMap{i->
    val a=p.points[i];val b=p.points[(i+1)%4];listOf(a.first,a.second,b.first,b.second,if(p.front)1f else 0f)
}}.toFloatArray()

/** Reduce tessellation, rather than silently disabling routing when a map exceeds one datagram. */
internal fun CursorTopology.coarsened():CursorTopology {
    val reduced=patches.groupBy{it.key}.values.flatMap{group->
        group.groupBy{it.source.top to it.source.bottom}.values.flatMap{row->
            row.sortedBy{it.source.left}.chunked(2).flatMap{pair->
                val a=pair.first();val b=pair.getOrNull(1)
                if(b!=null && a.source.right==b.source.left && a.points[1]==b.points[0] && a.points[2]==b.points[3])
                    listOf(a.copy(points=listOf(a.points[0],b.points[1],b.points[2],a.points[3]),source=a.source.copy(right=b.source.right),depth=(a.depth+b.depth)*.5f))
                else pair
            }
        }
    }
    return copy(patches=reduced)
}

internal data class CursorLocation(val key:String,val u:Float,val v:Float,val x:Float,val y:Float,val sourceX:Float=x,val sourceY:Float=y)
internal fun cursorGlow(u:Float,v:Float,aspect:Float):FloatArray=buildList<Float>{
    for(radius in listOf(.012f,.018f))for(i in 0 until 20){
        val a=i*2*Math.PI/20;val b=(i+1)*2*Math.PI/20
        add(u+radius*kotlin.math.cos(a).toFloat());add(v+radius*aspect*kotlin.math.sin(a).toFloat())
        add(u+radius*kotlin.math.cos(b).toFloat());add(v+radius*aspect*kotlin.math.sin(b).toFloat())
    }
}.toFloatArray()
