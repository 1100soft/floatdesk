package dev.floatdesk.client

import dev.floatdesk.client.workspace.centerDesktop
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
import androidx.compose.runtime.mutableStateMapOf
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
import dev.floatdesk.client.workspace.repairDesktopPlacement
import dev.floatdesk.client.workspace.restoreViewer
import dev.floatdesk.client.workspace.withViewingDistances
import dev.floatdesk.client.workspace.reconcileMonitors
import dev.floatdesk.client.workspace.monitorPlaced
import dev.floatdesk.client.workspace.rebaseLayoutOrigin
import dev.floatdesk.client.workspace.rebaseSupportingPlacement
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
    LaunchedEffect(connection) { connection.start(context) }
    DisposableEffect(connection) { onDispose { connection.disconnect() } }
    val session = LocalSession.current
    var sceneEpoch by remember{mutableIntStateOf(0)}
    var sceneActive by remember { mutableStateOf(false) }
    var workspace by remember { mutableStateOf(store.load()) }
    val startupWakeMac=remember{workspace.wakeMac}
    LaunchedEffect(startupWakeMac){
        if(startupWakeMac.isNotEmpty())kotlinx.coroutines.withContext(Dispatchers.IO){sendWakeOnLan(startupWakeMac)}
    }
    val workspaceAtLifecycle=rememberUpdatedState(workspace)
    var restoreViewerPending by remember {mutableStateOf(true)}
    var centerOnStartup by remember{mutableStateOf(true)}
    var xrResumed by remember(session){mutableStateOf(false)}
    var spatialUiAvailable by remember(session){mutableStateOf(false)}
    val sceneCapabilities=remember{setOf(androidx.xr.scenecore.SpatialCapability.SPATIAL_UI,
        androidx.xr.scenecore.SpatialCapability.SPATIAL_3D_CONTENT)}
    DisposableEffect(session) {
        val current = session
        onSession(current)
        val lifecycle=current?.lifecycleOwner?.lifecycle
        xrResumed=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
        spatialUiAvailable=current?.scene?.spatialCapabilities?.containsAll(sceneCapabilities)==true
        val observer = LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) store.save(workspaceAtLifecycle.value)
            xrResumed=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
        }
        val capabilities=java.util.function.Consumer<Set<androidx.xr.scenecore.SpatialCapability>> {
            spatialUiAvailable=it.containsAll(sceneCapabilities)
        }
        lifecycle?.addObserver(observer)
        current?.scene?.addSpatialCapabilitiesChangedListener(context.mainExecutor,capabilities)
        onDispose {
            lifecycle?.removeObserver(observer)
            current?.scene?.removeSpatialCapabilitiesChangedListener(capabilities)
            onSession(null)
        }
    }
    LaunchedEffect(deviceInteractive,activityResumed,xrResumed,spatialUiAvailable) {
        val ready=deviceInteractive && activityResumed && xrResumed && spatialUiAvailable
        if(ready)delay(350) // Full Space capabilities can briefly flap during transition.
        sceneActive=ready
        connection.setRenderingActive(ready)
    }
    LaunchedEffect(session,deviceInteractive,activityResumed,spatialUiAvailable) {
        val current=session ?: return@LaunchedEffect
        if(!deviceInteractive || !activityResumed)return@LaunchedEffect
        // RESUMED alone can describe the gray Home Space panel after wake.
        // Request Full Space until the runtime actually grants spatial UI.
        while(!sceneActive || !spatialUiAvailable) {
            runCatching{current.scene.requestFullSpace()}.onFailure{Log.w("Floatdesk","Full-space recovery request failed",it)}
            delay(1000)
        }
    }
    LaunchedEffect(session,sceneActive) {
        val current=session ?: return@LaunchedEffect
        if(sceneActive) {
            Log.i("Floatdesk","scene recovery generation=$sceneEpoch")
            connection.recoverRenderingOutputs()
            val hands=runCatching { current.configure(Config.Builder(current.config).setHandTracking(HandTrackingMode.BOTH).build()) }
            val head=runCatching { current.configure(Config.Builder(current.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()) }
            Log.i("Floatdesk","tracking activation hands=$hands head=$head")
            // Preserve native parents/materials over normal sleep. A replacement
            // is only needed if SceneCore actually lost the Compose hierarchy.
            delay(2500)
            val root=deskSpace.get().liveOrNull()
            val attached=root!=null && runCatching{root.getScale(Space.ACTIVITY)}.isSuccess
            if(!attached){
                deskSpace.set(null)
                deskRootVersion++
                sceneEpoch++
                Log.w("Floatdesk","Rebuilding detached XR workspace after wake generation=$sceneEpoch")
                connection.recoverRenderingOutputs()
            }
        }
    }
    LaunchedEffect(workspace.screenSpacing) {connection.configurePresentation(workspace.screenSpacing)}
    LaunchedEffect(workspace.encoderQuality,workspace.encoderMbps,workspace.sharpening,workspace.detailAreaPixels,workspace.detailTargetFps){connection.configureVideo(workspace.encoderQuality,workspace.encoderMbps,workspace.sharpening,workspace.denseFraction,workspace.detailAreaPixels,workspace.detailTargetFps)}
    val pointerControl = remember { PointerControl() }
    val gazeStabilizer = remember { GazeStabilizer() }
    val gazeWindowFocus=remember{GazeWindowFocus()}
    val gazeDetail=remember{GazeWindowFocus(dwellMs=300)}
    var gazeDetailWindow by remember { mutableStateOf<Long?>(null) }
    val magnification=remember{WindowMagnification()}
    var inPlaceWindow by remember { mutableStateOf<Long?>(null) }
    val magnifierViewer=remember { AtomicReference<Pose?>(null) }
    var magnifierViewerReady by remember { mutableStateOf(false) }
    var magnifierMoving by remember { mutableStateOf(false) }
    var typingLocked by remember { mutableStateOf(false) }
    var magnifierReady by remember { mutableStateOf(false) }
    var magnifierFailed by remember { mutableStateOf(false) }
    val magnifierHands=remember { MagnifierContacts() }
    val fixedWindows=remember { AtomicReference<List<Pair<String,MagnifiedWindow>>>(emptyList()) }
    val popupWindows=remember {AtomicReference<List<MagnifiedWindow>>(emptyList())}
    val popupRuntime=remember {mutableStateMapOf<String,MagnifiedWindow>()}
    fun paneSource(key:String,id:Long?)=if(key.startsWith("menu:") && id!=null)
        connection.windowCaptures.region(id) else connection.windowCaptures.source(id)
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
        }
    }
    val systemReady=remember {androidx.compose.runtime.mutableStateMapOf<Long,Boolean>()}
    val fixedRuntime=remember { androidx.compose.runtime.mutableStateMapOf<String,MagnifiedWindow>() }
    val referenceInk=remember { AtomicReference(dev.floatdesk.client.media.VideoAim()) }
    var referencePane by remember { mutableStateOf<MagnifiedWindow?>(null) }
    var referenceReady by remember { mutableStateOf(false) }
    val fixedPins=remember { androidx.compose.runtime.mutableStateMapOf<String,Long>() }
    val fixedDisplayed=remember { androidx.compose.runtime.mutableStateMapOf<String,Long>() }
    val fixedReady=remember { androidx.compose.runtime.mutableStateMapOf<String,Boolean>() }
    val fixedEditing=remember { mutableSetOf<String>() }
    var lastFixedWindow by remember { mutableStateOf<Long?>(null) }
    val toolbar=remember { HoverToolbarState() }
    var toolbarTarget by remember { mutableStateOf<ToolbarTarget?>(null) }
    var openToolbarStrip by remember { mutableStateOf<ToolbarStrip?>(null) }
    var openToolbarWindow by remember { mutableStateOf<Long?>(null) }
    var toolbarHovered by remember { mutableStateOf(false) }
    var snapGridHovered by remember { mutableStateOf(false) }
    var snapGridDragging by remember { mutableStateOf(false) }
    var snapGridExpanded by remember { mutableStateOf(false) }
    val snapGridPresence=remember{SnapGridPresence()}
    val toolbarPanels=remember { AtomicReference<List<ToolbarStrip>>(emptyList()) }
    val snapGridSurface=remember{AtomicReference<ToolbarStrip?>(null)}
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
    val windowDetailAvailable=connection.workspaceInfo.value?.windowMagnify==true
    LaunchedEffect(workspace.experimentalViewedDetail,workspace.sphericalWarp,workspace.gazeToFocus,workspace.magnifyWindow,windowDetailAvailable) {
        if((workspace.experimentalViewedDetail || workspace.sphericalWarp || workspace.gazeToFocus || workspace.magnifyWindow || windowDetailAvailable) && context.checkSelfPermission("android.permission.EYE_TRACKING_FINE")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
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
    var pendingFocusMonitor by remember {mutableStateOf<Int?>(null)}
    var pendingFocusWindow by remember {mutableStateOf<Long?>(null)}
    var focusedDetailWindow by remember {mutableStateOf<Long?>(null)}
    fun requestFocus(target:FocusTarget){
        if(connection.requestFocus(target)){pendingFocusMonitor=0;pendingFocusWindow=target.windowId}
    }
    val focusGestures=remember {FocusGestures {pinchSettings.value}}
    val following = latchedFollow || (holdingFist && !suppressFist)

    val grabs = remember { mutableMapOf<Int, TwoHandState>() }
    val originGrab=remember{LayoutOriginGrab()}
    var originDownAt by remember{mutableStateOf<Long?>(null)}
    DisposableEffect(session) {
        session?.scene?.panelClippingConfig=androidx.xr.scenecore.PanelClippingConfig(isDepthTestEnabled=false)
        val current = session
        val listener = Runnable {
            Log.w("Floatdesk","XR activity origin changed; cancelling gestures")
            if(!restoreViewerPending)workspace=workspace.copy(viewerReference=store.viewerReference ?: workspace.viewerReference)
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
    fun recordPlacement(reason:String) {
        if(!store.hasPlacementChange(workspace))return
        val view=magnifierViewer.get() ?: displayHead.get()
        if(view!=null)workspace=workspace.copy(cursorMapReference=view)
        store.recordLayout(workspace,reason)
    }
    fun addFixedMagnifier(){
        val desktop=workspace.monitorAt(0) ?: return
        val viewer=magnifierViewer.get() ?: displayHead.get() ?: return
        val id=java.util.UUID.randomUUID().toString()
        val source=connection.workspaceInfo.value?.windows?.firstOrNull{it.id==(inPlaceWindow ?: lastFixedWindow)} ?: connection.workspaceInfo.value?.windows?.firstOrNull()
        if(lastFixedWindow==null)lastFixedWindow=source?.id
        val window=magnifiedWindow(source?.id ?: 0,source?.width ?: 1280,source?.height ?: 800,viewer,viewHalfAngles.get()[0],viewHalfAngles.get()[1],handTracking.unitsPerMeter,workspace.sphericalWarp && RenderPolicy.CustomMeshes)
        val record=saveFixedMagnifier(id,window,desktop.copy(pose=workspace.layoutOrigin ?: desktop.pose),handTracking.unitsPerMeter)
        store.recordLayout(workspace,"before adding magnifier")
        workspace=workspace.copy(magnifyWindow=true,fixedMagnifiers=workspace.fixedMagnifiers+record)
        recordPlacement("add magnifier")
    }
    LaunchedEffect(workspace.magnifyWindow,workspace.fixedMagnifiers.isEmpty()){
        magnification.dismiss(SystemClock.uptimeMillis());inPlaceWindow=null;magnifierReady=false
        if(workspace.magnifyWindow){
            while(workspace.fixedMagnifiers.isEmpty() && (workspace.monitorAt(0)==null || magnifierViewer.get()==null))delay(100)
            if(workspace.fixedMagnifiers.isEmpty()){delay(100);if(workspace.fixedMagnifiers.isEmpty())addFixedMagnifier()}
        }
    }
    fun resetDesk() {
        originGrab.clear()
        magnification.dismiss(SystemClock.uptimeMillis());inPlaceWindow=null
        pendingFocusMonitor=null;pendingFocusWindow=null;focusedDetailWindow=null
        grabs.values.forEach {it.cancel()};pointerControl.clear()
        manipulating=false;holdingFist=false;suppressFist=true
        val head=magnifierViewer.get() ?: store.viewerReference ?: return
        val viewer=Pose(handTracking.points["VIEWER"]?.copyOf() ?: head.position.copyOf(),head.rotation.copyOf())
        val angles=viewHalfAngles.get()
        store.recordLayout(workspace,"before reset")
        workspace=workspace.reset(monitors,store,viewer,angles,handTracking.unitsPerMeter)
        workspace=workspace.copy(cursorMapReference=viewer)
        store.recordLayout(workspace,"reset")
        systemPlacements.clear()
        restoreViewerPending=false
        followGeneration+=1
    }
    val focusReply by connection.focusResult
    LaunchedEffect(focusReply) {
        val reply=focusReply ?: return@LaunchedEffect
        val index=pendingFocusMonitor ?: return@LaunchedEffect
        pendingFocusMonitor=null
        val selectedWindow=pendingFocusWindow;pendingFocusWindow=null
        if(workspace.layoutModeActive)return@LaunchedEffect
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
        val fitted=focusDesktopRegion(screen,bounds,viewer,angles[0],angles[1],handTracking.unitsPerMeter,workspace.sphericalWarp,workspace.desktopHome ?: screen.pose)
        if(workspace.layoutOrigin==null || workspace.desktopHome==null)
            workspace=workspace.copy(layoutOrigin=workspace.layoutOrigin ?: screen.pose,desktopHome=workspace.desktopHome ?: screen.pose)
        workspace=workspace.monitorPlaced(index,fitted,store,true)
        focusedDetailWindow=selectedWindow
        gestureNote="";restoreViewerPending=false;suppressFist=true;followGeneration+=1
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
        val viewer=magnifierViewer.get() ?: return null
        fun foreground(point:FloatArray,right:FloatArray,magnifier:Boolean):ToolbarStrip {
            val range=distance(viewer.position,point)
            val lifted=foregroundGlowPoint(point,viewer.position,unit,handTracking.desktopSphereRadius)
            val normal=subtract(viewer.position,lifted)
            val projectedUp=cross3(normal,right)
            val up=projectedUp.takeIf{distance(it,FloatArray(3))>.0001f} ?: rotateVector(viewer.rotation,0f,1f,0f)
            val rotation=androidx.xr.runtime.math.Quaternion.fromLookTowards(
                Vector3(normal[0],normal[1],normal[2]),Vector3(up[0],up[1],up[2]))
            val apparent=distance(viewer.position,lifted).coerceIn(.20f*unit,maxOf(.20f*unit,range))
            return toolbarStrip(Pose(lifted,floatArrayOf(rotation.x,rotation.y,rotation.z,rotation.w)),apparent*.075f,magnifier)
        }
        return when(target){
            is ToolbarTarget.Magnifier->{
                val pane=fixedRuntime[target.key]?.takeIf{fixedReady[target.key]==true && fixedDisplayed[target.key]==it.id} ?: return null
                val frame=.005f*pane.units
                val top=floatArrayOf(0f,pane.height*.5f-frame,0f)
                val edge=pane.mesh?.project(top) ?: top
                fun at(x:Float):FloatArray{
                    val sample=floatArrayOf(x,top[1],0f)
                    return transformPoint(pane.pose,pane.mesh?.project(sample,onTriangles=true) ?: sample)
                }
                foreground(transformPoint(pane.pose,edge),subtract(at(pane.width*.12f),at(-pane.width*.12f)),true)
            }
            is ToolbarTarget.Window->{
                popupWindows.get().firstOrNull{it.id==target.id}?.let{pane->
                    fun at(u:Float)=magnifierCursorPoint(pane,u,0f)
                    return foreground(at(.5f),subtract(at(.65f),at(.35f)),false)
                }
                val info=connection.workspaceInfo.value ?: return null
                val source=info.windows.firstOrNull{it.id==target.id} ?: return null
                val desktop=workspace.monitorAt(0) ?: return null;val b=info.bounds() ?: return null
                val bounds=FocusRect((source.x-b[0]).toFloat()/b[2],(source.y-b[1]).toFloat()/b[3],
                    (source.x+source.width-b[0]).toFloat()/b[2],(source.y+source.height-b[1]).toFloat()/b[3])
                val top=info.visibleRects(bounds,workspace.screenSpacing).minWithOrNull(
                    compareBy<FocusRect>{it.top}.thenByDescending{it.right-it.left}) ?: return null
                fun at(u:Float)=transformPoint(desktop.pose,pointerSurfacePoint(PointerTarget(0,u,top.top,FloatArray(3)),
                    desktop.physicalWidthM,desktop.physicalHeightM,pointerControl.meshes[0]))
                val delta=(top.right-top.left)*.24f
                foreground(at(top.centerU),subtract(at(top.centerU+delta),at(top.centerU-delta)),false)
            }
            null->null
        }
    }
    var savedLayoutPreviews by remember(store){mutableStateOf(store.layoutPreviews())}
    var palmPanel by remember { mutableStateOf<PalmPanelState?>(null) }
    val palmPreferences=remember(context){context.getSharedPreferences("palm-menu",android.content.Context.MODE_PRIVATE)}
    val palmLayouts=remember(palmPreferences){mutableStateMapOf<PalmPage,PalmCustomLayout>().apply{
        PalmPage.entries.forEach{page->PalmCustomLayout.read(palmPreferences.getString(page.name,null))?.let{put(page,it)}}
    }}
    var palmEdit by remember{mutableStateOf<PalmEditSession?>(null)}
    val palmEditing=palmEdit!=null
    val poke = remember { PalmPoke() }
    var lastPalmAction by remember { mutableStateOf(0L) }
    val palmAction: (PalmAction) -> Unit = { action ->
        val now = SystemClock.uptimeMillis()
        if (!palmEditing && handInputAllowed && palmPanel!=null && now - lastPalmAction > 450 && palmActionEnabled(action,workspace,connection.workspaceInfo.value)) {
            lastPalmAction = now
            panelPressed=action; feedbackAt=now
            clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            when (action) {
                PalmAction.EDIT -> {palmPanel?.let{panel->palmEdit=PalmEditSession(panel,panel.customLayout ?: PalmCustomLayout.defaults(panel.page))};poke.clear()}
                PalmAction.FOLLOW -> { latchedFollow = !latchedFollow; suppressFist = true; followGeneration += 1 }
                PalmAction.RESET -> resetDesk()
                PalmAction.TAP_TO_FOCUS -> {workspace=workspace.copy(tapToFocus=!workspace.tapToFocus);store.save(workspace)}
                PalmAction.DRAG_TO_MOVE -> {workspace=workspace.copy(dragToMove=!workspace.dragToMove);store.save(workspace)}
                PalmAction.HANDS_OFF -> {workspace=workspace.copy(handsOff=!workspace.handsOff);store.save(workspace)}
                PalmAction.SEARCH -> { connection.disconnect(); connection.start(context) }
                PalmAction.SETTINGS -> { session?.scene?.requestFullSpace();toggleDisplays() }
                PalmAction.HAND_MOUSE -> { workspace=workspace.copy(handMouse=!workspace.handMouse);store.save(workspace) }
                PalmAction.LAYOUT,PalmAction.DONE -> {
                    if(action==PalmAction.LAYOUT){
                        val view=magnifierViewer.get() ?: displayHead.get()
                        if(view!=null)workspace=workspace.restoreDesktopFraming(monitors,view,viewHalfAngles.get(),handTracking.unitsPerMeter)
                        store.recordLayout(workspace,"enter layout")
                    }
                    val done=action==PalmAction.DONE
                    grabs.values.forEach{it.cancel()};originGrab.clear();pointerControl.clear();manipulating=false;pendingFocusMonitor=null
                    toolbar.clear();toolbarTarget=null;openToolbarStrip=null;openToolbarWindow=null
                    latchedFollow=false
                    connection.cancelGazeFocus()
                    workspace=workspace.copy(layoutMode=!done)
                    palmPanel=if(done)null else palmPanel?.copy(page=PalmPage.LAYOUT)
                    store.save(workspace)
                }
                PalmAction.UNDO,PalmAction.REDO -> {
                    if(!manipulating && !magnifierMoving){
                        store.stepLayout(workspace,if(action==PalmAction.UNDO)-1 else 1)?.let{
                            workspace=it
                            systemPlacements.clear()
                            pointerControl.clear()
                            followGeneration+=1
                        }
                    }
                }
                PalmAction.RESET_ORIGIN -> {
                    val desktop=workspace.monitorAt(0)?.pose
                    val prior=workspace.layoutOrigin
                    if(desktop!=null && prior!=null){
                        val origin=prior.copy(position=desktop.position.copyOf())
                        val units=handTracking.unitsPerMeter
                        systemPlacements.replaceAll{_,record->rebaseSupportingPlacement(record,prior,origin,units)}
                        workspace=workspace.rebaseLayoutOrigin(origin,units)
                        store.recordLayout(workspace,"reset origin")
                    }
                }
                PalmAction.ALIGN_AXES,PalmAction.SNAP_OBJECTS,PalmAction.FACE_USER,PalmAction.AUTO_ADJUST -> {
                    val view=magnifierViewer.get() ?: displayHead.get()
                    if(view!=null && !manipulating && !magnifierMoving){
                        val adjustment=when(action){
                            PalmAction.ALIGN_AXES->LayoutAdjustment.ALIGN
                            PalmAction.SNAP_OBJECTS->LayoutAdjustment.SNAP
                            PalmAction.FACE_USER->LayoutAdjustment.FACE
                            else->LayoutAdjustment.AUTO
                        }
                        val visiblePanes=fixedWindows.get().mapNotNull{(key,_)->fixedRuntime[key]?.takeIf{fixedReady[key]==true && fixedDisplayed[key]==it.id}}+
                            systemWindows.get().mapNotNull{pane->systemRuntime[pane.id]?.takeIf{systemReady[it.id]==true}}+listOfNotNull(referencePane.takeIf{workspace.desktopReference && referenceReady})
                        val contentSizes=visiblePanes.mapNotNull{pane->pane.layoutFootprint?.id?.let{id->
                            val image=magnifierContentRect(pane)
                            id to (pane.width*(image.right-image.left) to pane.height*(image.bottom-image.top))
                        }}.toMap()
                        val aspects=contentSizes.mapValues{(_,size)->size.first/size.second}
                        val adjusted=try{
                            workspace.adjustLayout(adjustment,view,handTracking.unitsPerMeter,contentSizes,contentSizes.keys,workspace.supportSeats(contentSizes.keys,aspects))
                        }catch(failure:IllegalArgumentException){
                            Log.w("Floatdesk","Layout adjustment rejected invalid geometry",failure)
                            null
                        }
                        if(adjusted!=null && adjusted!==workspace){
                            workspace=adjusted
                            systemPlacements.clear()
                            store.recordLayout(workspace,adjustment.name.lowercase())
                        }
                    }
                }
                PalmAction.RESET_SUPPORT -> {
                    val view=magnifierViewer.get() ?: displayHead.get()
                    if(view!=null && !manipulating && !magnifierMoving){
                        val fixed=fixedWindows.get()
                        val panels=systemWindows.get().filter{pane->workspace.systemPanels.any{it.id==pane.layoutFootprint?.id}}
                        val aspects=(fixed.map{(id,pane)->id to pane}+panels.mapNotNull{pane->pane.layoutFootprint?.id?.let{it to pane}})
                            .filter{(_,pane)->pane.sourceWidth>0 && pane.sourceHeight>0}.associate{(id,pane)->id to pane.sourceWidth.toFloat()/pane.sourceHeight}
                        val shown=fixed.map{it.first}.toSet()+aspects.keys+listOfNotNull(workspace.referencePlacement?.id?.takeIf{workspace.desktopReference})
                        val reset=try{
                            workspace.seatSupportObjects(view,handTracking.unitsPerMeter,workspace.supportSeats(shown,aspects),resize=true)
                        }catch(failure:IllegalArgumentException){
                            Log.w("Floatdesk","Support reset rejected invalid geometry",failure)
                            null
                        }
                        if(reset!=null && reset!==workspace){
                            workspace=reset
                            systemPlacements.clear()
                            store.recordLayout(workspace,"reset support")
                        }
                    }
                }
                PalmAction.PANELS -> { workspace=workspace.copy(magnifySystemPanels=!workspace.magnifySystemPanels);store.save(workspace) }
                PalmAction.REFERENCE -> { workspace=workspace.copy(desktopReference=!workspace.desktopReference);store.save(workspace) }
                PalmAction.WARP -> {
                    grabs.values.forEach { it.cancel() }
                    manipulating = false
                    followGeneration += 1
                    workspace = workspace.copy(sphericalWarp = RenderPolicy.SphericalDesktop && !workspace.sphericalWarp)
                    store.save(workspace)
                }
                PalmAction.SAVE_1,PalmAction.SAVE_2,PalmAction.SAVE_3 -> {
                    if(!manipulating && !magnifierMoving){
                        store.saveLayoutSlot(action.ordinal-PalmAction.SAVE_1.ordinal,workspace,connection.layoutMapDebug)
                        savedLayoutPreviews=store.layoutPreviews()
                    }
                }
                PalmAction.LOAD_1,PalmAction.LOAD_2,PalmAction.LOAD_3 -> {
                    if(!manipulating && !magnifierMoving)store.restoreLayoutSlot(action.ordinal-PalmAction.LOAD_1.ordinal,workspace)?.let{
                        workspace=it
                        systemPlacements.clear()
                        pointerControl.clear()
                        grabs.values.forEach{it.cancel()}
                        originGrab.clear()
                        followGeneration++
                    }
                }
                PalmAction.DIAGNOSTICS -> { workspace = workspace.copy(viewDetailDebug = !workspace.viewDetailDebug); store.save(workspace) }
                PalmAction.AIM_MAGNIFY -> {workspace=workspace.copy(aimToMagnify=!workspace.aimToMagnify);store.save(workspace)}
                PalmAction.GAZE_FOCUS -> { workspace = workspace.copy(gazeToFocus = !workspace.gazeToFocus); store.save(workspace) }
                PalmAction.MAGNIFIER -> { workspace = nextMagnifier(workspace); store.save(workspace) }
                PalmAction.MOUSE_SPACES -> { workspace = workspace.copy(cursorRemap = !workspace.cursorRemap); store.save(workspace) }
            }
        }
    }
    val palmActionNow = rememberUpdatedState(palmAction)
    fun updateManipulation(active: Boolean,movingOrigin:Boolean=false) {
        if(!active && manipulating && workspace.layoutModeActive){
            if(movingOrigin)store.recordLayout(workspace,"origin gesture")
            else recordPlacement("desktop gesture")
        }
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
        var detailPauseReason:String?=null
        val qualityGaze=QualityGazeTracker()
        data class MouseAimSurface(val pose:Pose,val width:Float,val height:Float,val mesh:MonitorMesh?,val window:MagnifiedWindow?=null)
        data class MouseAimAnchor(val target:PointerTarget,val hand:FloatArray,val eye:FloatArray,val contact:FloatArray)
        val mouseAimAnchors=mutableMapOf<String,MouseAimAnchor>()
        var lastMouseAimSide:String?=null
        var lastMouseAimAt=0L
        val hudGaze=HudGazeRegions(dev.floatdesk.client.workspace.ViewLayers.DiagnosticsDistanceM)
        var inputWasResumed=true
        var fistHeld = 0
        var panelHand: String? = null
        var lastPalmSeen = 0L
        var detailSeenAt=0L
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
            delay(if(connection.thermalPaused.value)50 else 10)
            tick += 1
            val wantEyes = context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") == android.content.pm.PackageManager.PERMISSION_GRANTED
            val resumed = connection.isRenderingActive() && current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            updateEyeTracking(wantEyes,resumed)
            if(!resumed) {
                magnification.dismiss(SystemClock.uptimeMillis());inPlaceWindow=null
                sampledHands.set(emptyList())
                if(inputWasResumed) {
                    windowGrab.cancel();windowDragRay.clear();dragProxy=null;toolbar.clear();toolbarTarget=null
                    pinchFrames.drain(SystemClock.uptimeMillis())
                    if(handInputAllowed) palmInteractionEpoch++
                    handInputAllowed=false
                    val release=mouseGestures.update(SystemClock.uptimeMillis(),false,true,emptyMap(),null)
                    release.target?.let {target->
                        target.hostPoint(connection::desktopPoint)?.let{(u,v)->
                            connection.movePointer(target.monitor,u,v,false,target.spaceKey)
                        }
                    }
                    release.scroll?.let { connection.touchpad(it.fingers,it.x,it.y) }
                    grabs.values.forEach { it.cancel() }
                    pointerControl.clear(resetAiming=true);palmPanel=null;holdingFist=false;followLock=null
                    deskSpace.get()?.let { parent ->
                        guideRenderer?.update(parent,emptyList())
                        guideRenderer?.updateRays(parent,emptyMap(),null,1f,true)
                        guideRenderer?.updateLayoutOrigin(parent,null,handTracking.unitsPerMeter){}
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
            // A detached Compose root can still be returned by SceneCore until its
            // replacement is parented. Skip that frame instead of treating it as live.
            val coordinates=runCatching{
                val scale=current.scene.perceptionSpace.transformVectorTo(Vector3.Right,root).length
                scale to perceived?.let{deskPose(current,root,it)}
            }.getOrNull() ?: continue
            val (unitsPerMeter,candidateHead)=coordinates
            if (!unitsPerMeter.isFinite() || unitsPerMeter <= 0f) continue
            handTracking.unitsPerMeter = unitsPerMeter
            handTracking.nowMs = SystemClock.uptimeMillis()
            handTracking.pinched.clear()
            displayHead.set(candidateHead?.toWorkspacePose())
            val reliable = trackingGuard.update(handTracking.nowMs, candidateHead?.takeIf { trackingAvailable }?.translation?.toArray(),
                candidateHead?.let { floatArrayOf(it.rotation.x,it.rotation.y,it.rotation.z,it.rotation.w) },unitsPerMeter)
            val head = candidateHead?.takeIf { reliable }
            if(head!=null) {
                val reference=head.toWorkspacePose()
                val usableWorkspace=workspace.repairDesktopPlacement(reference,viewHalfAngles.get(),unitsPerMeter)
                if(usableWorkspace!==workspace){
                    workspace=usableWorkspace
                    restoreViewerPending=false
                    centerOnStartup=false
                    store.viewerReference=reference
                    store.viewerUnitsPerMeter=unitsPerMeter
                    store.save(workspace)
                    store.recordLayout(workspace,"recover invalid desktop placement")
                    followLock=null
                    grabs.values.forEach{it.cancel()}
                    Log.w("Floatdesk","Recovered invalid desktop placement")
                }
                val recovery=trackingRecovery.update(handTracking.nowMs,true,reference.position,reference.rotation,unitsPerMeter)
                if(recovery!=null && !restoreViewerPending && workspace.anchorUuid==null) {
                    val prior=Pose(recovery.first,recovery.second)
                    store.rebaseLayoutHistory(prior,store.viewerUnitsPerMeter,reference,unitsPerMeter)
                    workspace=workspace.copy(viewerReference=prior,referenceUnitsPerMeter=store.viewerUnitsPerMeter).restoreViewer(reference,unitsPerMeter)
                    grabs.values.forEach { it.cancel() };pointerControl.clear()
                    manipulating=false;suppressFist=true;followLock=null
                    magnification.dismiss(handTracking.nowMs);inPlaceWindow=null
                    Log.w("Floatdesk","Recovered workspace after tracking discontinuity")
                }
                if(restoreViewerPending && !workspace.layouts[workspace.activeLayout].isNullOrEmpty()) {
                    if(workspace.anchorUuid==null)store.rebaseLayoutHistory(workspace.viewerReference ?: Pose(),
                        workspace.referenceUnitsPerMeter ?: unitsPerMeter,reference,unitsPerMeter)
                    workspace=workspace.restoreViewer(reference,unitsPerMeter)
                    if(centerOnStartup){
                        val target=Pose(transformPoint(reference,floatArrayOf(0f,0f,-workspace.farthestDistanceM*unitsPerMeter)),reference.rotation.copyOf())
                        workspace=workspace.centerDesktop(target)
                        centerOnStartup=false
                    }
                    if(!store.hasLayoutHistory()){
                        Log.i("Floatdesk","Initializing layout history, previous cursor reference=${workspace.cursorMapReference?.position?.contentToString()}")
                        workspace=workspace.copy(cursorMapReference=reference)
                        store.viewerReference=reference
                        store.viewerUnitsPerMeter=unitsPerMeter
                        store.recordLayout(workspace,"restore layout")
                    }
                    restoreViewerPending=false
                }
                store.viewerReference=reference
                store.viewerUnitsPerMeter=unitsPerMeter
                if(workspace.legacyDistances && !restoreViewerPending){
                    workspace=workspace.withViewingDistances(ViewLayers.DefaultNearestM,ViewLayers.DefaultFarthestM,reference,unitsPerMeter).copy(legacyDistances=false)
                    ViewLayers.configure(workspace.nearestDistanceM,workspace.farthestDistanceM)
                    store.save(workspace)
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
                hudGaze.hit(local,subtract(end,local),viewHalfAngles.get()[0],viewHalfAngles.get()[1],workspace.showResources && workspace.viewDetailDebug,workspace.viewDetailDebug || workspace.layoutModeActive,workspace.sphericalWarp)
            }
            val contentEyeRays=if(hudReading)emptyList() else eyeRays
            val screen=workspace.monitorAt(0)
            val currentEye=handTracking.points["VIEWER"] ?: candidateHead?.translation?.toArray()
            val desktopGuideRange=if(screen!=null && currentEye!=null){
                val localEye=viewerInMonitor(currentEye,screen.pose.position,screen.pose.rotation)
                val mesh=pointerControl.meshes[0].takeIf{workspace.sphericalWarp}
                if(mesh!=null)(mesh.innerRadius-distance(localEye,mesh.eye)).takeIf{it>.02f*unitsPerMeter}
                else if(!workspace.sphericalWarp){
                    val closest=floatArrayOf(localEye[0].coerceIn(-screen.physicalWidthM*.5f,screen.physicalWidthM*.5f),
                        localEye[1].coerceIn(-screen.physicalHeightM*.5f,screen.physicalHeightM*.5f),0f)
                    distance(localEye,closest).takeIf{it>.02f*unitsPerMeter}
                }else null
            }else null
            handTracking.desktopSphereRadius=desktopGuideRange
            val qualityTargets=if(!workspace.experimentalViewedDetail || connection.limitOptionalRendering || screen==null)emptyList() else eyeRays.mapNotNull{(origin,direction)->
                val local=viewerInMonitor(origin,screen.pose.position,screen.pose.rotation)
                val endpoint=viewerInMonitor(add(origin,direction),screen.pose.position,screen.pose.rotation)
                extendedQualityTarget(local,subtract(endpoint,local),screen.physicalWidthM,screen.physicalHeightM,pointerControl.meshes[0])
                    ?.let{connection.presentation().extendedDesktopPoint(it.first,it.second)}
            }
            val raw=if(qualityTargets.isEmpty())null else qualityTargets.map{it.first}.average().toFloat() to qualityTargets.map{it.second}.average().toFloat()
            val sharedGaze=qualityGaze.update(raw,handTracking.nowMs)
            connection.detailDebug(workspace.experimentalViewedDetail,workspace.viewDetailDebug,workspace.showResources)
            if(displayControlsOpen || head==null || manipulating || !workspace.experimentalViewedDetail || connection.limitOptionalRendering) {
                val reason=when {
                    displayControlsOpen->"Paused: Workspace open"
                    head==null->"Paused: head tracking unavailable"
                    manipulating->"Paused: desktop manipulation"
                    connection.limitOptionalRendering->"Paused: headset cooling"
                    else->"Normal full-desktop streaming"
                }
                if(detailPauseReason!=reason){detailPauseReason=reason;connection.viewedDetail(null,reason)}
            } else if(handTracking.nowMs-lastDetailAt>=50){
                detailPauseReason=null
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
            displayHead.get()?.takeIf{reliable}?.let{reference->
                magnifierViewer.set(Pose(grips["VIEWER"]?.copyOf() ?: reference.position,reference.rotation))
                if(!magnifierViewerReady)magnifierViewerReady=true
            }
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
                    if(!palmEditing)palmPanel = null
                }
                poke.clear()
            } else {
                if (panelHand != candidate.side) poke.clear()
                if (palmPanel == null) Log.i("Floatdesk", "palm panel shown side=${candidate.side} unitsPerMeter=${candidate.unitsPerMeter} pose=${candidate.pose.toRuntimePose()}")
                panelHand = candidate.side
                lastPalmSeen = SystemClock.uptimeMillis()
                val centered=candidate.copy(page=if(workspace.layoutModeActive)PalmPage.LAYOUT else PalmPage.MAIN,pose=palmPanel?.pose ?: (head ?: candidate.pose.toRuntimePose()).compose(
                    androidx.xr.runtime.math.Pose(Vector3(0f,0f,-ViewLayers.PalmMenuDistanceM*unitsPerMeter))).toWorkspacePose())
                palmPanel = centered.copy(customLayout=palmLayouts[centered.page])
                val other = if (candidate.side == "LEFT") "RIGHT" else "LEFT"
                if(!palmEditing)poke.update(centered.copy(customLayout=palmLayouts[centered.page]), fingerTips[other])?.let { palmActionNow.value(it) }
                // A platform click may arrive on release after our direct-contact press.
                if (poke.latchedContact) lastPalmAction = SystemClock.uptimeMillis()
            }
            if(palmPanel!=null && !panelEyePermissionAsked && context.checkSelfPermission("android.permission.EYE_TRACKING_FINE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                panelEyePermissionAsked=true
                eyePermission.launch("android.permission.EYE_TRACKING_FINE")
            }
            val originViewer=magnifierViewer.get()
            val originGuide=if(reliable && !displayControlsOpen && workspace.layoutModeActive)
                workspace.layoutOrigin?.let{origin->originViewer?.let{view->layoutOriginGuide(origin,view,
                    workspace.nearestDistanceM*unitsPerMeter,workspace.farthestDistanceM*unitsPerMeter,workspace.sphericalWarp)}}else null
            val pendingOrigin=originDownAt
            if(pendingOrigin!=null){
                if(originGuide==null || manipulating || handTracking.nowMs-pendingOrigin>180L)originDownAt=null
                else {
                    val side=handTracking.pinched.singleOrNull()
                    val hand=side?.let{grips[it]}
                    val origin=workspace.layoutOrigin
                    if(side!=null && hand!=null && origin!=null && originViewer!=null){
                        originDownAt=null
                        originGrab.begin(side,handTracking.nowMs,origin,originViewer,hand,unitsPerMeter)
                        if(originGrab.active){grabs.values.forEach{it.cancel()};pointerControl.clear();updateManipulation(true)}
                    }
                }
            }
            if(originGrab.active){
                if(originGuide==null || originViewer==null)originGrab.clear()
                else originGrab.advance(originViewer,grips,handTracking.pinched,handTracking.nowMs)
                    ?.let{origin->
                        val prior=workspace.layoutOrigin
                        if(prior!=null && distance(prior.position,origin.position)>.001f*unitsPerMeter){
                            systemPlacements.replaceAll{_,record->rebaseSupportingPlacement(record,prior,origin,unitsPerMeter)}
                            workspace=workspace.rebaseLayoutOrigin(origin,unitsPerMeter)
                        }
                    }
                if(!originGrab.active){updateManipulation(false,movingOrigin=true);store.save(workspace)}
            }
            guideRenderer?.updateLayoutOrigin(root,originGuide,unitsPerMeter,desktopGuideRange){event->
                val side=event.pointerType.toString()
                when(event.action){
                    androidx.xr.scenecore.InputEvent.Action.DOWN->{
                        if(!workspace.layoutModeActive || displayControlsOpen || manipulating)return@updateLayoutOrigin
                        val origin=workspace.layoutOrigin ?: return@updateLayoutOrigin
                        val view=magnifierViewer.get() ?: return@updateLayoutOrigin
                        val handSide=side.takeIf{it=="LEFT" || it=="RIGHT"} ?: handTracking.pinched.singleOrNull()
                        val hand=handSide?.let{grips[it]}
                        if(handSide==null || hand==null){
                            originDownAt=handTracking.nowMs
                            return@updateLayoutOrigin
                        }
                        originDownAt=null
                        originGrab.begin(handSide,handTracking.nowMs,origin,view,hand,handTracking.unitsPerMeter)
                        if(originGrab.active){grabs.values.forEach{it.cancel()};pointerControl.clear();updateManipulation(true)}
                    }
                    androidx.xr.scenecore.InputEvent.Action.UP, androidx.xr.scenecore.InputEvent.Action.CANCEL->{
                        originDownAt=null
                        if(originGrab.active){
                            if(side=="LEFT" || side=="RIGHT")originGrab.end(side) else originGrab.clear()
                            if(!originGrab.active){updateManipulation(false,movingOrigin=true);store.save(workspace)}
                        }
                    }
                    else->Unit
                }
            }
            if(workspace.layoutModeActive && !originGrab.active) for ((index, grab) in grabs) {
                val wasBusy = grab.busy
                val file = workspace
                val placement = file.monitorAt(index) ?: continue
                grab.advance(handTracking, placement)?.let {
                    workspace=file.monitorPlaced(index,it.copy(freePose=true),store,false)
                }
                if (wasBusy && !grab.busy) {
                    updateManipulation(false)
                    store.save(workspace)
                }
            }
            val hoverHands = pointerControl.hands(grips.keys,handTracking.nowMs,aimPoses).filterValues {connection.desktopPoint(it.monitor,it.u,it.v)!=null}
            // Preview and acquisition share current validated surface UVs. Do not
            // require a second native DOWN after both physical pinches are already down.
            if(workspace.layoutModeActive && !originGrab.active && !(toolbarTarget!=null && (toolbarHovered || snapGridHovered || snapGridDragging)) && listOf("LEFT","RIGHT").none{it in magnifierHands} && listOf("LEFT","RIGHT").all {it in handTracking.pinched} ) {
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
            val panel=palmPanel.takeUnless{palmEditing}
            val focusedButton=panel?.buttons?.firstOrNull{it.action==panelFocused}
            val palmTip=if(panel!=null && focusedButton!=null)
                palmTooltipPlacement(panel,focusedButton,palmTooltip(focusedButton.action,workspace,latchedFollow)) else null
            val panelHits=if(panel==null)emptyList()else contentEyeRays.mapNotNull{(origin,direction)->
                if(focusedButton!=null && palmTip?.contains(origin,direction)==true)
                    floatArrayOf(focusedButton.x+focusedButton.w*.5f,focusedButton.y+focusedButton.h*.5f)
                else palmRayPoint(panel,origin,direction)
            }
            val panelGaze=if(panelHits.isEmpty()) null else floatArrayOf(panelHits.map {it[0]}.average().toFloat(),panelHits.map {it[1]}.average().toFloat())
            val activate=panelInput.update(handTracking.nowMs,panel?.side,panelGaze,panel?.let { mouseSamples[it.side]?.middleMetres },
                panel?.buttons.orEmpty().map { PanelButtonArea(it.action.ordinal,it.x,it.y,it.w,it.h) })
            panelFocused=panelInput.focused?.let { PalmAction.entries.getOrNull(it) }
            if(activate!=null) PalmAction.entries.getOrNull(activate)?.let { palmActionNow.value(it) }
            if(panel!=null && tick%50==0) Log.i("Floatdesk","panel gaze resources=${gazeEyes.size} tracked=${gazeEyes.count { it.state.value.trackingState==TrackingState.TRACKING }} open=${gazeEyes.count { it.state.value.isOpen }} device=${deviceState?.trackingState} rays=${eyeRays.size} hits=${panelHits.size}")
            fun magnifiedTarget(uv:Pair<Float,Float>,windowId:Long?,key:String):PointerTarget? {
                val win=paneSource(key,windowId) ?: return null
                val bounds=connection.workspaceInfo.value?.bounds() ?: return null
                val sourceU=(win.x-bounds[0]+uv.first*win.width)/bounds[2]
                val sourceV=(win.y-bounds[1]+uv.second*win.height)/bounds[3]
                val mapped=connection.presentation().extendedDisplayPoint(sourceU,sourceV) ?: uv
                return PointerTarget(0,mapped.first,mapped.second,floatArrayOf(0f,0f,0f),spaceKey=key,sourcePoint=sourceU.coerceIn(0f,1f) to sourceV.coerceIn(0f,1f))
            }
            val desktopHands=hoverHands.filterKeys{it !in magnifierHands}
            val panelHands=magnifierHands.mapNotNull { (side,uv) ->
                val window=magnifierHands.window(side)
                if(window==-1L)return@mapNotNull side to PointerTarget(0,uv.first,uv.second,floatArrayOf(0f,0f,0f),"reference")
                val owner=magnifierHands.owner(side) ?: return@mapNotNull null
                val key=when {
                    owner.startsWith("system:")->"shell:$window"
                    owner.startsWith("popup:")->"menu:$window"
                    else->owner
                }
                magnifiedTarget(uv,window,key)?.let { side to it }
            }.toMap()
            val contextHands=if(workspace.handMouseActive)desktopHands+panelHands.filterValues{it.spaceKey!="reference"} else emptyMap()
            val nativeFocusGazeEnabled=(workspace.focusMode || workspace.windowGrabMode) && pointerControl.systemGazeEnabled
            val nativeFocusGaze=if(nativeFocusGazeEnabled && !hudReading)pointerControl.systemGaze() else null
            val gazeTargets=if(reliable && (nativeFocusGazeEnabled || connection.workspaceInfo.value?.windowMagnify==true || workspace.gazeToFocus)) contentEyeRays.mapNotNull { (origin,direction) -> gazePointerTarget(origin,direction,workspace,pointerControl.meshes) } else emptyList()
            // Average binocular contacts on the same monitor; a valid single eye can still aim.
            val rawDesktopGazeTarget = gazeTargets.firstOrNull()?.let { first ->
                val same = gazeTargets.filter { it.monitor == first.monitor }
                first.copy(u=same.map { it.u }.average().toFloat(),v=same.map { it.v }.average().toFloat())
            }
            val visibleMagnifiers=buildList {
                if(workspace.desktopReference && referenceReady)referencePane?.let{add("reference" to it)}
                if(workspace.magnifyWindow)fixedRuntime.forEach{(key,pane)->if(fixedReady[key]==true && fixedDisplayed[key]==pane.id)add(key to pane)}
                if(sceneActive && !displayControlsOpen)popupWindows.get().forEach{add("menu:${it.id}" to it)}
                if(magnifierSurfacesAllowed(workspace.magnifySystemPanels,sceneActive,displayControlsOpen))systemRuntime.forEach{(id,pane)->if(systemReady[id]==true)add("shell:$id" to pane)}
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
            val magnifierGaze=paneGaze?.takeUnless{it.third=="reference"}?.let{(window,uv,key)->magnifiedTarget(magnifierContentPoint(window,uv),window.id,key)}
            val rawGazeTarget=if(toolbarGaze)null else if(gazeBlockedByPane)magnifierGaze else rawDesktopGazeTarget
            val gazeTarget=if(contextHands.isEmpty() && !hudReading) gazeStabilizer.update(rawGazeTarget?.takeIf {connection.desktopPoint(it.monitor,it.u,it.v)!=null},handTracking.nowMs) else { gazeStabilizer.reset(); null }
            val monitorGrab=originGrab.active || grabs.values.any { it.engaged }
            val nativeManipulation=pointerControl.nativeManipulating
            val choice = pointerControl.choose(nativeFocusGazeEnabled,gazeTarget ?: nativeFocusGaze?.takeUnless{gazeBlockedByPane},contextHands,monitorGrab || nativeManipulation,handTracking.nowMs,previewHands=desktopHands.takeIf{workspace.layoutModeActive}.orEmpty())
            if (choice.mode != lastPointerMode) {
                Log.i("Floatdesk","mouse source=${choice.mode} hands=${hoverHands.mapValues {it.value.monitor}} eyes=${gazeTargets.size} native=$nativeManipulation palm=${palmPanel!=null}")
                lastPointerMode = choice.mode
            }
            dimPointer = workspace.handMouseActive && choice.mode == PointerMode.HAND
            fun mouseSurface(target:PointerTarget):MouseAimSurface?{
                if(target.spaceKey=="desktop")return workspace.monitorAt(target.monitor)?.let{
                    MouseAimSurface(it.pose,it.physicalWidthM,it.physicalHeightM,pointerControl.meshes[target.monitor])
                }
                return visibleMagnifiers.firstOrNull{it.first==target.spaceKey}?.second?.let{
                    MouseAimSurface(it.pose,it.width,it.height,it.mesh,it)
                }
            }
            fun mouseWristInDesk(side:String?):FloatArray? =
                aimPoses[side]?.position?.let{point->
                    deskPose(current,root,androidx.xr.runtime.math.Pose(Vector3(point[0],point[1],point[2])))
                        .translation.toArray()
                } ?: grips[side]
            fun captureMouseAnchor(side:String,target:PointerTarget):MouseAimAnchor?{
                val surface=mouseSurface(target) ?: return null
                val hand=mouseWristInDesk(side) ?: return null
                val eye=magnifierViewer.get()?.position ?: return null
                val uv=if(surface.window==null)target.u to target.v else magnifierHands[side]?.let{
                    val rect=magnifierContentRect(surface.window)
                    (rect.left+it.first*(rect.right-rect.left)) to (rect.top+it.second*(rect.bottom-rect.top))
                } ?: return null
                val local=pointerSurfacePoint(target.copy(u=uv.first,v=uv.second),surface.width,surface.height,surface.mesh)
                return MouseAimAnchor(target,hand.copyOf(),eye.copyOf(),transformPoint(surface.pose,local))
            }
            var mouseBeamContact:FloatArray?=null
            val mouseAim=if(workspace.handMouseActive){
                if(!mouseGestures.engaged){
                    if(handTracking.nowMs-lastMouseAimAt>180L)mouseAimAnchors.clear()
                    val selected=contextHands.entries.takeIf{choice.mode==PointerMode.HAND}?.maxByOrNull{(side,contact)->
                        if(contact.spaceKey=="desktop")pointerControl.contactTime(side) ?: 0L else magnifierHands.contactTime(side) ?: 0L
                    }
                    val native=selected?.value
                    val side=selected?.key
                    if(side!=null && native!=null){
                        captureMouseAnchor(side,native)?.let{anchor->
                            mouseAimAnchors.clear()
                            mouseAimAnchors[side]=anchor
                            lastMouseAimSide=side
                            lastMouseAimAt=handTracking.nowMs
                        }
                    }
                    native
                }else{
                    val side=lastMouseAimSide
                    val anchor=mouseAimAnchors[side]
                    val native=lockedMouseContact(mouseGestures.heldTarget,contextHands[side])
                    val eventAt=if(native?.spaceKey=="desktop")pointerControl.contactTime(side) else magnifierHands.contactTime(side)
                    if(native!=null && eventAt!=null && handTracking.nowMs-eventAt<=85L &&
                        movedMouseContact(anchor?.target,native)){
                        if(side!=null)captureMouseAnchor(side,native)?.let{
                            mouseBeamContact=it.contact
                            if(eventAt>lastMouseAimAt){
                                mouseAimAnchors[side]=it
                                lastMouseAimAt=eventAt
                            }
                        }
                        native
                    }else{
                        val hand=mouseWristInDesk(side)
                        if(anchor==null || hand==null)null else mouseSurface(anchor.target)?.let{surface->
                            val gain=distance(anchor.eye,anchor.contact)/distance(anchor.eye,anchor.hand).coerceAtLeast(.1f*unitsPerMeter)
                            val contact=FloatArray(3){anchor.contact[it]+(hand[it]-anchor.hand[it])*gain}
                            val point=surfaceInputHit(anchor.eye to subtract(contact,anchor.eye),surface.pose,surface.width,surface.height,surface.mesh)
                                ?: return@let null
                            val flat=if(surface.mesh==null)point else surface.mesh.unproject(point) ?: return@let null
                            val u=flat[0]/surface.width+.5f;val v=.5f-flat[1]/surface.height
                            mouseBeamContact=transformPoint(surface.pose,point)
                            val target=anchor.target
                            val window=surface.window
                            if(window==null)target.copy(u=u,v=v,local=point)
                            else magnifiedTarget(magnifierContentPoint(window,u to v),window.id,target.spaceKey)
                        }
                    }
                }
            }else{mouseAimAnchors.clear();null}
            val focusInfo=connection.workspaceInfo.value
            connection.typingQuietMs=workspace.typingQuietMs
            if(!windowGrab.engaged && (workspace.gazeToFocus || focusInfo?.windowMagnify==true))connection.pollTyping(handTracking.nowMs)
            typingLocked=connection.typingLocked(handTracking.nowMs)
            val paneOwner=paneGaze?.first?.id?.let{connection.windowCaptures.owner(it)}
            val popupGaze=paneOwner!=null && paneOwner!=paneGaze?.first?.id
            val shellGaze=paneOwner?.let{id->focusInfo?.systemWindows?.any{it.id==id}}==true
            val gridSurface=snapGridSurface.get()
            val gridGaze=gridSurface!=null && contentEyeRays.any{(origin,direction)->gridSurface.contains(origin,direction)}
            val gridAim=gridSurface!=null && desktopHands.any{(side,hit)->
                val start=grips[side] ?: return@any false
                val desktop=workspace.monitorAt(hit.monitor) ?: return@any false
                val contact=transformPoint(desktop.pose,pointerSurfacePoint(hit,desktop.physicalWidthM,desktop.physicalHeightM,pointerControl.meshes[hit.monitor]))
                gridSurface.contains(start,subtract(contact,start))
            }
            if(snapGridExpanded){
                val now=handTracking.nowMs
                snapGridPresence.update(now,snapGridHovered || snapGridDragging || gridGaze || gridAim)
                if(snapGridPresence.expired(now))snapGridExpanded=false
            }else snapGridPresence.clear()
            val toolbarEngaged=toolbarTarget!=null && (toolbarHovered || snapGridHovered || snapGridDragging || snapGridExpanded || toolbarGaze)
            val autoFocusBlocked=workspace.layoutModeActive || connection.thermalPaused.value || windowGrab.engaged || shellGaze || hudReading || typingLocked || magnifierMoving || toolbarEngaged || !reliable || !resumed || displayControlsOpen || palmPanel!=null ||
                choice.mode.reservesDesktop || monitorGrab || nativeManipulation || mouseGestures.engaged || connection.focusBusy.value || focusInfo?.gazeFocus!=true
            val autoFocusWindow=if(!autoFocusBlocked && workspace.gazeToFocus)paneOwner?.takeIf{id->focusInfo?.windows?.any{it.id==id}==true} ?: rawGazeTarget?.takeIf{it.monitor==0}?.let{hit->
                connection.desktopPoint(hit.monitor,hit.u,hit.v)?.let{(u,v)->focusInfo?.hover(u,v)?.window?.windowId}
            }else null
            gazeWindowFocus.update(handTracking.nowMs,workspace.gazeToFocus,autoFocusBlocked,autoFocusWindow)?.let{connection.requestGazeFocus(it)}
            if(autoFocusBlocked || !workspace.gazeToFocus)connection.cancelGazeFocus()
            fun windowAtMagnificationContact(hit:PointerTarget):Long?{
                val point=connection.desktopPoint(hit.monitor,hit.u,hit.v)
                return point?.let{(u,v)->focusInfo?.hover(u,v)?.window?.windowId}
            }
            val gazeMagnifyTarget=if(!gazeBlockedByPane && !toolbarGaze)
                rawDesktopGazeTarget?.let(::windowAtMagnificationContact) else null
            val aimingContacts=if(workspace.aimToMagnify && !toolbarEngaged)
                pointerControl.focusHands(desktopHands)+panelHands.filterValues{it.spaceKey=="reference"} else emptyMap()
            val handMagnifyTarget=aimingContacts.entries.maxByOrNull{(side,hit)->
                if(hit.spaceKey=="reference")magnifierHands.contactTime(side) ?: 0L else pointerControl.contactTime(side) ?: 0L
            }?.value?.let(::windowAtMagnificationContact)
            val magnifyTarget=(handMagnifyTarget ?: gazeMagnifyTarget.takeIf{workspace.gazeToFocus})?.let{focusInfo?.magnificationOwner(it) ?: it}
            val detailTarget=(paneOwner?.takeIf{id->focusInfo?.windows?.any{it.id==id}==true} ?: gazeMagnifyTarget).takeIf{reliable && resumed && !displayControlsOpen && palmPanel==null &&
                !hudReading && !monitorGrab && !nativeManipulation && !windowGrab.engaged && !magnifierMoving}
            val canPatchByGaze=!workspace.layoutModeActive && !connection.thermalPaused.value && focusInfo?.windowMagnify==true && resumed && !displayControlsOpen && !connection.reloading.value
            if(detailTarget==gazeDetailWindow && detailTarget!=null)detailSeenAt=handTracking.nowMs
            gazeDetail.update(handTracking.nowMs,canPatchByGaze,false,detailTarget)?.let{
                gazeDetailWindow=it;detailSeenAt=handTracking.nowMs
            }
            if(!canPatchByGaze || handTracking.nowMs-detailSeenAt>500)gazeDetailWindow=null
            val currentMagnifier=inPlaceWindow
            val magnifierChanged=currentMagnifier?.let{id->focusInfo?.windows?.none{it.id==id} ?: true}==true
            val magnifyBlocked=(typingLocked && currentMagnifier==null) || magnifierChanged || (currentMagnifier==null && (choice.mode.reservesDesktop || mouseGestures.engaged)) || !resumed || (!magnification.pinned && (!reliable || displayControlsOpen || palmPanel!=null || monitorGrab || nativeManipulation)) || magnifierFailed || focusInfo?.windowMagnify!=true
            val aimingAtMagnification=workspace.aimToMagnify && currentMagnifier!=null &&
                magnifierHands.keys.any{side->
                    val id=magnifierHands.window(side)
                    id==currentMagnifier || id?.let{focusInfo?.magnificationOwner(it)}==currentMagnifier
                }
            val activeMagnifier=if(workspace.layoutModeActive)currentMagnifier else magnification.update(handTracking.nowMs,
                workspace.magnifyWindow && (workspace.gazeToFocus || workspace.aimToMagnify),magnifyBlocked,magnifyTarget,
                aimingAtMagnification || magnifyTarget==currentMagnifier,magnifierReady,
                windowGrab.engaged || popupGaze || shellGaze || hudReading || mouseGestures.engaged || typingLocked || magnifierMoving || toolbarEngaged,
                workspace.aimToMagnify || workspace.gazeToFocus && eyeRays.isNotEmpty(),retain=true)
            if(activeMagnifier!=currentMagnifier){
                inPlaceWindow=activeMagnifier
                if(activeMagnifier!=null && workspace.gazeToFocus && !autoFocusBlocked)connection.requestGazeFocus(activeMagnifier)
                magnifierReady=false
                magnifierHands.clear()
            }
            if(activeMagnifier!=null)lastFixedWindow=activeMagnifier
            if(lastFixedWindow!=null && focusInfo!=null && focusInfo.windows.none{it.id==lastFixedWindow})lastFixedWindow=null
            magnifierFailed=false
            val referenceFocusEnabled=workspace.focusMode || workspace.windowGrabMode
            val referenceFocus=if(referenceFocusEnabled)panelHands.filterValues{it.spaceKey=="reference"} else emptyMap()
            val referenceGaze=paneGaze?.takeIf{referenceFocusEnabled && it.third=="reference"}?.second?.let{uv->PointerTarget(0,uv.first,uv.second,floatArrayOf(0f,0f,0f),"reference")}
            val liveFocusContacts=focusAimContacts(referenceFocus,if(nativeFocusGazeEnabled && !hudReading)referenceGaze else null)
            val dragPoses=grips.filterKeys{it=="LEFT" || it=="RIGHT"}.mapValues{(_,point)->HandAimPose(point,floatArrayOf(0f,0f,0f,1f))}
            val focusContacts=if(windowGrab.engaged)windowDragRay.point(dragPoses)?.let{mapOf(it)} ?: liveFocusContacts else liveFocusContacts
            val focusHover=if((workspace.focusMode || workspace.windowGrabMode) && focusInfo!=null)focusContacts.mapNotNull {(side,hit)->
                if(hit.monitor!=0)return@mapNotNull null
                connection.desktopPoint(hit.monitor,hit.u,hit.v)?.let{(u,v)->focusInfo.hover(u,v)}?.let{side to it}
            }.toMap() else emptyMap()
            // Gaze highlights only under system eye-and-hand aiming; with hand-only aiming the hand must hover.
            val eyeAiming=pointerControl.systemGazeEnabled
            val magnifierHover=(if(eyeAiming)paneGaze?.third?.takeIf{key->fixedDisplayed.containsKey(key)} else null)
                ?: magnifierPointerHover.entries.firstOrNull{(key,hover)->workspace.fixedMagnifiers.any{record->record.id==key} && (hover.first || eyeAiming && hover.second)}?.key
            fun windowAt(hit:PointerTarget?)=hit?.takeIf{it.monitor==0}?.let{connection.desktopPoint(it.monitor,it.u,it.v)}?.let{(u,v)->focusInfo?.hover(u,v)?.window?.windowId}
            val systemGaze=if(hudReading)null else pointerControl.systemGaze()
            val childHover=paneGaze?.takeIf{it.third.startsWith("menu:")}?.first?.id
                ?: panelHands.values.firstNotNullOfOrNull{hit->hit.spaceKey.removePrefix("menu:")
                    .takeIf{hit.spaceKey.startsWith("menu:")}?.toLongOrNull()}
            val windowHover=childHover?.takeIf{id->focusInfo?.windows?.any{it.id==id}==true} ?: (if(!eyeAiming || gazeBlockedByPane)null else windowAt(systemGaze) ?: windowAt(rawDesktopGazeTarget))
                ?: hoverHands.filterKeys{it !in magnifierHands}.values.firstNotNullOfOrNull{windowAt(it)}
            val viewerPoint=handTracking.points["VIEWER"] ?: handTracking.points["HEAD"]
            val stripRays=contentEyeRays+listOfNotNull(systemGaze?.let{g->
                val placement=workspace.monitorAt(g.monitor)
                if(placement==null || viewerPoint==null)null else {
                    val point=transformPoint(placement.pose,pointerSurfacePoint(g,placement.physicalWidthM,placement.physicalHeightM,pointerControl.meshes[g.monitor]))
                    viewerPoint to subtract(point,viewerPoint)
                }
            })
            val openStrip=openToolbarStrip;val litStrip=toolbarStripFor(toolbar.highlight)
            val gazeStrip=when{
                openStrip!=null && stripRays.any{(o,d)->toolbarPanels.get().any{it.contains(o,d)}}->toolbar.open
                litStrip!=null && stripRays.any{(o,d)->litStrip.containsMarker(o,d)}->toolbar.highlight
                else->null
            }
            if(workspace.layoutModeActive)toolbar.clear() else toolbar.update(handTracking.nowMs,windowHover?.takeIf{it==childHover}?.let{ToolbarTarget.Window(it)} ?: magnifierHover?.let{ToolbarTarget.Magnifier(it)} ?: windowHover?.let{ToolbarTarget.Window(it)},gazeStrip,toolbarEngaged,
                windowGrab.engaged || magnifierMoving || monitorGrab || nativeManipulation || displayControlsOpen || palmPanel!=null || !sceneActive || !resumed)
            if(toolbar.open!=toolbarTarget){
                val opened=toolbar.open
                openToolbarStrip=toolbarStripFor(opened)
                openToolbarWindow=when(opened){
                    is ToolbarTarget.Window->opened.id
                    is ToolbarTarget.Magnifier->fixedDisplayed[opened.key]
                    null->null
                }
                toolbarTarget=opened
            }
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
            val paneGrabPreview=workspace.layoutModeActive && magnifierHands.owner("LEFT")?.let{it==magnifierHands.owner("RIGHT")}==true
            val hardInputBlock=connection.thermalPaused.value || workspace.layoutModeActive || magnifierMoving || displayControlsOpen || palmPanel!=null || palmEditing
            val gestureBlocked=hardInputBlock || paneGrabPreview || toolbarEngaged || choice.mode.reservesDesktop
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
                    val side=windowGrab.aimSide;val handSide=windowGrab.handSide;val hit=focusContacts[side];val hand=dragPoses[handSide];val reference=referencePane
                    val desktop=workspace.monitorAt(0)?.let{base->reference?.let{base.copy(pose=it.pose,physicalWidthM=it.width,physicalHeightM=it.height)}}
                    val source=windowGrab.source;val b=focusInfo?.bounds();val viewer=magnifierViewer.get()
                    if(side!=null && handSide!=null && hit!=null && hand!=null && desktop!=null && source!=null && b!=null && viewer!=null)windowDragRay.begin(handSide,hand,hit,desktop,reference?.mesh,viewer,source,b,handTracking.unitsPerMeter,side)
                }else windowDragRay.clear()
                dragProxy=windowDragRay.pane
                grabUpdate.move?.let{connection.windowAction(it.id,if(it.width!=null)"geometry" else "move",it)}
                val selected=focusGestures.update(workspace.focusMode,
                    focusGate.isNotEmpty(),samples,focusHover).takeUnless{grabUpdate.move!=null || windowGrab.preview!=null}
                if(selected?.resetDesktop==true)resetDesk()
                else if(selected!=null)requestFocus(selected)
                val mouseEngaged=mouseGestures.engaged
                val supportAim=mouseAim?.sourcePoint!=null
                val mouseBlocked=if(supportAim)hardInputBlock || paneGrabPreview || toolbarHovered || snapGridDragging else gestureBlocked
                val mouse=mouseGestures.update(frame.at,workspace.handMouseActive,
                    hardInputBlock || !mouseEngaged && (mouseBlocked || uncertain || referenceFocus.isNotEmpty() && mouseAim==null),
                    if(mouseEngaged)samples else samples.filterKeys{it !in referenceFocus},mouseAim)
                mouse.scroll?.let {
                    if(it.fingers==0) Log.i("Floatdesk","mouse scroll released at=${frame.at}")
                    connection.touchpad(it.fingers,if(workspace.reverseHandScroll)1f-it.x else it.x,if(workspace.reverseHandScroll)1f-it.y else it.y)
                }
                if(mouse.dragging != lastMouseDragging) {
                    Log.i("Floatdesk","mouse drag=${mouse.dragging}")
                    lastMouseDragging=mouse.dragging
                }
                mouse.target?.let {target->target.hostPoint(connection::desktopPoint)?.let {(u,v)->
                    connection.movePointer(target.monitor,u,v,mouse.dragging,target.spaceKey)
                }}
                mouse.click?.let {
                    if(it.button==3) clickView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    Log.i("Floatdesk","mouse click button=${it.button}")
                    it.target.hostPoint(connection::desktopPoint)?.let {(u,v)->connection.clickPointer(it.target.monitor,u,v,it.button)}
                }
            }
            handTracking.pointerHeld=mouseGestures.engaged || windowGrab.engaged
            val previews = if (workspace.layoutModeActive && !monitorGrab && !nativeManipulation && !displayControlsOpen && hoverHands.keys.none{it in magnifierHands}) hoverHands.values.groupBy { it.monitor }.mapNotNull { (index,points) ->
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
            val keyLabel=connection.typingLabel(handTracking.nowMs)
            for((index,placement) in workspace.layouts[workspace.activeLayout].orEmpty().withIndex()) {
                val segments=ArrayList<Float>();val colors=ArrayList<Float>()
                var keyCue:dev.floatdesk.client.media.VideoKeyLabel?=null
                if(index==0 && keyLabel!=null && focusInfo!=null){
                    val typed=focusInfo.windows.firstOrNull{it.id==connection.typingWindow}
                    val b=focusInfo.bounds()
                    if(typed!=null && b!=null){
                        val rect=FocusRect((typed.x-b[0]).toFloat()/b[2],(typed.y-b[1]).toFloat()/b[3],(typed.x+typed.width-b[0]).toFloat()/b[2],(typed.y+typed.height-b[1]).toFloat()/b[3])
                        focusInfo.visibleRects(rect,workspace.screenSpacing).maxByOrNull{visible->
                            (visible.right-visible.left)*(visible.bottom-visible.top)
                        }?.let{keyCue=dev.floatdesk.client.media.VideoKeyLabel(keyLabel,it)}
                    }
                }
                if(index==0)connection.cursorWires["desktop"]?.let{wires->segments.addAll(wires.toList());repeat(wires.size/4){colors.addAll(listOf(.6f,.85f,1f))}}
                connection.aimOverlay(index,dev.floatdesk.client.media.VideoAim(segments.toFloatArray(),floatArrayOf(),maxOf(2f,placement.pixelWidth*.001f),.85f,colors.toFloatArray(),keyCue))
            }
            guideRenderer?.let { renderer ->
                runCatching {
                    val guides=grabs.mapNotNull { (index,grab) -> workspace.monitorAt(index)?.let {grab.displayGuide(it,pointerControl.meshes[index])} ?: previews[index] } + handTracking.paneGuides.values
                    renderer.update(root,guides,handTracking.desktopSphereRadius)
                    val location=connection.cursorLocation
                    val ownerPane=visibleMagnifiers.firstOrNull{it.first==location?.key}?.second
                    val pointer=location?.let{location->
                        val source=if(location.key=="desktop" || location.key=="reference")
                            focusInfo?.bounds()?.let{dev.floatdesk.client.net.DesktopWindow(0,it[0],it[1],it[2],it[3])}
                        else ownerPane?.let{paneSource(location.key,it.id)}
                        val uv=source?.let{cursorSourceUv(location,it.x.toFloat(),it.y.toFloat(),it.width.toFloat(),it.height.toFloat())}
                        if(uv==null)null
                        else if(location.key=="desktop"){
                            workspace.monitorAt(0)?.let{pane->
                                val point=connection.presentation().extendedDisplayPoint(uv.first,uv.second) ?: return@let null
                                transformPoint(pane.pose,pointerSurfacePoint(PointerTarget(0,point.first,point.second,FloatArray(3)),
                                    pane.physicalWidthM,pane.physicalHeightM,pointerControl.meshes[0]))
                            }
                        }else ownerPane?.let{pane->
                            val point=if(location.key=="reference")
                                connection.presentation().extendedDisplayPoint(uv.first,uv.second) ?: return@let null
                            else uv
                            magnifierCursorPoint(pane,point.first,point.second)
                        }
                    }
                    val pointerViewer=magnifierViewer.get()
                    val ownerRange=ownerPane?.let{pane->pointerViewer?.let{eye->
                        // Native surface ordering can also use the entity's origin.
                        minOf(paneFrontRadius(pane,eye),distance(pane.pose.position,eye.position))
                    }}
                    renderer.updatePointer(root,pointer,pointerViewer?.position,handTracking.unitsPerMeter,
                        handTracking.desktopSphereRadius,ownerRange)
                    val pulse=.5f+.5f*kotlin.math.sin(SystemClock.uptimeMillis()%2400L/2400f*2f*Math.PI.toFloat())
                    renderer.updateHighlight(root,highlightStrip?.pose?.position,highlightStrip?.pose?.rotation ?: floatArrayOf(0f,0f,0f,1f),highlightStrip?.cell ?: 0f,pulse)
                    val draw: ()->Unit = {
                        val contactsNow=pointerControl.hands(grips.keys,SystemClock.uptimeMillis(),aimPoses).filterValues {connection.desktopPoint(it.monitor,it.u,it.v)!=null}
                        val show=(!workspace.layoutModeActive || handPreviewMonitor(contactsNow)==null && handTracking.paneGuides.isEmpty()) &&
                            (palmPanel==null || workspace.layoutModeActive) && !monitorGrab && !nativeManipulation
                        val inToolbar=toolbarHovered || snapGridHovered
                        val heldAimSide=lastMouseAimSide.takeIf{workspace.handMouseActive && mouseGestures.engaged}
                        val aimingHands=if(show)(contactsNow.keys+magnifierHands.keys+listOfNotNull(heldAimSide)) else emptySet()
                        val rays=aimingHands.mapNotNull{side->
                            val origin=grips[side] ?: return@mapNotNull null
                            val owner=magnifierHands.owner(side)
                            val id=magnifierHands.window(side)
                            val key=when{
                                owner?.startsWith("system:")==true->"shell:$id"
                                owner?.startsWith("popup:")==true->"menu:$id"
                                else->owner
                            }
                            val pane=visibleMagnifiers.firstOrNull{it.first==key}?.second
                            val uv=magnifierHands[side]
                            val contact=if(side==heldAimSide){
                                mouseBeamContact ?: return@mapNotNull null
                            }else if(pane!=null && uv!=null){
                                val flat=floatArrayOf((uv.first-.5f)*pane.width,(.5f-uv.second)*pane.height,0f)
                                transformPoint(pane.pose,pane.mesh?.project(flat,onTriangles=true) ?: flat)
                            }else{
                                val hit=contactsNow[side] ?: return@mapNotNull null
                                if(inToolbar && pointerControl.departed(side))return@mapNotNull null
                                val placement=workspace.monitorAt(hit.monitor) ?: return@mapNotNull null
                                transformPoint(placement.pose,pointerSurfacePoint(hit,placement.physicalWidthM,
                                    placement.physicalHeightM,pointerControl.meshes[hit.monitor]))
                            }
                            side to (origin to visibleGrabContact(contact,handTracking.points["VIEWER"] ?: handTracking.points["HEAD"],handTracking.unitsPerMeter))
                        }.toMap()
                        renderer.updateRays(root,rays,handTracking.points["VIEWER"] ?: handTracking.points["HEAD"],handTracking.unitsPerMeter,true,handTracking.desktopSphereRadius)
                    }
                    redrawAim.set(draw)
                     }.onFailure {
                    Log.e("Floatdesk", "Could not update grab guides", it)
                    guideRenderer = null
                    renderer.close()
                }
            }
            val wantFollow = !restoreViewerPending && !workspace.layoutModeActive && !manipulating && head != null && (latchedFollow || (holdingFist && !suppressFist))
            if (!wantFollow) {
                if (followLock != null) {
                    store.save(workspace)
                    followLock = null
                }
            } else if (followLock == null) {
                followLock = FollowLock.capture(head,workspace)
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
            val reconciled=workspace.reconcileMonitors(monitors.map { Triple(it.name,it.width,it.height) })
            val first=reconciled.monitorAt(0)
            val savedCenter=if(first!=null && (first.viewCenterU!=.5f || first.viewCenterV!=.5f))reconciled.layoutOrigin ?: first.pose else first?.pose
            val next=reconciled.copy(layoutOrigin=reconciled.layoutOrigin ?: first?.pose,desktopHome=reconciled.desktopHome ?: savedCenter)
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
                    workspace = it
                    followGeneration += 1
                },
                monitors = monitors,
                store = store,
                density = density,
                pixelDensity = pixelDensity,
                palmPanel = palmEdit?.let{it.panel.copy(customLayout=it.draft)} ?: palmPanel,
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
                savedLayoutPreviews=savedLayoutPreviews,
                palmEditing=palmEditing,
                onPalmDraft={draft->palmEdit=palmEdit?.copy(draft=draft)},
                onPalmEdited={page,layout->
                    palmEdit=null
                    if(layout!=null){
                        palmLayouts[page]=layout
                        palmPreferences.edit().putString(page.name,layout.encode()).apply()
                        palmPanel=palmPanel?.copy(customLayout=layout)
                    }
                },

            )
        }
        key(sceneEpoch){Subspace { desk() }}
        val magnifierPatch=(inPlaceWindow ?: lastFixedWindow)?.let{connection.workspaceInfo.value?.magnificationSource(it) ?: it}
        val detailsVisible=sceneActive && !displayControlsOpen && !connection.thermalPaused.value
        val detailId=if(!detailsVisible)null else gazeDetailWindow ?: focusedDetailWindow?.takeIf{id->connection.workspaceInfo.value?.windows?.any{it.id==id}==true} ?: magnifierPatch.takeIf{workspace.magnifyWindow}
        val magnifierRoot=remember(deskRootVersion){deskSpace.get()}
        InPlaceWindowDetail(connection,detailId,
            {if(workspace.magnifyWindow)magnifierReady=true},
            {message->if(workspace.magnifyWindow)magnifierFailed=true;Log.w("Floatdesk",message)})
        val layoutBasis=workspace.monitorAt(0)?.let{desktop->workspace.layoutOrigin?.let{desktop.copy(pose=it)} ?: desktop}
        val fixedViewer=if(magnifierViewerReady)magnifierViewer.get() else null
        if(magnifierRoot!=null && layoutBasis!=null && fixedViewer!=null){
            val enabled=magnifierSurfacesAllowed(workspace.desktopReference,sceneActive && !restoreViewerPending,displayControlsOpen)
            val unit=handTracking.unitsPerMeter
            val record=workspace.referencePlacement ?: run {
                val origin=Pose(transformPoint(fixedViewer,floatArrayOf(.32f*unit,-.22f*unit,-MagnifierDistanceM*unit)),fixedViewer.rotation)
                saveFixedMagnifier("reference",MagnifiedWindow(-1,origin,.42f*unit,.25f*unit,layoutBasis.pixelWidth,layoutBasis.pixelHeight),layoutBasis,unit)
            }
            if(enabled && workspace.referencePlacement==null)SideEffect{workspace=workspace.copy(referencePlacement=record);store.save(workspace)}
            val display=workspace.monitorAt(0) ?: layoutBasis
            val source=dev.floatdesk.client.net.DesktopWindow(-1,0,0,display.pixelWidth,display.pixelHeight)
            val pane=fixedMagnifier(record,layoutBasis,source,fixedViewer,unit,
                workspace.sphericalWarp && RenderPolicy.CustomMeshes,referencePane,updateGeometry=false)
            WindowMagnifierPanel(session,magnifierRoot,pane.takeIf{enabled},connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                {referenceReady=true},{message->referenceReady=false;Log.w("Floatdesk",message)},
                {side,uv->magnifierHands.update("reference",side,uv,-1,now=SystemClock.uptimeMillis())},{next,changed->
                    referencePane=next
                    if(changed)workspace=workspace.copy(referencePlacement=saveFixedMagnifier("reference",next,layoutBasis,unit))
                },{moving->if(moving)fixedEditing.add("reference")else if(fixedEditing.remove("reference"))recordPlacement("reference gesture");magnifierMoving=fixedEditing.isNotEmpty()},
                topologyKey="reference",onHidden={referenceReady=false},showFrame=false,
                allowManipulation=workspace.layoutModeActive,freeManipulation=workspace.layoutModeActive,desktopMirror=true,projectionMode=workspace.sphericalWarp,mirrorOverlay=referenceInk::get)
        }
        val renderedFixed=mutableListOf<Pair<String,MagnifiedWindow>>()
        if(session!=null && magnifierRoot!=null && layoutBasis!=null && fixedViewer!=null){
            for(record in workspace.fixedMagnifiers) key(record.id){
                val selected=fixedPins[record.id] ?: openToolbarWindow?.takeIf{(toolbarTarget as? ToolbarTarget.Magnifier)?.key==record.id} ?: inPlaceWindow ?: lastFixedWindow ?: connection.workspaceInfo.value?.windows?.firstOrNull()?.id
                val capture=selected?.let{connection.workspaceInfo.value?.magnificationSource(it) ?: it}
                val source=connection.windowCaptures.source(capture) ?: fixedRuntime[record.id]?.takeIf{it.id==capture}?.let{
                    dev.floatdesk.client.net.DesktopWindow(it.id,0,0,it.sourceWidth,it.sourceHeight)
                }
                val paneEnabled=source!=null && source.id>0L && magnifierSurfacesAllowed(workspace.magnifyWindow,sceneActive && !restoreViewerPending,displayControlsOpen)
                val pane=if(paneEnabled)fixedMagnifier(record,layoutBasis,source,fixedViewer,handTracking.unitsPerMeter,workspace.sphericalWarp && RenderPolicy.CustomMeshes,previous=fixedRuntime[record.id],updateGeometry=false)else fixedRuntime[record.id] ?: fixedMagnifier(record,layoutBasis,source,fixedViewer,handTracking.unitsPerMeter,false)
                if(paneEnabled)renderedFixed.add(record.id to pane)
                DisposableEffect(record.id,pane.id){fixedReady[record.id]=false;onDispose{fixedReady.remove(record.id);fixedDisplayed.remove(record.id)}}
                WindowMagnifierPanel(session,magnifierRoot,pane.takeIf{paneEnabled},connection,record.id in fixedPins,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                    {magnifierReady=true},{message->fixedReady[record.id]=false;Log.w("Floatdesk",message)},
                    {side,uv->magnifierHands.update(record.id,side,uv?.let{
                        magnifierContentPoint(fixedRuntime[record.id]?.takeIf{shown->shown.id==pane.id} ?: pane,it)
                    },pane.id,now=SystemClock.uptimeMillis())},
                    {next,manipulated->
                        fixedRuntime[record.id]=next
                        if(manipulated && record.id in fixedEditing){
                            val saved=saveFixedMagnifier(record.id,next,layoutBasis,handTracking.unitsPerMeter)
                            workspace=workspace.copy(fixedMagnifiers=workspace.fixedMagnifiers.map{if(it.id==record.id)saved else it})
                        }
                    },{editing->
                        if(editing)fixedEditing.add(record.id)else if(fixedEditing.remove(record.id))recordPlacement("magnifier gesture")
                        magnifierMoving=fixedEditing.isNotEmpty()
                    },{hands,gaze->if(gaze)pointerControl.noteSystemGaze();if(hands || gaze)magnifierPointerHover[record.id]=hands to gaze else magnifierPointerHover.remove(record.id)},allowManipulation=workspace.layoutModeActive,freeManipulation=workspace.layoutModeActive,topologyKey=record.id,onHidden={fixedReady[record.id]=false},
                    projectionMode=workspace.sphericalWarp,retainLastFrame=true,savedPaneSize=record.widthM to record.heightM,onDisplayed={id->fixedDisplayed[record.id]=id;fixedReady[record.id]=true})
            }
        }
        val renderedSystem=mutableListOf<MagnifiedWindow>()
        val systemInfo by connection.workspaceInfo
        if(session!=null && magnifierRoot!=null && layoutBasis!=null && fixedViewer!=null && workspace.magnifySystemPanels) {
            val shellRows=systemInfo?.systemWindows.orEmpty().sortedWith(compareBy<dev.floatdesk.client.net.DesktopWindow>{!it.panel}.thenBy{it.id})
            var shellSlots by remember{mutableIntStateOf(0)}
            if(shellRows.size>shellSlots)shellSlots=shellRows.size
            repeat(shellSlots){slot->key("system-slot",slot){
                val row=shellRows.getOrNull(slot)
                var retainedRow by remember{mutableStateOf(row)}
                if(row!=null)retainedRow=row
                val live=row ?: requireNotNull(retainedRow)
                // Plasma reuses its preview X11 window ID across icons. The live catalogue
                // has the current extent before the retained capture reports another frame.
                val source=if(live.panel)connection.windowCaptures.source(live.id) ?: live else live
                val output=systemInfo?.screens?.firstOrNull{source.x+source.width/2 in it.x until it.x+it.width && source.y+source.height/2 in it.y until it.y+it.height}
                val edge=if(source.height>source.width){if(output!=null && source.x<output.x+output.width/2)"left" else "right"}else{if(output!=null && source.y<output.y+output.height/2)"top" else "bottom"}
                val placementKey="panel:${output?.name ?: "desktop"}:$edge"
                val record=workspace.systemPanels.firstOrNull{live.panel && it.id==placementKey} ?: systemPlacements.getOrPut(placementKey){
                    val initial=magnifiedWindow(live.id,source.width,source.height,fixedViewer,viewHalfAngles.get()[0],viewHalfAngles.get()[1],handTracking.unitsPerMeter,workspace.sphericalWarp && RenderPolicy.CustomMeshes,layerDistanceM=dev.floatdesk.client.workspace.ViewLayers.ShellDistanceM)
                    val vertical=source.height>source.width
                    val x=if(vertical && output!=null && source.x<output.x+output.width/2)-.32f else if(vertical).32f else 0f
                    val y=if(vertical)0f else if(output!=null && source.y<output.y+output.height/2).20f else -.20f
                    val shifted=initial.copy(pose=initial.pose.copy(position=transformPoint(fixedViewer,floatArrayOf(x*handTracking.unitsPerMeter,y*handTracking.unitsPerMeter,-dev.floatdesk.client.workspace.ViewLayers.ShellDistanceM*handTracking.unitsPerMeter))))
                    saveFixedMagnifier(placementKey,shifted,layoutBasis,handTracking.unitsPerMeter).also{saved->
                        if(live.panel){workspace=workspace.copy(systemPanels=workspace.systemPanels.filter{it.id!=placementKey}+saved);store.save(workspace)}
                    }
                }
                // Capture reconnects must not resize a persisted visual element.
                val panels=renderedSystem.mapNotNull{pane->
                    shellRows.firstOrNull{it.id==pane.id && it.panel}?.let{pane to (connection.windowCaptures.source(it.id) ?: it)}
                }
                val entry=remember(row,panels.map{it.first.id}){
                    if(live.panel)null else {
                        val cursor=androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation{connection.cursors.value[0]?.fragment}
                        val bounds=systemInfo?.bounds()
                        val cursorPoint=if(cursor?.visible==true && bounds!=null)
                            (bounds[0]+cursor.normX*bounds[2]) to (bounds[1]+cursor.normY*bounds[3])
                        else connection.cursorLocation?.let{it.sourceX to it.sourceY}
                        val hovered=cursorPoint?.let{(x,y)->panels.firstOrNull{(_,bar)->
                            x>=bar.x && x<bar.x+bar.width && y>=bar.y && y<bar.y+bar.height
                        }}
                        val owner=hovered ?: panels.minByOrNull{(_,bar)->
                            val dx=maxOf(bar.x-source.x-source.width,source.x-bar.x-bar.width,0)
                            val dy=maxOf(bar.y-source.y-source.height,source.y-bar.y-bar.height,0)
                            dx.toLong()*dx+dy.toLong()*dy
                        }
                        owner?.let{(pane,bar)->
                            val anchor=cursorPoint.takeIf{hovered!=null} ?: ((source.x+source.width*.5f).coerceIn(bar.x.toFloat(),(bar.x+bar.width).toFloat()) to
                                (source.y+source.height*.5f).coerceIn(bar.y.toFloat(),(bar.y+bar.height).toFloat()))
                            pane.id to anchor
                        }
                    }
                }
                val attached=entry?.let{(id,_)->panels.firstOrNull{it.first.id==id}}
                val pane=if(attached!=null)stickyMagnifier(attached.first,attached.second,source,fixedViewer,
                    viewHalfAngles=viewHalfAngles.get(),panelAnchor=entry.second)
                    else fixedMagnifier(record,layoutBasis,source,fixedViewer,handTracking.unitsPerMeter,workspace.sphericalWarp && RenderPolicy.CustomMeshes,systemRuntime[live.id],dev.floatdesk.client.workspace.ViewLayers.ShellDistanceM,updateGeometry=false)
                val enabled=row!=null && magnifierSurfacesAllowed(true,sceneActive && !restoreViewerPending,displayControlsOpen)
                if(enabled)renderedSystem.add(pane)
                DisposableEffect(live.id){onDispose{systemReady.remove(live.id);systemRuntime.remove(live.id)}}
                WindowMagnifierPanel(session,magnifierRoot,pane.takeIf{enabled},connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                    {systemReady[live.id]=true},{message->systemReady[live.id]=false;Log.w("Floatdesk",message)},
                    {side,uv->magnifierHands.update("system:${live.id}",side,uv?.let{magnifierContentPoint(pane,it)},pane.id,now=SystemClock.uptimeMillis())},
                    {next,manipulated->systemRuntime[live.id]=next
                        if(manipulated && "system:${live.id}" in fixedEditing){
                            val saved=saveFixedMagnifier(placementKey,next,layoutBasis,handTracking.unitsPerMeter)
                            systemPlacements[placementKey]=saved
                            if(live.panel)workspace=workspace.copy(systemPanels=workspace.systemPanels.filter{it.id!=placementKey}+saved)
                        }
                    },{moving->
                        if(moving)fixedEditing.add("system:${live.id}")else if(fixedEditing.remove("system:${live.id}"))recordPlacement("panel gesture")
                        magnifierMoving=fixedEditing.isNotEmpty()
                    },allowManipulation=workspace.layoutModeActive && live.panel,freeManipulation=workspace.layoutModeActive && live.panel,topologyKey="shell:${live.id}",projectionMode=workspace.sphericalWarp,transientSurface=!live.panel,previewBounds=row?.takeUnless{it.panel},placementViewer=workspace.cursorMapReference.takeIf{live.panel},onHidden={systemReady[live.id]=false},showFrame=false)
            }}
        }
        if(session!=null && magnifierRoot!=null && sceneActive && !displayControlsOpen){
            WindowMagnifierPanel(session,magnifierRoot,dragProxy,connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                {},{message->Log.w("Floatdesk",message)},{_,_->},{_,_->},{},allowManipulation=false,showFrame=false,topologyKey="window-drag-preview",receiveInput=false,projectFrame=false,projectionMode=workspace.sphericalWarp)
        }
        val renderedPopups=mutableListOf<MagnifiedWindow>()
        if(session!=null && magnifierRoot!=null && fixedViewer!=null && sceneActive && !displayControlsOpen){
            val owners=renderedFixed+renderedSystem.map{"system:${it.id}" to it}
            val assignedPopups=mutableSetOf<Long>()
            for((ownerKey,requestedOwner) in owners.sortedBy{(_,pane)->pane.layerDistanceM}) key("popup-owner",ownerKey){
                val owner=(when{
                    ownerKey.startsWith("system:")->systemRuntime[requestedOwner.id]
                    else->fixedRuntime[ownerKey]
                })?.takeIf{it.id==requestedOwner.id} ?: requestedOwner
                val popups=childPaneOrder(owner.id,connection.windowCaptures.popups(owner.id))
                    .filter{p->systemInfo?.systemWindows?.none{it.id==p.id}!=false && assignedPopups.add(p.id)}
                var retainedSlots by remember {mutableStateOf(emptyList<Long?>())}
                val slots=retainedChildSlots(retainedSlots,popups.map{it.id})
                SideEffect{retainedSlots=slots}
                val visibleParents=mutableMapOf(owner.id to owner)
                val byId=popups.associateBy{it.id}
                val slotById=slots.withIndex().associate{(slot,id)->id to slot}
                val order=popups.map{slotById.getValue(it.id)} + slots.indices.filter{slots[it]==null}
                val siblingIndices=popups.groupBy{it.transientFor ?: owner.id}.values
                    .flatMap{siblings->siblings.mapIndexed{index,popup->popup.id to index}}.toMap()
                for(slot in order)key(slot){
                    val popup=byId[slots[slot]]
                    val parentId=popup?.transientFor ?: owner.id
                    val parent=visibleParents[parentId]
                    val source=if(parentId==owner.id)connection.windowCaptures.source(parentId)
                        else connection.windowCaptures.region(parentId)
                    val pane=if(parent!=null && source!=null && popup!=null)
                        foregroundMagnifierPopup(parent,stickyMagnifier(parent,source,popup,fixedViewer,
                            siblingIndices.getValue(popup.id)),fixedViewer,handTracking.desktopSphereRadius)
                    else null
                    val name="popup:$ownerKey:$slot"
                    DisposableEffect(name,popup?.id){popupReady[name]=false;onDispose{popupReady.remove(name);popupRuntime.remove(name)}}
                    val actual=popupRuntime[name]?.takeIf{it.id==pane?.id}
                    if(actual!=null && popupReady[name]==true){
                        renderedPopups.add(actual)
                        visibleParents[actual.id]=actual
                    }
                    WindowMagnifierPanel(session,magnifierRoot,pane,connection,false,magnifierViewer,{handTracking.unitsPerMeter},handTracking,
                        {popupReady[name]=true},{message->popupReady[name]=false;Log.w("Floatdesk",message)},
                        {side,uv->magnifierHands.update(name,side,if(pane!=null)uv?.let{magnifierContentPoint(actual ?: pane,it)}else null,pane?.id,now=SystemClock.uptimeMillis())},
                        {shown,_->popupRuntime[name]=shown},{},topologyKey="menu:${popup?.id}",projectionMode=workspace.sphericalWarp,onHidden={popupReady[name]=false},captureId=owner.id,regionId=popup?.id,allowManipulation=false,showFrame=false,transientSurface=true,projectFrame=false,placementViewer=fixedViewer)
                }
            }
        }
        SideEffect{popupWindows.set(renderedPopups)}
        SideEffect{systemWindows.set(renderedSystem)}
        SideEffect{fixedWindows.set(renderedFixed)}
        LaunchedEffect(connection,sceneActive,workspace.cursorRemap,workspace.layoutModeActive,displayControlsOpen){
            if(!RenderPolicy.CursorTopology){connection.publishCursorMap(CursorTopology());return@LaunchedEffect}
            while(isActive){
                val head=magnifierViewer.get()
                val desktop=workspace.monitorAt(0)
                val info=connection.workspaceInfo.value
                val patches=mutableListOf<CursorPatch>()
                var mapViewer=workspace.cursorMapReference ?: (workspace.layoutOrigin ?: desktop?.pose)?.let{
                    Pose(transformPoint(it,floatArrayOf(0f,0f,ViewLayers.DesktopDistanceM*handTracking.unitsPerMeter)),it.rotation)
                }
                if(sceneActive && !restoreViewerPending && (workspace.cursorRemap || workspace.layoutModeActive) && !displayControlsOpen && head!=null && desktop!=null && info!=null){
                    fun desktopPatches(view:Pose):List<CursorPatch> = buildList {
                        val bounds=info.bounds() ?: return@buildList
                        for(screen in info.screens){
                            val source=FocusRect(screen.x.toFloat(),screen.y.toFloat(),(screen.x+screen.width).toFloat(),(screen.y+screen.height).toFloat())
                            val logical=FocusRect((screen.x-bounds[0]).toFloat()/bounds[2],(screen.y-bounds[1]).toFloat()/bounds[3],(screen.x+screen.width-bounds[0]).toFloat()/bounds[2],(screen.y+screen.height-bounds[1]).toFloat()/bounds[3])
                            val region=info.visibleRects(logical,workspace.screenSpacing).firstOrNull() ?: continue
                            addAll(cursorPatches("desktop",desktop.pose,desktop.physicalWidthM,desktop.physicalHeightM,
                                pointerControl.meshes[0],source,view,region,divisions=1,projectBounds=true).map{it.copy(depth=ViewLayers.DesktopDistanceM)})
                        }
                    }
                    val canonicalDesktop=desktopPatches(mapViewer ?: head).filter{it.valid()}
                    if(canonicalDesktop.isEmpty()){
                        mapViewer=head
                        patches+=desktopPatches(head)
                    }else patches+=canonicalDesktop
                    val panes=buildList{
                        if(workspace.desktopReference && referenceReady)referencePane?.let{add("reference" to it)}
                        addAll(fixedWindows.get().mapNotNull{(key, _)->
                            fixedRuntime[key]?.takeIf{fixedReady[key]==true && fixedDisplayed[key]==it.id}?.let{key to it}
                        })
                        addAll(systemWindows.get().mapNotNull{pane->systemRuntime[pane.id]?.takeIf{systemReady[it.id]==true}?.let{"shell:${it.id}" to it}})
                        addAll(popupWindows.get().map{"menu:${it.id}" to it})
                    }
                    for((key,pane) in panes){
                        val source=if(key=="reference")info.bounds()?.let{dev.floatdesk.client.net.DesktopWindow(-1,it[0],it[1],it[2],it[3])}
                            else paneSource(key,pane.id)
                        if(source==null)continue
                        patches+=cursorPatches(key,pane.pose,pane.width,pane.height,pane.mesh,
                            FocusRect(source.x.toFloat(),source.y.toFloat(),(source.x+source.width).toFloat(),(source.y+source.height).toFloat()),mapViewer!!,
                            region=magnifierContentRect(pane),front=true,projectBounds=true).map{it.copy(depth=pane.layerDistanceM)}
                    }
                }
                val topology=CursorTopology(patches).logicalPixels()
                connection.layoutMapDebug=if(workspace.layoutModeActive)topology.debugSegments() else floatArrayOf()
                connection.publishCursorMap(if(workspace.cursorRemap)topology else CursorTopology())
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
        val toolbarWindow=openToolbarWindow
        val toolbarSource=systemInfo?.windows?.firstOrNull{it.id==toolbarWindow}
        var hoveredToolbarTooltip by remember(toolbarTarget){mutableStateOf<WindowToolbarTooltip?>(null)}
        var toolbarTooltip by remember(toolbarTarget){mutableStateOf<WindowToolbarTooltip?>(null)}
        var tooltipPanelHovered by remember(toolbarTarget){mutableStateOf(false)}
        LaunchedEffect(hoveredToolbarTooltip,tooltipPanelHovered){
            val label=hoveredToolbarTooltip
            if(label!=null)toolbarTooltip=label
            else if(!tooltipPanelHovered){delay(180);toolbarTooltip=null}
        }
        var snapButtonHovered by remember(toolbarTarget){mutableStateOf(false)}
        LaunchedEffect(toolbarTarget){toolbarHovered=false;snapGridHovered=false;snapGridDragging=false;snapGridExpanded=false}
        LaunchedEffect(snapButtonHovered){
            if(snapButtonHovered)snapGridExpanded=true
        }
        val toolbarMagnifier=(toolbarTarget as? ToolbarTarget.Magnifier)?.key
        val toolbarPinned=toolbarMagnifier in fixedPins
        val toolbarStrip=openToolbarStrip.takeIf{toolbarTarget!=null && !workspace.layoutModeActive}
        val shownPanels=mutableListOf<ToolbarStrip>()
        var shownGrid:ToolbarStrip?=null
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
            fun closeToolbar(){toolbar.clear();toolbarTarget=null;openToolbarStrip=null;openToolbarWindow=null}
            shownPanels+=toolbarStrip.copy(width=barW)
            Subspace{SpatialPanel(modifier=placedPanel(barPose,barW,barH,density,pixelDensity),shape=square){
                WindowToolbar(toolbarWindow,magnifierBar,snap,snapGridExpanded,cell,gap,onHover={toolbarHovered=it},onTooltip={hoveredToolbarTooltip=it},
                    onSnapHover={snapButtonHovered=it},onSnapToggle={snapGridExpanded=!snapGridExpanded},
                    onAction={action->
                        if(action=="focus"){
                            val source=toolbarSource
                            val bounds=systemInfo?.bounds()
                            if(source!=null && bounds!=null){
                                val rect=FocusRect((source.x-bounds[0]).toFloat()/bounds[2],(source.y-bounds[1]).toFloat()/bounds[3],
                                    (source.x+source.width-bounds[0]).toFloat()/bounds[2],(source.y+source.height-bounds[1]).toFloat()/bounds[3])
                                requestFocus(FocusTarget(rect,"",source.id))
                            }
                        }else toolbarWindow?.let{connection.windowAction(it,action)}
                        closeToolbar()
                    },
                    onAdd={addFixedMagnifier()},
                    pinned=toolbarPinned,onPin={
                        if(toolbarMagnifier!=null){
                            if(toolbarMagnifier in fixedPins)fixedPins.remove(toolbarMagnifier)
                            else toolbarWindow?.let{fixedPins[toolbarMagnifier]=it}
                        }
                        closeToolbar()
                    },
                    lastMagnifier=workspace.fixedMagnifiers.size<=1,
                    onRemove={
                        val selected=(toolbarTarget as? ToolbarTarget.Magnifier)?.key
                        if(selected!=null && workspace.fixedMagnifiers.size<=1){
                            workspace=workspace.copy(magnifyWindow=false)
                            store.save(workspace)
                            magnification.dismiss(SystemClock.uptimeMillis())
                        }
                        else if(selected!=null){
                            store.recordLayout(workspace,"before removing magnifier")
                            workspace=workspace.copy(fixedMagnifiers=workspace.fixedMagnifiers.filter{it.id!=selected})
                            fixedPins.remove(selected);fixedRuntime.remove(selected)
                            recordPlacement("remove magnifier")
                        }
                        magnifierPointerHover.remove(selected);closeToolbar()
                    })
            }}
            if(snapGridExpanded){
                val gridW=WindowMenuLayout.gridWidth(gapRatio)*cellW;val gridH=WindowMenuLayout.gridHeight(gapRatio)*cellW
                val snapOffset=if(magnifierBar)cellW+2*gapW else 0f
                val nominal=toolbarStrip.copy(pose=Pose(transformPoint(barPose,floatArrayOf(snapOffset,-(barH+gridH)*.5f,.002f*unit)),barPose.rotation),
                    width=gridW,height=gridH)
                val viewer=magnifierViewer.get()
                val gridOwner=toolbarMagnifier?.let{fixedRuntime[it]}
                    ?: popupWindows.get().firstOrNull{it.id==toolbarWindow}
                val grid=if(viewer==null)nominal else nominal.gridInFrontOf(viewer,handTracking.desktopSphereRadius,gridOwner,unit)
                shownGrid=grid
                shownPanels+=grid
                val ratio=grid.width/gridW
                Subspace{SpatialPanel(modifier=placedPanel(grid.pose,grid.width,grid.height,density,pixelDensity),shape=square){
                    SnapGridMenu(toolbarWindow,snap,cell*ratio,gap*ratio,onHover={snapGridHovered=it},onDrag={snapGridDragging=it}){range->
                        val screen=systemInfo?.screens?.firstOrNull{r->toolbarSource!=null && toolbarSource.x+toolbarSource.width/2 in r.x until r.x+r.width && toolbarSource.y+toolbarSource.height/2 in r.y until r.y+r.height} ?: systemInfo?.screens?.firstOrNull{it.primary}
                        if(toolbarWindow!=null && screen!=null){
                            val work=windowWorkArea(screen,systemInfo?.systemWindows.orEmpty())
                            connection.windowAction(toolbarWindow,"geometry",windowGridCell(toolbarWindow,work[0],work[1],
                                work[2],work[3],range.columns,range.rows,range.col,range.row,range.endCol,range.endRow))
                        }
                        snapGridExpanded=false;snapGridHovered=false
                    }
                }}
            }
            toolbarTooltip?.let{tip->
                val tipW=maxOf(cellW*2.2f,cellW*tip.label.length*.11f);val tipH=cellW*.40f
                val x=WindowMenuLayout.buttonCenter(tip.index,magnifierBar,gapRatio)*cellW
                val tipPose=Pose(transformPoint(barPose,floatArrayOf(x,cellW*.5f+tipH*.42f,.003f*unit)),barPose.rotation)
                shownPanels+=toolbarStrip.copy(pose=tipPose,width=tipW,height=tipH)
                Subspace{SpatialPanel(modifier=placedPanel(tipPose,tipW,tipH,density,pixelDensity)){
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF18232E)).trackMenuHover{tooltipPanelHovered=it!=null},contentAlignment=androidx.compose.ui.Alignment.Center){
                        androidx.compose.foundation.text.BasicText(tip.label,Modifier.padding(horizontal=6.dp),style=androidx.compose.ui.text.TextStyle(color=Color.White),maxLines=1,softWrap=false,
                            autoSize=androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize=6.sp,maxFontSize=48.sp,stepSize=.5f.sp))
                    }
                }}
            }
        }

        SideEffect{toolbarPanels.set(shownPanels.toList());snapGridSurface.set(shownGrid)}
    } else {
        controls()
    }
}
