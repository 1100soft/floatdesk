package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import java.nio.file.Paths

internal data class GrabGuide(
    val corners: List<FloatArray>,
    val contacts: List<FloatArray>,
    val pinches: List<FloatArray>,
    val unitsPerMeter: Float,
    val eye: FloatArray? = null,
    val outline: List<FloatArray> = corners,
)

/** Lift only the visible decoration, along its view ray, without moving the logical held pixel. */
internal fun visibleGrabContact(contact: FloatArray, eye: FloatArray?, unitsPerMeter: Float): FloatArray {
    if (eye == null) return contact
    val delta = FloatArray(3) { eye[it] - contact[it] }
    val range = kotlin.math.sqrt(delta.sumOf { (it * it).toDouble() }).toFloat()
    if (range < 1e-6f) return contact
    val offset = minOf(0.008f * unitsPerMeter, range * 0.01f)
    return FloatArray(3) { contact[it] + delta[it] * offset / range }
}

/** Native meshes without Interactable/Movable components: guides must not capture hand input. */
internal class GrabGuideRenderer private constructor(
    private val session: Session,
    private val models: List<GltfModel>,
) : AutoCloseable {
    private class Group(val root: Entity, val parts: List<GltfModelEntity>)
    private val groups = mutableListOf<Group>()
    private var closed = false
    private var pointerPoint: GltfModelEntity? = null
    private val aimBeams=mutableListOf<SurfaceEntity>()
    private val grabBeams=mutableListOf<SurfaceEntity>()
    private val grabPoints=mutableListOf<SurfaceEntity>()

    private fun newGroup(edgeCount: Int): Group {
        val root = Entity.create(session, name = "Floatdesk grab guide")
        // Outline segments, two tethers, then matching contact/pinch spheres.
        val indices = List(edgeCount) { 0 } + listOf(0, 1, 2, 3, 2, 3)
        val parts = indices.map { GltfModelEntity.create(session, models[it], parent = root) }
        return Group(root, parts)
    }

    fun update(parent: Entity, guides: List<GrabGuide>) {
        if (closed) return
        val tethers=mutableListOf<Pair<FloatArray,FloatArray>>()
        val contacts=mutableListOf<FloatArray>()
        for ((index, guide) in guides.withIndex()) {
            val edgeCount = guide.outline.size
            val previous = groups.getOrNull(index)
            val group = if (previous?.parts?.size == edgeCount + 6) previous else {
                previous?.let { old -> old.parts.forEach { it.parent = null }; old.root.parent = null }
                newGroup(edgeCount).also { if (index < groups.size) groups[index] = it else groups.add(it) }
            }
            group.root.parent = parent
            group.root.setEnabled(true)
            val unit = guide.unitsPerMeter
            for (edge in 0 until edgeCount) beam(group.parts[edge], guide.outline[edge], guide.outline[(edge + 1) % edgeCount], 0.0025f * unit)
            for (hand in 0..1) {
                val contact = visibleGrabContact(guide.contacts[hand], guide.eye, unit)
                group.parts[edgeCount + hand].setEnabled(false)
                group.parts[edgeCount + 2 + hand].setEnabled(false)
                tethers.add(contact to guide.pinches[hand]); contacts.add(contact)
                point(group.parts[edgeCount + 4 + hand], guide.pinches[hand], 0.004f * unit)
            }
        }
        for (index in guides.size until groups.size) groups[index].root.setEnabled(false)
        val unit=guides.firstOrNull()?.unitsPerMeter ?: 1f
        val eye=guides.firstOrNull()?.eye
        surfaceRays(parent,tethers,eye,.0015f*unit,1f,grabBeams,true)
        surfacePoints(parent,contacts,eye,.012f*unit)
    }

    fun updatePointer(parent: Entity, position: FloatArray?, eye: FloatArray?, unitsPerMeter: Float) {
        if (closed) return
        if (position == null) { pointerPoint?.setEnabled(false); return }
        val entity = pointerPoint ?: GltfModelEntity.create(session,models[2]).also { pointerPoint=it; it.setAlpha(.2f) }
        entity.parent=parent
        entity.setEnabled(true)
        point(entity,visibleGrabContact(position,eye,unitsPerMeter),.003f*unitsPerMeter)
    }

    fun updateRays(parent: Entity,rays: List<Pair<FloatArray,FloatArray>>,eye: FloatArray?,unitsPerMeter: Float,dimmed: Boolean) {
        surfaceRays(parent,rays,eye,.0009f*unitsPerMeter,if(dimmed) .18f else .4f,aimBeams,false)
    }

    @android.annotation.SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    private fun surfaceRays(parent: Entity,rays: List<Pair<FloatArray,FloatArray>>,eye: FloatArray?,width: Float,alpha: Float,
                            surfaces: MutableList<SurfaceEntity>,alternating: Boolean) {
        if(closed) return
        while(surfaces.size<rays.size) {
            val surface=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(1f,.001f)),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)
            surface.setSurfacePixelDimensions(IntSize2d(128,4))
            surface.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            // A plain native surface has no Compose input policy or InteractableComponent.
            // The entire beam shares one compositor layer, including where it overlaps video.
            val canvas=surface.getSurface().lockCanvas(null)
            canvas.drawColor(if(alternating && surfaces.size%2==1) android.graphics.Color.rgb(255,211,97) else android.graphics.Color.rgb(108,249,255))
            surface.getSurface().unlockCanvasAndPost(canvas)
            surfaces.add(surface)
        }
        for((index,entity) in surfaces.withIndex()) {
            val ray=rays.getOrNull(index)
            if(ray==null || eye==null) { entity.setEnabled(false); continue }
            val a=vector(ray.first); val b=vector(ray.second); val delta=b-a
            if(delta.length<.00001f) { entity.setEnabled(false); continue }
            val along=delta.toNormalized(); val middle=(a+b)*.5f
            val toward=vector(eye)-middle
            val dot=toward.x*along.x+toward.y*along.y+toward.z*along.z
            val normal=toward-along*dot
            if(normal.length<.00001f) { entity.setEnabled(false); continue }
            val up=Vector3(normal.y*along.z-normal.z*along.y,normal.z*along.x-normal.x*along.z,normal.x*along.y-normal.y*along.x)
            entity.shape=SurfaceEntity.Shape.Quad(FloatSize2d(delta.length,width))
            entity.setPose(Pose(middle,Quaternion.fromLookTowards(normal,up)))
            entity.setAlpha(alpha)
            entity.parent=parent; entity.setEnabled(true)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    private fun surfacePoints(parent: Entity,points: List<FloatArray>,eye: FloatArray?,diameter: Float) {
        while(grabPoints.size<points.size) {
            val entity=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(diameter,diameter)),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)
            entity.setSurfacePixelDimensions(IntSize2d(32,32))
            entity.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            val canvas=entity.getSurface().lockCanvas(null)
            canvas.drawColor(android.graphics.Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color=if(grabPoints.size%2==1) android.graphics.Color.rgb(255,211,97) else android.graphics.Color.rgb(108,249,255)
            canvas.drawCircle(16f,16f,15f,paint)
            entity.getSurface().unlockCanvasAndPost(canvas)
            grabPoints.add(entity)
        }
        for((index,entity) in grabPoints.withIndex()) {
            val position=points.getOrNull(index)
            if(position==null || eye==null) { entity.setEnabled(false); continue }
            entity.shape=SurfaceEntity.Shape.Quad(FloatSize2d(diameter,diameter))
            entity.setPose(Pose(vector(position),Quaternion.fromLookTowards(vector(eye)-vector(position),Vector3.Up)))
            entity.parent=parent; entity.setEnabled(true)
        }
    }

    private fun vector(value: FloatArray) = Vector3(value[0], value[1], value[2])

    private fun beam(entity: GltfModelEntity, from: FloatArray, to: FloatArray, width: Float) {
        val a = vector(from)
        val b = vector(to)
        val delta = b - a
        if (delta.length < 1e-6f) { entity.setEnabled(false); return }
        entity.setEnabled(true)
        entity.setPose(Pose((a + b) * 0.5f, Quaternion.fromRotation(Vector3.Right, delta)))
        // A glTF node's public localScale supports nonuniform scale without restricted entity APIs.
        entity.nodes.first().localScale = Vector3(delta.length, width, width)
    }

    private fun point(entity: GltfModelEntity, position: FloatArray, radius: Float) {
        entity.setPose(Pose(vector(position)))
        entity.setScale(radius)
    }

    override fun close() {
        if (closed) return
        closed = true
        groups.forEach { group ->
            group.parts.forEach { it.parent = null }
            group.root.parent = null
        }
        (aimBeams+grabBeams+grabPoints).forEach { it.parent=null }
        aimBeams.clear(); grabBeams.clear(); grabPoints.clear()
        pointerPoint?.parent = null; pointerPoint = null
        groups.clear()
        models.forEach { it.close() }
    }

    companion object {
        suspend fun create(session: Session): GrabGuideRenderer {
            val loaded = mutableListOf<GltfModel>()
            try {
                for (asset in listOf("cyan-beam", "amber-beam", "cyan-point", "amber-point")) {
                    loaded.add(GltfModel.create(session, Paths.get("grab-guides/$asset.glb")))
                }
                return GrabGuideRenderer(session, loaded)
            } catch (failure: Throwable) {
                loaded.forEach { it.close() }
                throw failure
            }
        }
    }
}
