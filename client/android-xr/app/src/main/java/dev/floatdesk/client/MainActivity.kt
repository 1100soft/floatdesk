package dev.floatdesk.client

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.addCallback
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
        // A root XR task should pause on Back, just as on Home. Keeping its
        // session avoids the reproducible native SDK final-teardown fault;
        // existing lifecycle gates stop video/input work while backgrounded.
        onBackPressedDispatcher.addCallback(this){moveTaskToBack(true)}
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

    override fun onStop() {
        // SceneCore tears down native resources before Activity.onDestroy. Dispose
        // our Compose scene while the XR session is still alive, stopping producers
        // and releasing child entities before that native teardown starts.
        if(isFinishing || isChangingConfigurations){
            val content=findViewById<android.view.ViewGroup>(android.R.id.content)
            for(i in 0 until content.childCount)(content.getChildAt(i) as? androidx.compose.ui.platform.ComposeView)?.disposeComposition()
        }
        super.onStop()
    }

    private fun rememberAnchor() {
        val session = activeSession ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            AnchorController(store).ensure(session)
        }
    }
}
