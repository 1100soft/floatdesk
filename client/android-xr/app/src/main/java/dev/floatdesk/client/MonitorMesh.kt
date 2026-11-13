package dev.floatdesk.client

import kotlin.math.sqrt

/** Desk poses and image dimensions share units; do not send an already-local eye through another scene space. */
internal fun viewerInMonitor(viewer: FloatArray, position: FloatArray, rotation: FloatArray): FloatArray {
    val x=viewer[0]-position[0]; val y=viewer[1]-position[1]; val z=viewer[2]-position[2]
    val qx=-rotation[0]; val qy=-rotation[1]; val qz=-rotation[2]; val qw=rotation[3]
    val tx=2f*(qy*z-qz*y); val ty=2f*(qz*x-qx*z); val tz=2f*(qx*y-qy*x)
    return floatArrayOf(x+qw*tx+qy*tz-qz*ty,y+qw*ty+qz*tx-qx*tz,z+qw*tz+qx*ty-qy*tx)
}

/** Viewer-centered sphere with optional tangent-plane vertex projection around the viewed pixel. */
internal class MonitorMesh(
    val width: Float,
    val height: Float,
    val eye: FloatArray,
    val columns: Int = 64,
    val rows: Int = 40,
    focus:FloatArray?=null,
    val center:FloatArray=floatArrayOf(0f,0f,0f),
) {
    val radius = sqrt((0..2).sumOf { val d=eye[it]-center[it];(d*d).toDouble() }).toFloat()
    val normal = FloatArray(3) { (eye[it]-center[it]) / radius }
    val tangentX = floatArrayOf(1f-normal[0]*normal[0]/(1f+normal[2]), -normal[0]*normal[1]/(1f+normal[2]), -normal[0])
    val tangentY = floatArrayOf(-normal[0]*normal[1]/(1f+normal[2]), 1f-normal[1]*normal[1]/(1f+normal[2]), -normal[1])
    var projection:ViewProjection?=null
    var frameNormal=normal;private set
    var frameTangentX=tangentX;private set
    var frameTangentY=tangentY;private set
    // Preserve the local angular scale; compress only the far periphery before the antipode.
    val radiansPerMeter = if(focus==null) minOf(1f/radius,2.2f/sqrt(width*width+height*height)) else 1f/radius
    val focus=focus?.copyOf()
    private fun angularDistance(a:Float):Float {
        val start=1.5f
        val span=Math.PI.toFloat()-.01f-start
        return if(a<=start) a else start+span*(1f-kotlin.math.exp(-(a-start)/span))
    }
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
        if(focus==null) {
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
        } else {
            // The old equal-edge recurrence folds at wider fields. The geodesic map
            // is valid up to its antipodal cut and needs only the unchanged source UVs.
            for(row in 0..rows) for(col in 0..columns) {
                val vertex=row*(columns+1)+col
                texCoords[vertex*2]=col.toFloat()/columns;texCoords[vertex*2+1]=row.toFloat()/rows
            }
        }
        // Spherical exponential map: angular distance from focus is preserved.
        // Only the axes through the viewed point are great circles; peripheral curvature remains.
        // Keep that point on the reference sphere so changing focus does not chase itself.
        this.focus?.let { centre ->
            require(centre.size==2 && centre.all { it.isFinite() })
            val referenceX=(centre[0]-center[0])*radiansPerMeter;val referenceY=(centre[1]-center[1])*radiansPerMeter
            val referenceLength=sqrt(referenceX*referenceX+referenceY*referenceY)
            val referenceAngle=angularDistance(referenceLength)
            val referenceSinc=if(referenceAngle<.00001f) 1f else kotlin.math.sin(referenceAngle)/referenceLength
            val anchor=FloatArray(3){eye[it]+radius*(-kotlin.math.cos(referenceAngle)*normal[it]+referenceSinc*(referenceX*tangentX[it]+referenceY*tangentY[it]))}
            val n=FloatArray(3) { (eye[it]-anchor[it])/radius }
            fun cross(a:FloatArray,b:FloatArray)=floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
            val turn=cross(normal,n)
            val turnLength=sqrt(turn.sumOf{(it*it).toDouble()}).toFloat()
            val cosine=kotlin.math.cos(referenceAngle)
            val sine=kotlin.math.sin(referenceAngle)
            fun transport(t:FloatArray):FloatArray {
                if(turnLength<.000001f)return t.copyOf()
                val axis=FloatArray(3){turn[it]/turnLength}
                val perpendicular=cross(axis,t)
                val parallel=(0..2).sumOf{(axis[it]*t[it]).toDouble()}.toFloat()
                return FloatArray(3){t[it]*cosine+perpendicular[it]*sine+axis[it]*parallel*(1f-cosine)}
            }
            val tx=transport(tangentX);val ty=transport(tangentY)
            frameNormal=n;frameTangentX=tx;frameTangentY=ty
            for(row in 0..rows) for(col in 0..columns) {
                val x=((col.toFloat()/columns-.5f)*width-centre[0])*radiansPerMeter
                val y=((.5f-row.toFloat()/rows)*height-centre[1])*radiansPerMeter
                val sourceLength=sqrt(x*x+y*y)
                val angle=angularDistance(sourceLength)
                val sinc=if(sourceLength<.00001f) 1f else kotlin.math.sin(angle)/sourceLength
                val cosine=kotlin.math.cos(angle)
                val vertex=row*(columns+1)+col
                for(axis in 0..2) positions[vertex*3+axis]=eye[axis]+radius*(-cosine*n[axis]+sinc*(x*tx[axis]+y*ty[axis]))
            }
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
    fun project(flat: FloatArray, applyProjection:Boolean=true, onTriangles:Boolean=false): FloatArray {
        if(applyProjection && projection!=null) {
            val uv=projection!!.inverse(flat[0]/width+.5f,.5f-flat[1]/height)
            return project(floatArrayOf((uv[0]-.5f)*width,(.5f-uv[1])*height,0f),false,onTriangles)
        }
        val u=(flat[0]/width+.5f)*columns; val v=(.5f-flat[1]/height)*rows
        val col=kotlin.math.floor(u).toInt().coerceIn(0,columns-1)
        val row=kotlin.math.floor(v).toInt().coerceIn(0,rows-1)
        val x=u-col; val y=v-row; val a=row*(columns+1)+col
        val verts=if(x+y<=1f) intArrayOf(a,a+columns+1,a+1) else intArrayOf(a+1,a+columns+1,a+columns+2)
        val weights=if(x+y<=1f) floatArrayOf(1f-x-y,y,x) else floatArrayOf(1f-y,1f-x,x+y-1f)
        val direction=FloatArray(3) { axis -> (0..2).sumOf { (positions[verts[it]*3+axis]*weights[it]).toDouble() }.toFloat()-eye[axis] }
        if(onTriangles) return FloatArray(3){eye[it]+direction[it]}
        val length=sqrt(direction.sumOf { (it*it).toDouble() }).toFloat()
        return FloatArray(3) { eye[it]+direction[it]*radius/length }
    }

    /** Invert the actual texture triangles, so curved ray contacts retain their exact image pixels. */
    fun unproject(hit: FloatArray, applyProjection:Boolean=true): FloatArray? {
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
            val uv=if(applyProjection) projection?.source(imageU,imageV) else null
            return floatArrayOf(((uv?.get(0) ?: imageU)-.5f)*width,(.5f-(uv?.get(1) ?: imageV))*height,0f)
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

/** Ray/triangle selection shared by native hover, gaze and grab acquisition.
 * Intersect the rendered triangles, not a sphere plus a fixed coarse-collider tolerance.
 */
internal fun meshRayHit(mesh:MonitorMesh,origin:FloatArray,direction:FloatArray):FloatArray? {
    var nearest=Float.POSITIVE_INFINITY
    val p=mesh.positions
    for(i in mesh.indices.indices step 3) {
        val a=mesh.indices[i]*3;val b=mesh.indices[i+1]*3;val c=mesh.indices[i+2]*3
        val ex=p[b]-p[a];val ey=p[b+1]-p[a+1];val ez=p[b+2]-p[a+2]
        val fx=p[c]-p[a];val fy=p[c+1]-p[a+1];val fz=p[c+2]-p[a+2]
        val hx=direction[1]*fz-direction[2]*fy;val hy=direction[2]*fx-direction[0]*fz;val hz=direction[0]*fy-direction[1]*fx
        val det=ex*hx+ey*hy+ez*hz
        if(kotlin.math.abs(det)<1e-10f)continue
        val tx=origin[0]-p[a];val ty=origin[1]-p[a+1];val tz=origin[2]-p[a+2]
        val u=(tx*hx+ty*hy+tz*hz)/det
        if(u<-.00001f || u>1.00001f)continue
        val qx=ty*ez-tz*ey;val qy=tz*ex-tx*ez;val qz=tx*ey-ty*ex
        val v=(direction[0]*qx+direction[1]*qy+direction[2]*qz)/det
        if(v<-.00001f || u+v>1.00001f)continue
        val t=(fx*qx+fy*qy+fz*qz)/det
        if(t>0f && t<nearest)nearest=t
    }
    return if(nearest.isFinite()) FloatArray(3){origin[it]+direction[it]*nearest} else null
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
    return meshRayHit(mesh,origin,direction)?.takeIf {inside(it)}
}

/** Use the collision event's own transform, not a potentially different scene-reference pose. */
internal fun eventMatrixHit(hit: FloatArray, matrix: FloatArray,
                            width: Float, height: Float, mesh: MonitorMesh?, inputScale: Float = 1f, rayDirection:FloatArray?=null,rayOrigin:FloatArray?=null): FloatArray? {
    if (!inputScale.isFinite() || inputScale <= 0f) return null
    val unscaled = FloatArray(3) { hit[it]/inputScale }
    fun transform(m: FloatArray): FloatArray {
        val w=m[3]*unscaled[0]+m[7]*unscaled[1]+m[11]*unscaled[2]+m[15]
        return FloatArray(3) { (m[it]*unscaled[0]+m[4+it]*unscaled[1]+m[8+it]*unscaled[2]+m[12+it])/w }
    }
    fun error(p: FloatArray): Float = if (mesh == null) kotlin.math.abs(p[2]) else
        kotlin.math.abs(kotlin.math.sqrt((0..2).sumOf { val d=p[it]-mesh.eye[it]; (d*d).toDouble() }).toFloat()-mesh.radius)
    var transformed=transform(matrix)
    if(rayDirection!=null && mesh!=null) {
        val ray=FloatArray(3){axis->matrix[axis]*rayDirection[0]+matrix[4+axis]*rayDirection[1]+matrix[8+axis]*rayDirection[2]}
        val length=sqrt(ray.sumOf {(it*it).toDouble()}).toFloat()
        if(!length.isFinite() || length<1e-8f)return null
        for(i in ray.indices)ray[i]/=length
        val origin=rayOrigin?.let { source ->
            val v=FloatArray(3){source[it]/inputScale}
            val w=matrix[3]*v[0]+matrix[7]*v[1]+matrix[11]*v[2]+matrix[15]
            FloatArray(3){(matrix[it]*v[0]+matrix[4+it]*v[1]+matrix[8+it]*v[2]+matrix[12+it])/w}
        } ?: run {
            // Compatibility for callers without a native origin: start before the whole sphere.
            FloatArray(3){transformed[it]-ray[it]*(2f*mesh.radius+.03f)}
        }
        return monitorRayHit(origin,ray,width,height,mesh)

    }
    return transformed.takeIf { p ->
        if (!p.all { it.isFinite() } || error(p) > .015f) false else {
            val flat = if (mesh == null) p else mesh.unproject(p)
            flat != null && kotlin.math.abs(flat[0]) <= width*.5f+.001f && kotlin.math.abs(flat[1]) <= height*.5f+.001f
        }
    }
}

/** Immutable, bounded GPU lookup: physical surface UV -> source presentation UV.
 * Sphere vertices, native colliders and output boundaries never change with focus.
 */
internal class ViewProjection(val columns:Int,val rows:Int,val sourceUV:FloatArray,val bounds:FloatArray) {
    init { require(columns>0 && rows>0 && sourceUV.size==(columns+1)*(rows+1)*2 && sourceUV.all {it.isFinite() && it in 0f..1f}) }
    val encodedRgba=ByteArray((columns+1)*(rows+1)*4).also { out ->
        for(i in 0 until sourceUV.size/2) for(axis in 0..1) {
            val value=(sourceUV[i*2+axis]*65535f+.5f).toInt().coerceIn(0,65535)
            out[i*4+axis*2]=(value ushr 8).toByte();out[i*4+axis*2+1]=value.toByte()
        }
    }
    fun source(u:Float,v:Float):FloatArray {
        var rect=-1
        for(i in bounds.indices step 4) if(u>=bounds[i] && u<=bounds[i+2] && v>=bounds[i+1] && v<=bounds[i+3]) {rect=i;break}
        if(rect<0)return floatArrayOf(u,v)
        val x=u.coerceIn(0f,1f)*columns;val y=v.coerceIn(0f,1f)*rows
        val col=kotlin.math.floor(x).toInt().coerceIn(0,columns-1);val row=kotlin.math.floor(y).toInt().coerceIn(0,rows-1)
        val fx=x-col;val fy=y-row
        fun lookup(axis:Int):Float {
            fun p(dx:Int,dy:Int)=sourceUV[((row+dy)*(columns+1)+col+dx)*2+axis]
            return (p(0,0)*(1-fx)+p(1,0)*fx)*(1-fy)+(p(0,1)*(1-fx)+p(1,1)*fx)*fy
        }
        val wx=kotlin.math.sin(Math.PI*(u-bounds[rect])/(bounds[rect+2]-bounds[rect])).toFloat()
        val wy=kotlin.math.sin(Math.PI*(v-bounds[rect+1])/(bounds[rect+3]-bounds[rect+1])).toFloat()
        val weight=wx*wx*wy*wy
        val maxX=.04f*(bounds[rect+2]-bounds[rect]);val maxY=.04f*(bounds[rect+3]-bounds[rect+1])
        return floatArrayOf(u+(lookup(0)-u).coerceIn(-maxX,maxX)*weight,v+(lookup(1)-v).coerceIn(-maxY,maxY)*weight)
    }
    fun inverse(u:Float,v:Float):FloatArray {
        var x=u;var y=v
        for(i in 0 until 16) {
            val mapped=source(x,y)
            val dx=u-mapped[0];val dy=v-mapped[1]
            if(kotlin.math.abs(dx)+kotlin.math.abs(dy)<.0000001f) break
            x=(x+dx).coerceIn(0f,1f);y=(y+dy).coerceIn(0f,1f)
        }
        return floatArrayOf(x,y)
    }
    companion object {
        fun create(mesh:MonitorMesh,focus:FloatArray,rects:FloatArray,spacing:Float):ViewProjection {
            val anchor=mesh.project(floatArrayOf(focus[0],focus[1],0f),false)
            val normal=FloatArray(3){(mesh.eye[it]-anchor[it])/mesh.radius}
            fun cross(a:FloatArray,b:FloatArray)=floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
            fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf {(a[it]*b[it]).toDouble()}.toFloat()
            val turn=cross(mesh.normal,normal);val cosine=dot(mesh.normal,normal)
            fun transport(t:FloatArray):FloatArray {val a=cross(turn,t);val b=cross(turn,a);return FloatArray(3){t[it]+a[it]+b[it]/(1+cosine).coerceAtLeast(.001f)}}
            val tx=transport(mesh.tangentX);val ty=transport(mesh.tangentY)
            val uv=FloatArray(mesh.texCoords.size)
            for(i in 0 until uv.size/2) {
                val u=mesh.texCoords[i*2];val v=mesh.texCoords[i*2+1]
                val ray=FloatArray(3){(mesh.positions[i*3+it]-mesh.eye[it])/mesh.radius}
                val denominator=-dot(ray,normal)
                var dx=0f;var dy=0f
                if(denominator>.3f) {
                    val x=focus[0]+dot(ray,tx)/(denominator*mesh.radiansPerMeter)
                    val y=focus[1]+dot(ray,ty)/(denominator*mesh.radiansPerMeter)
                    val ax=((u-.5f)*mesh.width-focus[0])*mesh.radiansPerMeter
                    val ay=((.5f-v)*mesh.height-focus[1])*mesh.radiansPerMeter
                    val falloff=kotlin.math.exp(-(ax*ax+ay*ay)/.5f)
                    // Correct only a local fraction; full gnomonic remapping looks planar.
                    dx=(x/mesh.width+.5f-u).coerceIn(-.04f,.04f)*.35f*falloff
                    dy=(.5f-y/mesh.height-v).coerceIn(-.04f,.04f)*.35f*falloff
                }
                uv[i*2]=(u+dx).coerceIn(0f,1f);uv[i*2+1]=(v+dy).coerceIn(0f,1f)
            }
            val bounds=FloatArray(rects.size)
            for(i in rects.indices step 4) {
                val cx=(rects[i]+rects[i+2])*.5f;val cy=(rects[i+1]+rects[i+3])*.5f;val scale=1-spacing
                bounds[i]=cx+(rects[i]-cx)*scale;bounds[i+1]=cy+(rects[i+1]-cy)*scale
                bounds[i+2]=cx+(rects[i+2]-cx)*scale;bounds[i+3]=cy+(rects[i+3]-cy)*scale
            }
            return ViewProjection(mesh.columns,mesh.rows,uv,bounds)
        }
    }
}

internal data class InteractionPatch(val center:FloatArray,val rotation:FloatArray,val scale:FloatArray,val childRotation:FloatArray)
/** Exact triangle affine transform via two rotations and diagonal scale (2D SVD). */
internal fun interactionPatches(mesh:MonitorMesh,columns:Int=12,rows:Int=8):List<InteractionPatch> {
    fun sub(a:FloatArray,b:FloatArray)=FloatArray(3){a[it]-b[it]}
    fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf {(a[it]*b[it]).toDouble()}.toFloat()
    fun cross(a:FloatArray,b:FloatArray)=floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    fun unit(a:FloatArray):FloatArray {val l=sqrt(dot(a,a));return FloatArray(3){a[it]/l}}
    fun quaternion(x:FloatArray,y:FloatArray,z:FloatArray):FloatArray {
        val trace=x[0]+y[1]+z[2]
        return if(trace>0) {val t=sqrt(trace+1)*2;floatArrayOf((y[2]-z[1])/t,(z[0]-x[2])/t,(x[1]-y[0])/t,t/4)}
        else if(x[0]>y[1] && x[0]>z[2]) {val t=sqrt(1+x[0]-y[1]-z[2])*2;floatArrayOf(t/4,(y[0]+x[1])/t,(z[0]+x[2])/t,(y[2]-z[1])/t)}
        else if(y[1]>z[2]) {val t=sqrt(1+y[1]-x[0]-z[2])*2;floatArrayOf((y[0]+x[1])/t,t/4,(z[1]+y[2])/t,(z[0]-x[2])/t)}
        else {val t=sqrt(1+z[2]-x[0]-y[1])*2;floatArrayOf((z[0]+x[2])/t,(z[1]+y[2])/t,t/4,(x[1]-y[0])/t)}
    }
    fun triangle(a:FloatArray,b:FloatArray,c:FloatArray):InteractionPatch {
        val first=sub(b,a);val second=sub(c,a);val right=unit(first)
        val normal=unit(cross(first,second));val up=unit(cross(normal,right))
        val l=dot(first,right).toDouble();val shear=dot(second,right).toDouble();val h=dot(second,up).toDouble()
        val angle=.5*kotlin.math.atan2(2*l*shear,l*l-shear*shear-h*h)
        val co=kotlin.math.cos(angle);val si=kotlin.math.sin(angle)
        val sx=kotlin.math.hypot(l*co+shear*si,h*si)
        val sy=l*h/sx
        val ux=(l*co+shear*si)/sx;val uy=h*si/sx
        val x=FloatArray(3){(right[it]*ux+up[it]*uy).toFloat()}
        val y=FloatArray(3){(-right[it]*uy+up[it]*ux).toFloat()}
        return InteractionPatch(a,quaternion(x,y,normal),floatArrayOf(sx.toFloat(),sy.toFloat(),1f),floatArrayOf(0f,0f,-kotlin.math.sin(angle/2).toFloat(),kotlin.math.cos(angle/2).toFloat()))
    }
    return buildList {
        for(row in 0 until rows) for(col in 0 until columns) {
            fun p(x:Int,y:Int)=mesh.project(floatArrayOf(((col+x).toFloat()/columns-.5f)*mesh.width,(.5f-(row+y).toFloat()/rows)*mesh.height,0f))
            val a=p(0,0);val b=p(1,0);val c=p(0,1);val d=p(1,1)
            add(triangle(a,c,b));add(triangle(b,c,d))
        }
    }
}

/** Analytic log map of head direction, so a curved edge outside the reference rectangle can still focus. */
internal fun sphericalViewFocus(mesh:MonitorMesh,direction:FloatArray):FloatArray? {
    if(direction.size!=3 || !direction.all {it.isFinite()})return null
    val length=sqrt(direction.sumOf {(it*it).toDouble()}).toFloat()
    if(length<.00001f)return null
    fun dot(axis:FloatArray)=(0..2).sumOf {(direction[it]*axis[it]/length).toDouble()}.toFloat()
    val forward=-dot(mesh.normal)
    if(forward < -.9999f)return null
    val x=dot(mesh.tangentX);val y=dot(mesh.tangentY)
    val sine=sqrt(x*x+y*y)
    val angle=kotlin.math.atan2(sine,forward)
    val span=Math.PI.toFloat()-.01f-1.5f
    val sourceAngle=if(angle<=1.5f) angle else 1.5f-span*kotlin.math.ln((1f-(angle-1.5f)/span).coerceAtLeast(.000001f))
    val scale=(if(sine<.00001f) 1f else sourceAngle/sine)/mesh.radiansPerMeter
    return floatArrayOf((mesh.center[0]+x*scale).coerceIn(-mesh.width*.5f,mesh.width*.5f),(mesh.center[1]+y*scale).coerceIn(-mesh.height*.5f,mesh.height*.5f))
}
