package dev.floatdesk.client

import android.annotation.SuppressLint
import android.view.Surface
import android.util.Log
import androidx.xr.scenecore.SpatialPointerComponent
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.InputEvent
import androidx.xr.compose.subspace.layout.SpatialInputEvent
import androidx.xr.scenecore.PixelDensity
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.Texture
import androidx.xr.scenecore.scene
import dev.floatdesk.client.workspace.MonitorPlacement
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Keep Compose's flat surface/handles; give curved video an independently owned native canvas. */
@OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class MonitorWarp : AutoCloseable {
    var mesh: MonitorMesh? = null
        private set
    private var qualitySize:Pair<Int,Int>?=null
    private var pixelTarget:SurfaceEntity?=null
    private var pixelSize:Pair<Int,Int>?=null
    fun qualityResolution(size:Pair<Int,Int>?):Boolean {if(qualitySize==size)return false;qualitySize=size;return true}
    private fun applyPixels(target:SurfaceEntity,placement:MonitorPlacement){
        val size=qualitySize ?: (placement.pixelWidth to placement.pixelHeight)
        if(pixelTarget!==target || pixelSize!=size){target.setSurfacePixelDimensions(androidx.xr.runtime.math.IntSize2d(size.first,size.second));pixelTarget=target;pixelSize=size}
    }
    private var input: SurfaceEntity? = null
    private var live: SurfaceEntity? = null
    private var flatWidth = 0f
    private var flatHeight = 0f
    private var appliedShape: SurfaceEntity.Shape? = null
    private var interaction: InteractableComponent? = null
    private val sequences = mutableSetOf<String>()
    private var curvedInput: CurvedMonitorInput? = null
    private var inputPointer:SpatialPointerComponent?=null
    private var ownsInputPointer=false
    private var pointerRefreshAt=0L
    private var inputListener: ((InputEvent) -> Unit)? = null
    private var mask: Texture? = null
    private var ownerEnabled=true
    private var directInteraction = false
    private var screenRects=floatArrayOf(0f,0f,1f,1f)
    private var spacing=0f
    private var presentationDirty=true
    private var focus:FloatArray?=null
    private var referenceMesh:MonitorMesh?=null
    val gazeReference:MonitorMesh? get()=if(mesh==null)null else referenceMesh ?: mesh
    private var projectionUpdatedAt=0L
    private var geometryUpdatedAt=0L
    fun presentation(rects:FloatArray,inset:Float) {
        if(!screenRects.contentEquals(rects) || spacing!=inset) {screenRects=rects.copyOf();spacing=inset;presentationDirty=true}
    }
    @SuppressLint("RestrictedApi")
    private fun videoShape(width:Float,height:Float,sphere:MonitorMesh?):SurfaceEntity.Shape {
        if(sphere==null && videoCoverageIsFull(screenRects,spacing)) return SurfaceEntity.Shape.Quad(FloatSize2d(width,height))
        val geometry=clippedVideoGeometry(width,height,sphere,screenRects,spacing)
        return SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(FloatBuffer.wrap(geometry.positions),FloatBuffer.wrap(geometry.texCoords),IntBuffer.wrap(geometry.indices)))
    }
    private var hoverWindow=0L
    private val hoverActions=mutableMapOf<String,Int>()
    private val hoverCounts=mutableMapOf<String,Int>()
    private val hoverLast=mutableMapOf<String,Long>()
    private var hoverGap=0L
    val outputSurface: Surface? get() = live?.getSurface()
    val displayEntity: SurfaceEntity? get()=live
    fun matchesSize(placement: MonitorPlacement)=flatWidth==placement.physicalWidthM && flatHeight==placement.physicalHeightM &&
        (mesh?.center?.contentEquals(projectionCenter(placement)) ?: true)
    private fun projectionCenter(placement:MonitorPlacement)=floatArrayOf((placement.viewCenterU-.5f)*placement.physicalWidthM,(.5f-placement.viewCenterV)*placement.physicalHeightM,0f)

    @SuppressLint("RestrictedApi")
    suspend fun syncCurvedInput(session: Session) {
        val sphere = mesh
        if (sphere == null) {
            curvedInput?.entity?.setEnabled(false)
            input?.setEnabled(ownerEnabled)
            input?.primaryAlphaMaskTexture = null
            input?.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
            if (!directInteraction) interaction?.let { component ->
                check(input?.addComponent(component) == true)
                directInteraction = true
            }
            return
        }
        if (mask == null) mask = Texture.create(session, java.nio.file.Paths.get("transparent-monitor-mask.png"))
        input?.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.TRANSPARENT
        input?.primaryAlphaMaskTexture = mask
        if (directInteraction) {
            interaction?.let { input?.removeComponent(it) }
            directInteraction = false
        }
        if (curvedInput?.mesh === sphere) { curvedInput?.entity?.parent=live;curvedInput?.entity?.setEnabled(true);input?.setEnabled(false);return }
        curvedInput?.let { it.update(sphere);it.entity.parent=live;it.entity.setEnabled(true);input?.setEnabled(false);return }
        val target = live ?: return
        val listener = inputListener ?: return
        val owner = input
        val next = CurvedMonitorInput.create(session, target, sphere, listener)
        // Loading suspends. A monitor removal/resize can close this owner meanwhile.
        if (input !== owner || live !== target || mesh !== sphere) {
            next.close()
            return
        }
        val old = curvedInput
        curvedInput = next
        old?.close()
        input?.setEnabled(false)
    }

    private fun applyShape(target: SurfaceEntity, shape: SurfaceEntity.Shape) {
        target.shape = shape
        appliedShape = shape
    }

    @SuppressLint("RestrictedApi")
    fun attach(session: Session, inputSurface: Surface, placement: MonitorPlacement, density: PixelDensity? = null, onEvent: ((SpatialInputEvent) -> Unit)? = null, onHover: ((String, FloatArray?, FloatArray?, Float, Float) -> Unit)? = null): Surface {
        close()
        val target = session.scene.getEntitiesOfType(SurfaceEntity::class.java)
            .firstOrNull { it.getSurface() === inputSurface }
            ?: error("Could not find monitor input entity")
        target.setSurfacePixelDimensions(androidx.xr.runtime.math.IntSize2d(placement.pixelWidth,placement.pixelHeight))
        target.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
        input = target
        ownerEnabled=target.isEnabled(false)
        live = target
        flatWidth = placement.physicalWidthM; flatHeight = placement.physicalHeightM
        if (density != null && onEvent != null) {
            val listener: (InputEvent) -> Unit = listener@ { event ->
                if(input !== target) return@listener // Ignore callbacks queued before detach/reconnect.
                val expected = if (mesh != null) curvedInput?.entity else target
                // Decorative surfaces can appear before the actual monitor in the hit stack.
                // Keep the native monitor hit and its matrix together instead of using the front hit.
                val info = event.hitInfoList.firstOrNull { it.inputEntity === expected } ?: event.hitInfoList.firstOrNull()
                val gazeSource=event.source==InputEvent.Source.GAZE_AND_GESTURE
                // Native gaze can recreate its icon after shape/aiming-mode changes. Reapply
                // the desktop-only policy to the entity actually receiving this hover.
                if(gazeSource && info?.inputEntity===expected && expected!=null &&
                    event.action!=InputEvent.Action.HOVER_EXIT && event.action!=InputEvent.Action.CANCEL){
                    val now=android.os.SystemClock.uptimeMillis()
                    if(event.action==InputEvent.Action.HOVER_ENTER || now-pointerRefreshAt>=1000){
                        expected.getComponentsOfType(SpatialPointerComponent::class.java).forEach{it.spatialPointerIcon=SpatialPointerIcon.NONE}
                        pointerRefreshAt=now
                    }
                }
                val side = if(gazeSource)"GAZE" else event.pointerType.toString()
                if(gazeSource && event.action==InputEvent.Action.CANCEL){
                    onHover?.invoke(side,null,null,flatWidth,flatHeight);sequences.remove(side);return@listener
                }
                if (event.action == InputEvent.Action.HOVER_ENTER || event.action == InputEvent.Action.HOVER_MOVE || event.action == InputEvent.Action.HOVER_EXIT) {
                    if (event.source == InputEvent.Source.HANDS || gazeSource) {
                        val now=android.os.SystemClock.uptimeMillis()
                        if(hoverWindow==0L) hoverWindow=now
                        hoverLast.put(side,now)?.let { hoverGap=maxOf(hoverGap,now-it) }
                        hoverCounts[side]=(hoverCounts[side] ?: 0)+1
                        val action=event.action.toString()
                        hoverActions[action]=(hoverActions[action] ?: 0)+1
                        if(now-hoverWindow>=5000) {
                            Log.i("Floatdesk","native hover counts=$hoverCounts windowMs=${now-hoverWindow} maxGapMs=$hoverGap actions=$hoverActions curved=${mesh!=null}")
                            hoverWindow=now;hoverCounts.clear();hoverActions.clear();hoverGap=0
                        }
                        if (event.action == InputEvent.Action.HOVER_EXIT && info != null && info.inputEntity !== expected) return@listener
                        val hoverHit = info?.hitPosition
                        val point = if (event.action != InputEvent.Action.HOVER_EXIT && info?.inputEntity === expected && hoverHit != null)
                            eventMatrixHit(floatArrayOf(hoverHit.x,hoverHit.y,hoverHit.z),info.transform.data,flatWidth,flatHeight,mesh,target.getScale(Space.ACTIVITY),rayDirection=if(mesh!=null) floatArrayOf(event.direction.x,event.direction.y,event.direction.z) else null,rayOrigin=floatArrayOf(event.origin.x,event.origin.y,event.origin.z)) else null
                        if(event.action!=InputEvent.Action.HOVER_EXIT) {
                            val result=if(point!=null) "RESOLVED" else if(info?.inputEntity===expected) "UNRESOLVED" else "OTHER_TARGET"
                            hoverActions[result]=(hoverActions[result] ?: 0)+1
                        }
                        val flat = point?.let { mesh?.unproject(it) ?: it }
                        onHover?.invoke(side,point,flat,flatWidth,flatHeight)
                    }
                    return@listener
                }
                if (event.action == InputEvent.Action.DOWN) {
                    if (info?.inputEntity !== expected || expected == null) {
                        Log.i("Floatdesk","monitor rejected DOWN side=$side hits=${event.hitInfoList.size} expected=${expected?.javaClass?.simpleName} entities=${event.hitInfoList.map { it.inputEntity.javaClass.simpleName }}")
                        return@listener
                    }
                    sequences.add(side)
                }
                if (side !in sequences && (side != "DEFAULT" || sequences.isEmpty())) return@listener
                val hit = info?.hitPosition
                val inputScale = target.getScale(Space.ACTIVITY)
                // Hit position includes the activity root's scale; HitInfo.transform does not.
                val local = if (info != null && hit != null)
                    eventMatrixHit(floatArrayOf(hit.x,hit.y,hit.z),info.transform.data,
                        flatWidth,flatHeight,mesh,inputScale,rayDirection=if(mesh!=null) floatArrayOf(event.direction.x,event.direction.y,event.direction.z) else null,rayOrigin=floatArrayOf(event.origin.x,event.origin.y,event.origin.z)) else null
                if (event.action == InputEvent.Action.DOWN) {
                    Log.i("Floatdesk","native event contact=${local?.contentToString()} inputScale=$inputScale hit=$hit matrix=${info?.transform?.data?.contentToString()}")
                }
                if (event.source == InputEvent.Source.HANDS || gazeSource) {
                    val point=if(event.action==InputEvent.Action.CANCEL) null else local
                    // A release can omit HitInfo; keep a still-hovered gaze target for repeated taps.
                    if(!gazeSource || point!=null)onHover?.invoke(side,point,point?.let { mesh?.unproject(it) ?: it },flatWidth,flatHeight)
                }
                if(gazeSource){
                    if(event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL)sequences.remove(side)
                    return@listener
                }
                fun pixels(v: Vector3) = Vector3(density.convertMetersToPixels(v.x),density.convertMetersToPixels(v.y),density.convertMetersToPixels(v.z))
                onEvent(SpatialInputEvent(source=event.source,pointerType=event.pointerType,action=event.action,timestamp=event.timestamp,
                    hitPosition=local?.let { pixels(Vector3(it[0],it[1],it[2])) },origin=pixels(event.origin),direction=pixels(event.direction)))
                if (event.action == InputEvent.Action.UP || event.action == InputEvent.Action.CANCEL) {
                    if (side == "DEFAULT") sequences.clear() else sequences.remove(side)
                }
            }
            inputListener = listener
            val component = NativeMonitorInput.create(session, listener)
            check(target.addComponent(component)) { "Could not attach native monitor input" }
            interaction = component
            val existingPointer=target.getComponentsOfType(SpatialPointerComponent::class.java).firstOrNull()
            val pointer=existingPointer ?: SpatialPointerComponent.create(session).also{check(target.addComponent(it))}
            pointer.spatialPointerIcon=SpatialPointerIcon.NONE
            inputPointer=pointer;ownsInputPointer=existingPointer==null
            directInteraction = true
        }
        return inputSurface
    }

    @SuppressLint("RestrictedApi") // CustomMesh is available in the pinned SceneCore 1.0 runtime.
    fun update(session: Session, root: Entity, eye: FloatArray?, placement: MonitorPlacement,
               enabled: Boolean, held: Boolean, forceUpdate: Boolean = false, viewDirection:FloatArray? = null, freezeProjection:Boolean=held,viewFocus:FloatArray?=null) {
        val original = input ?: return
        if(enabled==(mesh!=null))live?.let{applyPixels(it,placement)}
        if (live !== original) live?.let {
            it.parent = original.parent
            it.setPose(original.getPose())
            it.setScale(original.getScale())
        }
        // A captured shape must remain rigid while either contact is held.
        val modeChanged=enabled != (mesh!=null)
        if (held && !forceUpdate && !modeChanged) return
        val candidateViewer = eye?.let { viewerInMonitor(it,placement.pose.position,placement.pose.rotation) }
        val referenceCenter=projectionCenter(placement)
        val viewer=candidateViewer?.takeIf { p->p.all{it.isFinite()} && (0..2).sumOf{val d=p[it]-referenceCenter[it];(d*d).toDouble()}>1e-8 }
            ?: mesh?.eye?.takeIf { forceUpdate }
        // A sphere is valid beside/behind its reference plane. A planar z threshold
        // froze it at an old eye position after off-center enlargement or rotation.
        if (enabled && viewer == null) return
        val usable = enabled
        if (!usable) {
            if (live !== original) { live?.parent = null; live = original }
            if (presentationDirty || mesh != null || flatWidth != placement.physicalWidthM || flatHeight != placement.physicalHeightM) {
                val shape = videoShape(placement.physicalWidthM,placement.physicalHeightM,null)
                applyShape(original, shape)
                flatWidth = placement.physicalWidthM; flatHeight = placement.physicalHeightM
                mesh = null;focus=null;presentationDirty=false
            }
            applyPixels(original,placement)
            return
        }
        if (live!==original)live?.let{applyPixels(it,placement)}
        if (viewer == null) return
        val previous = mesh
        val now=android.os.SystemClock.uptimeMillis()
        if((viewDirection!=null || viewFocus!=null) && previous!=null && !forceUpdate && !presentationDirty && matchesSize(placement) && now-geometryUpdatedAt<50) return
        val center=projectionCenter(placement)
        var reference=referenceMesh
        if(reference==null || reference.width!=placement.physicalWidthM || reference.height!=placement.physicalHeightM || !reference.center.contentEquals(center) ||
            viewer.indices.any {kotlin.math.abs(viewer[it]-reference!!.eye[it])>.01f}) {
            if(reference?.center?.contentEquals(center)!=true)focus=center.copyOf(2)
            reference=MonitorMesh(placement.physicalWidthM,placement.physicalHeightM,viewer,focus=center.copyOf(2),center=center)
            referenceMesh=reference
        }
        if(!freezeProjection && (viewFocus!=null || eye!=null && viewDirection!=null) && (previous==null || now-projectionUpdatedAt>=50)) {
            if(viewFocus!=null && viewFocus.size==2 && viewFocus.all{it.isFinite()}) {
                // The shared gaze target was already filtered against the stable reference sphere.
                focus=floatArrayOf(((viewFocus[0]-.5f)*placement.physicalWidthM).coerceIn(-reference!!.width*.5f,reference.width*.5f),((.5f-viewFocus[1])*placement.physicalHeightM).coerceIn(-reference.height*.5f,reference.height*.5f))
            } else if(eye!=null && viewDirection!=null) {
                val endpoint=viewerInMonitor(FloatArray(3){eye[it]+viewDirection[it]},placement.pose.position,placement.pose.rotation)
                sphericalViewFocus(reference!!,FloatArray(3){endpoint[it]-viewer[it]})?.let {desired->
                    val next=floatArrayOf(desired[0].coerceIn(-reference.width*.5f,reference.width*.5f),desired[1].coerceIn(-reference.height*.5f,reference.height*.5f))
                    val old=focus
                    focus=if(old==null)next else FloatArray(2){old[it]+.35f*(next[it]-old[it])}
                }
            }
            projectionUpdatedAt=now
        }
        val selectedFocus=focus?.let {floatArrayOf(it[0].coerceIn(-reference!!.width*.5f,reference.width*.5f),it[1].coerceIn(-reference.height*.5f,reference.height*.5f))} ?: center.copyOf(2)
        val focusChanged=(previous?.focus==null || (0..1).any {kotlin.math.abs(selectedFocus[it]-previous.focus!![it])>.001f})
        val changed = focusChanged || previous?.center?.contentEquals(center)!=true || presentationDirty || previous == null || previous.width != placement.physicalWidthM || previous.height != placement.physicalHeightM ||
            viewer.indices.any { kotlin.math.abs(viewer[it] - previous.eye[it]) > 0.01f }
        if (changed) {
            val next = MonitorMesh(placement.physicalWidthM, placement.physicalHeightM, viewer,focus=selectedFocus,center=center)
            if (previous == null || previous.width != next.width || previous.height != next.height) {
                val sdkEye = eye?.let { root.transformPoseTo(Pose(Vector3(it[0],it[1],it[2])),original).translation }
                val h=next.project(floatArrayOf(next.width*.5f,0f,0f))
                val v=next.project(floatArrayOf(0f,next.height*.5f,0f))
                fun sag(point: FloatArray) = (0..2).sumOf { (point[it]*viewer[it]/next.radius).toDouble() }.toFloat()
                Log.i("Floatdesk","warp geometry map=geodesic viewer=${viewer.contentToString()} sdkViewer=$sdkEye radius=${next.radius} radiusActivity=${next.radius*original.getScale(Space.ACTIVITY)} image=${placement.pixelWidth}x${placement.pixelHeight} extent=${next.width}x${next.height} horizontalSag=${sag(h)} verticalSag=${sag(v)}")
            }
            val shape = videoShape(next.width,next.height,next)
            val target = if (live === original) SurfaceEntity.create(session,
                shape = shape, pose = original.getPose(), parent = original.parent,
                superSampling = SurfaceEntity.SuperSampling.NONE).also {
                it.setScale(original.getScale())
                applyPixels(it,placement)
                it.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
                val pointer=SpatialPointerComponent.create(session)
                check(it.addComponent(pointer))
                pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                live = it
            } else live!!
            applyShape(target, shape)
            flatWidth = next.width; flatHeight = next.height
            mesh = next;presentationDirty=false;geometryUpdatedAt=now
        } else if (live?.shape !== appliedShape) {
            appliedShape?.let { applyShape(live!!, it) }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun close() {
        // Restore the Compose-owned surface before releasing our independent curved resources.
        curvedInput?.close(); curvedInput = null
        if (live !== input) live?.parent = null
        input?.setEnabled(ownerEnabled)
        input?.primaryAlphaMaskTexture = null
        input?.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
        mask?.close(); mask = null
        inputListener = null
        interaction?.let { component -> runCatching { input?.removeComponent(component) } }
        interaction = null; sequences.clear()
        if(ownsInputPointer)inputPointer?.let{runCatching{input?.removeComponent(it)}};inputPointer=null;ownsInputPointer=false
        directInteraction = false
        live = null
        input = null
        mesh = null
        focus = null
        referenceMesh=null
        projectionUpdatedAt=0L
        geometryUpdatedAt=0L
        appliedShape = null
    }
}
