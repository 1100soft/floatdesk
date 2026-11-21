package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
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
@Suppress("DEPRECATION")
@android.annotation.SuppressLint("RestrictedApi")
internal class GrabGuideRenderer private constructor(
    private val session: Session,
    private val models: List<GltfModel>,
) : AutoCloseable {
    private class Group(val root: Entity, val parts: List<GltfModelEntity>)
    private val groups = mutableListOf<Group>()
    private var closed = false
    private val nodeStates=java.util.IdentityHashMap<Entity,NativeNodeState>()
    private fun state(entity:Entity)=nodeStates.getOrPut(entity){NativeNodeState(entity)}
    private var pointerPoint: GltfModelEntity? = null
    private val highlights=mutableMapOf<Int,SurfaceEntity>()
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
            // Retain a single outline pool across flat/curved transitions.
            // Replacing groups would repeatedly create and dispose material instances.
            val edgeCount = 24
            val outline = List(edgeCount) { edge ->
                val fraction = edge.toFloat() * guide.outline.size / edgeCount
                val from = fraction.toInt()
                val a = guide.outline[from]
                val b = guide.outline[(from + 1) % guide.outline.size]
                FloatArray(3) { axis -> a[axis] + (b[axis] - a[axis]) * (fraction - from) }
            }
            val group = groups.getOrNull(index) ?: newGroup(edgeCount).also { groups.add(it) }
            group.root.parent = parent
            group.root.setEnabled(true)
            val unit = guide.unitsPerMeter
            for (edge in 0 until edgeCount) beam(group.parts[edge], outline[edge], outline[(edge + 1) % edgeCount], 0.0025f * unit)
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

    /**
     * A soft translucent rounded rectangle drawn on a plain native surface, which shares the video
     * compositor path (so it is not hidden by the desktop) and has no hit region (so hands aim through).
     * One texture per aspect ratio; [alpha] animates without redrawing.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    fun updateHighlight(parent: Entity, position: FloatArray?, rotation: FloatArray, width: Float, height: Float, alpha: Float) {
        if (closed) return
        val key = if (position == null || width <= 0f || height <= 0f) null else (width / height * 4f).toInt()
        for ((k, entity) in highlights) if (k != key) state(entity).enabled(false)
        if (key == null || position == null) return
        val entity = highlights.getOrPut(key) {
            val pixelHeight = 64; val pixelWidth = (pixelHeight * key / 4f).toInt().coerceIn(pixelHeight, 1024)
            SurfaceEntity.create(session, shape = decorationShape(1f, 1f), parent = null, superSampling = SurfaceEntity.SuperSampling.NONE).also { surface ->
                surface.setSurfacePixelDimensions(IntSize2d(pixelWidth, pixelHeight))
                surface.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.TRANSPARENT
                val canvas = surface.getSurface().lockCanvas(null)
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val feather = pixelHeight * .16f
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = android.graphics.Color.argb(120, 138, 223, 255)
                paint.maskFilter = android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(android.graphics.RectF(feather, feather, pixelWidth - feather, pixelHeight - feather), pixelHeight * .28f, pixelHeight * .28f, paint)
                surface.getSurface().unlockCanvasAndPost(canvas)
            }
        }
        state(entity).scale(Vector3(width, height, 1f))
        state(entity).pose(Pose(vector(position), Quaternion(rotation[0], rotation[1], rotation[2], rotation[3])))
        state(entity).alpha(alpha)
        state(entity).parent(parent); state(entity).enabled(true)
    }

    fun updateRays(parent: Entity,rays: Map<String,Pair<FloatArray,FloatArray>>,eye: FloatArray?,unitsPerMeter: Float,dimmed: Boolean) {
        val slots=listOf("LEFT","RIGHT").map { rays[it] ?: (FloatArray(3) to FloatArray(3)) }
        surfaceRays(parent,slots,eye,.0009f*unitsPerMeter,if(dimmed) .18f else .4f,aimBeams,true)
    }

    @android.annotation.SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    private fun surfaceRays(parent: Entity,rays: List<Pair<FloatArray,FloatArray>>,eye: FloatArray?,width: Float,alpha: Float,
                            surfaces: MutableList<SurfaceEntity>,alternating: Boolean) {
        if(closed) return
        while(surfaces.size<rays.size) {
            val surface=SurfaceEntity.create(session,shape=decorationShape(1f,1f),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)
            surface.setSurfacePixelDimensions(IntSize2d(128,4))
            // Every beam texel is opaque; dimming belongs to entity alpha. This matches
            // the video media path, but large-live-desktop visibility remains unresolved.
            surface.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE
            // A plain native surface has no Compose input policy or InteractableComponent.
            // The entire beam shares one compositor layer, including where it overlaps video.
            val canvas=surface.getSurface().lockCanvas(null)
            canvas.drawColor(if(alternating && surfaces.size%2==1) android.graphics.Color.rgb(193,128,255) else android.graphics.Color.rgb(108,249,255))
            surface.getSurface().unlockCanvasAndPost(canvas)
            surfaces.add(surface)
        }
        for((index,entity) in surfaces.withIndex()) {
            val ray=rays.getOrNull(index)
            if(ray==null || eye==null) { state(entity).enabled(false); continue }
            val a=vector(ray.first); val b=vector(ray.second); val delta=b-a
            if(delta.length<.00001f) { state(entity).enabled(false); continue }
            val along=delta.toNormalized(); val middle=(a+b)*.5f
            val toward=vector(eye)-middle
            val dot=toward.x*along.x+toward.y*along.y+toward.z*along.z
            val normal=toward-along*dot
            if(normal.length<.00001f) { state(entity).enabled(false); continue }
            val up=Vector3(normal.y*along.z-normal.z*along.y,normal.z*along.x-normal.x*along.z,normal.x*along.y-normal.y*along.x)
            // Keep the native mesh allocation stable: replacing it every sample can
            // crash Impress and stall input on the UI thread. Transform the unit mesh.
            state(entity).scale(Vector3(delta.length,width,1f))
            state(entity).pose(Pose(middle,Quaternion.fromLookTowards(normal,up)))
            state(entity).alpha(alpha)
            state(entity).parent(parent); state(entity).enabled(true)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    private fun surfacePoints(parent: Entity,points: List<FloatArray>,eye: FloatArray?,diameter: Float) {
        while(grabPoints.size<points.size) {
            val entity=SurfaceEntity.create(session,shape=decorationShape(1f,1f),
                parent=null,superSampling=SurfaceEntity.SuperSampling.NONE)
            entity.setSurfacePixelDimensions(IntSize2d(32,32))
            entity.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT
            val canvas=entity.getSurface().lockCanvas(null)
            canvas.drawColor(android.graphics.Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color=if(grabPoints.size%2==1) android.graphics.Color.rgb(193,128,255) else android.graphics.Color.rgb(108,249,255)
            canvas.drawCircle(16f,16f,15f,paint)
            entity.getSurface().unlockCanvasAndPost(canvas)
            grabPoints.add(entity)
        }
        for((index,entity) in grabPoints.withIndex()) {
            val position=points.getOrNull(index)
            if(position==null || eye==null) { state(entity).enabled(false); continue }
            state(entity).scale(diameter)
            state(entity).pose(Pose(vector(position),Quaternion.fromLookTowards(vector(eye)-vector(position),Vector3.Up)))
            state(entity).parent(parent); state(entity).enabled(true)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun decorationShape(width: Float,height: Float): SurfaceEntity.Shape.CustomMesh {
        val x=width*.5f; val y=height*.5f
        // Quad canvases create rectangular native hit regions even without an input
        // component. Custom mesh canvases render the decoration without that quad target.
        return SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(
            java.nio.FloatBuffer.wrap(floatArrayOf(-x,-y,0f,x,-y,0f,x,y,0f,-x,y,0f)),
            java.nio.FloatBuffer.wrap(floatArrayOf(0f,1f,1f,1f,1f,0f,0f,0f)),
            java.nio.IntBuffer.wrap(intArrayOf(0,1,2,0,2,3))))
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
        nodeStates.clear()
        groups.forEach { group ->
            if(!group.root.isDisposed)group.root.dispose()
        }
        (aimBeams+grabBeams+grabPoints).forEach { if(!it.isDisposed){it.parent=null; it.dispose()} }
        aimBeams.clear(); grabBeams.clear(); grabPoints.clear()
        pointerPoint?.dispose(); pointerPoint = null
        highlights.values.forEach { if(!it.isDisposed){it.parent=null; it.dispose()} }; highlights.clear()
        groups.clear()
        if(session.lifecycleOwner.lifecycle.currentState!=androidx.lifecycle.Lifecycle.State.DESTROYED)models.forEach { it.close() }
    }

    companion object {
        suspend fun create(session: Session): GrabGuideRenderer {
            val loaded = mutableListOf<GltfModel>()
            try {
                for (asset in listOf("cyan-beam", "violet-beam", "cyan-point", "violet-point")) {
                    loaded.add(GltfModel.create(session, Paths.get("grab-guides/$asset.glb")))
                }
                return GrabGuideRenderer(session, loaded)
            } catch (failure: Throwable) {
                if(session.lifecycleOwner.lifecycle.currentState!=androidx.lifecycle.Lifecycle.State.DESTROYED)loaded.forEach { it.close() }
                throw failure
            }
        }
    }
}
