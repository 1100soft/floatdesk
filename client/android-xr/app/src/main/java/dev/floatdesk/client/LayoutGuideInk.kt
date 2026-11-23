package dev.floatdesk.client

import dev.floatdesk.client.media.VideoAim
import dev.floatdesk.client.workspace.MonitorPlacement
import kotlin.math.cos
import kotlin.math.sin

/** Composite the overlapping parts into desktop video when the native video layer covers scene meshes. */
internal fun layoutGuideInk(guide:LayoutOriginGuide, desktop:MonitorPlacement, mesh:MonitorMesh?):VideoAim {
    val viewer=guide.viewer
    val localEye=viewerInMonitor(viewer.position,desktop.pose.position,desktop.pose.rotation)
    val width=desktop.physicalWidthM
    val height=desktop.physicalHeightM
    val segments=ArrayList<Float>()
    val colors=ArrayList<Float>()
    fun project(point:FloatArray):Pair<Float,Float>? {
        val local=viewerInMonitor(point,desktop.pose.position,desktop.pose.rotation)
        val ray=normalize3(subtract(local,localEye))
        val image=if(mesh!=null){
            val target=FloatArray(3){mesh.eye[it]+ray[it]*mesh.radius}
            mesh.unproject(target,applyProjection=false) ?: return null
        }else{
            if(kotlin.math.abs(ray[2])<.00001f)return null
            val t=-localEye[2]/ray[2]
            if(t<=0f)return null
            FloatArray(3){localEye[it]+ray[it]*t}
        }
        val u=image[0]/width+.5f
        val v=.5f-image[1]/height
        return (u to v).takeIf{u in -.03f..1.03f && v in -.03f..1.03f}
    }
    fun outline(points:List<FloatArray>,color:List<Float>){
        var previous:Pair<Float,Float>?=null
        for(point in points){
            val current=project(point)
            val start=previous
            if(current!=null && start!=null && kotlin.math.abs(start.first-current.first)<.22f && kotlin.math.abs(start.second-current.second)<.22f){
                segments.addAll(listOf(start.first,start.second,current.first,current.second));colors.addAll(color)
            }
            previous=current
        }
    }
    if(dot(subtract(guide.near,viewer.position),rotateVector(viewer.rotation,0f,0f,-1f))<=0f)return VideoAim()
    for(layer in 1 downTo 0){
        val center=if(layer==0)guide.near else guide.far
        val range=if(layer==0)guide.nearRange else guide.farRange
        val color=if(layer==0)listOf(.22f,1f,1f) else listOf(.76f,.38f,1f)
        for(axis in 0..1){
            val points=List(if(guide.spherical)49 else 25){i->
                if(guide.spherical){
                    val angle=(i-24)*Math.PI.toFloat()/24f
                    val across=sin(angle)*range
                    add(viewer.position,rotateVector(guide.rotation,if(axis==0)across else 0f,if(axis==1)across else 0f,-cos(angle)*range))
                }else{
                    val across=(i-12)*range/12f
                    add(center,rotateVector(guide.rotation,if(axis==0)across else 0f,if(axis==1)across else 0f,0f))
                }
            }
            outline(points,color)
        }
        if(layer==0){
            val radius=guide.gripRadius
            for(scale in listOf(.60f,1f,1.36f)) outline(List(17){i->
                val angle=i*Math.PI.toFloat()/8f
                add(center,rotateVector(guide.rotation,cos(angle)*radius*scale,sin(angle)*radius*scale,0f))
            },color.map{it*(if(scale>1f).55f else 1f)})
        }
    }
    return VideoAim(segments.toFloatArray(),opacity=.85f,colors=colors.toFloatArray())
}
