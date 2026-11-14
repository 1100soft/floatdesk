package dev.floatdesk.client

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.PixelDensity
import androidx.xr.compose.subspace.layout.SpatialInputEvent
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.workspace.Pose

internal class GrabTracking {
    val points = mutableMapOf<String, FloatArray>()
    val pinched = mutableSetOf<String>()
    var pointerHeld=false
    var unitsPerMeter = 1f
    var nowMs = 0L
}

/** A virtual monitor held between the hands, with a fixed similarity mapping to the real one. */
internal class TwoHandState {
    var engaged by mutableStateOf(false)
        private set
    var guide: GrabGuide? = null
        private set
    var frozenMesh: MonitorMesh? = null
        private set
    val bothDown get() = hands.size == 2
    val busy get() = hands.isNotEmpty() || engaged
    val blocksNative get() = busy // Rearming the two-point gesture must not disable the handles.
    private data class Grip(val local: FloatArray)
    private val hands = mutableMapOf<String, Grip>()
    private val released = mutableSetOf<String>()
    private var needsRelease = false
    private var base: MonitorPlacement? = null
    private var last: MonitorPlacement? = null
    private var startLeft = floatArrayOf(0f, 0f, 0f)
    private var startRight = floatArrayOf(0f, 0f, 0f)
    private var worldLeft = floatArrayOf(0f, 0f, 0f)
    private var worldRight = floatArrayOf(0f, 0f, 0f)
    private var alignment = floatArrayOf(0f, 0f, 0f, 1f)
    private var gain = 1f
    private var lastSeen = 0L
    private var began = 0L
    private var openSince: Long? = null
    private var previousLeft: FloatArray? = null
    private var previousRight: FloatArray? = null

    fun cancel() {
        hands.clear(); engaged = false; base = null; guide = null; frozenMesh = null
        needsRelease = true; released.clear(); openSince = null
    }

    private fun rearm(tracking: GrabTracking) {
        if(!needsRelease)return
        for(side in listOf("LEFT","RIGHT")) if(tracking.points[side]!=null && side !in tracking.pinched) released.add(side)
        if(released.size==2) {needsRelease=false;released.clear()}
    }

    fun onEvent(event: SpatialInputEvent, current: MonitorPlacement, density: PixelDensity,
                tracking: GrabTracking, mesh: MonitorMesh? = null, planarInput: Boolean = false, selectedHit: FloatArray? = null): MonitorPlacement? {
        val side = event.pointerType.toString()
        rearm(tracking)
        // Some runtimes end a two-hand sequence with DEFAULT, not LEFT/RIGHT.
        if (event.action == InputEvent.Action.UP || event.action == InputEvent.Action.CANCEL) {
            // Native surface rebuilds and SDK pinch thresholds are not physical releases.
            if(!busy || listOf("LEFT","RIGHT").all {it in tracking.pinched}) return null
            val result = if (engaged) last else null
            if (!needsRelease) cancel()
            else { hands.clear(); engaged = false; base = null; guide = null; frozenMesh = null }
            if (side == "LEFT" || side == "RIGHT") released.add(side)
            else released.addAll(listOf("LEFT", "RIGHT"))
            if (released.size == 2) needsRelease = false
            return result
        }
        if (event.action != InputEvent.Action.DOWN || side !in listOf("LEFT", "RIGHT")) return null
        rearm(tracking)
        if (needsRelease || engaged || tracking.points[side] == null || side !in tracking.pinched) return null
        // Only fresh DOWN hits select pixels. MOVE can never turn a rejected/pending pair into a grab.
        val hit = selectedHit ?: event.hitPosition?.let { eventMeters(it, density) } ?: return null
        val flat = if (mesh != null && !planarInput) mesh.unproject(hit) ?: return null else hit
        val local = if (mesh != null && planarInput) mesh.project(flat) else hit
        if (kotlin.math.abs(flat[0]) > current.physicalWidthM * 0.5f + 0.001f ||
            kotlin.math.abs(flat[1]) > current.physicalHeightM * 0.5f + 0.001f) return null
        if (mesh == null) local[2] = 0f
        return acquire(side,local,current,tracking,mesh)
    }

    // Joint-confirmed pinches can acquire the already validated preview contacts even
    // when the runtime misses a native DOWN for one hand.
    fun acquire(side:String,local:FloatArray,current:MonitorPlacement,tracking:GrabTracking,mesh:MonitorMesh?):MonitorPlacement? {
        rearm(tracking)
        if(needsRelease || engaged || tracking.points[side]==null || side !in tracking.pinched)return null
        if (hands.isEmpty()) { began = tracking.nowMs; lastSeen = tracking.nowMs; frozenMesh = mesh }
        hands[side] = Grip(local)
        if (hands.size != 2) return null
        val left = tracking.points["LEFT"]
        val right = tracking.points["RIGHT"]
        if (left == null || right == null) { cancel(); return null }
        val leftLocal = hands.getValue("LEFT").local
        val rightLocal = hands.getValue("RIGHT").local
        val mapping=grabMapping(current,leftLocal,rightLocal,tracking,mesh)
        if(mapping==null) {cancel();return null}
        startLeft = left.copyOf(); startRight = right.copyOf()
        worldLeft = mapping.left;worldRight = mapping.right
        gain = mapping.gain;alignment = mapping.alignment
        previousLeft = left.copyOf(); previousRight = right.copyOf()
        base = current; last = current; engaged = true
        began = tracking.nowMs; lastSeen = tracking.nowMs; openSince = null
        publishGuide(current, tracking)
        Log.i("Floatdesk", "virtual monitor locked gain=$gain")
        return null
    }

    fun advance(tracking: GrabTracking, current: MonitorPlacement): MonitorPlacement? {
        rearm(tracking)
        if (!busy) return null
        // A delayed render/input tick is not tracking loss when both fresh samples
        // are present. Missing samples time out below; position jumps still cancel.
        val left = tracking.points["LEFT"]
        val right = tracking.points["RIGHT"]
        val required = if (engaged) listOf("LEFT", "RIGHT") else hands.keys.toList()
        if (required.any { tracking.points[it] == null }) {
            if (tracking.nowMs - lastSeen >= 120) cancel()
            return null // Never move using a mixture of fresh and stale hands.
        }
        lastSeen = tracking.nowMs
        if (tracking.nowMs - began >= 100 && required.any { it !in tracking.pinched }) {
            val opened = openSince
            if (opened == null) openSince = tracking.nowMs
            else if (tracking.nowMs - opened >= 40) cancel()
            return null
        }
        openSince = null
        if (!engaged || left == null || right == null) return null
        if (distance(left, right) < 0.06f * tracking.unitsPerMeter ||
            previousLeft?.let { distance(it, left) > 0.25f * tracking.unitsPerMeter } == true ||
            previousRight?.let { distance(it, right) > 0.25f * tracking.unitsPerMeter } == true) {
            cancel(); return null
        }
        previousLeft = left.copyOf(); previousRight = right.copyOf()
        fun target(start: FloatArray, now: FloatArray, world: FloatArray): FloatArray {
            val delta = subtract(now, start)
            val mapped = rotateVector(alignment, delta[0] * gain, delta[1] * gain, delta[2] * gain)
            return add(world, mapped)
        }
        val original = base ?: return null
        val result = placeGrabbed(hands.getValue("LEFT").local, hands.getValue("RIGHT").local,
            target(startLeft, left, worldLeft), target(startRight, right, worldRight),
            current.pose.rotation, original.physicalWidthM, original.physicalHeightM, current)
        if (result != null) {
            last = result
            publishGuide(result, tracking)
        }
        return result
    }

    /** Display the projected result without changing the rigid hand-to-monitor control transform. */
    fun displayGuide(current:MonitorPlacement,mesh:MonitorMesh?):GrabGuide? {
        val original=base ?: return guide
        val existing=guide ?: return null
        if(!engaged || mesh==null)return existing
        val contacts=listOf("LEFT","RIGHT").map {side ->
            val local=hands.getValue(side).local
            val flat=frozenMesh?.unproject(local) ?: local
            val projected=mesh.project(floatArrayOf(flat[0]/original.physicalWidthM*mesh.width,
                flat[1]/original.physicalHeightM*mesh.height,0f),onTriangles=true)
            transformPoint(current.pose,projected)
        }
        val inverse=floatArrayOf(-alignment[0],-alignment[1],-alignment[2],alignment[3])
        val outline=mesh.outline.map {p ->
            val d=subtract(transformPoint(current.pose,p),worldLeft)
            add(startLeft,rotateVector(inverse,d[0]/gain,d[1]/gain,d[2]/gain))
        }
        return existing.copy(contacts=contacts,outline=outline)
    }

    private fun publishGuide(current: MonitorPlacement, tracking: GrabTracking) {
        val original = base ?: return
        val scale = current.physicalWidthM / original.physicalWidthM
        val contacts = listOf("LEFT", "RIGHT").map { side ->
            transformPoint(current.pose, hands.getValue(side).local, scale)
        }
        val inverse = floatArrayOf(-alignment[0], -alignment[1], -alignment[2], alignment[3])
        fun miniature(world: FloatArray): FloatArray {
            val delta = subtract(world, worldLeft)
            val local = rotateVector(inverse, delta[0] / gain, delta[1] / gain, delta[2] / gain)
            return add(startLeft, local)
        }
        val halfW = current.physicalWidthM / 2f
        val halfH = current.physicalHeightM / 2f
        val outline = frozenMesh?.outline?.map { miniature(transformPoint(current.pose, it, scale)) }
        val corners = listOf(
            floatArrayOf(-halfW, halfH, 0f), floatArrayOf(halfW, halfH, 0f),
            floatArrayOf(halfW, -halfH, 0f), floatArrayOf(-halfW, -halfH, 0f),
        ).map { miniature(transformPoint(current.pose, it)) }
        guide = GrabGuide(corners, contacts, contacts.map { miniature(it) }, tracking.unitsPerMeter, tracking.points["HEAD"]?.copyOf(), outline ?: corners)
    }
}

/**
 * One rigid transform of the screen. The two grabbed pixels are [localLeft]
 * and [localRight]. [targetLeft] and [targetRight] are where those pixels
 * have to be. Scale is the ratio of the two spans, rotation is the smallest
 * turn that maps one span onto the other, and the center is whatever puts
 * the first pixel on its target.
 */
internal fun placeGrabbed(
    localLeft: FloatArray,
    localRight: FloatArray,
    targetLeft: FloatArray,
    targetRight: FloatArray,
    referenceRotation: FloatArray,
    baseWidth: Float,
    baseHeight: Float,
    current: MonitorPlacement,
): MonitorPlacement? {
    val localSpan = subtract(localRight, localLeft)
    val targetSpan = subtract(targetRight, targetLeft)
    val localLength = distance(localLeft, localRight)
    val targetLength = distance(targetLeft, targetRight)
    if (localLength < 0.02f || targetLength < 0.02f) return null
    // A similarity transform must preserve both endpoints. Size clamps here make them slide.
    val scale = targetLength / localLength
    val initialSpan = rotateVector(referenceRotation, localSpan[0], localSpan[1], localSpan[2])
    // Transport the previous orientation, avoiding a roll flip when the span passes 180 degrees.
    // Two endpoints do not constrain twist about their connecting line.
    val turn = quaternionBetween(initialSpan, targetSpan)
    val rotation = multiplyQuaternion(turn, referenceRotation)
    val localMid = midpoint(localLeft, localRight)
    val rotatedMid = rotateVector(rotation, localMid[0] * scale, localMid[1] * scale, localMid[2] * scale)
    val center = subtract(midpoint(targetLeft, targetRight), rotatedMid)
    return current.copy(
        pose = Pose(center, rotation),
        physicalWidthM = baseWidth * scale,
        physicalHeightM = baseHeight * scale,
    )
}


/** A fixed five-degree visual separation, independent of desktop dimensions. */
internal fun grabContactsSeparated(placement:MonitorPlacement,left:FloatArray,right:FloatArray,tracking:GrabTracking,mesh:MonitorMesh?):Boolean {
    val eye=tracking.points["VIEWER"] ?: tracking.points["HEAD"] ?: mesh?.eye?.let {transformPoint(placement.pose,it)} ?: return false
    val a=subtract(transformPoint(placement.pose,left),eye)
    val b=subtract(transformPoint(placement.pose,right),eye)
    val denominator=distance(a,floatArrayOf(0f,0f,0f))*distance(b,floatArrayOf(0f,0f,0f))
    return denominator>1e-8f && dot(a,b)/denominator <= kotlin.math.cos(Math.toRadians(5.0)).toFloat()
}

private data class GrabMapping(val left:FloatArray,val right:FloatArray,val gain:Float,val alignment:FloatArray)
/** Preview and activation must agree on eligibility and the virtual monitor transform. */
private fun grabMapping(placement:MonitorPlacement,left:FloatArray,right:FloatArray,tracking:GrabTracking,mesh:MonitorMesh?):GrabMapping? {
    val handLeft=tracking.points["LEFT"] ?: return null
    val handRight=tracking.points["RIGHT"] ?: return null
    val handSpan=distance(handLeft,handRight)
    if(!grabContactsSeparated(placement,left,right,tracking,mesh) || handSpan<.09f*tracking.unitsPerMeter)return null
    val worldLeft=transformPoint(placement.pose,left);val worldRight=transformPoint(placement.pose,right)
    return GrabMapping(worldLeft,worldRight,distance(left,right)/handSpan,
        quaternionBetween(subtract(handRight,handLeft),subtract(worldRight,worldLeft)))
}

internal fun previewGrabGuide(placement: MonitorPlacement, left: FloatArray, right: FloatArray,
                             tracking: GrabTracking, mesh: MonitorMesh?): GrabGuide? {
    val handLeft=tracking.points["LEFT"] ?: return null
    val handRight=tracking.points["RIGHT"] ?: return null
    val mapping=grabMapping(placement,left,right,tracking,mesh) ?: return null
    val worldLeft=mapping.left;val worldRight=mapping.right
    val gain=mapping.gain;val alignment=mapping.alignment
    val inverse=floatArrayOf(-alignment[0],-alignment[1],-alignment[2],alignment[3])
    fun miniature(local: FloatArray): FloatArray {
        val d=subtract(transformPoint(placement.pose,local),worldLeft)
        return add(handLeft,rotateVector(inverse,d[0]/gain,d[1]/gain,d[2]/gain))
    }
    val w=placement.physicalWidthM/2f; val h=placement.physicalHeightM/2f
    val corners=listOf(floatArrayOf(-w,h,0f),floatArrayOf(w,h,0f),floatArrayOf(w,-h,0f),floatArrayOf(-w,-h,0f)).map(::miniature)
    return GrabGuide(corners,listOf(worldLeft,worldRight),listOf(handLeft,handRight),tracking.unitsPerMeter,
        tracking.points["HEAD"],mesh?.outline?.map(::miniature) ?: corners)
}
