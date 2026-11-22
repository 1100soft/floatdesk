package dev.floatdesk.client

import androidx.compose.runtime.key
import android.util.Log
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.InteractionPolicy
import androidx.xr.compose.subspace.layout.SpatialInputEvent
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.pointerHoverIcon
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.compose.subspace.layout.offset as offset3d
import androidx.xr.compose.subspace.layout.requiredHeight
import androidx.xr.compose.subspace.layout.requiredWidth
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.PixelDensity
import dev.floatdesk.client.net.CursorView
import dev.floatdesk.client.net.DeskConnection
import dev.floatdesk.client.net.MonitorHello
import androidx.xr.compose.subspace.draw.scale
import dev.floatdesk.client.workspace.MonitorPlacement
import dev.floatdesk.client.workspace.ViewLayers
import dev.floatdesk.client.workspace.withViewingDistances
import dev.floatdesk.client.workspace.Pose
import dev.floatdesk.client.workspace.WorkspaceFile
import dev.floatdesk.client.workspace.WorkspaceStore
import dev.floatdesk.client.workspace.reconcileMonitors
import dev.floatdesk.client.workspace.monitorPlaced
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@Composable
internal fun DeskContent(
    connection: DeskConnection,
    onDeskRoot:(androidx.xr.scenecore.Entity)->Unit,
    viewer:AtomicReference<Pose?>,
    viewerAngles:AtomicReference<FloatArray>,
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
    onAddFixed:()->Unit,
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
    DiagnosticsPanel(connection,viewer,{handTracking.unitsPerMeter},{viewerAngles.get()},
        workspace.showResources,workspace.experimentalViewedDetail,workspace.viewDetailDebug,workspace.sphericalWarp)
    var managerPage by remember{mutableStateOf("Screens")}
    val managerHeight=when(managerPage){"Settings"->.40f;"Focus & magnification"->.40f;"Pinches"->.48f;"Viewing distance"->.42f;"Virtual screen setup"->.38f;else->.65f}
    val managerScale=remember(displayControlsPose){settingsPanelScale(handTracking.unitsPerMeter)}
    if(displayControlsOpen) SpatialPanel(modifier=placedPanel(displayControlsPose,if(managerPage=="Screens") .60f else .52f,managerHeight,density,pixelDensity,scale=managerScale)) {
        MonitorManagerPanel(connection,workspace.screenSpacing,{onWorkspace(workspace.copy(screenSpacing=it))},{store.save(latest.value)},onCloseDisplays,workspace.pinchThresholds,{profile->
            val next=latest.value.copy(pinchThresholds=profile);onWorkspace(next);store.save(next)
        },workspace.experimentalViewedDetail,{enabled->
            val next=latest.value.copy(experimentalViewedDetail=enabled);onWorkspace(next);store.save(next)
        },workspace.viewDetailDebug,{enabled->
            val next=latest.value.copy(viewDetailDebug=enabled);onWorkspace(next);store.save(next)
        },workspace,{next->onWorkspace(next);store.save(next)},onPage={managerPage=it},onAddFixed=onAddFixed,
            onViewingDistances={nearest,farthest,persist->
                val next=latest.value.withViewingDistances(nearest,farthest,store.viewerReference,store.viewerUnitsPerMeter)
                ViewLayers.configure(next.nearestDistanceM,next.farthestDistanceM)
                onWorkspace(next);if(persist)store.save(next)
            })
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
        val unit = panel.unitsPerMeter
        var hovered by remember(panel.page) { mutableStateOf<PalmAction?>(null) }
        SpatialPanel(modifier = placedPanel(panel.pose, panel.widthM * unit, panel.heightM * unit, density, pixelDensity),
            shape = androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape(androidx.compose.foundation.shape.CornerSize(0.dp))) {
            PalmControls(panel.page, workspace, connection.workspaceInfo.value, latchedFollow, panelFocused, panelPressed, { hovered = it }, onPalmAction)
        }
        (hovered ?: panelFocused)?.takeIf { shown -> panel.buttons.any { it.action == shown } }?.let { palmTooltip(it, workspace, latchedFollow) }?.let { label ->
            val tooltipPose = Pose(transformPoint(panel.pose, floatArrayOf(0f, panel.heightM * unit * .5f + .026f * unit, .006f * unit)), panel.pose.rotation)
            SpatialPanel(modifier = placedPanel(tooltipPose, panel.widthM * unit, PalmMenuLayout.tooltipHeightM * unit, density, pixelDensity)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF18232E)), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.text.BasicText(label, Modifier.padding(horizontal = 6.dp), style = androidx.compose.ui.text.TextStyle(color = Color.White),
                        maxLines = 1, softWrap = false,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = androidx.compose.ui.unit.TextUnit(6f, androidx.compose.ui.unit.TextUnitType.Sp),
                            maxFontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp), stepSize = androidx.compose.ui.unit.TextUnit(.5f, androidx.compose.ui.unit.TextUnitType.Sp)))
                }
            }
        }
    }
    // Retain native assets across Workspace visits; hide them instead of destroying/re-instancing glTF.
    val controlsOpenNow=rememberUpdatedState(displayControlsOpen)
    layout.forEachIndexed { index, placement ->
        androidx.compose.runtime.key(placement.hostSourceId,placement.pixelWidth,placement.pixelHeight) {
        val hands = remember(index) { grabs.getOrPut(index) { TwoHandState() } }
        var nativeResize by remember(index) { mutableStateOf(false) }
        var nativeMove by remember(index) { mutableStateOf(false) }
        var nativeGestureAt by remember(index) {mutableStateOf(0L)}
        var moveBefore by remember(index) { mutableStateOf<Pose?>(null) }
        var nativeGeometryPending by remember(index) { mutableStateOf(false) }
        var nativeResizeStartWidth by remember(index) { mutableStateOf(1f) }
        var nativeResizeStartHeight by remember(index) { mutableStateOf(1f) }
        val videoSurface = remember(index) { AtomicReference<Surface?>(null) }
        val warp = remember(index, session) { MonitorWarp() }
        val systemControls=remember(index,session) { MonitorSystemControls() }
        val cornerHandles=remember(index,session) { PaneCornerResize() }
        DisposableEffect(systemControls,cornerHandles) { onDispose { systemControls.close();cornerHandles.close();pointerControl.nativeManipulation(index,false) } }
        var frozenMonitor by remember(index) { mutableStateOf<FrozenMonitor?>(null) }
        LaunchedEffect(index, session) {
            val current = session ?: return@LaunchedEffect
            var grabProjectionAt=0L
            var outputResizePending=false
            while (isActive) {
                delay(20)
                if(controlsOpenNow.value) {
                    warp.setVisible(false);systemControls.setVisible(false);cornerHandles.cancel()
                    hands.cancel();pointerControl.clearMonitor(index)
                    if(nativeMove || nativeResize)onManipulate(false)
                    nativeMove=false;nativeResize=false;moveBefore=null
                    frozenMonitor?.suspendVisibility()
                    delay(100);continue
                }
                if(!connection.isRenderingActive() || !current.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { delay(100);continue }
                warp.setVisible(true);systemControls.setVisible(true)
                if (videoSurface.get() == null) continue
                val root = warp.coordinateParent.liveOrNull() ?: continue
                val file = latest.value
                val screen = file.monitorAt(index) ?: continue
                try {
                    if((!handInputNow.value || handTracking.pinched.isEmpty() && SystemClock.uptimeMillis()-nativeGestureAt>=120) && (nativeMove || nativeResize)) {
                        systemControls.close();nativeMove=false;nativeResize=false;moveBefore=null
                        nativeGeometryPending=true;onManipulate(false)
                    }
                    // Freeze before the platform DOWN arrives: replacing a native canvas while
                    // the user starts pinching can cancel its input acquisition.
                    connection.presentation().let {warp.presentation(it.rects,it.spacing)}
                    val sourceSize=connection.workspaceInfo.value?.bounds()
                    if(warp.qualityResolution(if((file.experimentalViewedDetail && connection.hasViewedDetail() || connection.hasWindowDetail()) && sourceSize!=null)qualityOutputSize(sourceSize[2],sourceSize[3],connection.qualityOutputLimit()) else null))outputResizePending=true
                    val projectionNow=SystemClock.uptimeMillis()
                    if(!hands.engaged || projectionNow-grabProjectionAt>=100) {
                        warp.update(current, root, handTracking.points["VIEWER"] ?: handTracking.points["HEAD"], screen, file.sphericalWarp,
                            (hands.busy && !hands.engaged) || nativeMove || nativeResize || handTracking.pointerHeld,
                            forceUpdate=(hands.engaged || !hands.busy) && (nativeGeometryPending || !warp.matchesSize(screen)),
                            viewDirection=handTracking.points["VIEW_DIRECTION"],
                            freezeProjection=(hands.busy && !hands.engaged) || nativeMove || nativeResize || handTracking.pointerHeld,
                            submitShape={update->connection.tryDesktopShapeUpdate(index,update)})
                        grabProjectionAt=projectionNow
                    }
                    if(outputResizePending){connection.refreshOutput(index);outputResizePending=false}
                    frozenMonitor?.update(screen,warp.mesh)
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
                            nativeGestureAt=SystemClock.uptimeMillis();nativeResize=true; onManipulate(true)
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
                        if(phase==SystemMovePhase.START) { if(hands.busy) hands.cancel();nativeGestureAt=SystemClock.uptimeMillis();nativeMove=true;onManipulate(true);return@update }
                        if(!nativeMove) return@update
                        val existing=latest.value.monitorAt(index) ?: return@update
                        val safe=before!=null && distance(before.position,sample.position)<=.25f && kotlin.math.abs(dot4(before.rotation,sample.rotation))>=.9f
                        val next=if(safe) existing.copy(pose=nativeRelativePose(before!!,sample,existing.pose)) else existing
                        val end=phase==SystemMovePhase.END
                        onWorkspace(latest.value.monitorPlaced(index,next,store,end))
                        if(end) { nativeMove=false;moveBefore=null;nativeGeometryPending=true;onManipulate(false) }
                    })
                    val handleEntity=warp.displayEntity
                    if(handleEntity!=null && !hands.engaged && handInputNow.value) {
                        cornerHandles.update(current,handleEntity,screen.physicalWidthM,screen.physicalHeightM,handTracking.unitsPerMeter,ViewLayers.DesktopDistanceM,handTracking,pointerControl.aimedAt(index)) { active,factor ->
                            if(!handInputNow.value || hands.engaged) { nativeResize=false; return@update }
                            val existing=latest.value.monitorAt(index) ?: return@update
                            if(active && !nativeResize) {
                                if(hands.busy) hands.cancel()
                                nativeResizeStartWidth=existing.physicalWidthM
                                nativeResizeStartHeight=existing.physicalHeightM
                                nativeGestureAt=SystemClock.uptimeMillis();nativeResize=true;onManipulate(true)
                            }
                            if(!nativeResize) return@update
                            nativeGeometryPending=true
                            val size=resizeDimensions(nativeResizeStartWidth,nativeResizeStartHeight,factor)
                            if(!active) { nativeResize=false;onManipulate(false) }
                            if(size!=null || !active) {
                                val next=size?.let { existing.copy(physicalWidthM=it.first,physicalHeightM=it.second) } ?: existing
                                onWorkspace(latest.value.monitorPlaced(index,next,store,!active))
                            }
                        }
                    } else cornerHandles.cancel()
                    pointerControl.nativeManipulation(index,systemControls.isManipulating)
                    pointerControl.meshes[index] = warp.mesh
                    pointerControl.qualityReferences[index]=warp.gazeReference
                    connection.projection(index,null)
                    warp.outputSurface?.let { display ->
                        if (videoSurface.get() !== display) {
                            warp.coordinateParent?.let(onDeskRoot)
                videoSurface.set(display)
                            connection.attachSurface(index, display)
                        }
                    }
                } catch (failure: Exception) {
                    Log.e("Floatdesk", "Could not update spherical monitor", failure)
                    nativeMove=false;nativeResize=false;systemControls.close()
                    pointerControl.nativeManipulation(index,false);onManipulate(false)
                    delay(1000)
                }
            }
        }
        LaunchedEffect(hands.engaged, session) {
            val current = session ?: return@LaunchedEffect
            if (!hands.engaged) return@LaunchedEffect
            val surface = videoSurface.get() ?: return@LaunchedEffect
            var frozen: FrozenMonitor? = null
            try {
                if(!RenderPolicy.CustomMeshes)return@LaunchedEffect
                frozen = FrozenMonitor.create(current, surface, placement.pixelWidth, placement.pixelHeight, hands.frozenMesh,dynamic=latest.value.sphericalWarp)
                val latestPlacement = latest.value.monitorAt(index) ?: return@LaunchedEffect
                frozen.update(latestPlacement,warp.mesh)
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
        if(!displayControlsOpen) frozenMonitor?.let { frozen -> SideEffect { frozen.update(placement,warp.mesh) } }

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
            superSamplingPattern = androidx.xr.compose.subspace.SuperSamplingPattern.None,
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
                warp.coordinateParent?.let(onDeskRoot)
                videoSurface.set(display)
                warp.setVisible(!controlsOpenNow.value)
                connection.attachSurface(index, display)
            }
            onSurfaceDestroyed {
                systemControls.close();cornerHandles.cancel()
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
internal fun placedPanel(
    pose: Pose,
    widthM: Float,
    heightM: Float,
    density: Density,
    pixelDensity: PixelDensity,
    dx: Float = 0f,
    dy: Float = 0f,
    dz: Float = 0f,
    scale: Float = 1f,
): SubspaceModifier {
    // Translation must wrap rotation: T * R, so turning a panel cannot orbit its center.
    val placed = SubspaceModifier
        .requiredWidth(widthM.meters(density, pixelDensity))
        .requiredHeight(heightM.meters(density, pixelDensity))
        .offset3d(
            x = (pose.position[0] + dx).meters(density, pixelDensity),
            y = (pose.position[1] + dy).meters(density, pixelDensity),
            z = (pose.position[2] + dz).meters(density, pixelDensity),
        )
        .rotate(pose.toQuaternion())
    return if (scale == 1f) placed else placed.scale(scale)
}

/** The Settings panel was laid out for 0.9 scene units; keep that apparent size on the controls layer. */
internal fun settingsPanelScale(unitsPerMeter: Float) = ViewLayers.ControlsDistanceM * unitsPerMeter / .9f

@Composable
internal fun MonitorCursor(
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
internal fun CursorPanel(
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
internal fun ControlPanel(
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
