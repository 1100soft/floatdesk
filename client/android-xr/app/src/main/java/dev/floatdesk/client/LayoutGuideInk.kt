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


/** Unit-radius sphere with a directional lighting coordinate, independent of video resolution. */
internal fun layoutOriginSphereGeometry():VideoGeometry {
    val columns=24
    val rows=12
    val stride=columns+1
    val positions=FloatArray((rows+1)*stride*3)
    val uv=FloatArray((rows+1)*stride*2)
    var index=0
    for(row in 0..rows)for(col in 0..columns){
        val phi=Math.PI*row/rows
        val theta=2.0*Math.PI*col/columns
        val x=(sin(phi)*cos(theta)).toFloat()
        val y=cos(phi).toFloat()
        val z=(sin(phi)*sin(theta)).toFloat()
        positions[index*3]=x;positions[index*3+1]=y;positions[index*3+2]=z
        uv[index*2]=(.48f+.33f*x+.34f*y+.40f*z).coerceIn(0f,1f)
        uv[index*2+1]=.5f
        index++
    }
    val triangles=ArrayList<Int>(rows*columns*6)
    for(row in 0 until rows)for(col in 0 until columns){
        val a=row*stride+col
        val b=a+stride
        if(row>0)triangles.addAll(listOf(a,a+1,b))
        if(row+1<rows)triangles.addAll(listOf(a+1,b+1,b))
    }
    return VideoGeometry(positions,uv,triangles.toIntArray())
}
