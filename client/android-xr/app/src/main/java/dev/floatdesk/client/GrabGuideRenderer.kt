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

/** Keep the projected position while moving a guide clear of the desktop triangles. */
internal fun foregroundGuidePoint(point:FloatArray,eye:FloatArray?,units:Float,desktopRange:Float?):FloatArray{
    if(eye==null || desktopRange==null)return point
    val distance=distance(point,eye)
    val limit=(desktopRange-maxOf(.035f*units,desktopRange*.08f)).coerceAtLeast(.05f*units)
    if(distance<=limit || !distance.isFinite())return point
    return FloatArray(3){eye[it]+(point[it]-eye[it])*limit/distance}
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
    private var highlight:SurfaceEntity?=null
    private var nextInkAllocation=0L
    private val aimBeams=mutableMapOf<Int,SurfaceEntity>()
    private val grabBeams=mutableMapOf<Int,SurfaceEntity>()
    private val grabContacts=mutableListOf<GltfModelEntity>()

    private var originCollider:SurfaceEntity?=null
    private var originSphere:SurfaceEntity?=null
    private val worldAxes=WorldLayoutAxes(session)

    fun updateLayoutOrigin(parent:Entity,guide:LayoutOriginGuide?,units:Float,desktopRange:Float?=null,onInput:(androidx.xr.scenecore.InputEvent)->Unit) {
        if(closed)return
        val hitGuide=guide?.foreground(desktopRange,units)
        worldAxes.update(parent,hitGuide)
        if(hitGuide==null || guide==null){
            originCollider?.let{state(it).enabled(false)}
            originSphere?.let{state(it).enabled(false)}
            return
        }
        val sphere=originSphere ?: createOriginSphere().also{originSphere=it}
        val collider=originCollider ?: SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(androidx.xr.runtime.math.FloatSize2d(1f,1f)),
            parent=null,superSampling=SurfaceEntity.SuperSampling.NONE,mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT).also{
            check(it.addComponent(NativeMonitorInput.create(session,onInput)))
            originCollider=it
        }
        val normal=vector(guide.viewer.position)-vector(hitGuide.near)
        val rotation=Quaternion.fromLookTowards(normal,Vector3.Up)
        state(sphere).apply{
            this.parent(parent)
            pose(Pose(vector(hitGuide.near),rotation))
            scale(hitGuide.gripRadius)
            enabled(true)
        }
        val hitCenter=(vector(hitGuide.near)+vector(guide.viewer.position)*.04f)*(1f/1.04f)
        state(collider).apply{
            this.parent(parent)
            pose(Pose(hitCenter,rotation))
            scale(Vector3(hitGuide.gripRadius*2.65f,hitGuide.gripRadius*2.65f,1f))
            enabled(true)
        }
    }

    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    private fun createOriginSphere():SurfaceEntity {
        val entity=SurfaceEntity.create(session,shape=nativeVideoMesh(layoutOriginSphereGeometry()),parent=null,
            superSampling=SurfaceEntity.SuperSampling.NONE,mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE)
        entity.setEnabled(false)
        entity.setSurfacePixelDimensions(IntSize2d(128,8))
        SurfaceInput.disableCollider(entity)
        val surface=entity.getSurface()
        val canvas=surface.lockCanvas(null)
        try{
            val paint=android.graphics.Paint()
            paint.shader=android.graphics.LinearGradient(0f,0f,canvas.width.toFloat(),0f,
                intArrayOf(0xFF145674.toInt(),0xFF55E6FF.toInt(),0xFFE3FBFF.toInt()),
                floatArrayOf(0f,.64f,1f),android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPaint(paint)
        }finally{surface.unlockCanvasAndPost(canvas)}
        return entity
    }

    private fun newGroup(edgeCount: Int): Group {
        val root = Entity.create(session, name = "Floatdesk grab guide")
        // The outline and pinch markers share the same retained model pool.
        val indices = List(edgeCount) { 0 } + listOf(2,3)
        val parts = indices.map { GltfModelEntity.create(session, models[it], parent = root) }
        return Group(root, parts)
    }

    fun update(parent: Entity, guides: List<GrabGuide>,desktopRange:Float?=null) {
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
                val contact=foregroundGuidePoint(visibleGrabContact(guide.contacts[hand],guide.eye,unit),guide.eye,unit,desktopRange)
                val pinch=foregroundGuidePoint(guide.pinches[hand],guide.eye,unit,desktopRange)
                tethers+=contact to pinch
                contacts+=contact
                point(group.parts[edgeCount+hand],pinch,.004f*unit)
            }
        }
        for (index in guides.size until groups.size) groups[index].root.setEnabled(false)
        val unit=guides.firstOrNull()?.unitsPerMeter ?: 1f
        updateGuideBeams(parent,tethers,grabBeams,.0022f*unit,1f)
        for(index in contacts.indices){
            if(index>=grabContacts.size)grabContacts+=GltfModelEntity.create(session,models[2+index%2])
            state(grabContacts[index]).apply{
                this.parent(parent);pose(Pose(vector(contacts[index])));scale(.006f*unit);enabled(true)
            }
        }
        for(i in contacts.size until grabContacts.size)state(grabContacts[i]).enabled(false)

    }

    fun updatePointer(parent: Entity, position: FloatArray?, eye: FloatArray?, unitsPerMeter: Float) {
        if (closed) return
        if (position == null) { pointerPoint?.setEnabled(false); return }
        val entity = pointerPoint ?: GltfModelEntity.create(session,models[2]).also { pointerPoint=it; it.setAlpha(.2f) }
        entity.parent=parent
        entity.setEnabled(true)
        point(entity,visibleGrabContact(position,eye,unitsPerMeter),.003f*unitsPerMeter)
    }

    fun updateHighlight(parent:Entity,position:FloatArray?,rotation:FloatArray,diameter:Float,pulse:Float){
        if(closed)return
        if(position==null || diameter<=0f){highlight?.let{state(it).enabled(false)};return}
        val marker=highlight ?: ink(layoutOriginSphereGeometry(),0x6639DFFF)?.also{highlight=it} ?: return
        state(marker).apply{
            this.parent(parent)
            pose(Pose(vector(position),Quaternion(rotation[0],rotation[1],rotation[2],rotation[3])))
            scale(diameter*(.37f+.04f*pulse))
            alpha(.65f+.2f*pulse)
            enabled(true)
        }
    }

    @OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
    private fun ink(geometry:VideoGeometry,color:Int):SurfaceEntity?{
        val now=android.os.SystemClock.uptimeMillis()
        if(now<nextInkAllocation)return null
        nextInkAllocation=now+90L
        val entity=SurfaceEntity.create(session,shape=nativeVideoMesh(geometry),parent=null,
            superSampling=SurfaceEntity.SuperSampling.NONE,mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT)
        entity.setEnabled(false)
        if(!SurfaceInput.disableCollider(entity)){
            entity.dispose()
            return null
        }
        entity.setSurfacePixelDimensions(IntSize2d(16,8))
        val surface=entity.getSurface()
        val canvas=surface.lockCanvas(null)
        try{canvas.drawColor(color,android.graphics.PorterDuff.Mode.SRC)}finally{surface.unlockCanvasAndPost(canvas)}
        return entity
    }

    fun updateRays(parent:Entity,rays:Map<String,Pair<FloatArray,FloatArray>>,eye:FloatArray?,unitsPerMeter:Float,dimmed:Boolean,desktopRange:Float?=null){
        if(closed)return
        val slots=listOf("LEFT","RIGHT").map{side->rays[side]?.let{(a,b)->
            foregroundGuidePoint(a,eye,unitsPerMeter,desktopRange) to foregroundGuidePoint(b,eye,unitsPerMeter,desktopRange)
        }}
        updateGuideBeams(parent,slots,aimBeams,.0014f*unitsPerMeter,if(dimmed).32f else .70f)
    }

    private fun updateGuideBeams(parent:Entity,rays:List<Pair<FloatArray,FloatArray>?>,
                                 entities:MutableMap<Int,SurfaceEntity>,width:Float,alpha:Float){
        for((index,ray) in rays.withIndex()){
            if(ray==null){entities[index]?.let{state(it).enabled(false)};continue}
            val from=vector(ray.first);val to=vector(ray.second);val delta=to-from
            if(delta.length<.00001f){entities[index]?.let{state(it).enabled(false)};continue}
            val entity=entities[index] ?: ink(beamGeometry,if(index%2==0)0xCC50E8FF.toInt() else 0xCCC386FF.toInt())
                ?.also{entities[index]=it} ?: continue
            state(entity).apply{
                this.parent(parent)
                pose(Pose((from+to)*.5f,Quaternion.fromRotation(Vector3.Right,delta)))
                scale(Vector3(delta.length,width,width))
                alpha(alpha)
                enabled(true)
            }
        }
        entities.forEach{(index,entity)->if(index>=rays.size)state(entity).enabled(false)}
    }

    private fun vector(value: FloatArray) = Vector3(value[0], value[1], value[2])

    private fun beam(entity: GltfModelEntity, from: FloatArray, to: FloatArray, width: Float) {
        val a = vector(from)
        val b = vector(to)
        val delta = b - a
        if (delta.length < 1e-6f) { entity.setEnabled(false); return }
        state(entity).enabled(true)
        state(entity).pose(Pose((a + b) * 0.5f, Quaternion.fromRotation(Vector3.Right, delta)))
        // A glTF node's public localScale supports nonuniform scale without restricted entity APIs.
        val node=entity.nodes.first()
        val scale=Vector3(delta.length,width,width)
        if(node.localScale!=scale)node.localScale=scale
    }

    private fun point(entity: GltfModelEntity, position: FloatArray, radius: Float) {
        entity.setPose(Pose(vector(position)))
        entity.setScale(radius)
    }

    override fun close() {
        if (closed) return
        closed = true
        worldAxes.close()
        originCollider?.let{if(!it.isDisposed){it.setEnabled(false);it.parent=null}}
        originCollider=null
        originSphere?.let{if(!it.isDisposed){it.setEnabled(false);it.parent=null}}
        originSphere=null
        nodeStates.clear()
        groups.forEach { group ->
            if(!group.root.isDisposed)group.root.dispose()
        }
        (aimBeams.values+grabBeams.values+grabContacts).forEach { if(!it.isDisposed){it.parent=null; it.dispose()} }
        aimBeams.clear();grabBeams.clear();grabContacts.clear()
        pointerPoint?.dispose(); pointerPoint = null
        highlight?.let { if(!it.isDisposed){it.parent=null; it.dispose()} }; highlight = null
        groups.clear()
        if(session.lifecycleOwner.lifecycle.currentState!=androidx.lifecycle.Lifecycle.State.DESTROYED)models.forEach { it.close() }
    }

    companion object {
        private val beamGeometry=VideoGeometry(
            floatArrayOf(-.5f,-.5f,-.5f,-.5f,.5f,-.5f,.5f,.5f,-.5f,.5f,-.5f,-.5f,
                -.5f,-.5f,.5f,-.5f,.5f,.5f,.5f,.5f,.5f,.5f,-.5f,.5f),
            FloatArray(16){.5f},
            intArrayOf(0,1,2,0,2,3,4,6,5,4,7,6,0,4,5,0,5,1,3,2,6,3,6,7,
                1,5,6,1,6,2,0,3,7,0,7,4),
        )

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
