package dev.floatdesk.client

import kotlin.math.sqrt

/** Conformal stereographic image projection: equal local pixel scale on both spherical axes. */
internal class MonitorMesh(
    val width: Float,
    val height: Float,
    val eye: FloatArray,
    val columns: Int = 64,
    val rows: Int = 40,
) {
    val radius = sqrt(eye.sumOf { (it * it).toDouble() }).toFloat()
    private val normal = FloatArray(3) { eye[it] / radius }
    private val tangentX = floatArrayOf(1f-normal[0]*normal[0]/(1f+normal[2]), -normal[0]*normal[1]/(1f+normal[2]), -normal[0])
    private val tangentY = floatArrayOf(-normal[0]*normal[1]/(1f+normal[2]), 1f-normal[1]*normal[1]/(1f+normal[2]), -normal[1])
    val positions = FloatArray((columns + 1) * (rows + 1) * 3)
    val texCoords = FloatArray((columns + 1) * (rows + 1) * 2)
    // SurfaceEntity video sampling uses bottom-left UVs. Embedded PNG/glTF uses top-left UVs.
    val videoTexCoords: FloatArray
        get() = FloatArray(texCoords.size) { if (it % 2 == 1) 1f - texCoords[it] else texCoords[it] }
    val indices = IntArray(columns * rows * 6)
    val outline: List<FloatArray>

    init {
        require(width.isFinite() && height.isFinite() && eye.size == 3 && eye.all { it.isFinite() } &&
            width > 0f && height > 0f && radius.isFinite() && radius > 0f && columns > 0 && rows > 0)
        for (row in 0..rows) for (col in 0..columns) {
            val u = col.toFloat() / columns
            val v = row.toFloat() / rows
            val point = project(floatArrayOf((u-.5f)*width,(.5f-v)*height,0f))
            val vertex = row * (columns + 1) + col
            point.copyInto(positions, vertex * 3)
            texCoords[vertex * 2] = u
            texCoords[vertex * 2 + 1] = v
        }
        var offset = 0
        for (row in 0 until rows) for (col in 0 until columns) {
            val a = row * (columns + 1) + col
            // Front faces point toward +Z, matching the original flat monitor.
            indices[offset++] = a
            indices[offset++] = a + columns + 1
            indices[offset++] = a + 1
            indices[offset++] = a + 1
            indices[offset++] = a + columns + 1
            indices[offset++] = a + columns + 2
        }
        fun vertex(row: Int, col: Int) = positions.copyOfRange((row * (columns + 1) + col) * 3, (row * (columns + 1) + col) * 3 + 3)
        outline = buildList {
            for (col in 0 until columns step 4) add(vertex(0, col))
            for (row in 0 until rows step 4) add(vertex(row, columns))
            for (col in columns downTo 1 step 4) add(vertex(rows, col))
            for (row in rows downTo 1 step 4) add(vertex(row, 0))
        }
    }

    fun project(flat: FloatArray): FloatArray {
        val r2 = (flat[0]*flat[0] + flat[1]*flat[1]) / (4f*radius*radius)
        val k = 1f / (1f+r2)
        return FloatArray(3) { (flat[0]*tangentX[it] + flat[1]*tangentY[it] + 2f*radius*r2*normal[it])*k }
    }

    /** Inverse stereographic projection, used for image bounds and precise curved-ray hits. */
    fun unproject(hit: FloatArray): FloatArray? {
        val z = hit.indices.sumOf { (hit[it]*normal[it]).toDouble() }.toFloat()
        val denominator = 1f - z/(2f*radius)
        if (denominator < 1e-5f) return null
        fun along(axis: FloatArray) = (0..2).sumOf { (hit[it]*axis[it]).toDouble() }.toFloat()/denominator
        return floatArrayOf(along(tangentX),along(tangentY),0f)
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
