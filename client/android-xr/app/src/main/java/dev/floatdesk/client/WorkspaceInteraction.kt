package dev.floatdesk.client

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.subspace.layout.SpatialMoveEvent
import androidx.xr.compose.subspace.layout.SpatialMoveEventType
import androidx.xr.compose.subspace.layout.SpatialResizeEvent
import androidx.xr.runtime.Config
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.PixelDensity
import androidx.xr.scenecore.scene
import dev.floatdesk.client.net.CursorView
import dev.floatdesk.client.net.MonitorHello
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.workspace.PanelPlacement
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceFile
import dev.floatdesk.client.workspace.WorkspaceStore
import dev.floatdesk.client.workspace.fitDesktopInView
import dev.floatdesk.client.workspace.deskLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun layoutFor(workspace: WorkspaceFile, monitors: List<MonitorHello>): List<MonitorPlacement> {
    val saved = workspace.layouts[workspace.activeLayout]
    if (saved != null && saved.size == monitors.size && monitors.isNotEmpty()) return saved
    if (monitors.isEmpty()) return emptyList()
    return deskLayout(monitors.map { Triple(it.name, it.width, it.height) })
}

internal fun WorkspaceFile.monitorAt(index: Int): MonitorPlacement? {
    return layouts[activeLayout]?.getOrNull(index)
}

internal fun WorkspaceFile.reset(monitors: List<MonitorHello>, store: WorkspaceStore,viewer:Pose,angles:FloatArray,units:Float): WorkspaceFile {
    if (monitors.isEmpty()) return this
    val nextLayouts = layouts.toMutableMap()
    nextLayouts[activeLayout] = fitDesktopInView(deskLayout(monitors.map { Triple(it.name, it.width, it.height) }),viewer,angles[0],angles[1],units,sphericalWarp,centralView=true)
    val next = copy(layouts = nextLayouts, control = PanelPlacement(pose=Pose(transformPoint(viewer,floatArrayOf(0f,-.55f*units,-1f*units)),viewer.rotation.copyOf())),viewerReference=viewer)
    store.save(next)
    return next
}

internal fun SpatialMoveEvent.workspacePose(pixelDensity: PixelDensity): Pose {
    val translation = pose.translation
    val rotation = pose.rotation
    return Pose(
        floatArrayOf(
            pixelDensity.convertPixelsToMeters(translation.x),
            pixelDensity.convertPixelsToMeters(translation.y),
            pixelDensity.convertPixelsToMeters(translation.z),
        ),
        floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
    )
}

internal fun SpatialResizeEvent.sizeInMeters(pixelDensity: PixelDensity): Pair<Float, Float>? {
    val width = pixelDensity.convertPixelsToMeters(size.width.toFloat())
    val height = pixelDensity.convertPixelsToMeters(size.height.toFloat())
    if (width < 0.05f || height < 0.05f) return null
    return width to height
}

internal fun Pose.toQuaternion(): Quaternion = Quaternion(rotation[0], rotation[1], rotation[2], rotation[3])

internal fun Float.meters(density: Density, pixelDensity: PixelDensity): Dp {
    return with(density) { pixelDensity.convertMetersToPixels(this@meters).toDp() }
}

internal class PinchMemory {
    private var previous: Pose? = null
    private var previousScale = 1f

    fun apply(event: SpatialMoveEvent, placement: MonitorPlacement, pixelDensity: PixelDensity): MonitorPlacement {
        val sample = event.workspacePose(pixelDensity)
        val before = previous
        val scale = event.scale.coerceAtLeast(.01f)
        val factor = (scale/previousScale).coerceIn(.5f,2f)
        previous = sample
        previousScale = scale
        if (event.type == SpatialMoveEventType.Start || before == null) return placement
        // Consume discontinuities as a new reference, never replay the jump on a later frame.
        val rotationDot = kotlin.math.abs(dot4(before.rotation,sample.rotation))
        if (distance(before.position,sample.position) > .25f || rotationDot < .9f) {
            Log.w("Floatdesk","native move discontinuity ignored")
            return placement
        }
        val size=resizeDimensions(placement.physicalWidthM,placement.physicalHeightM,factor) ?: return placement
        return placement.copy(
            pose = nativeRelativePose(before,sample,placement.pose),
            physicalWidthM = size.first,
            physicalHeightM = size.second,
        )
    }
}

internal const val PALM_WIDTH = 0.24f
internal const val PALM_HEIGHT = 0.24f
internal enum class PalmAction { FOLLOW, RESET, SEARCH, SPACE, WARP, EYES, GESTURES, MODE }
internal data class PalmPanelState(val side: String, val pose: Pose, val unitsPerMeter: Float = 1f)
internal data class PalmButton(val action: PalmAction, val x: Float, val y: Float, val w: Float = 0.44f, val h: Float = 0.17f)
internal val palmButtons = listOf(
    PalmButton(PalmAction.FOLLOW, 0.04f, 0.16f), PalmButton(PalmAction.RESET, 0.52f, 0.16f),
    PalmButton(PalmAction.SEARCH, 0.04f, 0.37f), PalmButton(PalmAction.SPACE, 0.52f, 0.37f),
    PalmButton(PalmAction.WARP, 0.04f, 0.58f),
    PalmButton(PalmAction.EYES, 0.52f, 0.58f),
    PalmButton(PalmAction.GESTURES, 0.04f, 0.79f), PalmButton(PalmAction.MODE,0.52f,0.79f),
)

internal fun sphericalActionLabel(enabled: Boolean): String = if (enabled) "Disable spherical" else "Enable spherical"

@Composable
internal fun PalmControls(latched: Boolean, warped: Boolean, eyes: Boolean, gestures: Boolean, focused: PalmAction?, pressed: PalmAction?, focusMode:Boolean, onAction: (PalmAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xF01C2430))) {
        Text("Floatdesk", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.offset(maxWidth * 0.06f, maxHeight * 0.04f))
        Text("Look + middle pinch",color=Color(0xFFB8D9EA),fontSize=8.sp,modifier=Modifier.offset(maxWidth*.06f,maxHeight*.105f))
        for (button in palmButtons) {
            Button(onClick = { onAction(button.action) },
                colors=ButtonDefaults.buttonColors(containerColor=when(button.action) { pressed -> Color(0xFF237E59); focused -> Color(0xFF315B82); else -> Color(0xFF354356) }),
                border=if(button.action==focused) BorderStroke(2.dp,Color(0xFF8ADFFF)) else null,
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp), modifier = Modifier
                .offset(maxWidth * button.x, maxHeight * button.y)
                .width(maxWidth * button.w).height(maxHeight * button.h)) {
                BasicText(when (button.action) {
                    PalmAction.FOLLOW -> if (latched) "Unlock" else "Follow"
                    PalmAction.RESET -> "Reset desk"
                    PalmAction.SEARCH -> "Reconnect"
                    PalmAction.SPACE -> "Workspace"
                    PalmAction.WARP -> sphericalActionLabel(warped)
                    PalmAction.EYES -> if (eyes) "Disable eye mouse" else "Enable eye mouse"
                    PalmAction.GESTURES -> if (gestures) "Mouse gestures on" else "Mouse gestures off"
                    PalmAction.MODE -> if(focusMode) "Mouse mode" else "Focus mode"
                }, style = TextStyle(color = Color.White), maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 4.sp, maxFontSize = 14.sp, stepSize = 0.25.sp))
            }
        }
    }
}

internal fun Vector3.toArray() = floatArrayOf(x, y, z)

internal fun palmControls(state: Hand.State, head: androidx.xr.runtime.math.Pose?, side: String, diagnostics: Boolean = false, allowMiddlePinch: Boolean = false, joints: Map<HandJointType,androidx.xr.runtime.math.Pose> = state.handJoints): PalmPanelState? {
    if (state.trackingState != TrackingState.TRACKING || head == null) return null
    val palm = joints[HandJointType.PALM] ?: return null
    val wrist = joints[HandJointType.WRIST]?.translation ?: return null
    val knuckle = joints[HandJointType.MIDDLE_PROXIMAL]?.translation ?: return null
    val toward = head.translation - palm.translation
    // OpenXR joint +Y points out of the back of the hand; -Y is the palm surface.
    // Use a signed test so showing the back of a hand cannot reveal the controls.
    val indexKnuckle = joints[HandJointType.INDEX_PROXIMAL]?.translation ?: return null
    val littleKnuckle = joints[HandJointType.LITTLE_PROXIMAL]?.translation ?: return null
    val planeNormal = palmNormal(wrist.toArray(), knuckle.toArray(), indexKnuckle.toArray(), littleKnuckle.toArray(), palm.down.toArray())
    val normal = Vector3(planeNormal[0], planeNormal[1], planeNormal[2])
    val span = distance(wrist, knuckle)
    val tips = listOf(HandJointType.INDEX_TIP, HandJointType.MIDDLE_TIP, HandJointType.RING_TIP, HandJointType.LITTLE_TIP)
        .mapNotNull { joints[it]?.translation?.toArray() }
    val otherTips=listOf(HandJointType.INDEX_TIP,HandJointType.RING_TIP,HandJointType.LITTLE_TIP)
        .mapNotNull { joints[it]?.translation?.toArray() }
    val panelPinch=allowMiddlePinch &&
        otherTips.size==3 && otherTips.all { distance(it,wrist.toArray())>span*1.55f }
    val open = openPalm(wrist.toArray(),tips,span) || panelPinch
    val facing = dot(normal, toward.toNormalized())
    val inFront = dot(palm.translation - head.translation, head.forward)
    if (diagnostics) Log.i("Floatdesk", "palm $side open=$open facing=$facing distance=${toward.length} front=$inFront")
    if (!open || toward.length !in 0.15f..1.0f || inFront < 0.05f || facing < 0.5f) return null
    val up = knuckle - wrist
    val rotation = Quaternion.fromLookTowards(normal, up)
    val position = palm.translation + normal * 0.018f
    return PalmPanelState(side, androidx.xr.runtime.math.Pose(position, rotation).toWorkspacePose())
}

internal fun palmRayPoint(panel: PalmPanelState, origin: FloatArray, direction: FloatArray): FloatArray? {
    val local=viewerInMonitor(origin,panel.pose.position,panel.pose.rotation)
    val end=viewerInMonitor(add(origin,direction),panel.pose.position,panel.pose.rotation)
    val d=subtract(end,local)
    if(local[2]<=0f || d[2]>=-.000001f) return null
    val t=-local[2]/d[2]
    val u=(local[0]+t*d[0])/(PALM_WIDTH*panel.unitsPerMeter)+.5f
    val v=.5f-(local[1]+t*d[1])/(PALM_HEIGHT*panel.unitsPerMeter)
    return if(u in -.03f..1.03f && v in -.03f..1.03f) floatArrayOf(u,v) else null
}

/** Direct fingertip contact, independent of whether the system emits a pinch/click ray. */
internal class PalmPoke {
    private var previous: FloatArray? = null
    private var armed = false
    var latchedContact = false
        private set
    fun clear() { previous = null; armed = false; latchedContact = false }
    fun update(panel: PalmPanelState, tip: FloatArray?): PalmAction? {
        if (tip == null) { clear(); return null }
        val local = screenLocal(panel.pose, tip).map { it / panel.unitsPerMeter }.toFloatArray()
        val before = previous
        previous = local
        if (local[2] > 0.025f) { armed = true; latchedContact = false }
        if (!armed || before == null || before[2] <= 0.006f || local[2] > 0.006f) return null
        armed = false
        val t = ((before[2] - 0.006f) / (before[2] - local[2])).coerceIn(0f, 1f)
        val x = (before[0] + (local[0] - before[0]) * t) / PALM_WIDTH + 0.5f
        val y = 0.5f - (before[1] + (local[1] - before[1]) * t) / PALM_HEIGHT
        val action = palmButtons.firstOrNull { x in it.x..(it.x + it.w) && y in it.y..(it.y + it.h) }?.action
        latchedContact = action != null
        return action
    }
}

/** Find the parent the video is actually rendered in; never infer it from a saved pose. */
internal fun findDeskSpace(session: androidx.xr.runtime.Session): Entity? {
    val activity = session.scene.activitySpace
    val queue = java.util.ArrayDeque<Entity>()
    queue.addAll(activity.children)
    while (queue.isNotEmpty()) {
        val entity = queue.removeFirst()
        if (entity is SurfaceEntity && entity.parent != null && entity.parent !== activity) return entity.parent
        queue.addAll(entity.children)
    }
    // Before video connects, Compose also parents the implicit main panel to its root.
    return session.scene.mainPanelEntity.parent?.takeIf { it !== activity }
}

internal fun deskPose(
    session: androidx.xr.runtime.Session,
    root: Entity,
    perceived: androidx.xr.runtime.math.Pose,
): androidx.xr.runtime.math.Pose = session.scene.perceptionSpace.transformPoseTo(perceived, root)

/** Anatomical palm plane, with its sign taken from the tracked palm surface. */
internal fun palmNormal(wrist: FloatArray, middle: FloatArray, index: FloatArray,
                       little: FloatArray, surface: FloatArray): FloatArray {
    val across = subtract(index, little)
    val along = subtract(middle, wrist)
    val normal = normalize3(floatArrayOf(
        across[1] * along[2] - across[2] * along[1],
        across[2] * along[0] - across[0] * along[2],
        across[0] * along[1] - across[1] * along[0],
    ))
    return if (dot(normal, surface) < 0f) normal.map { -it }.toFloatArray() else normal
}

internal fun openViewpoint(session: androidx.xr.runtime.Session): RenderViewpoint? {
    return runCatching { RenderViewpoint.left(session) }.getOrNull()
        ?: runCatching { RenderViewpoint.mono(session) }.getOrNull()
        ?: runCatching { RenderViewpoint.right(session) }.getOrNull()
}

/** One coherent joint sample, expressed in the actual Compose root. */
internal fun gazePointerTarget(origin: FloatArray, direction: FloatArray, workspace: WorkspaceFile,
                              meshes: Map<Int, MonitorMesh?>): PointerTarget? {
    var best: PointerTarget? = null
    var nearest = Float.POSITIVE_INFINITY
    for ((index,placement) in workspace.layouts[workspace.activeLayout].orEmpty().withIndex()) {
        val localOrigin=viewerInMonitor(origin,placement.pose.position,placement.pose.rotation)
        val endpoint=viewerInMonitor(add(origin,direction),placement.pose.position,placement.pose.rotation)
        val mesh=meshes[index]
        val hit=monitorRayHit(localOrigin,subtract(endpoint,localOrigin),placement.physicalWidthM,placement.physicalHeightM,mesh) ?: continue
        val flat=mesh?.unproject(hit) ?: hit
        val range=distance(localOrigin,hit)
        val u=flat[0]/placement.physicalWidthM+.5f; val v=.5f-flat[1]/placement.physicalHeightM
        if(range<nearest && u in 0f..1f && v in 0f..1f) {
            nearest=range; best=PointerTarget(index,u,v,hit)
        }
    }
    return best
}

/** Apply only the native gesture delta; native Start must not replace a saved monitor pose. */
internal fun nativeRelativePose(before: Pose, after: Pose, held: Pose): Pose {
    val inverse = floatArrayOf(-before.rotation[0],-before.rotation[1],-before.rotation[2],before.rotation[3])
    val turn = multiplyQuaternion(after.rotation,inverse)
    val translation = subtract(after.position,before.position)
    return Pose(add(held.position,translation),multiplyQuaternion(turn,held.rotation))
}
internal fun dot4(a: FloatArray,b: FloatArray): Float = (0..3).sumOf { (a[it]*b[it]).toDouble() }.toFloat()

internal fun eventMeters(point: Vector3, pixelDensity: PixelDensity): FloatArray {
    return floatArrayOf(
        pixelDensity.convertPixelsToMeters(point.x),
        pixelDensity.convertPixelsToMeters(point.y),
        pixelDensity.convertPixelsToMeters(point.z),
    )
}

internal fun screenLocal(pose: Pose, world: FloatArray): FloatArray {
    val delta = subtract(world, pose.position)
    val inverse = floatArrayOf(
        -pose.rotation.getOrElse(0) { 0f },
        -pose.rotation.getOrElse(1) { 0f },
        -pose.rotation.getOrElse(2) { 0f },
        pose.rotation.getOrElse(3) { 1f },
    )
    return rotateVector(inverse, delta[0], delta[1], delta[2])
}

internal fun transformPoint(pose: Pose, local: FloatArray, scale: Float = 1f): FloatArray {
    val rotated = rotateVector(pose.rotation, local[0] * scale, local[1] * scale, local[2] * scale)
    return floatArrayOf(
        pose.position.getOrElse(0) { 0f } + rotated[0],
        pose.position.getOrElse(1) { 0f } + rotated[1],
        pose.position.getOrElse(2) { 0f } + rotated[2],
    )
}

internal class FollowLock(
    val head: androidx.xr.runtime.math.Pose,
    val monitors: List<androidx.xr.runtime.math.Pose>,
) {
    fun advance(headNow: androidx.xr.runtime.math.Pose, file: WorkspaceFile): WorkspaceFile {
        val delta = headNow.compose(head.inverse)
        val current = file.layouts[file.activeLayout] ?: return file
        val moved = current.mapIndexed { index, monitor ->
            val source = monitors.getOrNull(index) ?: monitor.pose.toRuntimePose()
            monitor.copy(pose = delta.compose(source).toWorkspacePose())
        }.toMutableList()
        val layouts = file.layouts.toMutableMap()
        layouts[file.activeLayout] = moved
        return file.copy(
            layouts = layouts,
        )
    }

    companion object {
        fun capture(head: androidx.xr.runtime.math.Pose, file: WorkspaceFile): FollowLock {
            val monitors = file.layouts[file.activeLayout].orEmpty().map { it.pose.toRuntimePose() }
            return FollowLock(head, monitors)
        }
    }
}

internal data class HandRead(
    val fist: Boolean,
    val open: Boolean,
)

internal fun openPalm(wrist: FloatArray, tips: List<FloatArray>, span: Float): Boolean =
    span in 0.025f..0.15f && tips.size == 4 && tips.count { distance(it, wrist) > span * 1.35f } >= 3

internal fun firmFist(wrist: FloatArray, palm: FloatArray, tips: List<FloatArray>, span: Float): Boolean =
    tips.size == 4 && tips.all { distance(it, wrist) < span * 1.05f && distance(it, palm) < span * 0.70f }

internal fun readHand(state: Hand.State,joints: Map<HandJointType,androidx.xr.runtime.math.Pose> = state.handJoints): HandRead? {
    if (state.trackingState != TrackingState.TRACKING) return null
    val palmPose = joints[HandJointType.PALM] ?: return null
    val wrist = joints[HandJointType.WRIST]?.translation ?: return null
    val palm = palmPose.translation
    val middle = joints[HandJointType.MIDDLE_TIP]?.translation ?: return null
    val knuckle = joints[HandJointType.MIDDLE_PROXIMAL]?.translation ?: return null
    val span = distance(wrist, knuckle)
    if (span < 0.025f || span > 0.15f) return null
    val tips = listOf(
        HandJointType.INDEX_TIP,
        HandJointType.MIDDLE_TIP,
        HandJointType.RING_TIP,
        HandJointType.LITTLE_TIP,
    ).mapNotNull { joints[it]?.translation }
    if (tips.size < 4) return null
    val reach = tips.map { distance(it, palm) }.average().toFloat()
    if (reach < 0.01f) return null
    val fist = firmFist(wrist.toArray(), palm.toArray(), tips.map { it.toArray() }, span)
    val open = tips.count { distance(it, wrist) > span * 1.65f } >= 3
    return HandRead(fist, open)
}

internal fun dot(a: Vector3, b: Vector3): Float = a.x * b.x + a.y * b.y + a.z * b.z

internal fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

internal fun Pose.toRuntimePose(): androidx.xr.runtime.math.Pose {
    return androidx.xr.runtime.math.Pose(
        Vector3(position.getOrElse(0) { 0f }, position.getOrElse(1) { 0f }, position.getOrElse(2) { 0f }),
        Quaternion(
            rotation.getOrElse(0) { 0f },
            rotation.getOrElse(1) { 0f },
            rotation.getOrElse(2) { 0f },
            rotation.getOrElse(3) { 1f },
        ),
    )
}

internal fun androidx.xr.runtime.math.Pose.toWorkspacePose(): Pose {
    return Pose(
        floatArrayOf(translation.x, translation.y, translation.z),
        floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
    )
}

internal fun distance(a: Vector3, b: Vector3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

internal fun cursorBitmap(cursor: CursorView): Bitmap? {
    val pixels = cursor.pixels ?: return null
    val width = cursor.fragment.bitmapW
    val height = cursor.fragment.bitmapH
    if (width == 0 || height == 0 || pixels.size < width * height * 4) return null
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
        image.copyPixelsFromBuffer(ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN))
    }
}

/** Rotate a workspace-local offset by a quaternion stored as x, y, z, w. */
internal fun rotateVector(quaternion: FloatArray, x: Float, y: Float, z: Float): FloatArray {
    val qx = quaternion.getOrElse(0) { 0f }
    val qy = quaternion.getOrElse(1) { 0f }
    val qz = quaternion.getOrElse(2) { 0f }
    val qw = quaternion.getOrElse(3) { 1f }
    val tx = 2f * (qy * z - qz * y)
    val ty = 2f * (qz * x - qx * z)
    val tz = 2f * (qx * y - qy * x)
    return floatArrayOf(
        x + qw * tx + (qy * tz - qz * ty),
        y + qw * ty + (qz * tx - qx * tz),
        z + qw * tz + (qx * ty - qy * tx),
    )
}

internal fun subtract(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

internal fun add(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

internal fun midpoint(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

internal fun distance(a: FloatArray, b: FloatArray): Float {
    val d = subtract(a, b)
    return kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
}

internal fun quaternionBetween(from: FloatArray, to: FloatArray): FloatArray {
    val f = normalize3(from)
    val t = normalize3(to)
    val cosine = dot(f, t).coerceIn(-1f, 1f)
    val cross = cross3(f,t)
    val sine = kotlin.math.sqrt(dot(cross, cross))
    if (sine < 1e-7f) {
        if (cosine >= 0f) return floatArrayOf(0f, 0f, 0f, 1f)
        val axis = normalize3(floatArrayOf(-f[1], f[0], 0f).let {
            if (it[0] == 0f && it[1] == 0f) floatArrayOf(1f, 0f, 0f) else it
        })
        return floatArrayOf(axis[0], axis[1], axis[2], 0f)
    }
    // atan2 retains tiny ray changes, which acos/dot thresholds used to discard.
    val halfAngle = kotlin.math.atan2(sine, cosine) * 0.5f
    val factor = kotlin.math.sin(halfAngle) / sine
    return floatArrayOf(cross[0] * factor, cross[1] * factor, cross[2] * factor, kotlin.math.cos(halfAngle))
}

internal fun multiplyQuaternion(a: FloatArray, b: FloatArray): FloatArray {
    return floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )
}

internal fun normalize3(value: FloatArray): FloatArray {
    val length = kotlin.math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2])
    if (length < 1e-6f) return floatArrayOf(0f, 0f, 1f)
    return floatArrayOf(value[0] / length, value[1] / length, value[2] / length)
}

internal fun normalize4(value: FloatArray): FloatArray {
    val length = kotlin.math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2] + value[3] * value[3])
    if (length < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
    return floatArrayOf(value[0] / length, value[1] / length, value[2] / length, value[3] / length)
}

internal fun cross3(a:FloatArray,b:FloatArray):FloatArray= floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
