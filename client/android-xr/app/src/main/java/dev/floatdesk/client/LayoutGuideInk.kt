package dev.floatdesk.client

import kotlin.math.cos
import kotlin.math.sin

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

/** Two crossed spatial axes. The near axes enter beneath the origin marker's surface. */
internal fun worldAxisGeometry(spherical:Boolean):VideoGeometry {
    val vertices=ArrayList<Float>()
    val indices=ArrayList<Int>()
    fun strip(a:FloatArray,b:FloatArray,lateralInt:Int){
        val first=vertices.size/3
        for(p in listOf(a,b))for(sign in listOf(-1f,1f))for(axis in 0..2)
            vertices.add(p[axis]+if(axis==lateralInt)sign*.0015f else 0f)
        indices.addAll(listOf(first,first+1,first+2,first+1,first+3,first+2,
            first,first+2,first+1,first+1,first+2,first+3))
    }
    for(axis in 0..1){
        fun point(angle:Double):FloatArray{
            val v=sin(angle).toFloat()
            return floatArrayOf(if(axis==0)v else 0f,if(axis==1)v else 0f,-cos(angle).toFloat())
        }
        if(spherical){
            val divisions=128
            val gap=.0235
            val arc=2.0*Math.PI-2*gap
            for(i in 0 until divisions)strip(point(gap+arc*i/divisions),point(gap+arc*(i+1)/divisions),1-axis)
        }else for(sign in listOf(-1f,1f)){
            val a=FloatArray(3);val b=FloatArray(3)
            a[axis]=sign*.0235f;b[axis]=sign
            strip(a,b,1-axis)
        }
    }
    return VideoGeometry(vertices.toFloatArray(),FloatArray(vertices.size/3*2){.5f},indices.toIntArray())
}
