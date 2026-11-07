package dev.floatdesk.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset as offset3d
import androidx.xr.compose.subspace.layout.requiredHeight
import androidx.xr.compose.subspace.layout.requiredWidth
import androidx.xr.scenecore.scene
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
    var selected by remember { mutableIntStateOf(0) }
    var workspace by remember { mutableStateOf(store.load()) }
    val monitors by connection.monitors
    val status by connection.status
    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
    var note by remember { mutableStateOf("") }
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
            selected = selected,
            onDisconnect = {
                connection.disconnect()
                connection.start(context)
            },
            onAnchor = {
                note = "Room anchor is not available on this headset yet. The monitors still connect without it."
            },
            onOpenWorkspace = { session?.scene?.requestFullSpace() },
            onSelectPrevious = { selected = (selected - 1).coerceAtLeast(0) },
            onNudge = { dx, dy, dz -> workspace = workspace.moved(selected, dx, dy, dz, store) },
            onReset = { workspace = workspace.reset(monitors, store) },
        )
    }

    if (session != null) {
        val layout = layoutFor(workspace, monitors)
        Subspace {
            SpatialPanel(
                modifier = SubspaceModifier
                    .offset3d(x = 0f.m(), y = (-0.55f).m(), z = (-1.0f).m())
                    .requiredWidth(0.8f.m())
                    .requiredHeight(0.48f.m()),
            ) {
                controls()
            }
            layout.forEachIndexed { index, placement ->
                SpatialExternalSurface(
                    stereoMode = StereoMode.Mono,
                    modifier = SubspaceModifier
                        .offset3d(
                            x = placement.pose.position[0].m(),
                            y = placement.pose.position[1].m(),
                            z = placement.pose.position[2].m(),
                        )
                        .requiredWidth(placement.physicalWidthM.m())
                        .requiredHeight(placement.physicalHeightM.m()),
                ) {
                    onSurfaceCreated { surface -> connection.attachSurface(index, surface) }
                    onSurfaceDestroyed { connection.detachSurface(index) }
                }
                SpatialPanel(
                    modifier = SubspaceModifier
                        .offset3d(
                            x = placement.pose.position[0].m(),
                            y = (placement.pose.position[1] - placement.physicalHeightM / 2f - 0.06f).m(),
                            z = placement.pose.position[2].m(),
                        )
                        .requiredWidth(0.28f.m())
                        .requiredHeight(0.06f.m()),
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF3D5AFE)),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text("Monitor ${index + 1}", color = Color.White)
                    }
                }
            }
        }
    } else {
        controls()
    }
}

@Composable
private fun ControlPanel(
    status: String,
    spatial: Boolean,
    anchorLabel: String,
    selected: Int,
    onDisconnect: () -> Unit,
    onAnchor: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSelectPrevious: () -> Unit,
    onNudge: (Float, Float, Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.width(1500.dp).height(860.dp).background(Color(0xFF1C1C1E)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Floatdesk", color = Color.White)
        Text(status, color = Color.White)
        Text(
            if (spatial) {
                "The laptop screen appears on the monitor above. No address to type."
            } else {
                "Opening the workspace. If this stays flat, close the app and open it once more."
            },
            color = Color.White,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDisconnect) { Text("Search again") }
            Button(onClick = onOpenWorkspace) { Text("Open workspace") }
            Button(onClick = onAnchor) { Text("Save room anchor") }
        }
        Text(anchorLabel, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSelectPrevious) { Text("Monitor ${selected + 1}") }
            Button(onClick = { onNudge(-0.05f, 0f, 0f) }) { Text("Left") }
            Button(onClick = { onNudge(0.05f, 0f, 0f) }) { Text("Right") }
            Button(onClick = { onNudge(0f, 0.05f, 0f) }) { Text("Up") }
            Button(onClick = { onNudge(0f, 0f, 0.05f) }) { Text("Back") }
            Button(onClick = { onNudge(0f, 0f, -0.05f) }) { Text("Closer") }
            Button(onClick = onReset) { Text("Reset desk") }
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

/** Jetpack XR treats about 2000 dp as one meter in a subspace. */
private fun Float.m(): Dp = Dp(this * 2000f)
