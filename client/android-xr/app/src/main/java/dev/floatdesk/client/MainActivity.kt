package dev.floatdesk.client

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.floatdesk.client.workspace.AnchorController
import dev.floatdesk.client.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private lateinit var store: WorkspaceStore
    private var activeSession:androidx.xr.runtime.Session?=null

    private val scenePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) rememberAnchor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = WorkspaceStore(File(filesDir, "workspace.json"))
        val permissions = arrayOf(
            "android.permission.HAND_TRACKING",
            "android.permission.HEAD_TRACKING",
        ).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 7)
        }
        val dump = if (intent.getBooleanExtra("floatdesk_dump", false)) {
            File(getExternalFilesDir(null), "frame.jpg")
        } else {
            null
        }
        setContent {
            FloatdeskApp(store = store, dumpFile = dump,onSession={activeSession=it})
        }
    }

    private fun rememberAnchor() {
        val session = activeSession ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            AnchorController(store).ensure(session)
        }
    }
}
