package dev.floatdesk.client

import androidx.compose.runtime.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.xr.arcore.Eye
import androidx.xr.runtime.EyeTrackingMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import dev.floatdesk.client.net.DeskConnection
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceStore
import dev.floatdesk.client.workspace.focusDesktopRegion
import dev.floatdesk.client.workspace.ViewLayers
import dev.floatdesk.client.workspace.fixedDesktopDepth
import dev.floatdesk.client.workspace.restoreViewer
import dev.floatdesk.client.workspace.withViewingDistances
import dev.floatdesk.client.workspace.reconcileMonitors
import dev.floatdesk.client.workspace.monitorPlaced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private fun focusOutlines(targets:List<FocusTarget>, info:dev.floatdesk.client.net.WorkspaceInfo, spacing:Float):dev.floatdesk.client.media.VideoAim {
    val segments=ArrayList<Float>()
    val colors=ArrayList<Float>()
    for(target in targets)for(rect in info.visibleRects(target.rect,spacing)){
        val rgb=if(target.windowId==null)listOf(.4236f,.9777f,1f) else listOf(.757f,.502f,1f)
        segments.addAll(listOf(rect.left,rect.top,rect.right,rect.top,rect.right,rect.top,rect.right,rect.bottom,
            rect.right,rect.bottom,rect.left,rect.bottom,rect.left,rect.bottom,rect.left,rect.top))
        repeat(4){colors.addAll(rgb)}
    }
    return dev.floatdesk.client.media.VideoAim(segments.toFloatArray(),colors=colors.toFloatArray())
}

@Composable
internal fun FloatdeskApp(store: WorkspaceStore, dumpFile: File?,onSession:(androidx.xr.runtime.Session?)->Unit) {
    val connection = remember { DeskConnection(dumpFile) }
    val deskSpace = remember { AtomicReference<Entity?>(null) }
    var deskRootVersion by remember{mutableIntStateOf(0)}
    val context = LocalContext.current
    val deviceInteractive by rememberDeviceInteractive(context)
    val activityResumed by rememberActivityResumed(context)
    val interactiveNow=rememberUpdatedState(deviceInteractive)
    LaunchedEffect(connection) { connection.start(context) }
    DisposableEffect(connection) { onDispose { connection.disconnect() } }
    val session = LocalSession.current
    var sceneEpoch by remember{mutableIntStateOf(0)}
    var activatedScene by remember{mutableStateOf(false)}
    var sceneActive by remember { mutableStateOf(false) }
    var workspace by remember { mutableStateOf(store.load()) }
    val workspaceAtLifecycle=rememberUpdatedState(workspace)
    var restoreViewerPending by remember {mutableStateOf(true)}
    val depthPushes=remember {IntArray(1)}
    DisposableEffect(session,connection) {
        val current = session
        onSession(current)
        sceneActive=interactiveNow.value && current?.lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
        val observer = LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) store.save(workspaceAtLifecycle.value)
            sceneActive=interactiveNow.value && current?.lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
            connection.setRenderingActive(sceneActive)

        }
        current?.lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            current?.lifecycleOwner?.lifecycle?.removeObserver(observer)
            onSession(null)
        }
    }
    LaunchedEffect(session,deviceInteractive) {
        sceneActive=deviceInteractive && session?.lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
        connection.setRenderingActive(sceneActive)
    }
    LaunchedEffect(session,deviceInteractive,activityResumed,sceneActive){
        val current=session ?: return@LaunchedEffect
        if(deviceInteractive && activityResumed && !sceneActive){
            do {
                runCatching{current.scene.requestFullSpace()}.onFailure{Log.w("Floatdesk","Full-space recovery request failed",it)}
                Log.i("Floatdesk","wake acquisition activity=$activityResumed xr=${current.lifecycleOwner.lifecycle.currentState}")
                delay(1000)
            }while(current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)!=true)
        }
    }
    LaunchedEffect(session,sceneActive) {
        val current=session ?: return@LaunchedEffect
        if(sceneActive) {
            if(activatedScene){deskSpace.set(null);deskRootVersion++;sceneEpoch++}else activatedScene=true
            current.scene.requestFullSpace()
            Log.i("Floatdesk","scene recovery generation=$sceneEpoch")
            connection.recoverRenderingOutputs()
            val hands=runCatching { current.configure(Config.Builder(current.config).setHandTracking(HandTrackingMode.BOTH).build()) }
            val head=runCatching { current.configure(Config.Builder(current.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()) }
            Log.i("Floatdesk","tracking activation hands=$hands head=$head")
        }
    }
    LaunchedEffect(workspace.screenSpacing) {connection.configurePresentation(workspace.screenSpacing)}
    LaunchedEffect(workspace.encoderQuality,workspace.encoderMbps,workspace.sharpening,workspace.detailAreaPixels,workspace.detailTargetFps){connection.configureVideo(workspace.encoderQuality,workspace.encoderMbps,workspace.sharpening,workspace.denseFraction,workspace.detailAreaPixels,workspace.detailTargetFps)}
    val pointerControl = remember { PointerControl() }
    val gazeStabilizer = remember { GazeStabilizer() }
    val gazeWindowFocus=remember{GazeWindowFocus()}
    val magnification=remember{WindowMagnification()}
    var inPlaceWindow by remember { mutableStateOf<Long?>(null) }
    var magnified by remember { mutableStateOf<MagnifiedWindow?>(null) }
    val magnifierViewer=remember { AtomicReference<Pose?>(null) }
    var magnifierMoving by remember { mutableStateOf(false) }
    var magnifierPinned by remember { mutableStateOf(false) }
    var typingLocked by remember { mutableStateOf(false) }
    var magnifierMesh by remember { mutableStateOf<MonitorMesh?>(null) }
    var magnifierReady by remember { mutableStateOf(false) }
    var magnifierFailed by remember { mutableStateOf(false) }
    val magnifierHands=remember { MagnifierContacts() }
    val fixedWindows=remember { AtomicReference<List<Pair<String,MagnifiedWindow>>>(emptyList()) }
    val popupWindows=remember {AtomicReference<List<MagnifiedWindow>>(emptyList())}
    val popupReady=remember {androidx.compose.runtime.mutableStateMapOf<String,Boolean>()}
    val systemWindows=remember {AtomicReference<List<MagnifiedWindow>>(emptyList())}
    val systemRuntime=remember {androidx.compose.runtime.mutableStateMapOf<Long,MagnifiedWindow>()}
    val systemPlacements=remember {mutableMapOf<String,dev.floatdesk.client.workspace.FixedMagnifierPlacement>()}
    val appliedDistances=remember {floatArrayOf(Float.NaN,Float.NaN)}
    if(appliedDistances[0]!=workspace.nearestDistanceM || appliedDistances[1]!=workspace.farthestDistanceM) {
        val first=appliedDistances[0].isNaN()
        ViewLayers.configure(workspace.nearestDistanceM,workspace.farthestDistanceM)
        appliedDistances[0]=workspace.nearestDistanceM;appliedDistances[1]=workspace.farthestDistanceM
        if(!first) {
            // Saved records were rescaled with the workspace; re-read them and move live panes.
            systemPlacements.clear()
            magnified=magnified?.let{it.copy(width=it.width*MagnifierDistanceM/it.layerDistanceM,height=it.height*MagnifierDistanceM/it.layerDistanceM,layerDistanceM=MagnifierDistanceM,mesh=null)}
        }
    }
    val systemReady=remember {androidx.compose.runtime.mutableStateMapOf<Long,Boolean>()}
    val fixedRuntime=remember { androidx.compose.runtime.mutableStateMapOf<String,MagnifiedWindow>() }
    val referenceInk=remember { AtomicReference(dev.floatdesk.client.media.VideoAim()) }
    var referencePane by remember { mutableStateOf<MagnifiedWindow?>(null) }
    var referenceReady by remember { mutableStateOf(false) }
    val fixedPins=remember { androidx.compose.runtime.mutableStateMapOf<String,Long>() }
    val fixedReady=remember { androidx.compose.runtime.mutableStateMapOf<String,Boolean>() }
    val fixedEditing=remember { mutableSetOf<String>() }
    var lastFixedWindow by remember { mutableStateOf<Long?>(null) }
    val toolbar=remember { HoverToolbarState() }
    var toolbarTarget by remember { mutableStateOf<ToolbarTarget?>(null) }
    var toolbarHovered by remember { mutableStateOf(false) }
    var snapGridHovered by remember { mutableStateOf(false) }
    val toolbarPanels=remember { AtomicReference<List<ToolbarStrip>>(emptyList()) }
    val magnifierPointerHover=remember { androidx.compose.runtime.mutableStateMapOf<String,Pair<Boolean,Boolean>>() }
    var dragProxy by remember{mutableStateOf<MagnifiedWindow?>(null)}
    val windowDragRay=remember{WindowDragRay()}
    val windowGrab=remember{WindowGrabController{workspace.pinchThresholds}}
    val pinchSettings=rememberUpdatedState(workspace.pinchThresholds)
    val mouseGestures = remember { MouseGestures(thresholds={pinchSettings.value}) { Log.i("Floatdesk",it) } }
    val panelInput = remember { PanelGazeInput() }
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
    LaunchedEffect(workspace.experimentalViewedDetail,workspace.sphericalWarp,workspace.gazeToFocus,workspace.magnifyWindow) {
        if((workspace.experimentalViewedDetail || workspace.sphericalWarp || workspace.gazeToFocus || workspace.magnifyWindow) && context.checkSelfPermission("android.permission.EYE_TRACKING_FINE")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            eyePermission.launch("android.permission.EYE_TRACKING_FINE")
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
    var focusFollowUntil by remember {mutableStateOf(0L)}
    var pendingFocusMonitor by remember {mutableStateOf<Int?>(null)}
    val focusGestures=remember {FocusGestures {pinchSettings.value}}
    val following = latchedFollow || focusFollowUntil>SystemClock.uptimeMillis() || (holdingFist && !suppressFist)
    LaunchedEffect(focusFollowUntil) {
        if(focusFollowUntil>0) {delay((focusFollowUntil-SystemClock.uptimeMillis()).coerceAtLeast(0));focusFollowUntil=0;followGeneration+=1}
    }
    val grabs = remember { mutableMapOf<Int, TwoHandState>() }
    DisposableEffect(session) {
        session?.scene?.panelClippingConfig=androidx.xr.scenecore.PanelClippingConfig(isDepthTestEnabled=false)
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
    val displayHead=remember { AtomicReference<Pose?>(null) }
    val viewHalfAngles=remember {AtomicReference(floatArrayOf(Math.toRadians(35.0).toFloat(),Math.toRadians(30.0).toFloat()))}
    fun releaseSupportBasis() {
        val basis=workspace.supportBasis ?: return
        val desktop=workspace.monitorAt(0)?.pose ?: return
        val units=handTracking.unitsPerMeter
        fun rebase(record:dev.floatdesk.client.workspace.FixedMagnifierPlacement)=rebaseSupportingPlacement(record,basis,desktop,units)
        systemPlacements.replaceAll{_,record->rebase(record)}
        workspace=workspace.copy(supportBasis=null,fixedMagnifiers=workspace.fixedMagnifiers.map(::rebase),
            systemPanels=workspace.systemPanels.map(::rebase),referencePlacement=workspace.referencePlacement?.let(::rebase))
        store.save(workspace)
    }
    fun addFixedMagnifier(){
        val desktop=workspace.monitorAt(0) ?: return
        val viewer=magnifierViewer.get() ?: displayHead.get() ?: return
        val id=java.util.UUID.randomUUID().toString()
        val source=connection.workspaceInfo.value?.windows?.firstOrNull{it.id==(magnified?.id ?: inPlaceWindow ?: lastFixedWindow)} ?: connection.workspaceInfo.value?.windows?.firstOrNull()
        if(lastFixedWindow==null)lastFixedWindow=source?.id
        val window=magnifiedWindow(source?.id ?: 0,source?.width ?: 1280,source?.height ?: 800,viewer,viewHalfAngles.get()[0],viewHalfAngles.get()[1],handTracking.unitsPerMeter,workspace.sphericalWarp)
        val record=saveFixedMagnifier(id,window,desktop.copy(pose=workspace.supportBasis ?: desktop.pose),handTracking.unitsPerMeter)
        workspace=workspace.copy(magnifyWindow=true,magnifierMode="fixed",fixedMagnifiers=workspace.fixedMagnifiers+record);store.save(workspace)
    }
    LaunchedEffect(workspace.magnifierMode,workspace.magnifyWindow){
        if(workspace.magnifierMode=="fixed")lastFixedWindow=magnified?.id ?: inPlaceWindow ?: lastFixedWindow
        magnification.dismiss(SystemClock.uptimeMillis());magnified=null;inPlaceWindow=null;magnifierReady=false
        if(workspace.magnifyWindow && workspace.magnifierMode=="fixed"){
            while(workspace.fixedMagnifiers.isEmpty() && (workspace.monitorAt(0)==null || magnifierViewer.get()==null))delay(100)
            if(workspace.fixedMagnifiers.isEmpty())addFixedMagnifier()
        }
    }
    fun resetDesk() {
        magnification.dismiss(SystemClock.uptimeMillis());magnified=null;inPlaceWindow=null;connection.magnifierActive=false
        pendingFocusMonitor=null;focusFollowUntil=0
        grabs.values.forEach {it.cancel()};pointerControl.clear()
        manipulating=false;holdingFist=false;suppressFist=true
        val head=store.viewerReference ?: displayHead.get() ?: Pose()
        val viewer=Pose(handTracking.points["VIEWER"]?.copyOf() ?: head.position.copyOf(),head.rotation.copyOf())
        val angles=viewHalfAngles.get()
        if(workspace.focusMode && workspace.supportBasis==null)workspace=workspace.copy(supportBasis=workspace.monitorAt(0)?.pose)
        workspace=workspace.reset(monitors,store,viewer,angles,handTracking.unitsPerMeter)
        restoreViewerPending=false
        followGeneration+=1
    }
    val focusReply by connection.focusResult
    LaunchedEffect(focusReply) {
        val reply=focusReply ?: return@LaunchedEffect
        val index=pendingFocusMonitor ?: return@LaunchedEffect
        pendingFocusMonitor=null
        if(!workspace.focusMode)return@LaunchedEffect
        val rect=reply.rect
        if(!reply.ok || rect==null) {gestureNote=reply.message.ifEmpty{"Could not focus target"};return@LaunchedEffect}
        val pieces=connection.workspaceInfo.value?.visibleRects(rect,workspace.screenSpacing).orEmpty()
        if(pieces.isEmpty())return@LaunchedEffect
        val screen=workspace.monitorAt(index) ?: return@LaunchedEffect
        val head=store.viewerReference ?: displayHead.get() ?: return@LaunchedEffect
        val viewer=Pose(handTracking.points["VIEWER"]?.copyOf() ?: head.position.copyOf(),head.rotation.copyOf())
        val angles=viewHalfAngles.get()
        val bounds=floatArrayOf(pieces.minOf{it.left},pieces.minOf{it.top},pieces.maxOf{it.right},pieces.maxOf{it.bottom})
        grabs.values.forEach{it.cancel()};pointerControl.clear();manipulating=false
        val fitted=focusDesktopRegion(screen,bounds,viewer,angles[0],angles[1],handTracking.unitsPerMeter,workspace.sphericalWarp)
        if(workspace.supportBasis==null)workspace=workspace.copy(supportBasis=screen.pose)
        workspace=workspace.monitorPlaced(index,fitted,store,true)
        gestureNote="";restoreViewerPending=false;suppressFist=true;focusFollowUntil=SystemClock.uptimeMillis()+3000;followGeneration+=1
        clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }
    var displayControlsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(displayControlsOpen,sceneActive){connection.setDesktopVisible(!displayControlsOpen);if(displayControlsOpen || !sceneActive){toolbar.clear();toolbarTarget=null}}
    var displayControlsPose by remember { mutableStateOf(workspace.control.pose) }
    fun toggleDisplays() {
        displayControlsOpen=!displayControlsOpen
        if(displayControlsOpen) {
            displayHead.get()?.let { head->
                val scale=settingsPanelScale(handTracking.unitsPerMeter)
                displayControlsPose=Pose(transformPoint(head,floatArrayOf(0f,-.15f*scale,-.9f*scale)),head.rotation.copyOf())
            }
            connection.requestDisplays()
        }
    }

    val redrawAim=remember { AtomicReference<()->Unit>({}) }
    LaunchedEffect(session) {
        var frames=0
        var began=SystemClock.uptimeMillis()
        while(isActive) {
            if(!connection.isRenderingActive() || session?.lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)!=true) {
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
        if(!RenderPolicy.GrabGuides)return@LaunchedEffect
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
    DisposableEffect(guideRenderer){val renderer=guideRenderer;onDispose{renderer?.close()}}
    fun toolbarStripFor(target:ToolbarTarget?):ToolbarStrip?{
        val unit=handTracking.unitsPerMeter
        return when(target){
            is ToolbarTarget.Magnifier->{
                val pane=(if(target.key=="floating")magnified?.takeIf{magnifierReady} else fixedRuntime[target.key]?.takeIf{fixedReady[target.key]==true}) ?: return null
                val frame=.005f*pane.units
                val anchor=Pose(transformPoint(pane.pose,floatArrayOf(0f,pane.height*.5f+frame,.004f*pane.units)),pane.pose.rotation)
                toolbarStrip(anchor,pane.layerDistanceM*pane.units*.075f,true)
            }
            is ToolbarTarget.Window->{
                val info=connection.workspaceInfo.value ?: return null
                val source=info.windows.firstOrNull{it.id==target.id} ?: return null
                val desktop=workspace.monitorAt(0) ?: return null;val b=info.bounds() ?: return null
                fun edge(x:Float):FloatArray?{
                    val display=connection.presentation().extendedDisplayPoint((x-b[0])/b[2],(source.y-b[1]).toFloat()/b[3]) ?: return null
                    return transformPoint(desktop.pose,pointerSurfacePoint(PointerTarget(0,display.first,display.second,FloatArray(3)),desktop.physicalWidthM,desktop.physicalHeightM,pointerControl.meshes[0]))
                }
                val centre=edge(source.x+source.width*.5f) ?: return null
                val lifted=add(centre,rotateVector(desktop.pose.rotation,0f,0f,.02f*unit))
                toolbarStrip(Pose(lifted,desktop.pose.rotation),dev.floatdesk.client.workspace.ViewLayers.DesktopDistanceM*unit*.075f,false)
            }
            null->null
        }
    }
    var palmPanel by remember { mutableStateOf<PalmPanelState?>(null) }
    val poke = remember { PalmPoke() }
    var lastPalmAction by remember { mutableStateOf(0L) }
    val palmAction: (PalmAction) -> Unit = { action ->
        val now = SystemClock.uptimeMillis()
        if (handInputAllowed && palmPanel!=null && now - lastPalmAction > 450 && palmActionEnabled(action,workspace,connection.workspaceInfo.value)) {
            lastPalmAction = now
            panelPressed=action; feedbackAt=now
            clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            when (action) {
                PalmAction.FOLLOW -> { releaseSupportBasis();focusFollowUntil=0;latchedFollow = !latchedFollow; suppressFist = true; followGeneration += 1 }
                PalmAction.RESET -> resetDesk()
                PalmAction.MODE -> {
                    pendingFocusMonitor=null;grabs.values.forEach{it.cancel()};pointerControl.clear();manipulating=false
                    releaseSupportBasis();workspace=workspace.copy(interactionMode=workspace.interactionMode.next());store.save(workspace)
                }
                PalmAction.SEARCH -> { connection.disconnect(); connection.start(context) }
                PalmAction.SPACE -> { session?.scene?.requestFullSpace();toggleDisplays() }
                PalmAction.HAND_MOUSE -> { workspace=workspace.copy(handMouse=!workspace.handMouse);store.save(workspace) }
                PalmAction.MOVE, PalmAction.FREE -> {
                    val move=action==PalmAction.MOVE && !workspace.grabToMove
                    val free=action==PalmAction.FREE && !workspace.freeManipulation
                    grabs.values.forEach{it.cancel()};pointerControl.clear();manipulating=false;pendingFocusMonitor=null
                    releaseSupportBasis()
                    workspace=workspace.copy(grabToMove=move,freeManipulation=free)
                    store.save(workspace)
                }
                PalmAction.REFERENCE -> { workspace=workspace.copy(desktopReference=!workspace.desktopReference);store.save(workspace) }
                PalmAction.WARP -> {
                    grabs.values.forEach { it.cancel() }
                    manipulating = false
                    followGeneration += 1
                    workspace = workspace.copy(sphericalWarp = RenderPolicy.SphericalDesktop && !workspace.sphericalWarp)
                    store.save(workspace)
                }
                PalmAction.MORE -> palmPanel = palmPanel?.copy(page = PalmPage.MORE)
                PalmAction.BACK -> palmPanel = palmPanel?.copy(page = PalmPage.MAIN)
                PalmAction.DIAGNOSTICS -> { workspace = workspace.copy(viewDetailDebug = !workspace.viewDetailDebug); store.save(workspace) }
                PalmAction.GAZE_FOCUS -> { workspace = workspace.copy(gazeToFocus = !workspace.gazeToFocus); store.save(workspace) }
                PalmAction.MAGNIFIER -> { workspace = nextMagnifier(workspace); store.save(workspace) }
                PalmAction.MOUSE_SPACES -> { workspace = workspace.copy(cursorRemap = !workspace.cursorRemap); store.save(workspace) }
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
        var rightViewpoint = runCatching { RenderViewpoint.right(current) }.getOrNull()
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
        var lastFocusGate="<initial>"
        var lastDetailAt=0L
        val qualityGaze=QualityGazeTracker()
        val hudGaze=HudGazeRegions(dev.floatdesk.client.workspace.ViewLayers.DiagnosticsDistanceM)
        var inputWasResumed=true
        var fistHeld = 0
        var panelHand: String? = null
        var lastPalmSeen = 0L
        var followLock: FollowLock? = null
        var seenGeneration = followGeneration
        var tick = 0
        // Keep platform maintenance outside the gesture coroutine's bytecode body.
        // ART refuses to JIT oversized methods, leaving this hot loop interpreted.
        fun updateEyeTracking(wantEyes: Boolean, resumed: Boolean) {
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
            if(wantEyes && resumed && gazeEyes.isEmpty() && SystemClock.uptimeMillis()-eyeRequestAt>=1500 && !eyeNoticeShown) {
                eyeNoticeShown=true
                android.widget.Toast.makeText(context,"Eye tracking is unavailable. Hand pointing remains available.",android.widget.Toast.LENGTH_LONG).show()
            }
        }
        while (isActive) {
            delay(10)
            tick += 1
            val wantEyes = context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") == android.content.pm.PackageManager.PERMISSION_GRANTED
            val resumed = connection.isRenderingActive() && current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            updateEyeTracking(wantEyes,resumed)
            if(!resumed) {
                magnification.dismiss(SystemClock.uptimeMillis());magnified=null;inPlaceWindow=null;connection.magnifierActive=false
                sampledHands.set(emptyList())
                if(inputWasResumed) {
                    windowGrab.cancel();windowDragRay.clear();dragProxy=null;toolbar.clear();toolbarTarget=null
                    pinchFrames.drain(SystemClock.uptimeMillis())
                    if(handInputAllowed) palmInteractionEpoch++
                    handInputAllowed=false
                    val release=mouseGestures.update(SystemClock.uptimeMillis(),false,true,emptyMap(),null)
                    release.target?.let { connection.movePointer(it.monitor,it.u,it.v,false) }
                    release.scroll?.let { connection.touchpad(it.fingers,it.x,it.y) }
                    grabs.values.forEach { it.cancel() }
                    pointerControl.clear(resetAiming=true);palmPanel=null;holdingFist=false;followLock=null
                    deskSpace.get()?.let { parent ->
                        guideRenderer?.update(parent,emptyList())
                        guideRenderer?.updateRays(parent,emptyMap(),null,1f,true)
                        guideRenderer?.updateHighlight(parent,null,floatArrayOf(0f,0f,0f,1f),0f,0f)
                    }
                }
                inputWasResumed=false
                delay(100);continue
            }
            if(!inputWasResumed) {
                hands=emptyList()
                viewpoint=openViewpoint(current)
                rightViewpoint=runCatching { RenderViewpoint.right(current) }.getOrNull()
                device=runCatching { ArDevice.getInstance(current) }.getOrNull()
                tick=1
                Log.i("Floatdesk","tracking resources reacquired after wake")
            }
            inputWasResumed=true
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
            val fovs=listOfNotNull(viewpoint?.state?.value?.fieldOfView,rightViewpoint?.state?.value?.fieldOfView)
            val horizontal=fovs.flatMap {listOf(kotlin.math.abs(it.angleLeft),kotlin.math.abs(it.angleRight))}.filter {it.isFinite() && it>.05f}.minOrNull()
            val vertical=fovs.flatMap {listOf(kotlin.math.abs(it.angleUp),kotlin.math.abs(it.angleDown))}.filter {it.isFinite() && it>.05f}.minOrNull()
            if(horizontal!=null && vertical!=null)viewHalfAngles.set(floatArrayOf(horizontal,vertical))

            // SpatialExternalSurface is under Compose's recommended root, whose scale/pose
            // are independent of ActivitySpace. Always target the actual surface parent.
            val root = deskSpace.get().liveOrNull()
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
                    magnification.dismiss(handTracking.nowMs);magnified=null;inPlaceWindow=null;connection.magnifierActive=false
                    Log.w("Floatdesk","Recovered workspace after tracking discontinuity")
                }
                if(restoreViewerPending && !workspace.layouts[workspace.activeLayout].isNullOrEmpty()) {
                    workspace=workspace.restoreViewer(reference)
                    restoreViewerPending=false
                }
                store.viewerReference=reference
                store.viewerUnitsPerMeter=unitsPerMeter
                if(workspace.legacyDistances && !restoreViewerPending){
                    workspace=workspace.withViewingDistances(ViewLayers.DefaultNearestM,ViewLayers.DefaultFarthestM,reference,unitsPerMeter).copy(legacyDistances=false)
                    ViewLayers.configure(workspace.nearestDistanceM,workspace.farthestDistanceM)
                    store.save(workspace)
                }
                val active=workspace.layouts[workspace.activeLayout]
                if(active!=null && !workspace.grabToMove && !manipulating && grabs.values.none{it.busy} && !pointerControl.nativeManipulating){
                    val fixed=active.map{if(it.freePose)it else fixedDesktopDepth(it,reference,unitsPerMeter,true,preserveAngularSize=false)}
                    if(fixed.indices.any{fixed[it] !== active[it]}){
                        val pushes=++depthPushes[0]
                        if(pushes<=3 || pushes%100==0)Log.i("Floatdesk","desk depth pushes=$pushes")
                        workspace=workspace.copy(layouts=workspace.layouts.toMutableMap().also{it[workspace.activeLayout]=fixed.toMutableList()})
                    }
                }
            }
            if (!reliable) {
                trackingRecovery.update(handTracking.nowMs,false,null,null,unitsPerMeter)
                // Head quality controls follow, not availability of independently tracked hands.
                followLock = null
            }
            val eyeRays = if (trackingAvailable && deviceState != null) gazeEyes.mapNotNull { eye ->
                val state=eye.state.value
                if(!state.isOpen || state.trackingState!=TrackingState.TRACKING) null else {
                    val gaze=deskPose(current,root,deviceState.devicePose.compose(state.pose))
                    val q=gaze.rotation
                    gaze.translation.toArray() to rotateVector(floatArrayOf(q.x,q.y,q.z,q.w),0f,0f,-1f)
                }
            } else emptyList()
            val hudHead=displayHead.get()
            val hudReading=hudHead!=null && eyeRays.any{(origin,direction)->
                val local=viewerInMonitor(origin,hudHead.position,hudHead.rotation).map{it/unitsPerMeter}.toFloatArray()
                val end=viewerInMonitor(add(origin,direction),hudHead.position,hudHead.rotation).map{it/unitsPerMeter}.toFloatArray()
                hudGaze.hit(local,subtract(end,local),viewHalfAngles.get()[0],viewHalfAngles.get()[1],workspace.showResources,workspace.viewDetailDebug,workspace.sphericalWarp)
            }
            val contentEyeRays=if(hudReading)emptyList() else eyeRays
            val screen=workspace.monitorAt(0)
            val qualityTargets=if(!workspace.experimentalViewedDetail || screen==null)emptyList() else eyeRays.mapNotNull{(origin,direction)->
                val local=viewerInMonitor(origin,screen.pose.position,screen.pose.rotation)
                val endpoint=viewerInMonitor(add(origin,direction),screen.pose.position,screen.pose.rotation)
                extendedQualityTarget(local,subtract(endpoint,local),screen.physicalWidthM,screen.physicalHeightM,pointerControl.meshes[0])
                    ?.let{connection.presentation().extendedDesktopPoint(it.first,it.second)}
            }
            val raw=if(qualityTargets.isEmpty())null else qualityTargets.map{it.first}.average().toFloat() to qualityTargets.map{it.second}.average().toFloat()
            val sharedGaze=qualityGaze.update(raw,handTracking.nowMs)
            connection.detailDebug(workspace.experimentalViewedDetail,workspace.viewDetailDebug,workspace.showResources)
            if(displayControlsOpen || head==null || manipulating || !workspace.experimentalViewedDetail) {
                connection.viewedDetail(null,when {
                    displayControlsOpen->"Paused: Workspace open"
                    head==null->"Paused: head tracking unavailable"
                    manipulating->"Paused: desktop manipulation"
                    else->"Normal full-desktop streaming"
                })
            } else if(handTracking.nowMs-lastDetailAt>=50){
                lastDetailAt=handTracking.nowMs
                val angles=viewHalfAngles.get()
                val view=if(screen==null)null else viewedRegion(screen.physicalWidthM,screen.physicalHeightM,pointerControl.meshes[0],angles[0],angles[1]) {point->
                    viewerInMonitor(transformPoint(screen.pose,point),head.translation.toArray(),floatArrayOf(head.rotation.x,head.rotation.y,head.rotation.z,head.rotation.w))
                }
                connection.viewedDetail(view?.let{connection.presentation().desktopBounds(it)},center=sharedGaze,
                    targeting=if(raw!=null)"GAZE" else if(sharedGaze!=null)"GAZE LOST: holding target" else "GAZE unavailable: initial view center")
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
            displayHead.get()?.let{reference->if(reliable)magnifierViewer.set(Pose(grips["VIEWER"]?.copyOf() ?: reference.position,reference.rotation))}
            sampledHands.set(hands)
            val handStates=hands.map { (side,hand)->val state=hand.state.value;Triple(side,state,snapshotHandJoints(state)) }
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
                        if (workspace.pinchThresholds.indexClosed(side,distance(thumb,index))) handTracking.pinched.add(side)
                        grips[side] = deskPose(current, root, androidx.xr.runtime.math.Pose((thumb + index) * 0.5f)).translation.toArray()
                        fingerTips[side] = deskPose(current, root, androidx.xr.runtime.math.Pose(index)).translation.toArray()
                    }
                    palmControls(state, perceived, side, tick % 100 == 0, panelHand==side,joints)?.let { palmCandidates[side] = it.copy(pose = deskPose(current, root, it.pose.toRuntimePose()).toWorkspacePose(), unitsPerMeter = unitsPerMeter) }
                }
                val read = readHand(state,joints)
                if(read == null) continue
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
            val fingerContact=mouseSamples.any{(side,sample)->workspace.pinchThresholds.indexClosed(side,sample.indexMetres) || workspace.pinchThresholds.middleClosed(side,sample.middleMetres)}
            if (anyFist && !suppressFist && !fingerContact) {
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
                val centered=candidate.copy(page=palmPanel?.page ?: PalmPage.MAIN,pose=palmPanel?.pose ?: (head ?: candidate.pose.toRuntimePose()).compose(
                    androidx.xr.runtime.math.Pose(Vector3(0f,0f,-ViewLayers.PalmMenuDistanceM*unitsPerMeter))).toWorkspacePose())
                palmPanel = centered
                val other = if (candidate.side == "LEFT") "RIGHT" else "LEFT"
                poke.update(centered, fingerTips[other])?.let { palmActionNow.value(it) }
                // A platform click may arrive on release after our direct-contact press.
                if (poke.latchedContact) lastPalmAction = SystemClock.uptimeMillis()
            }
            if(palmPanel!=null && !panelEyePermissionAsked && context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                panelEyePermissionAsked=true
                eyePermission.launch("android.permission.EYE_TRACKING_FINE")
            }
            if(workspace.freeManipulation) for ((index, grab) in grabs) {
                val wasBusy = grab.busy
                val file = workspace
                val placement = file.monitorAt(index) ?: continue
                grab.advance(handTracking, placement)?.let {
                    val layout=if(index==0 && file.supportBasis==null)file.copy(supportBasis=placement.pose)else file
                    workspace=layout.monitorPlaced(index,it.copy(freePose=true),store,false)
                }
                if (wasBusy && !grab.busy) {
                    updateManipulation(false)
                    store.save(workspace)
                }
            }
            val hoverHands = pointerControl.hands(grips.keys,handTracking.nowMs,aimPoses).filterValues {connection.desktopPoint(it.monitor,it.u,it.v)!=null}
            // Preview and acquisition share current validated surface UVs. Do not
            // require a second native DOWN after both physical pinches are already down.
            if(workspace.freeManipulation && !(toolbarTarget!=null && (toolbarHovered || snapGridHovered)) && listOf("LEFT","RIGHT").none{it in magnifierHands} && listOf("LEFT","RIGHT").all {it in handTracking.pinched} && palmPanel==null) {
                val left=hoverHands["LEFT"];val right=hoverHands["RIGHT"]
                if(left!=null && right!=null && left.monitor==right.monitor) {
                    val placement=workspace.monitorAt(left.monitor)
                    val grab=grabs[left.monitor]
                    if(placement!=null && grab!=null && !grab.engaged) {
                        val wasBusy=grab.busy;val mesh=pointerControl.meshes[left.monitor]
                        for((side,hit) in listOf("LEFT" to left,"RIGHT" to right))
                            grab.acquire(side,pointerSurfacePoint(hit,placement.physicalWidthM,placement.physicalHeightM,mesh),placement,handTracking,mesh)
                        if(wasBusy!=grab.busy) updateManipulation(grab.busy)
                    }
                }
            }
            val panel=palmPanel
            val panelHits=if(panel==null) emptyList() else contentEyeRays.mapNotNull { (origin,direction) -> palmRayPoint(panel,origin,direction) }
            val panelGaze=if(panelHits.isEmpty()) null else floatArrayOf(panelHits.map {it[0]}.average().toFloat(),panelHits.map {it[1]}.average().toFloat())
            val activate=panelInput.update(handTracking.nowMs,panel?.side,panelGaze,panel?.let { mouseSamples[it.side]?.middleMetres },
                panel?.buttons.orEmpty().map { PanelButtonArea(it.action.ordinal,it.x,it.y,it.w,it.h) })
            panelFocused=panelInput.focused?.let { PalmAction.entries.getOrNull(it) }
            if(activate!=null) PalmAction.entries.getOrNull(activate)?.let { palmActionNow.value(it) }
            if(panel!=null && tick%50==0) Log.i("Floatdesk","panel gaze resources=${gazeEyes.size} tracked=${gazeEyes.count { it.state.value.trackingState==TrackingState.TRACKING }} open=${gazeEyes.count { it.state.value.isOpen }} device=${deviceState?.trackingState} rays=${eyeRays.size} hits=${panelHits.size}")
            fun magnifiedTarget(uv:Pair<Float,Float>,windowId:Long?=magnified?.id):PointerTarget? {
                val win=connection.windowCaptures.source(windowId) ?: return null
                val bounds=connection.workspaceInfo.value?.bounds() ?: return null
                val sourceU=(win.x-bounds[0]+uv.first*win.width)/bounds[2]
                val sourceV=(win.y-bounds[1]+uv.second*win.height)/bounds[3]
                val mapped=connection.presentation().extendedDisplayPoint(sourceU,sourceV) ?: return null
                return PointerTarget(0,mapped.first,mapped.second,floatArrayOf(0f,0f,0f))
            }
            val desktopHands=(if(workspace.focusMode || workspace.windowGrabMode)pointerControl.focusHands(hoverHands) else hoverHands).filterKeys{it !in magnifierHands}
            val panelHands=magnifierHands.mapNotNull { (side,uv) ->
                val window=magnifierHands.window(side)
                if(window==-1L)return@mapNotNull side to PointerTarget(0,uv.first,uv.second,floatArrayOf(0f,0f,0f),"reference")
                val owner=magnifierHands.owner(side) ?: return@mapNotNull null
                val key=when {
                    owner.startsWith("system:")->"shell:$window"
                    owner.startsWith("popup:")->"menu:$window"
                    else->owner
                }
                magnifiedTarget(uv,window)?.let { side to it.copy(spaceKey=key) }
            }.toMap()
            val contextHands=if(workspace.handMouseActive)desktopHands+panelHands else desktopHands
            val nativeFocusGazeEnabled=(workspace.focusMode || workspace.windowGrabMode) && pointerControl.systemGazeEnabled
            val nativeFocusGaze=if(nativeFocusGazeEnabled && !hudReading)pointerControl.systemGaze() else null
            val gazeTargets=if((nativeFocusGazeEnabled || workspace.gazeToFocus || workspace.magnifyWindow) && reliable) contentEyeRays.mapNotNull { (origin,direction) -> gazePointerTarget(origin,direction,workspace,pointerControl.meshes) } else emptyList()
            // Average binocular contacts on the same monitor; a valid single eye can still aim.
            val rawDesktopGazeTarget = gazeTargets.firstOrNull()?.let { first ->
                val same = gazeTargets.filter { it.monitor == first.monitor }
                first.copy(u=same.map { it.u }.average().toFloat(),v=same.map { it.v }.average().toFloat())
            }
            val visibleMagnifiers=buildList {
                if(magnifierSurfacesAllowed(workspace.magnifyWindow,sceneActive,displayControlsOpen) && magnifierReady)magnified?.let{add("floating" to it.copy(mesh=magnifierMesh))}
                if(workspace.desktopReference && referenceReady)referencePane?.let{add("reference" to it)}
                if(workspace.magnifierMode=="fixed")fixedWindows.get().filter{fixedReady[it.first]==true}.forEach{add(it)}
                if(sceneActive && !displayControlsOpen)popupWindows.get().forEach{add("menu:${it.id}" to it)}
                if(magnifierSurfacesAllowed(workspace.magnifySystemPanels,sceneActive,displayControlsOpen))systemWindows.get().filter{systemReady[it.id]==true}.forEach{add("shell:${it.id}" to it)}
            }
            val paneGaze=visibleMagnifiers.mapNotNull{(key,window)->contentEyeRays.firstNotNullOfOrNull{(o,d)->
                val depth=surfaceRayDistance(window.pose,window.width,window.height,window.mesh,o,d)
                val desktopDepth=gazePointerTarget(o,d,workspace,pointerControl.meshes)?.takeIf{connection.desktopPoint(it.monitor,it.u,it.v)!=null}?.let{hit->workspace.monitorAt(hit.monitor)?.let{surfaceRayDistance(it.pose,it.physicalWidthM,it.physicalHeightM,pointerControl.meshes[hit.monitor],o,d)}}
                if(depth==null || desktopDepth!=null && desktopDepth+.002f*handTracking.unitsPerMeter<depth)null
                else magnifierPoint(window,o,d)?.let{uv->depth to Triple(window,uv,key)}
            }}.minByOrNull{it.first}?.second
            val gazeBlockedByPane=paneGaze!=null
            // The toolbar is opaque to gaze: nothing behind it may take focus, magnify or move the eye mouse.
            val toolbarGaze=toolbarTarget!=null && contentEyeRays.any{(o,d)->toolbarPanels.get().any{it.contains(o,d)}}
            val magnifierGaze=paneGaze?.takeUnless{it.third=="reference"}?.let{(window,uv,key)->magnifiedTarget(magnifierContentPoint(window,uv),window.id)?.copy(spaceKey=key)}
            val rawGazeTarget=if(toolbarGaze)null else if(gazeBlockedByPane)magnifierGaze else rawDesktopGazeTarget
            val gazeTarget=if(contextHands.isEmpty() && !hudReading) gazeStabilizer.update(rawGazeTarget?.takeIf {connection.desktopPoint(it.monitor,it.u,it.v)!=null},handTracking.nowMs) else { gazeStabilizer.reset(); null }
            val monitorGrab=grabs.values.any { it.engaged }
            val nativeManipulation=pointerControl.nativeManipulating
            val choice = pointerControl.choose(nativeFocusGazeEnabled,gazeTarget ?: nativeFocusGaze?.takeUnless{gazeBlockedByPane},contextHands,monitorGrab || nativeManipulation,handTracking.nowMs,previewHands=desktopHands.takeIf{workspace.freeManipulation}.orEmpty())
            if (choice.mode != lastPointerMode) {
                Log.i("Floatdesk","mouse source=${choice.mode} hands=${hoverHands.mapValues {it.value.monitor}} eyes=${gazeTargets.size} native=$nativeManipulation palm=${palmPanel!=null}")
                lastPointerMode = choice.mode
            }
            dimPointer = choice.mode == PointerMode.HAND
            val focusInfo=connection.workspaceInfo.value
            connection.typingQuietMs=workspace.typingQuietMs
            if(!windowGrab.engaged && (workspace.gazeToFocus || workspace.magnifyWindow))connection.pollTyping(handTracking.nowMs)
            typingLocked=connection.typingLocked(handTracking.nowMs)
            val paneOwner=paneGaze?.first?.id?.let{connection.windowCaptures.owner(it)}
            val popupGaze=paneOwner!=null && paneOwner!=paneGaze?.first?.id
            val shellGaze=paneOwner?.let{id->focusInfo?.systemWindows?.any{it.id==id}}==true
            val toolbarEngaged=toolbarTarget!=null && (toolbarHovered || snapGridHovered || toolbarGaze)
            val autoFocusBlocked=windowGrab.engaged || shellGaze || hudReading || typingLocked || magnifierMoving || toolbarEngaged || !reliable || !resumed || displayControlsOpen || palmPanel!=null ||
                workspace.grabToMove || choice.mode.reservesDesktop || monitorGrab || nativeManipulation || mouseGestures.engaged || connection.focusBusy.value || focusInfo?.gazeFocus!=true
            val autoFocusWindow=if(!autoFocusBlocked && workspace.gazeToFocus)paneOwner?.takeIf{id->focusInfo?.windows?.any{it.id==id}==true} ?: rawGazeTarget?.takeIf{it.monitor==0}?.let{hit->
                connection.desktopPoint(hit.monitor,hit.u,hit.v)?.let{(u,v)->focusInfo?.hover(u,v)?.window?.windowId}
            }else null
            gazeWindowFocus.update(handTracking.nowMs,workspace.gazeToFocus,autoFocusBlocked,autoFocusWindow)?.let{connection.requestGazeFocus(it)}
            if(autoFocusBlocked || !workspace.gazeToFocus)connection.cancelGazeFocus()
            val magnifyTarget=if(gazeBlockedByPane || toolbarGaze)null else rawDesktopGazeTarget?.let{hit->connection.desktopPoint(hit.monitor,hit.u,hit.v)?.let{(u,v)->focusInfo?.hover(u,v)?.window?.windowId}}
            magnified?.let { panel ->
                val source=connection.windowCaptures.source(panel.id)
                val width=source?.width ?: panel.sourceWidth
                val height=source?.height ?: panel.sourceHeight
                if(panel.spherical!=workspace.sphericalWarp || width!=panel.sourceWidth || height!=panel.sourceHeight) {
                    val ref=magnifierViewer.get() ?: store.viewerReference ?: displayHead.get() ?: Pose()
                    magnified=magnifiedWindow(panel.id,width,height,ref,viewHalfAngles.get()[0],viewHalfAngles.get()[1],
                        unitsPerMeter,workspace.sphericalWarp,previous=panel)
                    magnifierMesh=magnified?.mesh
                    magnification.refresh(handTracking.nowMs)
                }
            }
            fun eligibleInPlace(id:Long):FocusRect? {
                val info=focusInfo ?: return null;val placement=screen ?: return null
                val viewer=magnifierViewer.get() ?: return null;val angles=viewHalfAngles.get()
                return inPlaceWindowRect(info,id,placement,pointerControl.meshes[0],viewer,angles[0],angles[1],unitsPerMeter,workspace.screenSpacing)
            }
            if(workspace.magnifierMode=="floating" && inPlaceWindow?.let{eligibleInPlace(it)==null}==true){magnification.dismiss(handTracking.nowMs);inPlaceWindow=null;magnifierReady=false}
            val currentMagnifier=magnified?.id ?: inPlaceWindow
            val magnifierChanged=currentMagnifier?.let{id->focusInfo?.windows?.none{it.id==id} ?: true}==true
            val magnifyBlocked=(typingLocked && currentMagnifier==null) || magnifierChanged || (currentMagnifier==null && (choice.mode.reservesDesktop || mouseGestures.engaged)) || !resumed || (!magnification.pinned && (!reliable || displayControlsOpen || palmPanel!=null || monitorGrab || nativeManipulation)) || magnifierFailed || focusInfo?.windowMagnify!=true
            val insideMagnifier=if(inPlaceWindow!=null)magnifyTarget==inPlaceWindow else magnified?.let{window->eyeRays.any{(o,d)->magnifierContainsGaze(window.copy(mesh=magnifierMesh),o,d)}}==true
            val activeMagnifier=magnification.update(handTracking.nowMs,workspace.magnifyWindow,magnifyBlocked,magnifyTarget,
                insideMagnifier,magnifierReady,workspace.grabToMove || windowGrab.engaged || popupGaze || shellGaze || hudReading || mouseGestures.engaged || typingLocked || magnifierMoving || toolbarEngaged,eyeRays.isNotEmpty(),retain=workspace.magnifierMode=="fixed")
            if(activeMagnifier!=currentMagnifier){
                magnified=null;inPlaceWindow=null
                activeMagnifier?.let{id->focusInfo?.windows?.firstOrNull{it.id==id}?.let{win->
                    connection.requestGazeFocus(id)
                    if(workspace.magnifierMode=="fixed" || eligibleInPlace(id)!=null)inPlaceWindow=id else {
                        val ref=magnifierViewer.get() ?: store.viewerReference ?: displayHead.get() ?: Pose()
                        magnified=magnifiedWindow(id,win.width,win.height,ref,viewHalfAngles.get()[0],viewHalfAngles.get()[1],unitsPerMeter,workspace.sphericalWarp)
                    }
                }}
                magnifierReady=false;magnifierMesh=magnified?.mesh;magnifierHands.clear()
            }
            if(workspace.magnifierMode=="fixed" && activeMagnifier!=null)lastFixedWindow=activeMagnifier
            if(lastFixedWindow!=null && focusInfo!=null && focusInfo.windows.none{it.id==lastFixedWindow})lastFixedWindow=null
            magnifierPinned=magnification.pinned
            magnifierFailed=false;connection.magnifierActive=magnified!=null
            val referenceFocusEnabled=workspace.focusMode && !workspace.freeManipulation
            val referenceFocus=if(referenceFocusEnabled)panelHands.filterValues{it.spaceKey=="reference"} else emptyMap()
            val referenceGaze=paneGaze?.takeIf{referenceFocusEnabled && it.third=="reference"}?.second?.let{uv->PointerTarget(0,uv.first,uv.second,floatArrayOf(0f,0f,0f),"reference")}
            val liveFocusContacts=focusAimContacts(desktopHands+referenceFocus,
                if(nativeFocusGazeEnabled && !hudReading)referenceGaze ?: (gazeTarget ?: nativeFocusGaze)?.takeUnless{gazeBlockedByPane} else null)
            val dragPoses=grips.filterKeys{it=="LEFT" || it=="RIGHT"}.mapValues{(_,point)->HandAimPose(point,floatArrayOf(0f,0f,0f,1f))}
            val focusContacts=if(windowGrab.engaged)windowDragRay.point(dragPoses)?.let{mapOf(it)} ?: liveFocusContacts else liveFocusContacts
            val focusHover=if((workspace.focusMode || workspace.windowGrabMode) && focusInfo!=null)focusContacts.mapNotNull {(side,hit)->
                if(hit.monitor!=0)return@mapNotNull null
                connection.desktopPoint(hit.monitor,hit.u,hit.v)?.let{(u,v)->focusInfo.hover(u,v)}?.let{side to it}
            }.toMap() else emptyMap()
            // Gaze highlights only under system eye-and-hand aiming; with hand-only aiming the hand must hover.
            val eyeAiming=pointerControl.systemGazeEnabled
            val magnifierHover=(if(eyeAiming)paneGaze?.third?.takeIf{key->key=="floating" || fixedWindows.get().any{it.first==key}} else null)
                ?: magnifierPointerHover.entries.firstOrNull{(key,hover)->(key=="floating" || workspace.fixedMagnifiers.any{record->record.id==key}) && (hover.first || eyeAiming && hover.second)}?.key
            fun windowAt(hit:PointerTarget?)=hit?.takeIf{it.monitor==0}?.let{connection.desktopPoint(it.monitor,it.u,it.v)}?.let{(u,v)->focusInfo?.hover(u,v)?.window?.windowId}
            val systemGaze=if(hudReading)null else pointerControl.systemGaze()
            val windowHover=(if(!eyeAiming || gazeBlockedByPane)null else windowAt(systemGaze) ?: windowAt(rawDesktopGazeTarget))
                ?: hoverHands.filterKeys{it !in magnifierHands}.values.firstNotNullOfOrNull{windowAt(it)}
            val viewerPoint=handTracking.points["VIEWER"] ?: handTracking.points["HEAD"]
            val stripRays=contentEyeRays+listOfNotNull(systemGaze?.let{g->
                val placement=workspace.monitorAt(g.monitor)
                if(placement==null || viewerPoint==null)null else {
                    val point=transformPoint(placement.pose,pointerSurfacePoint(g,placement.physicalWidthM,placement.physicalHeightM,pointerControl.meshes[g.monitor]))
                    viewerPoint to subtract(point,viewerPoint)
                }
            })
            val openStrip=toolbarStripFor(toolbar.open);val litStrip=toolbarStripFor(toolbar.highlight)
            val gazeStrip=when{
                openStrip!=null && stripRays.any{(o,d)->openStrip.contains(o,d)}->toolbar.open
                litStrip!=null && stripRays.any{(o,d)->litStrip.containsMarker(o,d)}->toolbar.highlight
                else->null
            }
            toolbar.update(handTracking.nowMs,magnifierHover?.let{ToolbarTarget.Magnifier(it)} ?: windowHover?.let{ToolbarTarget.Window(it)},gazeStrip,toolbarEngaged,
                workspace.grabToMove || windowGrab.engaged || magnifierMoving || monitorGrab || nativeManipulation || displayControlsOpen || palmPanel!=null || !sceneActive || !resumed)
            if(toolbar.open!=toolbarTarget)Log.i("Floatdesk","window toolbar open=${toolbar.open} highlight=${toolbar.highlight}")
            toolbarTarget=toolbar.open
            val highlightStrip=toolbarStripFor(toolbar.highlight?.takeIf{it!=toolbar.open})
            val focusGate=buildList {
                if(magnifierMoving || toolbarEngaged)add("magnifier interaction")
                if(choice.mode.reservesDesktop)add("two-hand manipulation")
                if(mouseSamples.isEmpty())add("hands unavailable")
                if(displayControlsOpen)add("workspace panel")
                if(palmPanel!=null)add("palm panel")
                if(nativeManipulation)add("native handle")
                if(connection.focusBusy.value)add("host request pending")
                if(quality.overlapping && quality.continuousOwner==null)add("ambiguous hand identities")
            }.joinToString(",")
            val focusGateKey="$focusGate|${workspace.pinchThresholds}"
            if(workspace.focusMode && focusGateKey!=lastFocusGate) {
                Log.i("Floatdesk","focus input gate=${focusGate.ifEmpty{"open"}} thresholds=${workspace.pinchThresholds}")
                lastFocusGate=focusGateKey
            }
            val paneGrabPreview=workspace.freeManipulation && magnifierHands.owner("LEFT")?.let{it==magnifierHands.owner("RIGHT")}==true
            val gestureBlocked=paneGrabPreview || workspace.grabToMove || magnifierMoving || toolbarEngaged || displayControlsOpen || choice.mode.reservesDesktop || palmPanel!=null
            for(frame in pinchFrames.drain(handTracking.nowMs)) {
                val samples=frame.samples.filterKeys { it in mouseSamples }.mapValues { (side,sample) ->
                    val basis=mouseSamples.getValue(side)
                    sample.copy(right=basis.right,up=basis.up)
                }
                if(workspace.handMouse) for((side,sample) in frame.samples) {
                    val contact=(if(workspace.pinchThresholds.indexClosed(side,sample.indexMetres)) 1 else 0) or (if(workspace.pinchThresholds.middleClosed(side,sample.middleMetres)) 2 else 0)
                    if(lastMouseContact.put(side,contact)!=contact) Log.i("Floatdesk",
                        "mouse contact side=$side fingers=$contact indexMm=${sample.indexMetres*1000} middleMm=${sample.middleMetres*1000} accepted=${side in samples} blocked=$gestureBlocked sampleAge=${handTracking.nowMs-frame.at}")
                }
                val uncertain=quality.overlapping && quality.continuousOwner==null
                val grabUpdate=windowGrab.update(frame.at,workspace.windowGrabMode,gestureBlocked || uncertain || nativeManipulation,samples,focusHover,focusInfo)
                if(windowGrab.engaged){
                    val side=windowGrab.aimSide;val handSide=windowGrab.handSide;val hit=focusContacts[side];val hand=dragPoses[handSide];val desktop=workspace.monitorAt(0)
                    val source=windowGrab.source;val b=focusInfo?.bounds();val viewer=magnifierViewer.get()
                    if(side!=null && handSide!=null && hit!=null && hand!=null && desktop!=null && source!=null && b!=null && viewer!=null)windowDragRay.begin(handSide,hand,hit,desktop,pointerControl.meshes[0],viewer,source,b,handTracking.unitsPerMeter,side)
                }else windowDragRay.clear()
                dragProxy=windowDragRay.pane
                grabUpdate.move?.let{connection.windowAction(it.id,if(it.width!=null)"geometry" else "move",it)}
                val selected=focusGestures.update(workspace.focusMode,
                    focusGate.isNotEmpty(),samples,focusHover)
                if(selected?.resetDesktop==true)resetDesk()
                else if(selected!=null && connection.requestFocus(selected))pendingFocusMonitor=0
                val mouse=mouseGestures.update(frame.at,workspace.handMouseActive,
                    gestureBlocked || uncertain,samples,choice.target?.takeIf{choice.mode==PointerMode.HAND})
                mouse.scroll?.let {
                    if(it.fingers==0) Log.i("Floatdesk","mouse scroll released at=${frame.at}")
                    connection.touchpad(it.fingers,it.x,it.y)
                }
                if(mouse.dragging != lastMouseDragging) {
                    Log.i("Floatdesk","mouse drag=${mouse.dragging}")
                    lastMouseDragging=mouse.dragging
                }
                mouse.target?.let {target->connection.desktopPoint(target.monitor,target.u,target.v)?.let {(u,v)->
                    connection.movePointer(target.monitor,u,v,mouse.dragging)
                    connection.claimCursor(target.spaceKey,u,v)
                }}
                mouse.click?.let {
                    if(it.button==3) clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    Log.i("Floatdesk","mouse click button=${it.button}")
                    connection.desktopPoint(it.target.monitor,it.target.u,it.target.v)?.let {(u,v)->connection.clickPointer(it.target.monitor,u,v,it.button)}
                }
            }
            handTracking.pointerHeld=mouseGestures.engaged || windowGrab.engaged
            val previews = if (workspace.freeManipulation && !monitorGrab && !nativeManipulation && palmPanel==null && !displayControlsOpen && hoverHands.keys.none{it in magnifierHands}) hoverHands.values.groupBy { it.monitor }.mapNotNull { (index,points) ->
                val left=hoverHands["LEFT"]?.takeIf { it.monitor == index }
                val right=hoverHands["RIGHT"]?.takeIf { it.monitor == index }
                val placement=workspace.monitorAt(index)
                if(points.size != 2 || left == null || right == null || placement == null) null else
                    previewGrabGuide(placement,
                        pointerSurfacePoint(left,placement.physicalWidthM,placement.physicalHeightM,pointerControl.meshes[index]),
                        pointerSurfacePoint(right,placement.physicalWidthM,placement.physicalHeightM,pointerControl.meshes[index]),
                        handTracking,pointerControl.meshes[index])?.let { index to it }
            }.toMap() else emptyMap()
            val referenceTargets=if(referenceFocusEnabled && !gestureBlocked)
                focusHover.filterKeys{focusContacts[it]?.spaceKey=="reference"}.values.flatMap{listOfNotNull(it.screen,it.window)}.distinct()
                else emptyList()
            referenceInk.set(if(focusInfo==null)dev.floatdesk.client.media.VideoAim() else focusOutlines(referenceTargets,focusInfo,workspace.screenSpacing))
            val twoHandDesktop=if(workspace.freeManipulation)handPreviewMonitor(hoverHands)else null
            for((index,placement) in workspace.layouts[workspace.activeLayout].orEmpty().withIndex()) {
                val segments=ArrayList<Float>();val colors=ArrayList<Float>()
                if(index==0 && twoHandDesktop!=index && !monitorGrab && (workspace.focusMode || workspace.windowGrabMode) && palmPanel==null && !displayControlsOpen && focusInfo!=null) {
                    val targets=windowGrab.source?.let{dragSource->
                        val move=windowGrab.preview ?: WindowMove(dragSource.id,dragSource.x,dragSource.y)
                        val b=focusInfo.bounds()!!
                        val source=focusInfo.windows.firstOrNull{it.id==move.id}
                        val rect=FocusRect((move.x-b[0]).toFloat()/b[2],(move.y-b[1]).toFloat()/b[3],(move.x+(move.width ?: source?.width ?: 1)-b[0]).toFloat()/b[2],(move.y+(move.height ?: source?.height ?: 1)-b[1]).toFloat()/b[3])
                        val origin=FocusRect((dragSource.x-b[0]).toFloat()/b[2],(dragSource.y-b[1]).toFloat()/b[3],(dragSource.x+dragSource.width-b[0]).toFloat()/b[2],(dragSource.y+dragSource.height-b[1]).toFloat()/b[3])
                        listOf(FocusTarget(origin,""),FocusTarget(rect,"",move.id))
                    } ?: focusHover.values.flatMap{listOfNotNull(it.screen,it.window)}.distinct()
                    val ink=focusOutlines(targets,focusInfo,workspace.screenSpacing)
                    segments.addAll(ink.segments.toList())
                    colors.addAll(ink.colors.toList())
                }
                if(index==0 && typingLocked && focusInfo!=null){
                    val typed=focusInfo.windows.firstOrNull{it.id==connection.typingWindow}
                    val b=focusInfo.bounds()
                    if(typed!=null && b!=null){
                        val rect=FocusRect((typed.x-b[0]).toFloat()/b[2],(typed.y-b[1]).toFloat()/b[3],(typed.x+typed.width-b[0]).toFloat()/b[2],(typed.y+typed.height-b[1]).toFloat()/b[3])
                        for(visible in focusInfo.visibleRects(rect,workspace.screenSpacing)){
                            val cue=typingIndicator(visible);segments.addAll(cue.toList());repeat(cue.size/4){colors.addAll(listOf(1f,.75f,.25f))}
                        }
                    }
                }
                if(index==0)connection.cursorWires["desktop"]?.let{wires->segments.addAll(wires.toList());repeat(wires.size/4){colors.addAll(listOf(.6f,.85f,1f))}}
                if(index==0)connection.cursorLocation?.takeIf{it.key=="desktop"}?.let{location->
                    val b=focusInfo?.bounds()
                    if(b!=null)connection.presentation().extendedDisplayPoint((location.sourceX-b[0])/b[2],(location.sourceY-b[1])/b[3])?.let{(u,v)->
                        val glow=cursorGlow(u,v,placement.physicalWidthM,placement.physicalHeightM,dev.floatdesk.client.workspace.ViewLayers.DesktopDistanceM*handTracking.unitsPerMeter)
                        segments.addAll(glow.toList());repeat(glow.size/4){colors.addAll(listOf(0f,1f,1f))}
                    }
                }
                connection.aimOverlay(index,dev.floatdesk.client.media.VideoAim(segments.toFloatArray(),floatArrayOf(),maxOf(2f,placement.pixelWidth*.001f),.85f,colors.toFloatArray()))
            }
            guideRenderer?.let { renderer ->
                runCatching { renderer.update(root, grabs.mapNotNull { (index,grab) -> workspace.monitorAt(index)?.let {grab.displayGuide(it,pointerControl.meshes[index])} ?: previews[index] } + handTracking.paneGuides.values)
                    // The desktop cursor is the target marker; avoid a second dot over it.
                    renderer.updatePointer(root,null,handTracking.points["HEAD"],handTracking.unitsPerMeter)
                    val pulse=.5f+.5f*kotlin.math.sin(SystemClock.uptimeMillis()%2400L/2400f*2f*Math.PI.toFloat())
                    renderer.updateHighlight(root,highlightStrip?.pose?.position,highlightStrip?.pose?.rotation ?: floatArrayOf(0f,0f,0f,1f),highlightStrip?.cell ?: 0f,pulse)
                    val draw: ()->Unit = {
                        val contactsNow=pointerControl.hands(grips.keys,SystemClock.uptimeMillis(),aimPoses).filterValues {connection.desktopPoint(it.monitor,it.u,it.v)!=null}
                        val show=contactsNow.keys.none{it in magnifierHands} && contactsNow.isNotEmpty() && (!workspace.freeManipulation || handPreviewMonitor(contactsNow)==null) && palmPanel==null && !monitorGrab && !nativeManipulation
                        val inToolbar=toolbarHovered || snapGridHovered
                        val rays=(if(show) contactsNow.filterKeys{!(inToolbar && pointerControl.departed(it))} else emptyMap()).mapNotNull { (side,hit) ->
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
            val wantFollow = !manipulating && head != null && (latchedFollow || focusFollowUntil>SystemClock.uptimeMillis() || (holdingFist && !suppressFist))
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
            onReset = { resetDesk() },
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
                onDeskRoot={root->if(deskSpace.getAndSet(root)!==root)deskRootVersion++},
                viewer=displayHead,
                viewerAngles=viewHalfAngles,
                workspace = workspace,
                onWorkspace = {
                    val modeChanged=it.interactionMode!=workspace.interactionMode
                    workspace = it
                    if(modeChanged)releaseSupportBasis()
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
                onAddFixed={addFixedMagnifier()},
                grabs = grabs,
                handTracking = handTracking,
                pointerControl = pointerControl,
                dimPointer = dimPointer,
                panelFocused = panelFocused,
                panelPressed = panelPressed,
                onManipulate = { updateManipulation(it) },
            )
        }
        key(sceneEpoch){Subspace { desk() }}
        InPlaceWindowDetail(connection,if(!magnifierSurfacesAllowed(workspace.magnifyWindow,sceneActive,displayControlsOpen))null else if(workspace.magnifierMode=="fixed" && workspace.fixedMagnifiers.isEmpty())null else magnified?.id ?: inPlaceWindow ?: if(workspace.magnifierMode=="fixed")lastFixedWindow else null,{if(magnified==null)magnifierReady=true},{message->magnifierFailed=true;Log.w("Floatdesk",message)})
        val magnifierRoot=remember(deskRootVersion){deskSpace.get()}
        if(magnifierRoot!=null) {
            WindowMagnifierPanel(session,magnifierRoot,magnified?.copy(desktopRotation=workspace.monitorAt(0)?.pose?.rotation).takeIf{magnifierSurfacesAllowed(workspace.magnifyWindow,sceneActive,displayControlsOpen)},connection,magnifierPinned,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                {magnifierReady=true},{message->magnifierFailed=true;Log.w("Floatdesk",message)},
                {side,uv->magnifierHands.update("floating",side,uv,magnified?.id)},
                {next,_->magnified=next;magnifierMesh=next.mesh},{moving->if(moving)fixedEditing.add("floating")else fixedEditing.remove("floating");magnifierMoving=fixedEditing.isNotEmpty()},allowManipulation=workspace.grabToMove,freeManipulation=workspace.freeManipulation,topologyKey="floating",onPointerHover={hands,gaze->if(gaze)pointerControl.noteSystemGaze();if(hands || gaze)magnifierPointerHover["floating"]=hands to gaze else magnifierPointerHover.remove("floating")},onHidden={magnifierReady=false})
        }
        val fixedDesktop=workspace.monitorAt(0)?.let{desktop->workspace.supportBasis?.let{desktop.copy(pose=it)} ?: desktop}
        val fixedViewer=magnifierViewer.get()
        if(magnifierRoot!=null && fixedDesktop!=null && fixedViewer!=null){
            val enabled=magnifierSurfacesAllowed(workspace.desktopReference,sceneActive,displayControlsOpen)
            val unit=handTracking.unitsPerMeter
            val record=workspace.referencePlacement ?: run {
                val origin=Pose(transformPoint(fixedViewer,floatArrayOf(.32f*unit,-.22f*unit,-MagnifierDistanceM*unit)),fixedViewer.rotation)
                saveFixedMagnifier("reference",MagnifiedWindow(-1,origin,.42f*unit,.25f*unit,fixedDesktop.pixelWidth,fixedDesktop.pixelHeight),fixedDesktop,unit)
            }
            if(enabled && workspace.referencePlacement==null)SideEffect{workspace=workspace.copy(referencePlacement=record);store.save(workspace)}
            val display=workspace.monitorAt(0) ?: fixedDesktop
            val source=dev.floatdesk.client.net.DesktopWindow(-1,0,0,display.pixelWidth,display.pixelHeight)
            val pane=fixedMagnifier(record,fixedDesktop,source,fixedViewer,unit,workspace.sphericalWarp,referencePane)
            WindowMagnifierPanel(session,magnifierRoot,pane.takeIf{enabled},connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                {referenceReady=true},{message->referenceReady=false;Log.w("Floatdesk",message)},
                {side,uv->magnifierHands.update("reference",side,uv,-1)},{next,changed->
                    referencePane=next
                    if(changed)workspace=workspace.copy(referencePlacement=saveFixedMagnifier("reference",next,fixedDesktop,unit))
                },{moving->if(moving)fixedEditing.add("reference")else{fixedEditing.remove("reference");store.save(workspace)};magnifierMoving=fixedEditing.isNotEmpty()},
                topologyKey="reference",onHidden={referenceReady=false},showFrame=false,
                allowManipulation=workspace.grabToMove,freeManipulation=workspace.freeManipulation,desktopMirror=true,mirrorOverlay=referenceInk::get)
        }
        val renderedFixed=mutableListOf<Pair<String,MagnifiedWindow>>()
        if(session!=null && magnifierRoot!=null && fixedDesktop!=null && fixedViewer!=null){
            for(record in workspace.fixedMagnifiers) key(record.id){
                val selected=fixedPins[record.id] ?: inPlaceWindow ?: lastFixedWindow ?: connection.workspaceInfo.value?.windows?.firstOrNull()?.id
                val source=connection.windowCaptures.source(selected)
                val paneEnabled=source!=null && source.id>0L && workspace.magnifierMode=="fixed" && magnifierSurfacesAllowed(workspace.magnifyWindow,sceneActive,displayControlsOpen)
                val pane=if(paneEnabled)fixedMagnifier(record,fixedDesktop,source,fixedViewer,handTracking.unitsPerMeter,workspace.sphericalWarp,previous=fixedRuntime[record.id])else fixedRuntime[record.id] ?: fixedMagnifier(record,fixedDesktop,source,fixedViewer,handTracking.unitsPerMeter,false)
                if(paneEnabled)renderedFixed.add(record.id to pane)
                DisposableEffect(record.id,pane.id){fixedReady[record.id]=false;onDispose{fixedReady.remove(record.id)}}
                WindowMagnifierPanel(session,magnifierRoot,pane.takeIf{paneEnabled},connection,record.id in fixedPins,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                    {fixedReady[record.id]=true;magnifierReady=true},{message->fixedReady[record.id]=false;Log.w("Floatdesk",message)},
                    {side,uv->magnifierHands.update(record.id,side,uv?.let{magnifierContentPoint(pane,it)},pane.id)},
                    {next,manipulated->
                        fixedRuntime[record.id]=next
                        if(manipulated && record.id in fixedEditing){
                            val desktop=fixedDesktop
                            if(desktop!=null){val saved=saveFixedMagnifier(record.id,next,desktop,handTracking.unitsPerMeter);workspace=workspace.copy(fixedMagnifiers=workspace.fixedMagnifiers.map{if(it.id==record.id)saved else it})}
                        }
                    },{editing->
                        if(editing)fixedEditing.add(record.id)else{fixedEditing.remove(record.id);store.save(workspace)}
                        magnifierMoving=fixedEditing.isNotEmpty()
                    },{hands,gaze->if(gaze)pointerControl.noteSystemGaze();if(hands || gaze)magnifierPointerHover[record.id]=hands to gaze else magnifierPointerHover.remove(record.id)},allowManipulation=workspace.grabToMove,freeManipulation=workspace.freeManipulation,topologyKey=record.id,onHidden={fixedReady[record.id]=false})
            }
        }
        val renderedSystem=mutableListOf<MagnifiedWindow>()
        val systemInfo by connection.workspaceInfo
        if(session!=null && magnifierRoot!=null && fixedDesktop!=null && fixedViewer!=null && workspace.magnifySystemPanels) {
            val shellRows=systemInfo?.systemWindows.orEmpty().sortedWith(compareBy<dev.floatdesk.client.net.DesktopWindow>{!it.panel}.thenBy{it.id})
            var shellSlots by remember{mutableIntStateOf(0)}
            if(shellRows.size>shellSlots)shellSlots=shellRows.size
            repeat(shellSlots){slot->key("system-slot",slot){
                val row=shellRows.getOrNull(slot)
                var retainedRow by remember{mutableStateOf(row)}
                if(row!=null)retainedRow=row
                val live=row ?: requireNotNull(retainedRow)
                val source=connection.windowCaptures.source(live.id) ?: live
                val output=systemInfo?.screens?.firstOrNull{source.x+source.width/2 in it.x until it.x+it.width && source.y+source.height/2 in it.y until it.y+it.height}
                val edge=if(source.height>source.width){if(output!=null && source.x<output.x+output.width/2)"left" else "right"}else{if(output!=null && source.y<output.y+output.height/2)"top" else "bottom"}
                val placementKey="panel:${output?.name ?: "desktop"}:$edge"
                val record=systemPlacements.getOrPut(placementKey){
                    workspace.systemPanels.firstOrNull{it.id==placementKey} ?: run{
                    val initial=magnifiedWindow(live.id,source.width,source.height,fixedViewer,viewHalfAngles.get()[0],viewHalfAngles.get()[1],handTracking.unitsPerMeter,workspace.sphericalWarp,layerDistanceM=dev.floatdesk.client.workspace.ViewLayers.ShellDistanceM)
                    val vertical=source.height>source.width
                    val x=if(vertical && output!=null && source.x<output.x+output.width/2)-.32f else if(vertical).32f else 0f
                    val y=if(vertical)0f else if(output!=null && source.y<output.y+output.height/2).20f else -.20f
                    val shifted=initial.copy(pose=initial.pose.copy(position=transformPoint(fixedViewer,floatArrayOf(x*handTracking.unitsPerMeter,y*handTracking.unitsPerMeter,-dev.floatdesk.client.workspace.ViewLayers.ShellDistanceM*handTracking.unitsPerMeter))))
                    saveFixedMagnifier(placementKey,shifted,fixedDesktop,handTracking.unitsPerMeter).also{saved->
                        if(live.panel){workspace=workspace.copy(systemPanels=workspace.systemPanels.filter{it.id!=placementKey}+saved);store.save(workspace)}
                    }
                    }
                }
                // Capture reconnects must not resize a persisted visual element.
                val contentRecord=record
                val attachedPanel=if(live.panel)null else renderedSystem.filter{p->systemInfo?.systemWindows?.any{it.id==p.id && it.panel}==true}.minByOrNull{p->
                    val b=connection.windowCaptures.source(p.id)!!
                    val dx=maxOf(b.x-source.x-source.width,source.x-b.x-b.width,0)
                    val dy=maxOf(b.y-source.y-source.height,source.y-b.y-b.height,0)
                    dx.toLong()*dx+dy.toLong()*dy
                }
                val pane=if(attachedPanel!=null)stickyMagnifier(attachedPanel,connection.windowCaptures.source(attachedPanel.id)!!,source,fixedViewer)
                    else fixedMagnifier(contentRecord,fixedDesktop,source,fixedViewer,handTracking.unitsPerMeter,workspace.sphericalWarp,systemRuntime[live.id],dev.floatdesk.client.workspace.ViewLayers.ShellDistanceM)
                val enabled=row!=null && magnifierSurfacesAllowed(true,sceneActive,displayControlsOpen)
                if(enabled)renderedSystem.add(pane)
                DisposableEffect(live.id){onDispose{systemReady.remove(live.id);systemRuntime.remove(live.id)}}
                WindowMagnifierPanel(session,magnifierRoot,pane.takeIf{enabled},connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                    {systemReady[live.id]=true},{message->systemReady[live.id]=false;Log.w("Floatdesk",message)},
                    {side,uv->magnifierHands.update("system:${live.id}",side,uv?.let{magnifierContentPoint(pane,it)},pane.id)},
                    {next,manipulated->systemRuntime[live.id]=next
                        if(manipulated && "system:${live.id}" in fixedEditing){
                            val saved=saveFixedMagnifier(placementKey,next,fixedDesktop,handTracking.unitsPerMeter)
                            systemPlacements[placementKey]=saved
                            if(live.panel)workspace=workspace.copy(systemPanels=workspace.systemPanels.filter{it.id!=placementKey}+saved)
                        }
                    },{moving->
                        if(moving)fixedEditing.add("system:${live.id}")else{fixedEditing.remove("system:${live.id}");store.save(workspace)}
                        magnifierMoving=fixedEditing.isNotEmpty()
                    },allowManipulation=workspace.grabToMove && live.panel,freeManipulation=workspace.freeManipulation && live.panel,topologyKey="shell:${live.id}",onHidden={systemReady[live.id]=false},showFrame=false)
            }}
        }
        if(session!=null && magnifierRoot!=null && sceneActive && !displayControlsOpen){
            WindowMagnifierPanel(session,magnifierRoot,dragProxy,connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                {},{message->Log.w("Floatdesk",message)},{_,_->},{_,_->},{},allowManipulation=false,showFrame=false,topologyKey="window-drag-preview",receiveInput=false,projectFrame=false)
        }
        val renderedPopups=mutableListOf<MagnifiedWindow>()
        if(session!=null && magnifierRoot!=null && fixedViewer!=null && sceneActive && !displayControlsOpen){
            val owners=renderedFixed+listOfNotNull(magnified?.let{"floating" to it})+renderedSystem.map{"system:${it.id}" to it}
            for((ownerKey,owner) in owners) key("popup-owner",ownerKey){
                val popups=connection.windowCaptures.popups(owner.id).filter{p->systemInfo?.systemWindows?.none{it.id==p.id}!=false}
                var retainedSlots by remember {mutableIntStateOf(0)}
                val count=maxOf(retainedSlots,popups.size)
                SideEffect{retainedSlots=count}
                for(slot in 0 until count) key(slot){
                    val popup=popups.getOrNull(slot)
                    val source=connection.windowCaptures.source(owner.id)
                    val pane=if(source!=null && popup!=null)stickyMagnifier(owner,source,popup,fixedViewer,slot)else null
                    val name="popup:$ownerKey:$slot"
                    DisposableEffect(name,popup?.id){popupReady[name]=false;onDispose{popupReady.remove(name)}}
                    if(pane!=null && popupReady[name]==true)renderedPopups.add(pane)
                    WindowMagnifierPanel(session,magnifierRoot,pane,connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                        {popupReady[name]=true},{message->popupReady[name]=false;Log.w("Floatdesk",message)},
                        {side,uv->magnifierHands.update(name,side,if(pane!=null)uv?.let{magnifierContentPoint(pane,it)}else null,pane?.id)},
                        {_,_->},{},topologyKey="menu:${popup?.id}",onHidden={popupReady[name]=false},captureId=owner.id,regionId=popup?.id,allowManipulation=false,showFrame=false)
                }
            }
        }
        SideEffect{popupWindows.set(renderedPopups)}
        SideEffect{systemWindows.set(renderedSystem)}
        SideEffect{fixedWindows.set(renderedFixed)}
        LaunchedEffect(connection,sceneActive,workspace.cursorRemap,displayControlsOpen){
            if(!RenderPolicy.CursorTopology){connection.publishCursorMap(CursorTopology());return@LaunchedEffect}
            while(isActive){
                val head=magnifierViewer.get()
                val desktop=workspace.monitorAt(0)
                val info=connection.workspaceInfo.value
                val patches=mutableListOf<CursorPatch>()
                val mapViewer=desktop?.let{Pose(transformPoint(it.pose,floatArrayOf(0f,0f,dev.floatdesk.client.workspace.ViewLayers.DesktopDistanceM*handTracking.unitsPerMeter)),it.pose.rotation)}
                if(sceneActive && workspace.cursorRemap && !displayControlsOpen && head!=null && desktop!=null && info!=null){
                    for(screen in info.screens){
                        val bounds=info.bounds() ?: continue
                        val source=FocusRect(screen.x.toFloat(),screen.y.toFloat(),(screen.x+screen.width).toFloat(),(screen.y+screen.height).toFloat())
                        val logical=FocusRect((screen.x-bounds[0]).toFloat()/bounds[2],(screen.y-bounds[1]).toFloat()/bounds[3],(screen.x+screen.width-bounds[0]).toFloat()/bounds[2],(screen.y+screen.height-bounds[1]).toFloat()/bounds[3])
                        val region=info.visibleRects(logical,workspace.screenSpacing).firstOrNull() ?: continue
                        patches+=cursorPatches("desktop",desktop.pose,desktop.physicalWidthM,desktop.physicalHeightM,pointerControl.meshes[0],source,mapViewer!!,region,divisions=1).map{it.copy(depth=dev.floatdesk.client.workspace.ViewLayers.DesktopDistanceM)}
                    }
                    val panes=buildList{
                        magnified?.let{add("floating" to it)}
                        if(workspace.desktopReference && referenceReady)referencePane?.let{add("reference" to it)}
                        addAll(fixedWindows.get())
                        addAll(systemWindows.get().map{"shell:${it.id}" to it})
                        addAll(popupWindows.get().map{"menu:${it.id}" to it})
                    }
                    for((key,pane) in panes){
                        val source=if(key=="reference")info.bounds()?.let{dev.floatdesk.client.net.DesktopWindow(-1,it[0],it[1],it[2],it[3])}
                            else connection.windowCaptures.source(pane.id)
                        if(source==null)continue
                        val logicalPose=if(pane.freePose)pane.pose else if(pane.fixed)pane.pose.copy(position=pane.requestedPosition ?: pane.pose.position)
                            else pane.pose.copy(position=transformPoint(mapViewer!!,FloatArray(3){pane.headOffset[it]*pane.layerDistanceM*pane.units}))
                        patches+=cursorPatches(key,logicalPose,pane.width,pane.height,pane.mesh,FocusRect(source.x.toFloat(),source.y.toFloat(),(source.x+source.width).toFloat(),(source.y+source.height).toFloat()),mapViewer!!,region=magnifierContentRect(pane),front=true).map{it.copy(depth=pane.layerDistanceM)}
                    }
                }
                connection.publishCursorMap(CursorTopology(patches).logicalPixels())
                delay(200)
            }
        }

        val connectionProblem by connection.problem
        val problemHead=remember(connectionProblem!=null,displayHead.get()==null){displayHead.get()}
        if(connectionProblem!=null && !displayControlsOpen)problemHead?.let{head->
            val unit=handTracking.unitsPerMeter
            val scale=ViewLayers.sizeScale(1)
            val pose=Pose(transformPoint(head,floatArrayOf(0f,-.12f*unit*scale,-ViewLayers.ControlsDistanceM*unit)),head.rotation)
            Subspace{SpatialPanel(modifier=placedPanel(pose,.30f*unit,.075f*unit,density,pixelDensity,scale=scale)){
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().background(Color(0xF02A1F1C)).padding(10.dp),
                    verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)){
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp),color=Color(0xFFFFC27A),strokeWidth=2.dp)
                    Text(connectionProblem.orEmpty(),color=Color.White,fontSize=12.sp)
                }
            }}
        }
        val toolbarPane=(toolbarTarget as? ToolbarTarget.Magnifier)?.key?.let{key->if(key=="floating")magnified else fixedRuntime[key]}
        val toolbarWindow=when(val target=toolbarTarget){is ToolbarTarget.Window->target.id;is ToolbarTarget.Magnifier->toolbarPane?.id;null->null}
        val toolbarSource=systemInfo?.windows?.firstOrNull{it.id==toolbarWindow}
        var toolbarTooltip by remember(toolbarTarget){mutableStateOf<String?>(null)}
        var snapExpanded by remember(toolbarTarget){mutableStateOf(false)}
        var snapButtonHovered by remember(toolbarTarget){mutableStateOf(false)}
        LaunchedEffect(toolbarTarget){toolbarHovered=false;snapGridHovered=false}
        LaunchedEffect(snapButtonHovered,snapGridHovered){
            if(snapButtonHovered)snapExpanded=true else if(!snapGridHovered){delay(400);snapExpanded=false}
        }
        val toolbarMagnifier=(toolbarTarget as? ToolbarTarget.Magnifier)?.key
        val toolbarPinned=if(toolbarMagnifier=="floating")magnifierPinned else toolbarMagnifier in fixedPins
        val toolbarStrip=toolbarStripFor(toolbarTarget)
        val shownPanels=mutableListOf<ToolbarStrip>()
        if(toolbarStrip!=null){
            val unit=handTracking.unitsPerMeter
            val cellW=toolbarStrip.cell;val gapW=toolbarStrip.gap
            val magnifierBar=toolbarStrip.magnifier
            val gapRatio=gapW/cellW
            val barW=WindowMenuLayout.toolbarWidth(magnifierBar,gapRatio)*cellW;val barH=toolbarStrip.height
            val barPose=toolbarStrip.pose
            val cell=cellW.meters(density,pixelDensity);val gap=gapW.meters(density,pixelDensity)
            val snap=toolbarSource?.let{source->systemInfo?.let{currentSnapCell(source,it)}}
            val square=androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape(androidx.compose.foundation.shape.CornerSize(0.dp))
            fun closeToolbar(){toolbar.clear();toolbarTarget=null}
            shownPanels+=toolbarStrip.copy(width=barW)
            Subspace{SpatialPanel(modifier=placedPanel(barPose,barW,barH,density,pixelDensity),shape=square){
                WindowToolbar(toolbarWindow,magnifierBar,snap,snapExpanded,cell,gap,onHover={toolbarHovered=it},onTooltip={toolbarTooltip=it},
                    onSnapHover={snapButtonHovered=it},onSnapToggle={snapExpanded=!snapExpanded},
                    onAction={action->toolbarWindow?.let{connection.windowAction(it,action)};closeToolbar()},
                    onAdd={addFixedMagnifier()},
                    pinned=toolbarPinned,onPin={
                        if(toolbarMagnifier=="floating"){
                            magnification.togglePin();magnifierPinned=magnification.pinned
                        }else if(toolbarMagnifier!=null){
                            if(toolbarMagnifier in fixedPins)fixedPins.remove(toolbarMagnifier)
                            else toolbarWindow?.let{fixedPins[toolbarMagnifier]=it}
                        }
                        closeToolbar()
                    },
                    onRemove={
                        val selected=(toolbarTarget as? ToolbarTarget.Magnifier)?.key
                        if(selected=="floating"){magnification.dismiss(SystemClock.uptimeMillis());magnified=null}
                        else if(selected!=null){workspace=workspace.copy(fixedMagnifiers=workspace.fixedMagnifiers.filter{it.id!=selected});fixedPins.remove(selected);fixedRuntime.remove(selected);store.save(workspace)}
                        magnifierPointerHover.remove(selected);closeToolbar()
                    })
            }}
            if(snapExpanded){
                val gridW=WindowMenuLayout.gridWidth(gapRatio)*cellW;val gridH=WindowMenuLayout.gridHeight(gapRatio)*cellW
                val snapOffset=if(magnifierBar)cellW+2*gapW else 0f
                val gridPose=Pose(transformPoint(barPose,floatArrayOf(snapOffset,-(barH+gridH)*.5f,.002f*unit)),barPose.rotation)
                shownPanels+=toolbarStrip.copy(pose=gridPose,width=gridW,height=gridH)
                Subspace{SpatialPanel(modifier=placedPanel(gridPose,gridW,gridH,density,pixelDensity),shape=square){
                    SnapGridMenu(toolbarWindow,snap,cell,gap,onHover={snapGridHovered=it}){columns,rows,col,row->
                        val screen=systemInfo?.screens?.firstOrNull{r->toolbarSource!=null && toolbarSource.x+toolbarSource.width/2 in r.x until r.x+r.width && toolbarSource.y+toolbarSource.height/2 in r.y until r.y+r.height} ?: systemInfo?.screens?.firstOrNull{it.primary}
                        if(toolbarWindow!=null && screen!=null)connection.windowAction(toolbarWindow,"geometry",windowGridCell(toolbarWindow,screen.x,screen.y,screen.width,screen.height,columns,rows,col,row))
                        snapExpanded=false;snapGridHovered=false
                    }
                }}
            }
            toolbarTooltip?.let{label->
                val tipW=maxOf(barW,cellW*4);val tipH=cellW*.5f
                val tipPose=Pose(transformPoint(barPose,floatArrayOf(0f,barH*.5f+gapW+tipH*.5f,0f)),barPose.rotation)
                shownPanels+=toolbarStrip.copy(pose=tipPose,width=tipW,height=tipH)
                Subspace{SpatialPanel(modifier=placedPanel(tipPose,tipW,tipH,density,pixelDensity)){
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF18232E)),contentAlignment=androidx.compose.ui.Alignment.Center){
                        androidx.compose.foundation.text.BasicText(label,Modifier.padding(horizontal=6.dp),style=androidx.compose.ui.text.TextStyle(color=Color.White),maxLines=1,softWrap=false,
                            autoSize=androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize=6.sp,maxFontSize=48.sp,stepSize=.5f.sp))
                    }
                }}
            }
        }

        SideEffect{toolbarPanels.set(shownPanels.toList())}
    } else {
        controls()
    }
}
