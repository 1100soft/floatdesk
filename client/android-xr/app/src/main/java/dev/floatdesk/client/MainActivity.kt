package dev.floatdesk.client

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.animation.follow.FollowMode
import androidx.xr.compose.subspace.animation.follow.FollowTarget
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.ResizePolicy
import androidx.xr.compose.subspace.layout.InteractionPolicy
import androidx.xr.compose.subspace.layout.SpatialInputEvent
import androidx.xr.compose.subspace.layout.SpatialMoveEvent
import androidx.xr.compose.subspace.layout.SpatialMoveEventType
import androidx.xr.compose.subspace.layout.SpatialResizeEvent
import androidx.xr.compose.subspace.layout.SpatialResizeEventType
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset as offset3d
import androidx.xr.compose.subspace.layout.requiredHeight
import androidx.xr.compose.subspace.layout.requiredWidth
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.unit.DpVolumeSize
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.PixelDensity
import androidx.xr.scenecore.scene
import dev.floatdesk.client.net.CursorView
import dev.floatdesk.client.net.DeskConnection
import dev.floatdesk.client.net.MonitorHello
import dev.floatdesk.client.workspace.AnchorController
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.workspace.PanelPlacement
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceFile
import dev.floatdesk.client.workspace.WorkspaceStore
import dev.floatdesk.client.workspace.controlPlaced
import dev.floatdesk.client.workspace.deskLayout
import dev.floatdesk.client.workspace.monitorPlaced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var store: WorkspaceStore

    private val scenePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) rememberAnchor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WorkspaceStore(File(filesDir, "workspace.json"))
        if (checkSelfPermission("android.permission.HAND_TRACKING") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.HAND_TRACKING"), 7)
        }
        val dump = if (intent.getBooleanExtra("floatdesk_dump", false)) {
            File(getExternalFilesDir(null), "frame.jpg")
        } else {
            null
        }
        setContent {
            FloatdeskApp(store = store, dumpFile = dump)
        }
    }

    private fun rememberAnchor() {
        val session = SessionHolder.session ?: return
        scope.launch(Dispatchers.IO) {
            AnchorController(store).ensure(session)
        }
    }
}

private object SessionHolder {
    var session: androidx.xr.runtime.Session? = null
}

@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
private fun FloatdeskApp(store: WorkspaceStore, dumpFile: File?) {
    val connection = remember { DeskConnection(dumpFile) }
    val context = LocalContext.current
    LaunchedEffect(connection) { connection.start(context) }
    val session = LocalSession.current
    SessionHolder.session = session
    var workspace by remember { mutableStateOf(store.load()) }
    val monitors by connection.monitors
    val status by connection.status
    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
    var note by remember { mutableStateOf("") }
    var headLocked by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val pixelDensity = session?.scene?.virtualPixelDensity
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        current.scene.requestFullSpace()
        runCatching {
            current.configure(
                Config.Builder(current.config)
                    .setHandTracking(HandTrackingMode.BOTH)
                    .setDeviceTracking(DeviceTrackingMode.SPATIAL)
                    .build(),
            )
        }
        val hands = listOfNotNull(
            runCatching { Hand.left(current) }.getOrNull(),
            runCatching { Hand.right(current) }.getOrNull(),
        )
        val wasFist = BooleanArray(hands.size)
        while (isActive) {
            delay(40)
            var anyFist = false
            var opened = false
            hands.forEachIndexed { index, hand ->
                val fist = handIsFist(hand.state.value)
                if (fist) anyFist = true
                if (wasFist[index] && !fist && handIsOpen(hand.state.value)) opened = true
                wasFist[index] = fist
            }
            when {
                anyFist -> headLocked = true
                opened -> headLocked = false
            }
        }
    }
    LaunchedEffect(monitors) {
        if (monitors.isNotEmpty() && workspace.layouts[workspace.activeLayout]?.size != monitors.size) {
            workspace = workspace.reset(monitors, store)
        }
    }

    val controls = @Composable {
        ControlPanel(
            status = status,
            spatial = spatial,
            anchorLabel = note.ifEmpty {
                workspace.anchorUuid?.let { "Workspace anchor $it" } ?: "No room anchor yet"
            },
            onDisconnect = {
                connection.disconnect()
                connection.start(context)
            },
            onAnchor = {
                note = "Room anchor is not available on this headset yet. The monitors still connect without it."
            },
            onOpenWorkspace = { session?.scene?.requestFullSpace() },
            onReset = { workspace = workspace.reset(monitors, store) },
            headLocked = headLocked,
            onToggleHead = { headLocked = !headLocked },
        )
    }

    if (session != null && pixelDensity != null) {
        val desk: @Composable () -> Unit = {
            DeskContent(
                connection = connection,
                workspace = workspace,
                onWorkspace = { workspace = it },
                monitors = monitors,
                store = store,
                density = density,
                pixelDensity = pixelDensity,
                controls = controls,
            )
        }
        if (headLocked) {
            Subspace(follow = FollowTarget.view(FollowMode.tight())) { desk() }
        } else {
            Subspace { desk() }
        }
    } else {
        controls()
    }
}

@Composable
private fun DeskContent(
    connection: DeskConnection,
    workspace: WorkspaceFile,
    onWorkspace: (WorkspaceFile) -> Unit,
    monitors: List<MonitorHello>,
    store: WorkspaceStore,
    density: Density,
    pixelDensity: PixelDensity,
    controls: @Composable () -> Unit,
) {
    val latest = rememberUpdatedState(workspace)
    val layout = layoutFor(workspace, monitors)
    val control = workspace.control
    SpatialPanel(
        modifier = placedPanel(
            pose = control.pose,
            widthM = control.widthM,
            heightM = control.heightM,
            density = density,
            pixelDensity = pixelDensity,
        ).movable(
            movePolicy = MovePolicy.system(scaleWithDistance = false) { event ->
                if (event.type != SpatialMoveEventType.End) return@system
                val current = latest.value.control
                onWorkspace(
                    latest.value.controlPlaced(
                        current.copy(pose = event.workspacePose(pixelDensity)),
                        store,
                        true,
                    ),
                )
            },
        ).resizable(
            minimumSize = DpVolumeSize(0.45f.meters(density, pixelDensity), 0.28f.meters(density, pixelDensity), 0.dp),
            maximumSize = DpVolumeSize(1.6f.meters(density, pixelDensity), 1.1f.meters(density, pixelDensity), 0.dp),
            resizePolicy = ResizePolicy.system { event ->
                if (event.type != SpatialResizeEventType.End) return@system
                val size = event.sizeInMeters(pixelDensity) ?: return@system
                onWorkspace(
                    latest.value.controlPlaced(
                        latest.value.control.copy(widthM = size.first, heightM = size.second),
                        store,
                        true,
                    ),
                )
            },
        ),
    ) {
        controls()
    }
    layout.forEachIndexed { index, placement ->
        val hands = remember(index) { TwoHandState() }
        val pinch = remember(index) { PinchMemory() }
        SpatialExternalSurface(
            stereoMode = StereoMode.Mono,
            interactionPolicy = remember(index) {
                object : InteractionPolicy {
                    override val isEnabled: Boolean = true
                    override fun onInputEvent(event: SpatialInputEvent) {
                        val file = latest.value
                        val existing = file.monitorAt(index) ?: return
                        val next = hands.onEvent(event, existing, pixelDensity) ?: return
                        onWorkspace(
                            file.monitorPlaced(
                                index,
                                next,
                                store,
                                event.action == InputEvent.Action.UP || event.action == InputEvent.Action.CANCEL,
                            ),
                        )
                    }
                }
            },
            modifier = placedPanel(
                pose = placement.pose,
                widthM = placement.physicalWidthM,
                heightM = placement.physicalHeightM,
                density = density,
                pixelDensity = pixelDensity,
            ).movable(
                movePolicy = MovePolicy.custom(scaleWithDistance = false) { event ->
                    if (hands.engaged) return@custom
                    val existing = latest.value.monitorAt(index) ?: return@custom
                    onWorkspace(
                        latest.value.monitorPlaced(
                            index,
                            pinch.apply(event, existing, pixelDensity),
                            store,
                            event.type == SpatialMoveEventType.End,
                        ),
                    )
                },
            ).resizable(
                minimumSize = DpVolumeSize(0.28f.meters(density, pixelDensity), 0.16f.meters(density, pixelDensity), 0.dp),
                maximumSize = DpVolumeSize(2.4f.meters(density, pixelDensity), 1.5f.meters(density, pixelDensity), 0.dp),
                maintainAspectRatio = true,
                resizePolicy = ResizePolicy.custom { event ->
                    if (hands.engaged) return@custom
                    val size = event.sizeInMeters(pixelDensity) ?: return@custom
                    val existing = latest.value.monitorAt(index) ?: return@custom
                    onWorkspace(
                        latest.value.monitorPlaced(
                            index,
                            existing.copy(physicalWidthM = size.first, physicalHeightM = size.second),
                            store,
                            event.type == SpatialResizeEventType.End,
                        ),
                    )
                },
            ),
        ) {
            onSurfaceCreated { surface -> connection.attachSurface(index, surface) }
            onSurfaceDestroyed { connection.detachSurface(index) }
        }
    }
}

@Composable
private fun placedPanel(
    pose: Pose,
    widthM: Float,
    heightM: Float,
    density: Density,
    pixelDensity: PixelDensity,
    dx: Float = 0f,
    dy: Float = 0f,
    dz: Float = 0f,
): SubspaceModifier {
    return SubspaceModifier
        .requiredWidth(widthM.meters(density, pixelDensity))
        .requiredHeight(heightM.meters(density, pixelDensity))
        .rotate(pose.toQuaternion())
        .offset3d(
            x = (pose.position[0] + dx).meters(density, pixelDensity),
            y = (pose.position[1] + dy).meters(density, pixelDensity),
            z = (pose.position[2] + dz).meters(density, pixelDensity),
        )
}

@Composable
private fun MonitorCursor(
    connection: DeskConnection,
    index: Int,
    monitor: MonitorPlacement,
    density: Density,
    pixelDensity: PixelDensity,
) {
    val cursors by connection.cursors
    CursorPanel(monitor, cursors[index], density, pixelDensity)
}

@Composable
private fun CursorPanel(
    monitor: MonitorPlacement,
    cursor: CursorView?,
    density: Density,
    pixelDensity: PixelDensity,
) {
    val fragment = cursor?.fragment ?: return
    if (!fragment.visible) return
    val bitmap = remember(fragment.shapeId, cursor.pixels) {
        cursorBitmap(cursor)
    } ?: return
    val pixelW = max(monitor.pixelWidth, 1).toFloat()
    val pixelH = max(monitor.pixelHeight, 1).toFloat()
    var widthM = monitor.physicalWidthM * bitmap.width / pixelW
    var heightM = monitor.physicalHeightM * bitmap.height / pixelH
    val longest = max(widthM, heightM)
    if (longest < 0.028f && longest > 0f) {
        val scale = 0.028f / longest
        widthM *= scale
        heightM *= scale
    }
    val hotspotX = widthM * fragment.hotspotX / max(bitmap.width, 1)
    val hotspotY = heightM * fragment.hotspotY / max(bitmap.height, 1)
    val pointerX = (fragment.normX - 0.5f) * monitor.physicalWidthM
    val pointerY = (0.5f - fragment.normY) * monitor.physicalHeightM
    val centerX = pointerX + widthM / 2f - hotspotX
    val centerY = pointerY - heightM / 2f + hotspotY
    val rotated = rotateVector(monitor.pose.rotation, centerX, centerY, -0.02f)
    SpatialPanel(
        modifier = SubspaceModifier
            .requiredWidth(widthM.meters(density, pixelDensity))
            .requiredHeight(heightM.meters(density, pixelDensity))
            .rotate(monitor.pose.toQuaternion())
            .offset3d(
                x = (monitor.pose.position[0] + rotated[0]).meters(density, pixelDensity),
                y = (monitor.pose.position[1] + rotated[1]).meters(density, pixelDensity),
                z = (monitor.pose.position[2] + rotated[2]).meters(density, pixelDensity),
            ),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
private fun ControlPanel(
    status: String,
    spatial: Boolean,
    anchorLabel: String,
    onDisconnect: () -> Unit,
    onAnchor: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onReset: () -> Unit,
    headLocked: Boolean,
    onToggleHead: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Floatdesk", color = Color.White)
        Text(status, color = Color.White)
        Text(
            if (spatial) {
                if (headLocked) {
                    "The desk is following your head. Close a fist to keep following, and open that hand to lock it in the room."
                } else {
                    "The desk stays where you leave it. Close a fist to make it follow your head, and open that hand to lock it again."
                }
            } else {
                "Opening the workspace. If this stays flat, close the app and open it once more."
            },
            color = Color.White,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDisconnect) { Text("Search again") }
            Button(onClick = onOpenWorkspace) { Text("Open workspace") }
            Button(onClick = onAnchor) { Text("Save room anchor") }
            Button(onClick = onReset) { Text("Reset desk") }
            Button(onClick = onToggleHead) { Text(if (headLocked) "Lock in space" else "Follow head") }
        }
        Text(anchorLabel, color = Color.White)
    }
}

private fun layoutFor(workspace: WorkspaceFile, monitors: List<MonitorHello>): List<MonitorPlacement> {
    val saved = workspace.layouts[workspace.activeLayout]
    if (saved != null && saved.size == monitors.size && monitors.isNotEmpty()) return saved
    if (monitors.isEmpty()) return emptyList()
    return deskLayout(monitors.map { Triple(it.name, it.width, it.height) })
}

private fun WorkspaceFile.monitorAt(index: Int): MonitorPlacement? {
    return layouts[activeLayout]?.getOrNull(index)
}

private fun WorkspaceFile.reset(monitors: List<MonitorHello>, store: WorkspaceStore): WorkspaceFile {
    if (monitors.isEmpty()) return this
    val nextLayouts = layouts.toMutableMap()
    nextLayouts[activeLayout] = deskLayout(monitors.map { Triple(it.name, it.width, it.height) })
    val next = copy(layouts = nextLayouts, control = PanelPlacement())
    store.save(next)
    return next
}

private fun SpatialMoveEvent.workspacePose(pixelDensity: PixelDensity): Pose {
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

private fun SpatialResizeEvent.sizeInMeters(pixelDensity: PixelDensity): Pair<Float, Float>? {
    val width = pixelDensity.convertPixelsToMeters(size.width.toFloat())
    val height = pixelDensity.convertPixelsToMeters(size.height.toFloat())
    if (width < 0.05f || height < 0.05f) return null
    return width to height
}

private fun Pose.toQuaternion(): Quaternion = Quaternion(rotation[0], rotation[1], rotation[2], rotation[3])

private fun Float.meters(density: Density, pixelDensity: PixelDensity): Dp {
    return with(density) { pixelDensity.convertMetersToPixels(this@meters).toDp() }
}

private class PinchMemory {
    private var startScale = 1f
    private var startWidth = 0f
    private var startHeight = 0f

    fun apply(event: SpatialMoveEvent, placement: MonitorPlacement, pixelDensity: PixelDensity): MonitorPlacement {
        if (event.type == SpatialMoveEventType.Start || startWidth == 0f) {
            startScale = event.scale.coerceAtLeast(0.01f)
            startWidth = placement.physicalWidthM
            startHeight = placement.physicalHeightM
        }
        val factor = (event.scale / startScale).coerceIn(0.25f, 4f)
        return placement.copy(
            pose = event.workspacePose(pixelDensity),
            physicalWidthM = (startWidth * factor).coerceIn(0.28f, 2.4f),
            physicalHeightM = (startHeight * factor).coerceIn(0.16f, 1.5f),
        )
    }
}

private class TwoHandState {
    var engaged: Boolean = false
        private set

    private val hands = HashMap<String, FloatArray>()
    private var baseline: Baseline? = null

    fun onEvent(event: SpatialInputEvent, current: MonitorPlacement, pixelDensity: PixelDensity): MonitorPlacement? {
        val key = event.pointerType.toString()
        if (key != "LEFT" && key != "RIGHT") return null
        when (event.action) {
            InputEvent.Action.UP, InputEvent.Action.CANCEL -> hands.remove(key)
            InputEvent.Action.DOWN, InputEvent.Action.MOVE -> {
                hands[key] = floatArrayOf(
                    pixelDensity.convertPixelsToMeters(event.origin.x),
                    pixelDensity.convertPixelsToMeters(event.origin.y),
                    pixelDensity.convertPixelsToMeters(event.origin.z),
                )
            }
            else -> return null
        }
        val left = hands["LEFT"]
        val right = hands["RIGHT"]
        if (left == null || right == null) {
            engaged = false
            baseline = null
            return null
        }
        val base = baseline ?: Baseline(left.copyOf(), right.copyOf(), current).also {
            baseline = it
            engaged = true
        }
        if (distance(left, right) < 0.02f) return null
        val scale = (distance(left, right) / distance(base.left, base.right).coerceAtLeast(0.02f))
            .coerceIn(0.25f, 4f)
        val turn = quaternionBetween(subtract(base.right, base.left), subtract(right, left))
        val mid0 = midpoint(base.left, base.right)
        val mid1 = midpoint(left, right)
        return base.placement.copy(
            pose = Pose(
                floatArrayOf(
                    base.placement.pose.position[0] + mid1[0] - mid0[0],
                    base.placement.pose.position[1] + mid1[1] - mid0[1],
                    base.placement.pose.position[2] + mid1[2] - mid0[2],
                ),
                multiplyQuaternion(turn, base.placement.pose.rotation),
            ),
            physicalWidthM = (base.placement.physicalWidthM * scale).coerceIn(0.28f, 2.4f),
            physicalHeightM = (base.placement.physicalHeightM * scale).coerceIn(0.16f, 1.5f),
        )
    }

    private class Baseline(
        val left: FloatArray,
        val right: FloatArray,
        val placement: MonitorPlacement,
    )
}

private fun handIsFist(state: Hand.State): Boolean = fingerReach(state)?.let { it < 0.085f } == true

private fun handIsOpen(state: Hand.State): Boolean = fingerReach(state)?.let { it > 0.13f } == true

private fun fingerReach(state: Hand.State): Float? {
    if (state.trackingState != TrackingState.TRACKING) return null
    val palm = state.handJoints[HandJointType.PALM]?.translation ?: return null
    val tips = listOf(
        HandJointType.INDEX_TIP,
        HandJointType.MIDDLE_TIP,
        HandJointType.RING_TIP,
        HandJointType.LITTLE_TIP,
    ).mapNotNull { state.handJoints[it]?.translation }
    if (tips.size < 4) return null
    return tips.map { distance(it, palm) }.average().toFloat()
}

private fun distance(a: Vector3, b: Vector3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

private fun cursorBitmap(cursor: CursorView): Bitmap? {
    val pixels = cursor.pixels ?: return null
    val width = cursor.fragment.bitmapW
    val height = cursor.fragment.bitmapH
    if (width == 0 || height == 0 || pixels.size < width * height * 4) return null
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
        image.copyPixelsFromBuffer(ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN))
    }
}

/** Rotate a workspace-local offset by a quaternion stored as x, y, z, w. */
private fun rotateVector(quaternion: FloatArray, x: Float, y: Float, z: Float): FloatArray {
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

private fun subtract(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

private fun midpoint(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

private fun distance(a: FloatArray, b: FloatArray): Float {
    val d = subtract(a, b)
    return kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
}

private fun quaternionBetween(from: FloatArray, to: FloatArray): FloatArray {
    val f = normalize3(from)
    val t = normalize3(to)
    val dot = f[0] * t[0] + f[1] * t[1] + f[2] * t[2]
    if (dot > 0.9995f) return floatArrayOf(0f, 0f, 0f, 1f)
    if (dot < -0.9995f) {
        val axis = normalize3(floatArrayOf(-f[1], f[0], 0f).let {
            if (it[0] == 0f && it[1] == 0f) floatArrayOf(1f, 0f, 0f) else it
        })
        return floatArrayOf(axis[0], axis[1], axis[2], 0f)
    }
    val cross = floatArrayOf(
        f[1] * t[2] - f[2] * t[1],
        f[2] * t[0] - f[0] * t[2],
        f[0] * t[1] - f[1] * t[0],
    )
    return normalize4(floatArrayOf(cross[0], cross[1], cross[2], 1f + dot))
}

private fun multiplyQuaternion(a: FloatArray, b: FloatArray): FloatArray {
    return floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )
}

private fun normalize3(value: FloatArray): FloatArray {
    val length = kotlin.math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2])
    if (length < 1e-6f) return floatArrayOf(0f, 0f, 1f)
    return floatArrayOf(value[0] / length, value[1] / length, value[2] / length)
}

private fun normalize4(value: FloatArray): FloatArray {
    val length = kotlin.math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2] + value[3] * value[3])
    if (length < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
    return floatArrayOf(value[0] / length, value[1] / length, value[2] / length, value[3] / length)
}
