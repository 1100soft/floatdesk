package dev.floatdesk.client

import kotlin.math.sqrt

/** A rectangular image projected radially onto a viewer-centered sphere, in monitor-local units. */
internal class MonitorMesh(
    val width: Float,
    val height: Float,
    val eye: FloatArray,
    val columns: Int = 64,
    val rows: Int = 40,
) {
    val radius = sqrt(eye.sumOf { (it * it).toDouble() }).toFloat()
    val positions = FloatArray((columns + 1) * (rows + 1) * 3)
    val texCoords = FloatArray((columns + 1) * (rows + 1) * 2)
    val indices = IntArray(columns * rows * 6)
    val outline: List<FloatArray>

    init {
        require(width.isFinite() && height.isFinite() && eye.size == 3 && eye.all { it.isFinite() } &&
            width > 0f && height > 0f && radius.isFinite() && radius > 0f && columns > 0 && rows > 0)
        for (row in 0..rows) for (col in 0..columns) {
            val u = col.toFloat() / columns
            val v = row.toFloat() / rows
            val dx = (u - .5f) * width - eye[0]
            val dy = (.5f - v) * height - eye[1]
            val dz = -eye[2]
            val factor = radius / sqrt(dx * dx + dy * dy + dz * dz)
            val vertex = row * (columns + 1) + col
            positions[vertex * 3] = eye[0] + dx * factor
            positions[vertex * 3 + 1] = eye[1] + dy * factor
            positions[vertex * 3 + 2] = eye[2] + dz * factor
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
        val delta = FloatArray(3) { flat[it] - eye[it] }
        val distance = sqrt(delta.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(3) { eye[it] + delta[it] * radius / distance }
    }

    /** Recover the image-plane pixel location from a hit on the curved mesh. */
    fun unproject(hit: FloatArray): FloatArray? {
        val dz = hit[2] - eye[2]
        if (kotlin.math.abs(dz) < 1e-6f) return null
        val t = -eye[2] / dz
        if (t <= 0f) return null
        return floatArrayOf(eye[0] + (hit[0] - eye[0]) * t, eye[1] + (hit[1] - eye[1]) * t, 0f)
    }
}
