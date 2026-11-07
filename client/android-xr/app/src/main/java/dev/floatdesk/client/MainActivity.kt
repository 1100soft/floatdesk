package dev.floatdesk.client

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.ResizePolicy
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
import androidx.xr.runtime.math.Quaternion
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

@Composable
private fun FloatdeskApp(store: WorkspaceStore, dumpFile: File?) {
    val connection = remember { DeskConnection(dumpFile) }
    val context = LocalContext.current
    LaunchedEffect(connection) { connection.start(context) }
    val session = LocalSession.current
    SessionHolder.session = session
    var workspace by remember { mutableStateOf(store.load()) }
    val workspaceNow by rememberUpdatedState(workspace)
    val monitors by connection.monitors
    val cursors by connection.cursors
    val status by connection.status
    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
    var note by remember { mutableStateOf("") }
    val density = LocalDensity.current
    val pixelDensity = session?.scene?.virtualPixelDensity
    LaunchedEffect(session) {
        session?.scene?.requestFullSpace()
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
        )
    }

    if (session != null && pixelDensity != null) {
        val layout = layoutFor(workspace, monitors)
        val control = workspace.control
        Subspace {
            SpatialPanel(
                modifier = SubspaceModifier
                    .requiredWidth(control.widthM.meters(density, pixelDensity))
                    .requiredHeight(control.heightM.meters(density, pixelDensity))
                    .rotate(control.pose.toQuaternion())
                    .offset3d(
                        x = control.pose.position[0].meters(density, pixelDensity),
                        y = control.pose.position[1].meters(density, pixelDensity),
                        z = control.pose.position[2].meters(density, pixelDensity),
                    )
                    .movable(
                        movePolicy = MovePolicy.custom(scaleWithDistance = false) { event ->
                            val current = workspaceNow.control
                            workspace = workspaceNow.controlPlaced(
                                current.copy(pose = event.workspacePose(pixelDensity)),
                                store,
                                event.type == SpatialMoveEventType.End,
                            )
                        },
                    )
                    .resizable(
                        minimumSize = DpVolumeSize(0.35f.meters(density, pixelDensity), 0.22f.meters(density, pixelDensity), 0.dp),
                        maximumSize = DpVolumeSize(1.6f.meters(density, pixelDensity), 1.1f.meters(density, pixelDensity), 0.dp),
                        resizePolicy = ResizePolicy.custom { event ->
                            val size = event.sizeInMeters(pixelDensity) ?: return@custom
                            workspace = workspaceNow.controlPlaced(
                                workspaceNow.control.copy(widthM = size.first, heightM = size.second),
                                store,
                                event.type == SpatialResizeEventType.End,
                            )
                        },
                    ),
            ) {
                controls()
            }
            layout.forEachIndexed { index, placement ->
                SpatialExternalSurface(
                    stereoMode = StereoMode.Mono,
                    modifier = SubspaceModifier
                        .requiredWidth(placement.physicalWidthM.meters(density, pixelDensity))
                        .requiredHeight(placement.physicalHeightM.meters(density, pixelDensity))
                        .rotate(placement.pose.toQuaternion())
                        .offset3d(
                            x = placement.pose.position[0].meters(density, pixelDensity),
                            y = placement.pose.position[1].meters(density, pixelDensity),
                            z = placement.pose.position[2].meters(density, pixelDensity),
                        )
                        .movable(
                            movePolicy = MovePolicy.custom(scaleWithDistance = false) { event ->
                                val existing = workspaceNow.monitorAt(index) ?: return@custom
                                workspace = workspaceNow.monitorPlaced(
                                    index,
                                    existing.copy(pose = event.workspacePose(pixelDensity)),
                                    store,
                                    event.type == SpatialMoveEventType.End,
                                )
                            },
                        )
                        .resizable(
                            minimumSize = DpVolumeSize(0.28f.meters(density, pixelDensity), 0.16f.meters(density, pixelDensity), 0.dp),
                            maximumSize = DpVolumeSize(2.4f.meters(density, pixelDensity), 1.5f.meters(density, pixelDensity), 0.dp),
                            maintainAspectRatio = true,
                            resizePolicy = ResizePolicy.custom { event ->
                                val size = event.sizeInMeters(pixelDensity) ?: return@custom
                                val existing = workspaceNow.monitorAt(index) ?: return@custom
                                workspace = workspaceNow.monitorPlaced(
                                    index,
                                    existing.copy(physicalWidthM = size.first, physicalHeightM = size.second),
                                    store,
                                    event.type == SpatialResizeEventType.End,
                                )
                            },
                        ),
                ) {
                    onSurfaceCreated { surface -> connection.attachSurface(index, surface) }
                    onSurfaceDestroyed { connection.detachSurface(index) }
                }
                CursorPanel(placement, cursors[index], density, pixelDensity)
            }
        }
    } else {
        controls()
    }
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
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Floatdesk", color = Color.White)
        Text(status, color = Color.White)
        Text(
            if (spatial) {
                "Grab a monitor or this panel to move and turn it. Drag the handles to resize. The pointer follows the laptop cursor."
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
