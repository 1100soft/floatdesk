package dev.floatdesk.client

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset as offset2d
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset as offset3d
import androidx.xr.compose.subspace.layout.requiredHeight
import androidx.xr.compose.subspace.layout.requiredWidth
import androidx.xr.compose.unit.Meter
import androidx.xr.compose.unit.toDp
import androidx.xr.scenecore.scene
import dev.floatdesk.client.net.CursorView
import dev.floatdesk.client.net.DeskConnection
import dev.floatdesk.client.net.MonitorHello
import dev.floatdesk.client.workspace.AnchorController
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.workspace.WorkspaceFile
import dev.floatdesk.client.workspace.WorkspaceStore
import dev.floatdesk.client.workspace.deskLayout
import dev.floatdesk.client.workspace.nudge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        setContent {
            FloatdeskApp(
                store = store,
                onAnchor = {
                    scenePermission.launch(Manifest.permission.SCENE_UNDERSTANDING_COARSE)
                },
            )
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
private fun FloatdeskApp(store: WorkspaceStore, onAnchor: () -> Unit) {
    val connection = remember { DeskConnection() }
    val session = LocalSession.current
    SessionHolder.session = session
    var host by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    var workspace by remember { mutableStateOf(store.load()) }
    val monitors by connection.monitors
    val cursors by connection.cursors
    val status by connection.status
    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
    LaunchedEffect(session, spatial) {
        if (session != null && spatial) session.scene.requestFullSpace()
    }
    LaunchedEffect(monitors) {
        if (monitors.isNotEmpty() && workspace.layouts[workspace.activeLayout]?.size != monitors.size) {
            workspace = workspace.reset(monitors, store)
        }
    }

    if (session != null && spatial) {
        val layout = layoutFor(workspace, monitors)
        Subspace {
            layout.forEachIndexed { index, placement ->
                SpatialExternalSurface(
                    stereoMode = StereoMode.Mono,
                    modifier = SubspaceModifier
                        .offset3d(
                            x = placement.pose.position[0].meters(),
                            y = placement.pose.position[1].meters(),
                            z = placement.pose.position[2].meters(),
                        )
                        .requiredWidth(placement.physicalWidthM.meters())
                        .requiredHeight(placement.physicalHeightM.meters()),
                ) {
                    onSurfaceCreated { surface -> connection.attachSurface(index, surface) }
                    onSurfaceDestroyed { connection.detachSurface(index) }
                    CursorLayer(cursors[index])
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Floatdesk")
        Text(status)
        Text(if (spatial) "Spatial surfaces are in the workspace." else "Spatial UI is not available on this device.")
        TextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { connection.connect(host) }) { Text("Connect") }
            Button(onClick = { connection.disconnect() }) { Text("Disconnect") }
            Button(onClick = onAnchor) { Text("Save room anchor") }
        }
        Text(workspace.anchorUuid?.let { "Workspace anchor $it" } ?: "No room anchor yet")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { selected = (selected - 1).coerceAtLeast(0) }) { Text("Monitor ${selected + 1}") }
            Button(onClick = {
                workspace = workspace.moved(selected, -0.05f, 0f, 0f, store)
            }) { Text("Left") }
            Button(onClick = {
                workspace = workspace.moved(selected, 0.05f, 0f, 0f, store)
            }) { Text("Right") }
            Button(onClick = {
                workspace = workspace.moved(selected, 0f, 0.05f, 0f, store)
            }) { Text("Up") }
            Button(onClick = {
                workspace = workspace.moved(selected, 0f, 0f, 0.05f, store)
            }) { Text("Back") }
            Button(onClick = {
                workspace = workspace.moved(selected, 0f, 0f, -0.05f, store)
            }) { Text("Closer") }
            Button(onClick = {
                val reset = workspace.reset(monitors, store)
                workspace = reset
            }) { Text("Reset desk") }
        }
    }
}

@Composable
private fun CursorLayer(cursor: CursorView?) {
    if (cursor == null || !cursor.fragment.visible) return
    val bitmap = remember(cursor.fragment.shapeId, cursor.pixels) {
        val pixels = cursor.pixels ?: return@remember null
        val width = cursor.fragment.bitmapW
        val height = cursor.fragment.bitmapH
        if (width == 0 || height == 0 || pixels.size < width * height * 4) return@remember null
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
            image.copyPixelsFromBuffer(ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .offset2d(
                        x = maxWidth * cursor.fragment.normX,
                        y = maxHeight * cursor.fragment.normY,
                    )
                    .size(cursor.fragment.bitmapW.dp, cursor.fragment.bitmapH.dp),
            )
        } else {
            Box(
                Modifier
                    .offset2d(x = maxWidth * cursor.fragment.normX, y = maxHeight * cursor.fragment.normY)
                    .size(12.dp)
                    .background(Color.White),
            )
        }
    }
}

private fun layoutFor(workspace: WorkspaceFile, monitors: List<MonitorHello>): List<MonitorPlacement> {
    val saved = workspace.layouts[workspace.activeLayout]
    if (saved != null && saved.size == monitors.size && monitors.isNotEmpty()) return saved
    if (monitors.isEmpty()) return emptyList()
    return deskLayout(monitors.map { Triple(it.name, it.width, it.height) })
}

private fun WorkspaceFile.moved(index: Int, dx: Float, dy: Float, dz: Float, store: WorkspaceStore): WorkspaceFile {
    val current = layouts[activeLayout] ?: return this
    if (index !in current.indices) return this
    val updated = current.toMutableList()
    updated[index] = nudge(updated[index], dx, dy, dz)
    val nextLayouts = layouts.toMutableMap()
    nextLayouts[activeLayout] = updated
    val next = copy(layouts = nextLayouts)
    store.save(next)
    return next
}

private fun WorkspaceFile.reset(monitors: List<MonitorHello>, store: WorkspaceStore): WorkspaceFile {
    if (monitors.isEmpty()) return this
    val nextLayouts = layouts.toMutableMap()
    nextLayouts[activeLayout] = deskLayout(monitors.map { Triple(it.name, it.width, it.height) })
    val next = copy(layouts = nextLayouts)
    store.save(next)
    return next
}

private fun Float.meters(): Dp = Meter(this).toDp()
