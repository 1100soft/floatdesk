package dev.floatdesk.client

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.xr.arcore.Eye
import androidx.xr.runtime.EyeTrackingMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.InteractionPolicy
import androidx.xr.compose.subspace.layout.SpatialInputEvent
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.ResizePolicy
import androidx.xr.compose.subspace.layout.SpatialMoveEvent
import androidx.xr.compose.subspace.layout.SpatialMoveEventType
import androidx.xr.compose.subspace.layout.SpatialResizeEvent
import androidx.xr.compose.subspace.layout.SpatialResizeEventType
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.unit.DpVolumeSize
import androidx.xr.compose.subspace.layout.pointerHoverIcon
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.compose.subspace.layout.offset as offset3d
import androidx.xr.compose.subspace.layout.requiredHeight
import androidx.xr.compose.subspace.layout.requiredWidth
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.Space
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
import dev.floatdesk.client.workspace.restoreViewer
import dev.floatdesk.client.workspace.reconcileMonitors
import dev.floatdesk.client.workspace.deskLayout
import dev.floatdesk.client.workspace.monitorPlaced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
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
    DisposableEffect(connection) { onDispose { connection.disconnect() } }
    val session = LocalSession.current
    var workspace by remember { mutableStateOf(store.load()) }
    val workspaceAtLifecycle=rememberUpdatedState(workspace)
    var restoreViewerPending by remember {mutableStateOf(true)}
    SessionHolder.session = session
    DisposableEffect(session,connection) {
        val current = session
        val observer = LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) store.save(workspaceAtLifecycle.value)
            connection.setRenderingActive(current?.lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)==true)
            if (current != null && event == Lifecycle.Event.ON_RESUME) {
                runCatching { current.configure(Config.Builder(current.config).setHandTracking(HandTrackingMode.BOTH).build()) }
                runCatching { current.configure(Config.Builder(current.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()) }
            }
        }
        current?.lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            current?.lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }
    LaunchedEffect(workspace.screenSpacing) {connection.configurePresentation(workspace.screenSpacing)}
    val pointerControl = remember { PointerControl() }
    val gazeStabilizer = remember { GazeStabilizer() }
    val mouseGestures = remember { MouseGestures { Log.i("Floatdesk",it) } }
    val panelInput = remember { PanelGazeInput(palmButtons.map { PanelButtonArea(it.action.ordinal,it.x,it.y,it.w,it.h) }) }
    var panelFocused by remember { mutableStateOf<PalmAction?>(null) }
    var panelPressed by remember { mutableStateOf<PalmAction?>(null) }
    var feedbackAt by remember { mutableStateOf(0L) }
    var panelEyePermissionAsked by remember { mutableStateOf(false) }
    val clickView=LocalView.current
    LaunchedEffect(feedbackAt) { if(feedbackAt>0) { delay(180); panelPressed=null } }
    var handInputAllowed by remember { mutableStateOf(true) }
    var palmInteractionEpoch by remember { mutableIntStateOf(0) }
    var dimPointer by remember { mutableStateOf(false) }
    val eyePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) android.widget.Toast.makeText(context,"Gaze permission was not granted. Hand pointing remains available.",android.widget.Toast.LENGTH_LONG).show()
    }
    val monitors by connection.monitors
    val status by connection.status
    val spatial = LocalSpatialCapabilities.current.isSpatialUiEnabled
    var note by remember { mutableStateOf("") }
    var latchedFollow by remember { mutableStateOf(false) }
    var holdingFist by remember { mutableStateOf(false) }
    var suppressFist by remember { mutableStateOf(false) }
    var manipulating by remember { mutableStateOf(false) }
    var followGeneration by remember { mutableIntStateOf(0) }
    var gestureNote by remember { mutableStateOf("Waiting for hand tracking…") }
    val following = latchedFollow || (holdingFist && !suppressFist)
    val grabs = remember { mutableMapOf<Int, TwoHandState>() }
    DisposableEffect(session) {
        val current = session
        val listener = Runnable {
            Log.w("Floatdesk","XR activity origin changed; cancelling gestures")
            workspace=workspace.copy(viewerReference=store.viewerReference ?: workspace.viewerReference)
            restoreViewerPending=true
            grabs.values.forEach { it.cancel() }
            manipulating = false
            suppressFist = true
            followGeneration += 1
        }
        current?.scene?.activitySpace?.addOriginChangedListener(listener)
        onDispose { runCatching { current?.scene?.activitySpace?.removeOriginChangedListener(listener) } }
    }
    val handTracking = remember { GrabTracking() }
    val grips = handTracking.points
    val deskSpace = remember { AtomicReference<Entity?>(null) }
    val displayHead=remember { AtomicReference<Pose?>(null) }
    var displayControlsOpen by remember { mutableStateOf(false) }
    var displayControlsPose by remember { mutableStateOf(workspace.control.pose) }
    fun toggleDisplays() {
        displayControlsOpen=!displayControlsOpen
        if(displayControlsOpen) {
            displayHead.get()?.let { head-> displayControlsPose=Pose(transformPoint(head,floatArrayOf(0f,-.15f,-.9f)),head.rotation.copyOf()) }
            connection.requestDisplays()
        }
    }

    val redrawAim=remember { AtomicReference<()->Unit>({}) }
    LaunchedEffect(session) {
        var frames=0
        var began=SystemClock.uptimeMillis()
        while(isActive) {
            if(session?.lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)!=true) {
                delay(100);continue
            }
            androidx.compose.runtime.withFrameNanos { }
            frames++
            val now=SystemClock.uptimeMillis()
            if(now-began>=5000) {
                Log.i("Floatdesk","ray display frames=$frames windowMs=${now-began}")
                frames=0;began=now
            }
            runCatching { redrawAim.get().invoke() }.onFailure { Log.w("Floatdesk","Ray frame update failed",it) }
        }
    }
    var guideRenderer by remember(session) { mutableStateOf<GrabGuideRenderer?>(null) }
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        val renderer = try {
            GrabGuideRenderer.create(current)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.e("Floatdesk", "Could not load grab guide meshes", failure)
            return@LaunchedEffect
        }
        guideRenderer = renderer
        try { awaitCancellation() } finally {
            guideRenderer = null
            renderer.close()
        }
    }
    var palmPanel by remember { mutableStateOf<PalmPanelState?>(null) }
    val poke = remember { PalmPoke() }
    var lastPalmAction by remember { mutableStateOf(0L) }
    val palmAction: (PalmAction) -> Unit = { action ->
        val now = SystemClock.uptimeMillis()
        if (handInputAllowed && palmPanel!=null && now - lastPalmAction > 450) {
            lastPalmAction = now
            panelPressed=action; feedbackAt=now
            clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            when (action) {
                PalmAction.FOLLOW -> { latchedFollow = !latchedFollow; suppressFist = true; followGeneration += 1 }
                PalmAction.RESET -> { workspace = workspace.reset(monitors, store); followGeneration += 1 }
                PalmAction.SEARCH -> { connection.disconnect(); connection.start(context) }
                PalmAction.SPACE -> { session?.scene?.requestFullSpace();toggleDisplays() }
                PalmAction.GESTURES -> {
                    workspace=workspace.copy(mouseGestures = !workspace.mouseGestures)
                    store.save(workspace)
                }
                PalmAction.EYES -> {
                    workspace = workspace.copy(eyeAiming = !workspace.eyeAiming)
                    store.save(workspace)
                    if (workspace.eyeAiming && context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        eyePermission.launch("android.permission.EYE_TRACKING_FINE")
                }
                PalmAction.WARP -> {
                    grabs.values.forEach { it.cancel() }
                    manipulating = false
                    followGeneration += 1
                    workspace = workspace.copy(sphericalWarp = !workspace.sphericalWarp)
                    store.save(workspace)
                }
            }
        }
    }
    val palmActionNow = rememberUpdatedState(palmAction)
    fun updateManipulation(active: Boolean) {
        manipulating = active
        holdingFist = false
        suppressFist = true
        followGeneration += 1
    }
    val density = LocalDensity.current
    val pixelDensity = session?.scene?.virtualPixelDensity
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        current.scene.requestFullSpace()
        // Hand tracking and head tracking are separate. One rejection used to
        // roll the whole config back, which left the hands disabled.
        val handsConfigured = runCatching {
            current.configure(
                Config.Builder(current.config)
                    .setHandTracking(HandTrackingMode.BOTH)
                    .build(),
            )
        }
        val headConfigured = runCatching {
            current.configure(
                Config.Builder(current.config)
                    .setDeviceTracking(DeviceTrackingMode.SPATIAL)
                    .build(),
            )
        }
        Log.i("Floatdesk", "tracking configure hands=$handsConfigured head=$headConfigured")
        var hands = emptyList<Pair<String, Hand>>()
        val sampledHands=java.util.concurrent.atomic.AtomicReference<List<Pair<String,Hand>>>(emptyList())
        val pinchFrames=PinchFrameBuffer()
        fun fingerSample(state: Hand.State): FingerPinch? {
            if(state.trackingState!=TrackingState.TRACKING) return null
            val joints=state.handJoints
            val thumb=joints[HandJointType.THUMB_TIP]?.translation ?: return null
            val index=joints[HandJointType.INDEX_TIP]?.translation ?: return null
            val middle=joints[HandJointType.MIDDLE_TIP]?.translation ?: return null
            return FingerPinch(distance(thumb,index),distance(thumb,middle),
                (joints[HandJointType.PALM]?.translation ?: joints[HandJointType.WRIST]?.translation)?.toArray())
        }
        launch(Dispatchers.Default) {
            val observers=mutableMapOf<String,Pair<Hand,kotlinx.coroutines.Job>>()
            val flowFrames=java.util.concurrent.atomic.AtomicInteger()
            var polledFrames=0
            var captureStatsAt=SystemClock.uptimeMillis()
            val observedAt=mutableMapOf<String,java.util.concurrent.atomic.AtomicLong>()
            while(isActive) {
                val sources=sampledHands.get()
                val active=sources.toMap()
                for(side in observers.keys.toList()) if(active[side] !== observers[side]?.first) {
                    observers.remove(side)?.second?.cancel()
                    observedAt.remove(side)
                    pinchFrames.offerHand(side,null,SystemClock.uptimeMillis())
                }
                for((side,hand) in sources) {
                    if(side !in observers) {
                        val received=java.util.concurrent.atomic.AtomicLong(0)
                        observedAt[side]=received
                        // Copy only joint scalars here; never mutate scene/UI on the publisher thread.
                        val job=launch(Dispatchers.Unconfined) {
                            hand.state.collect { state ->
                                if(sampledHands.get().any { it.first==side && it.second===hand }) {
                                    val now=SystemClock.uptimeMillis()
                                    received.set(now)
                                    flowFrames.incrementAndGet()
                                    pinchFrames.offerHand(side,fingerSample(state),now)
                                }
                            }
                        }
                        observers[side]=hand to job
                    }
                    val received=observedAt.getValue(side)
                    val before=received.get()
                    if(SystemClock.uptimeMillis()-before>30) {
                        // Some SDK runtimes mutate their joint buffer without a flow emission.
                        val state=hand.state.value
                        val sample=fingerSample(state)
                        if(received.get()==before && state===hand.state.value) {
                            polledFrames++
                            pinchFrames.offerHand(side,sample,SystemClock.uptimeMillis())
                        }
                    }
                }
                val now=SystemClock.uptimeMillis()
                if(now-captureStatsAt>=5000) {
                    if(sources.isNotEmpty()) Log.i("Floatdesk","pinch capture sdkFrames=${flowFrames.getAndSet(0)} fallbackFrames=$polledFrames windowMs=${now-captureStatsAt}")
                    else flowFrames.set(0)
                    polledFrames=0;captureStatsAt=now
                }
                delay(if(sources.isEmpty()) 100 else 5)
            }
        }
        // This headset is stereo, so the mono viewpoint is not created. The left
        // eye, then the device pose, is the head.
        var viewpoint = openViewpoint(current)
        val rightViewpoint = runCatching { RenderViewpoint.right(current) }.getOrNull()
        var device = runCatching { ArDevice.getInstance(current) }.getOrNull()
        Log.i("Floatdesk", "head source view=${viewpoint != null} device=${device != null}")
        val trackingGuard = TrackingGuard()
        val trackingRecovery = TrackingRecovery()
        val handQuality=HandTrackingQuality()
        var lastHandRejection=emptySet<String>()
        var lastHandOverlap=false
        val eyeLifecycle=EyeTrackingLifecycle()
        var gazeEyes = emptyList<Eye>()
        var eyeRequestAt=SystemClock.uptimeMillis()
        var eyeNoticeShown = false
        var lastPointerMode: PointerMode? = null
        var lastMouseDragging = false
        val lastMouseContact=mutableMapOf<String,Int>()
        var inputWasResumed=true
        var fistHeld = 0
        var panelHand: String? = null
        var lastPalmSeen = 0L
        var followLock: FollowLock? = null
        var seenGeneration = followGeneration
        var tick = 0
        while (isActive) {
            delay(10)
            tick += 1
            val wantEyes = context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") == android.content.pm.PackageManager.PERMISSION_GRANTED
            val resumed = current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val eyeAction=eyeLifecycle.update(SystemClock.uptimeMillis(),resumed,wantEyes,
                current.config.eyeTracking==EyeTrackingMode.FINE_TRACKING,gazeEyes.size==2,
                wantEyes && resumed && gazeEyes.any { it.state.value.trackingState==TrackingState.TRACKING })
            if(eyeAction.clearResources) {
                gazeEyes=emptyList();eyeNoticeShown=false;eyeRequestAt=SystemClock.uptimeMillis()
            }
            eyeAction.configureFine?.let { fine ->
                val result=runCatching { current.configure(Config.Builder(current.config)
                    .setEyeTracking(if(fine) EyeTrackingMode.FINE_TRACKING else EyeTrackingMode.DISABLED).build()) }
                Log.i("Floatdesk","eye tracking configured enabled=$fine result=$result")
            }
            if(eyeAction.refreshResources) {
                gazeEyes=listOfNotNull(runCatching { Eye.left(current) }.getOrNull(),runCatching { Eye.right(current) }.getOrNull())
                Log.i("Floatdesk","eye resources refreshed count=${gazeEyes.size}")
            }
            if(!resumed) {
                sampledHands.set(emptyList())
                if(inputWasResumed) {
                    pinchFrames.drain(SystemClock.uptimeMillis())
                    if(handInputAllowed) palmInteractionEpoch++
                    handInputAllowed=false
                    val release=mouseGestures.update(SystemClock.uptimeMillis(),false,true,emptyMap(),null)
                    release.target?.let { connection.movePointer(it.monitor,it.u,it.v,false) }
                    release.scroll?.let { connection.touchpad(it.fingers,it.x,it.y) }
                    grabs.values.forEach { it.cancel() }
                    pointerControl.clear();palmPanel=null;holdingFist=false;followLock=null
                    deskSpace.get()?.let { parent ->
                        guideRenderer?.update(parent,emptyList())
                        guideRenderer?.updateRays(parent,emptyMap(),null,1f,true)
                    }
                }
                inputWasResumed=false
                delay(100);continue
            }
            inputWasResumed=true
            if(wantEyes && resumed && gazeEyes.isEmpty() && SystemClock.uptimeMillis()-eyeRequestAt>=1500 && !eyeNoticeShown) {
                eyeNoticeShown=true
                android.widget.Toast.makeText(context,"Eye tracking is unavailable. Hand pointing remains available.",android.widget.Toast.LENGTH_LONG).show()
            }
            if (followGeneration != seenGeneration) {
                followLock = null
                seenGeneration = followGeneration
            }
            if (hands.size < 2 && tick % 50 == 1) {
                hands = listOfNotNull(
                    runCatching { "LEFT" to Hand.left(current) }.getOrNull(),
                    runCatching { "RIGHT" to Hand.right(current) }.getOrNull(),
                )
            }
            if(tick%25==1) {
                if(viewpoint==null) viewpoint=openViewpoint(current)
                if(device==null) device=runCatching { ArDevice.getInstance(current) }.getOrNull()
            }
            val deviceState = device?.state?.value
            // Joints and the raw viewpoint live in perception space. The desk is
            // stored in the Compose root, so follow must convert into that same space.
            val trackingAvailable = current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (deviceState == null || deviceState.trackingState == TrackingState.TRACKING)
            val perceived = viewpoint?.state?.value?.pose ?: deviceState?.devicePose?.takeIf { trackingAvailable }
            // SpatialExternalSurface is under Compose's recommended root, whose scale/pose
            // are independent of ActivitySpace. Always target the actual surface parent.
            val root = findDeskSpace(current) ?: deskSpace.get()
            if (root == null) { palmPanel = null; grips.clear(); pointerControl.clear(); continue }
            if (root !== deskSpace.get()) {
                deskSpace.set(root)
                Log.i("Floatdesk", "desk root pose=${root.getPose(Space.ACTIVITY)} scale=${root.getScale(Space.ACTIVITY)}")
            }
            val unitsPerMeter = current.scene.perceptionSpace.transformVectorTo(Vector3.Right, root).length
            if (!unitsPerMeter.isFinite() || unitsPerMeter <= 0f) continue
            handTracking.unitsPerMeter = unitsPerMeter
            handTracking.nowMs = SystemClock.uptimeMillis()
            handTracking.pinched.clear()
            val candidateHead = perceived?.let { deskPose(current, root, it) }
            displayHead.set(candidateHead?.toWorkspacePose())
            if (!trackingAvailable) pointerControl.clear()
            val reliable = trackingGuard.update(handTracking.nowMs, candidateHead?.takeIf { trackingAvailable }?.translation?.toArray(),
                candidateHead?.let { floatArrayOf(it.rotation.x,it.rotation.y,it.rotation.z,it.rotation.w) },unitsPerMeter)
            val head = candidateHead?.takeIf { reliable }
            if(head!=null) {
                val reference=head.toWorkspacePose()
                val recovery=trackingRecovery.update(handTracking.nowMs,true,reference.position,reference.rotation,unitsPerMeter)
                if(recovery!=null && !restoreViewerPending && workspace.anchorUuid==null) {
                    workspace=workspace.copy(viewerReference=Pose(recovery.first,recovery.second)).restoreViewer(reference)
                    grabs.values.forEach { it.cancel() };pointerControl.clear()
                    manipulating=false;suppressFist=true;followLock=null
                    Log.w("Floatdesk","Recovered workspace after tracking discontinuity")
                }
                if(restoreViewerPending && !workspace.layouts[workspace.activeLayout].isNullOrEmpty()) {
                    workspace=workspace.restoreViewer(reference)
                    restoreViewerPending=false
                }
                store.viewerReference=reference
            }
            if (!reliable) {
                trackingRecovery.update(handTracking.nowMs,false,null,null,unitsPerMeter)
                // Head quality controls follow, not availability of independently tracked hands.
                followLock = null
            }
            var anyFist = false
            var anyOpen = false
            var palmShown = false
            val palmCandidates = mutableMapOf<String, PalmPanelState>()
            val aimPoses=mutableMapOf<String,HandAimPose>()
            val fingerTips = mutableMapOf<String, FloatArray>()
            val mouseSamples = mutableMapOf<String,FingerPinch>()
            grips.clear()
            head?.let {
                grips["HEAD"] = it.translation.toArray()
                val q=it.rotation
                grips["VIEW_DIRECTION"] = floatArrayOf(-2f*(q.x*q.z+q.w*q.y),2f*(q.w*q.x-q.y*q.z),-1f+2f*(q.x*q.x+q.y*q.y))
            }
            // A sphere can be equidistant from one point: use the midpoint between the eyes.
            val rightEye = rightViewpoint?.state?.value?.pose
            val viewer = if (perceived != null && rightEye != null) {
                androidx.xr.runtime.math.Pose((perceived.translation + rightEye.translation) * 0.5f)
            } else perceived
            viewer?.takeIf { reliable }?.let { grips["VIEWER"] = deskPose(current, root, it).translation.toArray() }
            sampledHands.set(hands)
            val handStates=hands.map { (side,hand)->val state=hand.state.value;Triple(side,state,state.handJoints.toMap()) }
            val handSamples=handStates.mapNotNull { (side,state,joints) ->
                if(state.trackingState!=TrackingState.TRACKING) null else {
                    val wrist=joints[HandJointType.WRIST];val palm=joints[HandJointType.PALM]
                    val thumb=joints[HandJointType.THUMB_TIP];val index=joints[HandJointType.INDEX_TIP];val middle=joints[HandJointType.MIDDLE_TIP]
                    if(wrist==null || palm==null || thumb==null || index==null || middle==null) null else {
                        val q=wrist.rotation
                        side to HandTrackingSample(wrist.translation.toArray(),palm.translation.toArray(),thumb.translation.toArray(),
                            index.translation.toArray(),middle.translation.toArray(),floatArrayOf(q.x,q.y,q.z,q.w))
                    }
                }
            }.toMap()
            val quality=handQuality.update(handTracking.nowMs,handSamples)
            val allowed=quality.rejected.isEmpty() && handSamples.isNotEmpty()
            if(handInputAllowed && !allowed) palmInteractionEpoch++
            handInputAllowed=allowed
            for(side in quality.rejected) pointerControl.rejectHand(side)
            if(quality.rejected!=lastHandRejection || quality.overlapping!=lastHandOverlap) {
                Log.i("Floatdesk","hand quality rejected=${quality.rejected} overlap=${quality.overlapping} owner=${quality.continuousOwner} reasons=${quality.reasons}")
                lastHandRejection=quality.rejected;lastHandOverlap=quality.overlapping
            }
            var tracking = 0
            var rawTracking = 0
            for ((side,state,joints) in handStates) {
                if(state.trackingState==TrackingState.TRACKING) rawTracking++
                if(side in quality.rejected || side !in handSamples) continue
                if (state.trackingState == TrackingState.TRACKING) {
                    joints[HandJointType.WRIST]?.let { pose ->
                        val q=pose.rotation
                        aimPoses[side]=HandAimPose(pose.translation.toArray(),floatArrayOf(q.x,q.y,q.z,q.w))
                    }
                    val thumb = joints[HandJointType.THUMB_TIP]?.translation
                    val index = joints[HandJointType.INDEX_TIP]?.translation
                    val middle = joints[HandJointType.MIDDLE_TIP]?.translation
                    if(thumb != null && index != null && middle != null) {
                        val q=perceived?.rotation
                        val quaternion=if(q==null) floatArrayOf(0f,0f,0f,1f) else floatArrayOf(q.x,q.y,q.z,q.w)
                        mouseSamples[side]=FingerPinch(distance(thumb,index),distance(thumb,middle),
                            (joints[HandJointType.PALM]?.translation ?: joints[HandJointType.WRIST]?.translation)?.toArray(),rotateVector(quaternion,1f,0f,0f),rotateVector(quaternion,0f,1f,0f))
                    }
                    if (thumb != null && index != null) {
                        if (distance(thumb, index) < 0.04f) handTracking.pinched.add(side)
                        grips[side] = deskPose(current, root, androidx.xr.runtime.math.Pose((thumb + index) * 0.5f)).translation.toArray()
                        fingerTips[side] = deskPose(current, root, androidx.xr.runtime.math.Pose(index)).translation.toArray()
                    }
                    palmControls(state, perceived, side, tick % 100 == 0, panelHand==side,joints)?.let { palmCandidates[side] = it.copy(pose = deskPose(current, root, it.pose.toRuntimePose()).toWorkspacePose(), unitsPerMeter = unitsPerMeter) }
                }
                val read = readHand(state,joints)
                if(read == null) { mouseSamples.remove(side); continue }
                if(read.fist) mouseSamples.remove(side)
                tracking += 1
                if (read.fist) anyFist = true
                if (read.open) anyOpen = true
                if (palmCandidates.isNotEmpty()) palmShown = true
            }
            if (tick % 100 == 0) {
                val note = when {
                    hands.isEmpty() -> "This app is not receiving hand joints from the system."
                    rawTracking == 0 -> "Hand joints are paused, even though the system hands are visible."
                    head == null -> "Head pose is not updating, so follow cannot move the desk."
                    else -> ""
                }
                if (note.isNotEmpty()) {
                    gestureNote = note
                } else if (
                    gestureNote.startsWith("Hand") ||
                    gestureNote.startsWith("This") ||
                    gestureNote.startsWith("Waiting") ||
                    gestureNote.startsWith("Head")
                ) {
                    gestureNote = ""
                }
                Log.i(
                    "Floatdesk",
                    "hands=${hands.size} raw=$rawTracking read=$tracking fist=$anyFist palm=$palmShown head=${head?.translation}",
                )
            }
            if (anyOpen && !anyFist) suppressFist = false
            if (anyFist && !suppressFist) {
                fistHeld += 1
                holdingFist = fistHeld >= 16
            } else {
                fistHeld = 0
                holdingFist = false
            }
            if(quality.overlapping) palmCandidates.clear()
            val candidate = palmCandidates[panelHand] ?: palmCandidates.values.firstOrNull()
            if (candidate == null) {
                val hostTracked = hands.any { (side, hand) ->
                    side == panelHand && hand.state.value.trackingState == TrackingState.TRACKING
                }
                // Brief joint dropouts should not repeatedly destroy/recreate the Android panel.
                // A tracked hand turning away still hides it immediately. Never poke a stale pose.
                if (hostTracked || SystemClock.uptimeMillis() - lastPalmSeen > 120) {
                    panelHand = null
                    palmPanel = null
                }
                poke.clear()
            } else {
                if (panelHand != candidate.side) poke.clear()
                if (palmPanel == null) Log.i("Floatdesk", "palm panel shown side=${candidate.side} unitsPerMeter=${candidate.unitsPerMeter} pose=${candidate.pose.toRuntimePose()}")
                panelHand = candidate.side
                lastPalmSeen = SystemClock.uptimeMillis()
                palmPanel = candidate
                val other = if (candidate.side == "LEFT") "RIGHT" else "LEFT"
                poke.update(candidate, fingerTips[other])?.let { palmActionNow.value(it) }
                // A platform click may arrive on release after our direct-contact press.
                if (poke.latchedContact) lastPalmAction = SystemClock.uptimeMillis()
            }
            if(palmPanel!=null && !panelEyePermissionAsked && context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                panelEyePermissionAsked=true
                eyePermission.launch("android.permission.EYE_TRACKING_FINE")
            }
            for ((index, grab) in grabs) {
                val wasBusy = grab.busy
                val file = workspace
                val placement = file.monitorAt(index) ?: continue
                grab.advance(handTracking, placement)?.let { workspace = file.monitorPlaced(index, it, store, false) }
                if (wasBusy && !grab.busy) {
                    updateManipulation(false)
                    store.save(workspace)
                }
            }
            val hoverHands = pointerControl.hands(grips.keys,handTracking.nowMs,aimPoses).filterValues {connection.desktopPoint(it.monitor,it.u,it.v)!=null}
            val eyeRays = if (trackingAvailable && deviceState != null) gazeEyes.mapNotNull { eye ->
                val state=eye.state.value
                if(!state.isOpen || state.trackingState!=TrackingState.TRACKING) null else {
                    val gaze=deskPose(current,root,deviceState.devicePose.compose(state.pose))
                    val q=gaze.rotation
                    gaze.translation.toArray() to rotateVector(floatArrayOf(q.x,q.y,q.z,q.w),0f,0f,-1f)
                }
            } else emptyList()
            val panel=palmPanel
            val panelHits=if(panel==null) emptyList() else eyeRays.mapNotNull { (origin,direction) -> palmRayPoint(panel,origin,direction) }
            val panelGaze=if(panelHits.isEmpty()) null else floatArrayOf(panelHits.map {it[0]}.average().toFloat(),panelHits.map {it[1]}.average().toFloat())
            val activate=panelInput.update(handTracking.nowMs,panel?.side,panelGaze,panel?.let { mouseSamples[it.side]?.middleMetres })
            panelFocused=panelInput.focused?.let { PalmAction.entries.getOrNull(it) }
            if(activate!=null) PalmAction.entries.getOrNull(activate)?.let { palmActionNow.value(it) }
            if(panel!=null && tick%50==0) Log.i("Floatdesk","panel gaze resources=${gazeEyes.size} tracked=${gazeEyes.count { it.state.value.trackingState==TrackingState.TRACKING }} open=${gazeEyes.count { it.state.value.isOpen }} device=${deviceState?.trackingState} rays=${eyeRays.size} hits=${panelHits.size}")
            val gazeTargets=if(workspace.eyeAiming && reliable) eyeRays.mapNotNull { (origin,direction) -> gazePointerTarget(origin,direction,workspace,pointerControl.meshes) } else emptyList()
            // Average binocular contacts on the same monitor; a valid single eye can still aim.
            val rawGazeTarget = gazeTargets.firstOrNull()?.let { first ->
                val same = gazeTargets.filter { it.monitor == first.monitor }
                first.copy(u=same.map { it.u }.average().toFloat(),v=same.map { it.v }.average().toFloat())
            }
            val gazeTarget=if(hoverHands.isEmpty()) gazeStabilizer.update(rawGazeTarget?.takeIf {connection.desktopPoint(it.monitor,it.u,it.v)!=null},handTracking.nowMs) else { gazeStabilizer.reset(); null }
            val monitorGrab=grabs.values.any { it.engaged }
            val nativeManipulation=manipulating && grabs.values.none { it.busy }
            val choice = pointerControl.choose(workspace.eyeAiming,gazeTarget,hoverHands,monitorGrab || nativeManipulation,handTracking.nowMs)
            if (choice.mode != lastPointerMode) {
                Log.i("Floatdesk","mouse source=${choice.mode} hands=${hoverHands.size} eyes=${gazeTargets.size}")
                lastPointerMode = choice.mode
            }
            dimPointer = choice.mode == PointerMode.HAND
            val gestureBlocked=displayControlsOpen || choice.mode==PointerMode.PREVIEW || choice.mode==PointerMode.GRAB || palmPanel!=null
            for(frame in pinchFrames.drain(handTracking.nowMs)) {
                val samples=frame.samples.filterKeys { it in mouseSamples }.mapValues { (side,sample) ->
                    val basis=mouseSamples.getValue(side)
                    sample.copy(right=basis.right,up=basis.up)
                }
                if(workspace.mouseGestures) for((side,sample) in frame.samples) {
                    val contact=(if(sample.indexMetres<=mouseIndexContactMetres(side)) 1 else 0) or (if(sample.middleMetres<=mouseMiddleContactMetres(side)) 2 else 0)
                    if(lastMouseContact.put(side,contact)!=contact) Log.i("Floatdesk",
                        "mouse contact side=$side fingers=$contact indexMm=${sample.indexMetres*1000} middleMm=${sample.middleMetres*1000} accepted=${side in samples} blocked=$gestureBlocked sampleAge=${handTracking.nowMs-frame.at}")
                }
                val mouse=mouseGestures.update(frame.at,workspace.mouseGestures && trackingAvailable,
                    gestureBlocked || (quality.overlapping && quality.continuousOwner==null),samples,choice.target)
                mouse.scroll?.let {
                    if(it.fingers==0) Log.i("Floatdesk","mouse scroll released at=${frame.at}")
                    connection.touchpad(it.fingers,it.x,it.y)
                }
                if(mouse.dragging != lastMouseDragging) {
                    Log.i("Floatdesk","mouse drag=${mouse.dragging}")
                    lastMouseDragging=mouse.dragging
                }
                mouse.target?.let {target->connection.desktopPoint(target.monitor,target.u,target.v)?.let {(u,v)->connection.movePointer(target.monitor,u,v,mouse.dragging)}}
                mouse.click?.let {
                    if(it.button==3) clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    Log.i("Floatdesk","mouse click button=${it.button}")
                    connection.desktopPoint(it.target.monitor,it.target.u,it.target.v)?.let {(u,v)->connection.clickPointer(it.target.monitor,u,v,it.button)}
                }
            }
            handTracking.pointerHeld=mouseGestures.engaged
            for((index,placement) in workspace.layouts[workspace.activeLayout].orEmpty().withIndex()) {
                val marks=mutableListOf<Float>()
                connection.aimOverlay(index,dev.floatdesk.client.media.VideoAim(floatArrayOf(),marks.toFloatArray(),
                    .0009f*handTracking.unitsPerMeter/placement.physicalWidthM*placement.pixelWidth,if(dimPointer) .18f else .4f))
            }
            val previews = if (choice.mode == PointerMode.PREVIEW) hoverHands.values.groupBy { it.monitor }.mapNotNull { (index,points) ->
                val left=hoverHands["LEFT"]?.takeIf { it.monitor == index }
                val right=hoverHands["RIGHT"]?.takeIf { it.monitor == index }
                val placement=workspace.monitorAt(index)
                if(points.size != 2 || left == null || right == null || placement == null) null else
                    previewGrabGuide(placement,left.local,right.local,handTracking,pointerControl.meshes[index])?.let { index to it }
            }.toMap() else emptyMap()
            guideRenderer?.let { renderer ->
                runCatching { renderer.update(root, grabs.mapNotNull { (index,grab) -> grab.guide ?: previews[index] })
                    // The desktop cursor is the target marker; avoid a second dot over it.
                    renderer.updatePointer(root,null,handTracking.points["HEAD"],handTracking.unitsPerMeter)
                    val draw: ()->Unit = {
                        val contactsNow=pointerControl.hands(grips.keys,SystemClock.uptimeMillis(),aimPoses).filterValues {connection.desktopPoint(it.monitor,it.u,it.v)!=null}
                        val show=contactsNow.size==1 && palmPanel==null && !monitorGrab && !nativeManipulation
                        val rays=(if(show) contactsNow else emptyMap()).mapNotNull { (side,hit) ->
                            val origin=grips[side]; val placement=workspace.monitorAt(hit.monitor)
                            if(origin==null || placement==null) null else {
                                val local=pointerSurfacePoint(hit,placement.physicalWidthM,placement.physicalHeightM,pointerControl.meshes[hit.monitor])
                                val contact=transformPoint(placement.pose,local)
                                side to (origin to visibleGrabContact(contact,handTracking.points["VIEWER"] ?: handTracking.points["HEAD"],handTracking.unitsPerMeter))
                            }
                        }.toMap()
                        renderer.updateRays(root,rays,handTracking.points["VIEWER"] ?: handTracking.points["HEAD"],handTracking.unitsPerMeter,true)
                    }
                    redrawAim.set(draw)
                     }.onFailure {
                    Log.e("Floatdesk", "Could not update grab guides", it)
                    guideRenderer = null
                    renderer.close()
                }
            }
            val wantFollow = !manipulating && head != null && (latchedFollow || (holdingFist && !suppressFist))
            if (!wantFollow) {
                if (followLock != null) {
                    store.save(workspace)
                    followLock = null
                }
            } else if (followLock == null) {
                followLock = FollowLock.capture(head, workspace)
                gestureNote = if (latchedFollow) {
                    "Following, latched with the button. Use Unlock follow to stop."
                } else {
                    "Following while the fist stays closed."
                }
            } else {
                workspace = followLock.advance(head, workspace)
            }
        }
    }
    LaunchedEffect(monitors) {
        if(monitors.isNotEmpty()) {
            grabs.values.forEach { it.cancel() };pointerControl.clear()
            val next=workspace.reconcileMonitors(monitors.map { Triple(it.name,it.width,it.height) })
            if(next!=workspace) {workspace=next;store.save(next)}
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
            onOpenWorkspace = { session?.scene?.requestFullSpace();toggleDisplays() },
            onReset = {
                workspace = workspace.reset(monitors, store)
                followGeneration += 1
            },
            headLocked = following,
            latchedFollow = latchedFollow,
            gestureNote = gestureNote,
            onToggleHead = {
                latchedFollow = !latchedFollow
                suppressFist = false
                followGeneration += 1
            },
        )
    }

    if (session != null && pixelDensity != null) {
        val actionEpoch=palmInteractionEpoch
        val desk: @Composable () -> Unit = {
            DeskContent(
                connection = connection,
                workspace = workspace,
                onWorkspace = {
                    workspace = it
                    followGeneration += 1
                },
                monitors = monitors,
                store = store,
                density = density,
                pixelDensity = pixelDensity,
                palmPanel = palmPanel,
                latchedFollow = latchedFollow,
                onPalmAction = { action-> if(actionEpoch==palmInteractionEpoch) palmAction(action) },
                handInputAllowed = handInputAllowed,
                displayControlsOpen=displayControlsOpen,
                displayControlsPose=displayControlsPose,
                onCloseDisplays={displayControlsOpen=false},
                grabs = grabs,
                handTracking = handTracking,
                pointerControl = pointerControl,
                dimPointer = dimPointer,
                panelFocused = panelFocused,
                panelPressed = panelPressed,
                onManipulate = { updateManipulation(it) },
            )
        }
        Subspace { desk() }
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
    palmPanel: PalmPanelState?,
    latchedFollow: Boolean,
    onPalmAction: (PalmAction) -> Unit,
    handInputAllowed: Boolean,
    displayControlsOpen:Boolean,
    displayControlsPose:Pose,
    onCloseDisplays:()->Unit,
    grabs: MutableMap<Int, TwoHandState>,
    handTracking: GrabTracking,
    pointerControl: PointerControl,
    dimPointer: Boolean,
    panelFocused: PalmAction?,
    panelPressed: PalmAction?,
    onManipulate: (Boolean) -> Unit,
) {
    val session = LocalSession.current
    val latest = rememberUpdatedState(workspace)
    val handInputNow=rememberUpdatedState(handInputAllowed)
    val reloading by connection.reloading
    val layout = workspace.reconcileMonitors(monitors.map { Triple(it.name,it.width,it.height) }).layouts[workspace.activeLayout].orEmpty()
    if(displayControlsOpen) SpatialPanel(modifier=placedPanel(displayControlsPose,.95f,.70f,density,pixelDensity)) {
        MonitorManagerPanel(connection,workspace.screenSpacing,{onWorkspace(workspace.copy(screenSpacing=it))},{store.save(latest.value)},onCloseDisplays)
    }
    if(reloading && layout.isNotEmpty()) {
        val screen=layout.first()
        val pose=Pose(transformPoint(screen.pose,floatArrayOf(0f,0f,.05f)),screen.pose.rotation)
        SpatialPanel(modifier=placedPanel(pose,.40f,.10f,density,pixelDensity)) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().background(Color(0xEE1C2430)).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                Text("Updating screens…",color=Color.White)
            }
        }
    }
    palmPanel?.takeIf { handInputAllowed }?.let { panel ->
        SpatialPanel(modifier = placedPanel(panel.pose, PALM_WIDTH * panel.unitsPerMeter, PALM_HEIGHT * panel.unitsPerMeter, density, pixelDensity)) {
            PalmControls(latchedFollow, workspace.sphericalWarp, workspace.eyeAiming, workspace.mouseGestures, panelFocused, panelPressed, onPalmAction)
        }
    }
    layout.forEachIndexed { index, placement ->
        androidx.compose.runtime.key(placement.hostSourceId,placement.pixelWidth,placement.pixelHeight) {
        val hands = remember(index) { grabs.getOrPut(index) { TwoHandState() } }
        var nativeResize by remember(index) { mutableStateOf(false) }
        var nativeMove by remember(index) { mutableStateOf(false) }
        var moveBefore by remember(index) { mutableStateOf<Pose?>(null) }
        var nativeGeometryPending by remember(index) { mutableStateOf(false) }
        var nativeResizeStartWidth by remember(index) { mutableStateOf(1f) }
        var nativeResizeStartHeight by remember(index) { mutableStateOf(1f) }
        val videoSurface = remember(index) { AtomicReference<Surface?>(null) }
        val warp = remember(index, session) { MonitorWarp() }
        val systemControls=remember(index,session) { MonitorSystemControls() }
        DisposableEffect(systemControls) { onDispose { systemControls.close() } }
        LaunchedEffect(index, session) {
            val current = session ?: return@LaunchedEffect
            while (isActive) {
                delay(20)
                if(!current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { delay(100);continue }
                if (videoSurface.get() == null) continue
                val root = findDeskSpace(current) ?: continue
                val file = latest.value
                val screen = file.monitorAt(index) ?: continue
                try {
                    if(!handInputNow.value && (nativeMove || nativeResize)) {
                        systemControls.close();nativeMove=false;nativeResize=false;moveBefore=null
                        nativeGeometryPending=true;onManipulate(false)
                    }
                    // Freeze before the platform DOWN arrives: replacing a native canvas while
                    // the user starts pinching can cancel its input acquisition.
                    connection.presentation().let {warp.presentation(it.rects,it.spacing)}
                    warp.update(current, root, handTracking.points["VIEWER"] ?: handTracking.points["HEAD"], screen, file.sphericalWarp,
                        hands.busy || nativeMove || nativeResize || handTracking.pointerHeld,forceUpdate=!hands.busy && (nativeGeometryPending || !warp.matchesSize(screen)),viewDirection=handTracking.points["VIEW_DIRECTION"],freezeProjection=hands.busy || nativeMove || nativeResize || handTracking.pointerHeld)
                    warp.syncCurvedInput(current)
                    // Collider creation suspends: a newer resize event may arrive while awaiting it.
                    // Clear the pending update only after matching the latest canonical size.
                    latest.value.monitorAt(index)?.let { if(warp.matchesSize(it)) nativeGeometryPending=false }
                    val viewer=(handTracking.points["VIEWER"] ?: handTracking.points["HEAD"])?.let { viewerInMonitor(it,screen.pose.position,screen.pose.rotation) }
                    systemControls.update(current,warp.displayEntity,warp.mesh,screen.physicalWidthM,screen.physicalHeightM,viewer,
                        handTracking.unitsPerMeter,!hands.engaged && handInputNow.value,onResize={ event,factor ->
                        if(!handInputNow.value || hands.engaged) { nativeResize=false; return@update }
                        val existing=latest.value.monitorAt(index) ?: return@update
                        if(event.resizeState==androidx.xr.scenecore.ResizeEvent.ResizeState.START) {
                            if(hands.busy) hands.cancel()
                            nativeResizeStartWidth=existing.physicalWidthM
                            nativeResizeStartHeight=existing.physicalHeightM
                            nativeResize=true; onManipulate(true)
                        }
                        if(!nativeResize) return@update
                        nativeGeometryPending=true
                        val end=event.resizeState==androidx.xr.scenecore.ResizeEvent.ResizeState.END
                        val size=resizeDimensions(nativeResizeStartWidth,nativeResizeStartHeight,factor)
                        // END must always release ownership, even if the SDK's final size is invalid.
                        if(end) {nativeResize=false;onManipulate(false)}
                        if(size!=null || end) {
                            val next=size?.let {existing.copy(physicalWidthM=it.first,physicalHeightM=it.second)} ?: existing
                            onWorkspace(latest.value.monitorPlaced(index,next,store,end))
                        }
                    },onMove={ phase,pose ->
                        if(!handInputNow.value || hands.engaged) { nativeMove=false; return@update }
                        val sample=pose.toWorkspacePose()
                        val before=moveBefore
                        moveBefore=sample
                        if(phase==SystemMovePhase.START) { if(hands.busy) hands.cancel();nativeMove=true;onManipulate(true);return@update }
                        if(!nativeMove) return@update
                        val existing=latest.value.monitorAt(index) ?: return@update
                        val safe=before!=null && distance(before.position,sample.position)<=.25f && kotlin.math.abs(dot4(before.rotation,sample.rotation))>=.9f
                        val next=if(safe) existing.copy(pose=nativeRelativePose(before!!,sample,existing.pose)) else existing
                        val end=phase==SystemMovePhase.END
                        onWorkspace(latest.value.monitorPlaced(index,next,store,end))
                        if(end) { nativeMove=false;moveBefore=null;nativeGeometryPending=true;onManipulate(false) }
                    })
                    pointerControl.meshes[index] = warp.mesh
                    connection.projection(index,null) // Geometry projection uses the original streamed pixels.
                    warp.outputSurface?.let { display ->
                        if (videoSurface.get() !== display) {
                            videoSurface.set(display)
                            connection.attachSurface(index, display)
                        }
                    }
                } catch (failure: Exception) {
                    Log.e("Floatdesk", "Could not update spherical monitor", failure)
                    return@LaunchedEffect
                }
            }
        }
        var frozenMonitor by remember(index) { mutableStateOf<FrozenMonitor?>(null) }
        LaunchedEffect(hands.engaged, session) {
            val current = session ?: return@LaunchedEffect
            if (!hands.engaged) return@LaunchedEffect
            val surface = videoSurface.get() ?: return@LaunchedEffect
            var frozen: FrozenMonitor? = null
            try {
                frozen = FrozenMonitor.create(current, surface, placement.pixelWidth, placement.pixelHeight, hands.frozenMesh)
                val latestPlacement = latest.value.monitorAt(index) ?: return@LaunchedEffect
                frozen.update(latestPlacement)
                frozenMonitor = frozen
                Log.i("Floatdesk", "frozen native monitor active index=$index")
                awaitCancellation()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e("Floatdesk", "Could not freeze monitor for manipulation", failure)
            } finally {
                frozenMonitor = null
                frozen?.close()
            }
        }
        frozenMonitor?.let { frozen -> SideEffect { frozen.update(placement) } }

        val inputPolicy = remember(index) {
                object : InteractionPolicy {
                    override val isEnabled: Boolean = true
                    override fun onInputEvent(event: SpatialInputEvent) {
                        if(!handInputNow.value || nativeResize || nativeMove) return // The explicit handle owns this sequence.
                        if (event.action == InputEvent.Action.CANCEL && frozenMonitor != null && hands.engaged) return
                        val file = latest.value
                        val existing = file.monitorAt(index) ?: return
                        val wasBusy = hands.busy
                        if (event.action == InputEvent.Action.DOWN || event.action == InputEvent.Action.UP || event.action == InputEvent.Action.CANCEL) {
                            Log.i("Floatdesk", "monitor input index=$index side=${event.pointerType} action=${event.action} hit=${event.hitPosition} busy=${hands.busy}")
                        }
                        // The native listener supplies a surface-validated local contact from the
                        // raw perception-space hit; do not localize it again through ActivitySpace.
                        val next = hands.onEvent(event, existing, pixelDensity, handTracking, warp.mesh)
                        if (hands.blocksNative) nativeResize = false
                        if (wasBusy != hands.busy) onManipulate(hands.busy)
                        if (next == null) return
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
            }
        SpatialExternalSurface(
            stereoMode = StereoMode.Mono,
            modifier = placedPanel(
                pose = placement.pose,
                widthM = placement.physicalWidthM,
                heightM = placement.physicalHeightM,
                density = density,
                pixelDensity = pixelDensity,
            ).pointerHoverIcon(SpatialPointerIcon.NONE),
        ) {
            onSurfaceCreated { surface ->
                val current = session ?: return@onSurfaceCreated
                val display = warp.attach(current, surface, latest.value.monitorAt(index) ?: placement, pixelDensity, inputPolicy::onInputEvent) { side, point, flat, width, height ->
                    pointerControl.hover(index,side,point,flat,width,height,SystemClock.uptimeMillis())
                }
                videoSurface.set(display)
                connection.attachSurface(index, display)
            }
            onSurfaceDestroyed {
                systemControls.close()
                nativeMove=false;nativeResize=false;moveBefore=null;nativeGeometryPending=false
                onManipulate(false)
                val previousSurface=videoSurface.getAndSet(null)
                connection.detachSurface(index,previousSurface)
                hands.cancel()
                warp.close()
                pointerControl.clearMonitor(index)
            }
        }
        // Debug SpatialPanels intercept the system rays. Keep the video as the only hit target.
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
    // Translation must wrap rotation: T * R, so turning a panel cannot orbit its center.
    return SubspaceModifier
        .requiredWidth(widthM.meters(density, pixelDensity))
        .requiredHeight(heightM.meters(density, pixelDensity))
        .offset3d(
            x = (pose.position[0] + dx).meters(density, pixelDensity),
            y = (pose.position[1] + dy).meters(density, pixelDensity),
            z = (pose.position[2] + dz).meters(density, pixelDensity),
        )
        .rotate(pose.toQuaternion())
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
            .offset3d(
                x = (monitor.pose.position[0] + rotated[0]).meters(density, pixelDensity),
                y = (monitor.pose.position[1] + rotated[1]).meters(density, pixelDensity),
                z = (monitor.pose.position[2] + rotated[2]).meters(density, pixelDensity),
            )
            .rotate(monitor.pose.toQuaternion()),
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
    latchedFollow: Boolean,
    gestureNote: String,
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
                when {
                    headLocked && latchedFollow ->
                        "Follow is latched with the button. Use Unlock follow to stop. Turn an open palm toward your face for controls."
                    headLocked ->
                        "Following your head while that fist stays closed. Open the hand to stop."
                    else ->
                        "The desk stays where you put it. Close a fist and hold it to follow your head. Use the button to latch follow. Turn an open palm toward your face for controls."
                }
            } else {
                "Opening the workspace. If this stays flat, close the app and open it once more."
            },
            color = Color.White,
        )
        if (gestureNote.isNotEmpty()) {
            Text(gestureNote, color = Color.White)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDisconnect) { Text("Search again") }
            Button(onClick = onOpenWorkspace) { Text("Open workspace") }
            Button(onClick = onAnchor) { Text("Save room anchor") }
            Button(onClick = onReset) { Text("Reset desk") }
            Button(onClick = onToggleHead) {
                Text(if (latchedFollow) "Unlock follow" else "Latch follow")
            }
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

private const val PALM_WIDTH = 0.24f
private const val PALM_HEIGHT = 0.24f
private enum class PalmAction { FOLLOW, RESET, SEARCH, SPACE, WARP, EYES, GESTURES }
private data class PalmPanelState(val side: String, val pose: Pose, val unitsPerMeter: Float = 1f)
private data class PalmButton(val action: PalmAction, val x: Float, val y: Float, val w: Float = 0.44f, val h: Float = 0.17f)
private val palmButtons = listOf(
    PalmButton(PalmAction.FOLLOW, 0.04f, 0.16f), PalmButton(PalmAction.RESET, 0.52f, 0.16f),
    PalmButton(PalmAction.SEARCH, 0.04f, 0.37f), PalmButton(PalmAction.SPACE, 0.52f, 0.37f),
    PalmButton(PalmAction.WARP, 0.04f, 0.58f),
    PalmButton(PalmAction.EYES, 0.52f, 0.58f),
    PalmButton(PalmAction.GESTURES, 0.04f, 0.79f, w = 0.92f),
)

private fun sphericalActionLabel(enabled: Boolean): String = if (enabled) "Disable spherical" else "Enable spherical"

@Composable
private fun PalmControls(latched: Boolean, warped: Boolean, eyes: Boolean, gestures: Boolean, focused: PalmAction?, pressed: PalmAction?, onAction: (PalmAction) -> Unit) {
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
                    PalmAction.GESTURES -> if (gestures) "Disable gestures" else "Enable gestures"
                }, style = TextStyle(color = Color.White), maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 4.sp, maxFontSize = 14.sp, stepSize = 0.25.sp))
            }
        }
    }
}

private fun Vector3.toArray() = floatArrayOf(x, y, z)

private fun palmControls(state: Hand.State, head: androidx.xr.runtime.math.Pose?, side: String, diagnostics: Boolean = false, allowMiddlePinch: Boolean = false, joints: Map<HandJointType,androidx.xr.runtime.math.Pose> = state.handJoints): PalmPanelState? {
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

private fun palmRayPoint(panel: PalmPanelState, origin: FloatArray, direction: FloatArray): FloatArray? {
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
private class PalmPoke {
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
private fun findDeskSpace(session: androidx.xr.runtime.Session): Entity? {
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

private fun deskPose(
    session: androidx.xr.runtime.Session,
    root: Entity,
    perceived: androidx.xr.runtime.math.Pose,
): androidx.xr.runtime.math.Pose = session.scene.perceptionSpace.transformPoseTo(perceived, root)

/** Anatomical palm plane, with its sign taken from the tracked palm surface. */
private fun palmNormal(wrist: FloatArray, middle: FloatArray, index: FloatArray,
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

private fun openViewpoint(session: androidx.xr.runtime.Session): RenderViewpoint? {
    return runCatching { RenderViewpoint.left(session) }.getOrNull()
        ?: runCatching { RenderViewpoint.mono(session) }.getOrNull()
        ?: runCatching { RenderViewpoint.right(session) }.getOrNull()
}

/** One coherent joint sample, expressed in the actual Compose root. */
private class GrabTracking {
    val points = mutableMapOf<String, FloatArray>()
    val pinched = mutableSetOf<String>()
    var pointerHeld=false
    var unitsPerMeter = 1f
    var nowMs = 0L
}

/** A virtual monitor held between the hands, with a fixed similarity mapping to the real one. */
private class TwoHandState {
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
        if (listOf("LEFT", "RIGHT").all { tracking.points[it] != null && it !in tracking.pinched }) {
            needsRelease = false; released.clear()
        }
    }

    fun onEvent(event: SpatialInputEvent, current: MonitorPlacement, density: PixelDensity,
                tracking: GrabTracking, mesh: MonitorMesh? = null, planarInput: Boolean = false, selectedHit: FloatArray? = null): MonitorPlacement? {
        val side = event.pointerType.toString()
        // Some runtimes end a two-hand sequence with DEFAULT, not LEFT/RIGHT.
        if (event.action == InputEvent.Action.UP || event.action == InputEvent.Action.CANCEL) {
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
        if (needsRelease || engaged || tracking.points[side] == null) return null
        // Only fresh DOWN hits select pixels. MOVE can never turn a rejected/pending pair into a grab.
        val hit = selectedHit ?: event.hitPosition?.let { eventMeters(it, density) } ?: return null
        val flat = if (mesh != null && !planarInput) mesh.unproject(hit) ?: return null else hit
        val local = if (mesh != null && planarInput) mesh.project(flat) else hit
        if (kotlin.math.abs(flat[0]) > current.physicalWidthM * 0.5f + 0.001f ||
            kotlin.math.abs(flat[1]) > current.physicalHeightM * 0.5f + 0.001f) return null
        if (mesh == null) local[2] = 0f
        if (hands.isEmpty()) { began = tracking.nowMs; lastSeen = tracking.nowMs; frozenMesh = mesh }
        hands[side] = Grip(local)
        if (hands.size != 2) return null
        val left = tracking.points["LEFT"]
        val right = tracking.points["RIGHT"]
        if (left == null || right == null) { cancel(); return null }
        val leftLocal = hands.getValue("LEFT").local
        val rightLocal = hands.getValue("RIGHT").local
        val contactSpan = distance(leftLocal, rightLocal)
        val handSpan = distance(left, right)
        val minContact = maxOf(0.12f * tracking.unitsPerMeter, current.physicalWidthM * 0.18f)
        if (contactSpan < minContact || handSpan < 0.09f * tracking.unitsPerMeter) {
            Log.i("Floatdesk", "grab rejected: contactSpan=$contactSpan minimum=$minContact handSpan=$handSpan")
            cancel(); return null
        }
        startLeft = left.copyOf(); startRight = right.copyOf()
        worldLeft = transformPoint(current.pose, leftLocal)
        worldRight = transformPoint(current.pose, rightLocal)
        // This is exactly the scale/orientation that puts the same two pixels in the hands
        // on a miniature copy. Freeze it for the gesture; do not continually change sensitivity.
        gain = contactSpan / handSpan
        alignment = quaternionBetween(subtract(right, left), subtract(worldRight, worldLeft))
        previousLeft = left.copyOf(); previousRight = right.copyOf()
        base = current; last = current; engaged = true
        began = tracking.nowMs; lastSeen = tracking.nowMs; openSince = null
        publishGuide(current, tracking)
        Log.i("Floatdesk", "virtual monitor locked gain=$gain contactSpan=$contactSpan handSpan=$handSpan")
        return null
    }

    fun advance(tracking: GrabTracking, current: MonitorPlacement): MonitorPlacement? {
        rearm(tracking)
        if (!busy) return null
        if (tracking.nowMs - lastSeen >= 120) { cancel(); return null }
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
private fun placeGrabbed(
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

private fun gazePointerTarget(origin: FloatArray, direction: FloatArray, workspace: WorkspaceFile,
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

private fun previewGrabGuide(placement: MonitorPlacement, left: FloatArray, right: FloatArray,
                             tracking: GrabTracking, mesh: MonitorMesh?): GrabGuide? {
    val handLeft=tracking.points["LEFT"] ?: return null
    val handRight=tracking.points["RIGHT"] ?: return null
    val contactSpan=distance(left,right); val handSpan=distance(handLeft,handRight)
    if(contactSpan<maxOf(.12f*tracking.unitsPerMeter,placement.physicalWidthM*.18f) || handSpan<.09f*tracking.unitsPerMeter) return null
    val worldLeft=transformPoint(placement.pose,left); val worldRight=transformPoint(placement.pose,right)
    val gain=contactSpan/handSpan
    val alignment=quaternionBetween(subtract(handRight,handLeft),subtract(worldRight,worldLeft))
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

/** Apply only the native gesture delta; native Start must not replace a saved monitor pose. */
private fun nativeRelativePose(before: Pose, after: Pose, held: Pose): Pose {
    val inverse = floatArrayOf(-before.rotation[0],-before.rotation[1],-before.rotation[2],before.rotation[3])
    val turn = multiplyQuaternion(after.rotation,inverse)
    val translation = subtract(after.position,before.position)
    return Pose(add(held.position,translation),multiplyQuaternion(turn,held.rotation))
}
private fun dot4(a: FloatArray,b: FloatArray): Float = (0..3).sumOf { (a[it]*b[it]).toDouble() }.toFloat()

private fun eventMeters(point: Vector3, pixelDensity: PixelDensity): FloatArray {
    return floatArrayOf(
        pixelDensity.convertPixelsToMeters(point.x),
        pixelDensity.convertPixelsToMeters(point.y),
        pixelDensity.convertPixelsToMeters(point.z),
    )
}

private fun screenLocal(pose: Pose, world: FloatArray): FloatArray {
    val delta = subtract(world, pose.position)
    val inverse = floatArrayOf(
        -pose.rotation.getOrElse(0) { 0f },
        -pose.rotation.getOrElse(1) { 0f },
        -pose.rotation.getOrElse(2) { 0f },
        pose.rotation.getOrElse(3) { 1f },
    )
    return rotateVector(inverse, delta[0], delta[1], delta[2])
}

private fun transformPoint(pose: Pose, local: FloatArray, scale: Float = 1f): FloatArray {
    val rotated = rotateVector(pose.rotation, local[0] * scale, local[1] * scale, local[2] * scale)
    return floatArrayOf(
        pose.position.getOrElse(0) { 0f } + rotated[0],
        pose.position.getOrElse(1) { 0f } + rotated[1],
        pose.position.getOrElse(2) { 0f } + rotated[2],
    )
}

private class FollowLock(
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

private data class HandRead(
    val fist: Boolean,
    val open: Boolean,
)

private fun openPalm(wrist: FloatArray, tips: List<FloatArray>, span: Float): Boolean =
    span in 0.025f..0.15f && tips.size == 4 && tips.count { distance(it, wrist) > span * 1.35f } >= 3

private fun firmFist(wrist: FloatArray, palm: FloatArray, tips: List<FloatArray>, span: Float): Boolean =
    tips.size == 4 && tips.all { distance(it, wrist) < span * 1.05f && distance(it, palm) < span * 0.70f }

private fun readHand(state: Hand.State,joints: Map<HandJointType,androidx.xr.runtime.math.Pose> = state.handJoints): HandRead? {
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

private fun dot(a: Vector3, b: Vector3): Float = a.x * b.x + a.y * b.y + a.z * b.z

private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun Pose.toRuntimePose(): androidx.xr.runtime.math.Pose {
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

private fun androidx.xr.runtime.math.Pose.toWorkspacePose(): Pose {
    return Pose(
        floatArrayOf(translation.x, translation.y, translation.z),
        floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
    )
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

private fun add(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

private fun midpoint(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

private fun distance(a: FloatArray, b: FloatArray): Float {
    val d = subtract(a, b)
    return kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
}

private fun quaternionBetween(from: FloatArray, to: FloatArray): FloatArray {
    val f = normalize3(from)
    val t = normalize3(to)
    val cosine = dot(f, t).coerceIn(-1f, 1f)
    val cross = floatArrayOf(
        f[1] * t[2] - f[2] * t[1],
        f[2] * t[0] - f[0] * t[2],
        f[0] * t[1] - f[1] * t[0],
    )
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
