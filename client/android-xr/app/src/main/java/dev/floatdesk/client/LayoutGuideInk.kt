package dev.floatdesk.client

import kotlin.math.cos
import kotlin.math.sin

/** Perspective projection of the layout guides into a foreground media surface. */
internal class LayoutGuideProjection(private val guide:LayoutOriginGuide,private val halfWidth:Float,private val halfHeight:Float) {
    fun point(world:FloatArray):Pair<Float,Float>? {
        val viewer=guide.viewer
        val local=viewerInMonitor(world,viewer.position,viewer.rotation)
        val depth=-local[2]
        if(depth<=.001f)return null
        return (.5f+local[0]/(2f*depth*halfWidth)) to (.5f-local[1]/(2f*depth*halfHeight))
    }

    fun axis(layer:Int,axis:Int):List<Pair<Float,Float>?> {
        val center=if(layer==0)guide.near else guide.far
        val range=if(layer==0)guide.nearRange else guide.farRange
        return List(if(guide.spherical)97 else 2){i->
            val world=if(guide.spherical){
                val angle=i*Math.PI.toFloat()/48f
                val across=sin(angle)*range
                add(guide.viewer.position,rotateVector(guide.rotation,if(axis==0)across else 0f,if(axis==1)across else 0f,-cos(angle)*range))
            }else{
                val across=if(i==0)-range else range
                add(center,rotateVector(guide.rotation,if(axis==0)across else 0f,if(axis==1)across else 0f,0f))
            }
            point(world)
        }
    }
}
