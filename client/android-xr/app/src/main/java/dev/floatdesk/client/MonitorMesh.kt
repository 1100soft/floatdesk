package dev.floatdesk.client

import kotlin.math.sqrt

/** Desk poses and image dimensions share units; do not send an already-local eye through another scene space. */
internal fun viewerInMonitor(viewer: FloatArray, position: FloatArray, rotation: FloatArray): FloatArray {
    val x=viewer[0]-position[0]; val y=viewer[1]-position[1]; val z=viewer[2]-position[2]
    val qx=-rotation[0]; val qy=-rotation[1]; val qz=-rotation[2]; val qw=rotation[3]
    val tx=2f*(qy*z-qz*y); val ty=2f*(qz*x-qx*z); val tz=2f*(qx*y-qy*x)
    return floatArrayOf(x+qw*tx+qy*tz-qz*ty,y+qw*ty+qz*tx-qx*tz,z+qw*tz+qx*ty-qy*tx)
}

/** Equal adjacent-ray angular steps on a viewer-centered sphere (a spherical quadrilateral net). */
internal class MonitorMesh(
    val width: Float,
    val height: Float,
    val eye: FloatArray,
    val columns: Int = 64,
    val rows: Int = 40,
) {
    val radius = sqrt(eye.sumOf { (it * it).toDouble() }).toFloat()
    val normal = FloatArray(3) { eye[it] / radius }
    val tangentX = floatArrayOf(1f-normal[0]*normal[0]/(1f+normal[2]), -normal[0]*normal[1]/(1f+normal[2]), -normal[0])
    val tangentY = floatArrayOf(-normal[0]*normal[1]/(1f+normal[2]), 1f-normal[1]*normal[1]/(1f+normal[2]), -normal[1])
    // Keep one angular pixel scale in both axes; limit extreme fields uniformly to avoid folds.
    val radiansPerMeter = minOf(1f/radius, 2.2f/sqrt(width*width+height*height))
    val positions = FloatArray((columns + 1) * (rows + 1) * 3)
    val texCoords = FloatArray((columns + 1) * (rows + 1) * 2)
    val videoTexCoords: FloatArray
        get() = FloatArray(texCoords.size) { if (it % 2 == 1) 1f - texCoords[it] else texCoords[it] }
    val indices = IntArray(columns * rows * 6)
    val outline: List<FloatArray>

    init {
        require(width > 0f && height > 0f && width.isFinite() && height.isFinite() &&
            radius > 0f && radius.isFinite() && eye.size == 3 && eye.all { it.isFinite() } && eye[2] > 0f &&
            columns > 0 && rows > 0 && columns % 2 == 0 && rows % 2 == 0)
        val halfX = columns/2; val halfY = rows/2
        val dx = (width*radiansPerMeter/columns).toDouble()
        val dy = (height*radiansPerMeter/rows).toDouble()
        val cosineX = kotlin.math.cos(dx); val cosineY = kotlin.math.cos(dy)
        val quarter = Array((halfX+1)*(halfY+1)) { DoubleArray(3) }
        fun q(x: Int,y: Int) = quarter[y*(halfX+1)+x]
        fun dot(a: DoubleArray,b: DoubleArray) = (0..2).sumOf { a[it]*b[it] }
        fun unit(v: DoubleArray): DoubleArray { val length=sqrt(dot(v,v)); return DoubleArray(3) { v[it]/length } }
        for (x in 0..halfX) quarter[x] = doubleArrayOf(kotlin.math.sin(x*dx),0.0,-kotlin.math.cos(x*dx))
        for (y in 0..halfY) quarter[y*(halfX+1)] = doubleArrayOf(0.0,kotlin.math.sin(y*dy),-kotlin.math.cos(y*dy))
        for (y in 1..halfY) for (x in 1..halfX) {
            val left=q(x-1,y); val above=q(x,y-1); val diagonal=q(x-1,y-1)
            val c=dot(left,above); val denominator=1.0-c*c
            val a=(cosineX-c*cosineY)/denominator
            val b=(cosineY-c*cosineX)/denominator
            val base=DoubleArray(3) { a*left[it]+b*above[it] }
            val perpendicular=unit(doubleArrayOf(left[1]*above[2]-left[2]*above[1],left[2]*above[0]-left[0]*above[2],left[0]*above[1]-left[1]*above[0]))
            val expected=DoubleArray(3) { left[it]+above[it]-diagonal[it]-base[it] }
            val sign=if(dot(expected,perpendicular)>=0) 1.0 else -1.0
            val heightSquared=1.0-dot(base,base)
            check(heightSquared >= -1e-7) { "Angular mesh folded" }
            quarter[y*(halfX+1)+x]=unit(DoubleArray(3) { base[it]+sign*sqrt(heightSquared.coerceAtLeast(0.0))*perpendicular[it] })
        }
        for (row in 0..rows) for (col in 0..columns) {
            val x=col-halfX; val y=halfY-row
            val direction=q(kotlin.math.abs(x),kotlin.math.abs(y))
            val vx=direction[0].toFloat()*(if(x<0) -1f else 1f)
            val vy=direction[1].toFloat()*(if(y<0) -1f else 1f)
            val vz=direction[2].toFloat()
            val vertex=row*(columns+1)+col
            for (axis in 0..2) positions[vertex*3+axis]=eye[axis]+radius*(vx*tangentX[axis]+vy*tangentY[axis]+vz*normal[axis])
            texCoords[vertex*2]=col.toFloat()/columns
            texCoords[vertex*2+1]=row.toFloat()/rows
        }
        var offset=0
        for (row in 0 until rows) for (col in 0 until columns) {
            val a=row*(columns+1)+col
            for (index in intArrayOf(a,a+columns+1,a+1,a+1,a+columns+1,a+columns+2)) indices[offset++]=index
        }
        fun vertex(row: Int,col: Int)=positions.copyOfRange((row*(columns+1)+col)*3,(row*(columns+1)+col)*3+3)
        outline=buildList {
            for(col in 0 until columns step 4) add(vertex(0,col))
            for(row in 0 until rows step 4) add(vertex(row,columns))
            for(col in columns downTo 1 step 4) add(vertex(rows,col))
            for(row in rows downTo 1 step 4) add(vertex(row,0))
        }
    }

    /** Interpolate texture pixels on the same triangles used by video, then normalize to the sphere. */
    fun project(flat: FloatArray): FloatArray {
        val u=(flat[0]/width+.5f)*columns; val v=(.5f-flat[1]/height)*rows
        val col=kotlin.math.floor(u).toInt().coerceIn(0,columns-1)
        val row=kotlin.math.floor(v).toInt().coerceIn(0,rows-1)
        val x=u-col; val y=v-row; val a=row*(columns+1)+col
        val verts=if(x+y<=1f) intArrayOf(a,a+columns+1,a+1) else intArrayOf(a+1,a+columns+1,a+columns+2)
        val weights=if(x+y<=1f) floatArrayOf(1f-x-y,y,x) else floatArrayOf(1f-y,1f-x,x+y-1f)
        val direction=FloatArray(3) { axis -> (0..2).sumOf { (positions[verts[it]*3+axis]*weights[it]).toDouble() }.toFloat()-eye[axis] }
        val length=sqrt(direction.sumOf { (it*it).toDouble() }).toFloat()
        return FloatArray(3) { eye[it]+direction[it]*radius/length }
    }

    /** Invert the actual texture triangles, so curved ray contacts retain their exact image pixels. */
    fun unproject(hit: FloatArray): FloatArray? {
        val direction=FloatArray(3) { hit[it]-eye[it] }
        fun triangle(i0: Int,i1: Int,i2: Int): FloatArray? {
            val e1x=positions[i1*3]-positions[i0*3]; val e1y=positions[i1*3+1]-positions[i0*3+1]; val e1z=positions[i1*3+2]-positions[i0*3+2]
            val e2x=positions[i2*3]-positions[i0*3]; val e2y=positions[i2*3+1]-positions[i0*3+1]; val e2z=positions[i2*3+2]-positions[i0*3+2]
            val px=direction[1]*e2z-direction[2]*e2y; val py=direction[2]*e2x-direction[0]*e2z; val pz=direction[0]*e2y-direction[1]*e2x
            val determinant=e1x*px+e1y*py+e1z*pz
            if(kotlin.math.abs(determinant)<1e-12f) return null
            val tx=eye[0]-positions[i0*3]; val ty=eye[1]-positions[i0*3+1]; val tz=eye[2]-positions[i0*3+2]
            val u=(tx*px+ty*py+tz*pz)/determinant
            if(u < -.0001f || u > 1.0001f) return null
            val qx=ty*e1z-tz*e1y; val qy=tz*e1x-tx*e1z; val qz=tx*e1y-ty*e1x
            val v=(direction[0]*qx+direction[1]*qy+direction[2]*qz)/determinant
            if(v < -.0001f || u+v > 1.0001f || (e2x*qx+e2y*qy+e2z*qz)/determinant <= 0f) return null
            val imageU=texCoords[i0*2]*(1f-u-v)+texCoords[i1*2]*u+texCoords[i2*2]*v
            val imageV=texCoords[i0*2+1]*(1f-u-v)+texCoords[i1*2+1]*u+texCoords[i2*2+1]*v
            return floatArrayOf((imageU-.5f)*width,(.5f-imageV)*height,0f)
        }
        // Most hits need only the few triangles surrounding the nearest vertex.
        var nearest=0; var best=Float.POSITIVE_INFINITY
        for(i in 0 until positions.size/3) {
            val dx=positions[i*3]-hit[0]; val dy=positions[i*3+1]-hit[1]; val dz=positions[i*3+2]-hit[2]
            val distance=dx*dx+dy*dy+dz*dz
            if(distance<best) { best=distance; nearest=i }
        }
        val nearRow=nearest/(columns+1); val nearCol=nearest%(columns+1)
        for(row in (nearRow-1).coerceAtLeast(0)..(nearRow+1).coerceAtMost(rows-1)) for(col in (nearCol-1).coerceAtLeast(0)..(nearCol+1).coerceAtMost(columns-1)) {
            val a=row*(columns+1)+col
            triangle(a,a+columns+1,a+1)?.let { return it }
            triangle(a+1,a+columns+1,a+columns+2)?.let { return it }
        }
        for(i in indices.indices step 3) triangle(indices[i],indices[i+1],indices[i+2])?.let { return it }
        return null
    }
}

/** Select the visible image using a ray in monitor-local coordinates, never SDK-localized hits. */
internal fun monitorRayHit(origin: FloatArray, direction: FloatArray, width: Float, height: Float, mesh: MonitorMesh?): FloatArray? {
    fun inside(hit: FloatArray): Boolean {
        val flat = if (mesh != null) mesh.unproject(hit) ?: return false else hit
        return kotlin.math.abs(flat[0]) <= width*.5f+.001f && kotlin.math.abs(flat[1]) <= height*.5f+.001f
    }
    if (mesh == null) {
        if (kotlin.math.abs(direction[2]) < 1e-6f) return null
        val t = -origin[2]/direction[2]
        if (t <= 0f) return null
        return FloatArray(3) { origin[it]+direction[it]*t }.takeIf { inside(it) }
    }
    val delta = FloatArray(3) { origin[it]-mesh.eye[it] }
    fun dot(a: FloatArray,b: FloatArray) = (0..2).sumOf { (a[it]*b[it]).toDouble() }.toFloat()
    val a = dot(direction,direction)
    if (a < 1e-10f) return null
    val b = dot(delta,direction)
    val c = dot(delta,delta)-mesh.radius*mesh.radius
    val discriminant = b*b-a*c
    if (discriminant < 0f) return null
    for (t in listOf((-b-sqrt(discriminant))/a,(-b+sqrt(discriminant))/a)) {
        if (t <= 0f) continue
        val hit = FloatArray(3) { origin[it]+direction[it]*t }
        if (inside(hit)) return hit
    }
    return null
}

/** Use the collision event's own transform, not a potentially different scene-reference pose. */
internal fun eventMatrixHit(hit: FloatArray, matrix: FloatArray,
                            width: Float, height: Float, mesh: MonitorMesh?, inputScale: Float = 1f): FloatArray? {
    if (!inputScale.isFinite() || inputScale <= 0f) return null
    val unscaled = FloatArray(3) { hit[it]/inputScale }
    fun transform(m: FloatArray): FloatArray {
        val w=m[3]*unscaled[0]+m[7]*unscaled[1]+m[11]*unscaled[2]+m[15]
        return FloatArray(3) { (m[it]*unscaled[0]+m[4+it]*unscaled[1]+m[8+it]*unscaled[2]+m[12+it])/w }
    }
    fun error(p: FloatArray): Float = if (mesh == null) kotlin.math.abs(p[2]) else
        kotlin.math.abs(kotlin.math.sqrt((0..2).sumOf { val d=p[it]-mesh.eye[it]; (d*d).toDouble() }).toFloat()-mesh.radius)
    return transform(matrix).takeIf { p ->
        if (!p.all { it.isFinite() } || error(p) > .015f) false else {
            val flat = if (mesh == null) p else mesh.unproject(p)
            flat != null && kotlin.math.abs(flat[0]) <= width*.5f+.001f && kotlin.math.abs(flat[1]) <= height*.5f+.001f
        }
    }
}
