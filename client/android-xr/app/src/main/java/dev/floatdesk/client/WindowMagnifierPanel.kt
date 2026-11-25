package dev.floatdesk.client

import androidx.compose.runtime.*
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.*
import androidx.xr.scenecore.*
import dev.floatdesk.client.media.*
import dev.floatdesk.client.net.DeskConnection
import dev.floatdesk.client.workspace.MonitorPlacement
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One session-owned surface. Switching windows never recreates a native stereo surface. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
@Composable
internal fun WindowMagnifierPanel(session:Session,root:Entity,window:MagnifiedWindow?,connection:DeskConnection,
    pinned:Boolean,viewer:java.util.concurrent.atomic.AtomicReference<dev.floatdesk.client.workspace.Pose?>,
    units:()->Float,tracking:GrabTracking,
    onReady:()->Unit,onError:(String)->Unit,onHover:(String,Pair<Float,Float>?)->Unit,
    onPlacement:(MagnifiedWindow,Boolean)->Unit,onMoving:(Boolean)->Unit,onPointerHover:(hands:Boolean,gaze:Boolean)->Unit={_,_->},onHidden:()->Unit={},captureId:Long?=null,regionId:Long?=null,allowManipulation:Boolean=true,showFrame:Boolean=true,topologyKey:String?=null,receiveInput:Boolean=true,projectFrame:Boolean=true,freeManipulation:Boolean=false,desktopMirror:Boolean=false,mirrorOverlay:(()->VideoAim)?=null,onDisplayed:((Long)->Unit)?=null,projectionMode:Boolean=window?.spherical==true,transientSurface:Boolean=false,retainLastFrame:Boolean=false,savedPaneSize:Pair<Float,Float>?=null,previewBounds:dev.floatdesk.client.net.DesktopWindow?=null,placementViewer:dev.floatdesk.client.workspace.Pose?=null){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    val placementViewerNow=rememberUpdatedState(placementViewer)
    val windowNow=rememberUpdatedState(window);val placementNow=rememberUpdatedState(onPlacement);val movingNow=rememberUpdatedState(onMoving)
    val moveMode=rememberUpdatedState(allowManipulation);val freeMode=rememberUpdatedState(freeManipulation)
    val displayedNow=rememberUpdatedState(onDisplayed)
    val mirrorOverlayNow=rememberUpdatedState(mirrorOverlay)
    val pinNow=rememberUpdatedState(pinned)
    val pointerHoverNow=rememberUpdatedState(onPointerHover)
    val hiddenNow=rememberUpdatedState(onHidden)
    // Native mesh materials belong to their surface. Keep one surface per mode,
    // so switching projection never converts or disposes a live material.
    val transientKey=if(transientSurface)window?.let{Triple(it.id,it.sourceWidth,it.sourceHeight)} else null
    val owners=remember(session,root,transientKey,previewBounds){mapOf(false to MagnifierResources(),true to MagnifierResources())}
    val resources=owners.getValue(projectionMode)
    DisposableEffect(owners){onDispose{owners.values.forEach{it.close()}}}
    fun clearPointerHover(){resources.hovering.clear();pointerHoverNow.value(false,false)}
    val captureEpoch by connection.windowCaptureEpoch
    var failureToken by remember(resources){mutableIntStateOf(0)}
    var retryToken by remember(resources){mutableIntStateOf(0)}
    val retryPolicy=remember(resources){dev.floatdesk.client.net.WindowCaptureRetry()}
    LaunchedEffect(failureToken,window?.id){
        if(failureToken>0 && window!=null){delay(retryPolicy.nextDelayMs());retryToken++}
    }
    DisposableEffect(resources){onDispose{resources.entity?.takeUnless{it.isDisposed}?.setEnabled(false);resources.hasFrame=false;hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover();hiddenNow.value();movingNow.value(false)}}
    fun finishPlacement(){
        if(resources.dragged || resources.resizing)(resources.pending ?: resources.rendered)?.let{placementNow.value(it,true)}
        resources.dragged=false
    }
    val authoredSize=savedPaneSize?.let{(w,h)->
        kotlin.math.round(w*1000).toInt() to kotlin.math.round(h*1000).toInt()
    } ?: window?.layoutFootprint?.let{footprint->
        (kotlin.math.round(footprint.width*1000).toInt()) to (kotlin.math.round(footprint.height*1000).toInt())
    }
    LaunchedEffect(resources,window?.id,authoredSize,captureId,regionId,captureEpoch,retryToken,desktopMirror){
        hiddenNow.value();resources.visible=false;resources.stream?.close();resources.stream=null
        if(!retainLastFrame || window==null){
            resources.entity?.setEnabled(false);resources.curvedInput?.entity?.setEnabled(false);resources.hasFrame=false
        }
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()
        fun surfaceMode(value:MagnifiedWindow)=if(value.spherical==projectionMode)value else value.copy(spherical=projectionMode,mesh=null)
        val initial=window?.takeIf{(desktopMirror || it.id>0L) && it.sourceWidth>0 && it.sourceHeight>0}?.let(::surfaceMode) ?: return@LaunchedEffect
        if(transientSurface && regionId==null)delay(80) // Let a captured region settle before allocating its native mesh.
        while(connection.thermalPaused.value)delay(100)
        var initialViewer=viewer.get()
        while(initialViewer==null){withFrameNanos{};initialViewer=viewer.get()}
        val projectionEye=placementViewerNow.value ?: initialViewer
        val initialFrame=if(projectFrame)magnifierFrame(initial,initial,projectionEye,units(),true)else projectPaneAtPlacement(initial,initial,projectionEye,true)
        val requested=placePaneInsideDesktop(initialFrame,projectionEye,tracking.desktopSphereRadius)
        val old=resources.rendered
        val keepGeometry=resources.entity!=null && (if(savedPaneSize!=null)
            resources.authoredSize==authoredSize else sameNativePaneExtent(old,requested))
        if(!keepGeometry)resources.detachSurface()
        val sphericalFit=savedPaneSize!=null && requested.spherical && requested.mesh!=null && RenderPolicy.CustomMeshes
        val baseCanvas=if(sphericalFit){
            resources.baseGeometry ?: run {
                val extent=maxOf(requested.width,requested.height)+.01f*requested.units
                val eye=viewerInMonitor(projectionEye.position,requested.pose.position,requested.pose.rotation)
                requested.copy(width=extent,height=extent,mesh=MonitorMesh(extent,extent,eye,columns=32,rows=20))
            }
        }else null
        val target=if(baseCanvas!=null){
            val extent=baseCanvas.width-.01f*baseCanvas.units
            val aspect=requested.sourceWidth.toFloat()/requested.sourceHeight.coerceAtLeast(1)
            val width=extent*minOf(1f,aspect)
            val height=extent*minOf(1f,1f/aspect)
            val sphere=requireNotNull(baseCanvas.mesh)
            requested.copy(pose=centerPaneSphere(requested.pose,sphere,projectionEye),width=width,height=height,
                mesh=sphere.forSphericalAperture(width,height))
        }else if(keepGeometry && savedPaneSize!=null)
            requested.copy(mesh=resources.baseGeometry?.mesh?.forPaneExtent(requested.width,requested.height))
        else if(keepGeometry && old!=null)requested.copy(width=old.width,height=old.height,mesh=old.mesh)
        else requested
        try{
            fun shape(surface:MagnifiedWindow):SurfaceEntity.Shape{
                if(!RenderPolicy.CustomMeshes || !surface.spherical || surface.mesh==null){
                    val border=if(showFrame).01f*surface.units else 0f
                    return SurfaceEntity.Shape.Quad(FloatSize2d(surface.width+border,surface.height+border))
                }
                return nativeVideoMesh(if(showFrame)framedMagnifierGeometry(surface)else surface.mesh?.let{VideoGeometry(it.positions,it.videoTexCoords,it.indices)} ?: clippedVideoGeometry(surface.width,surface.height,null,floatArrayOf(0f,0f,1f,1f),0f))
            }
            val reusedSurface=resources.entity!=null
            if(resources.entity==null){
                delay(32) // Let the existing desktop's pending mesh commands flush before allocating a surface.
                val initialShape=if(baseCanvas!=null){
                    val mesh=requireNotNull(baseCanvas.mesh)
                    nativeVideoMesh(VideoGeometry(mesh.positions,mesh.videoTexCoords,mesh.indices))
                }else shape(target)
                resources.retain(initialShape)
                val entity=SurfaceEntity.create(session,shape=initialShape,parent=null,superSampling=SurfaceEntity.SuperSampling.NONE,
                    mediaBlendingMode=if(sphericalFit)SurfaceEntity.MediaBlendingMode.TRANSPARENT else SurfaceEntity.MediaBlendingMode.OPAQUE)
                resources.entity=entity;resources.baseGeometry=baseCanvas ?: target;entity.setEnabled(false)
                if(!receiveInput || !transientSurface && target.mesh?.planar==false)SurfaceInput.disableCollider(entity)
                val pointer=SpatialPointerComponent.create(session);entity.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                val listener:(InputEvent)->Unit=listener@{event->
                    val active=resources.rendered?.takeIf{resources.visible} ?: return@listener
                    val expected=resources.curvedInput?.entity ?: resources.entity
                    val hit=event.hitInfoList.firstOrNull{it.inputEntity===expected}
                    val mesh=resources.mesh
                    val capturedMove=event.action==InputEvent.Action.MOVE || event.action==InputEvent.Action.UP
                    val ray=lazy{surfaceInputRay(session,root,event)}
                    val acceptedRay=hit!=null || capturedMove
                    val sphere=resources.baseGeometry?.mesh?.takeIf{sphericalFit}
                    val aperture=if(acceptedRay && sphere!=null)sphericalPaneContact(active,sphere,ray.value) else null
                    val local=if(sphere!=null)aperture?.first else if(mesh!=null){
                        if(acceptedRay)surfaceInputHit(ray.value,active.pose,active.width,active.height,mesh) else null
                    }else{
                        (if(acceptedRay)surfaceInputHit(ray.value,active.pose,active.width,active.height,null) else null)
                            ?: hit?.hitPosition?.let{eventMatrixHit(floatArrayOf(it.x,it.y,it.z),hit.transform.data,
                                active.width,active.height,null,entity.getScale(Space.ACTIVITY))}
                    }
                    val uv=if(sphere!=null)aperture?.second else local?.let{point->
                        val flat=mesh?.unproject(point) ?: point
                        (flat[0]/active.width+.5f) to (.5f-flat[1]/active.height)
                    }
                    val pointerSide=event.pointerType.toString()
                    val handInput=event.source==InputEvent.Source.HANDS
                    val side=if(handInput && pointerSide=="DEFAULT" && pointerEventContinues(event.action))
                        resources.capturedHands.singleOrNull() ?: pointerSide else pointerSide
                    if(handInput){
                        if(event.action==InputEvent.Action.DOWN && local!=null && side in setOf("LEFT","RIGHT"))resources.capturedHands.add(side)
                        if(event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL)resources.capturedHands.remove(side)
                    }
                    if(hit!=null)updateSurfaceAimingIcon(expected,event.source==InputEvent.Source.GAZE_AND_GESTURE || connection.cursorLocation?.key==topologyKey && handInput)
                    if(event.source==InputEvent.Source.HANDS){
                        if(event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL)hoverNow.value(side,null)
                        else hoverNow.value(side,uv)
                    }
                    if(freeMode.value && event.source==InputEvent.Source.HANDS && side in setOf("LEFT","RIGHT")) {
                        if(local!=null && uv!=null && uv.first in 0f..1f && uv.second in 0f..1f)
                            resources.freeContacts[side]=local
                        if(event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL)
                            resources.freeContacts.remove(side)
                    }
                    val pointerKey=(if(event.source==InputEvent.Source.GAZE_AND_GESTURE)"gaze:" else "hand:")+side
                    val pointing=uv!=null && event.action!=InputEvent.Action.HOVER_EXIT && event.action!=InputEvent.Action.CANCEL
                    if(if(pointing)resources.hovering.add(pointerKey)else resources.hovering.remove(pointerKey))
                        pointerHoverNow.value(resources.hovering.any{it.startsWith("hand:")},resources.hovering.any{it.startsWith("gaze:")})
                    if(moveMode.value && event.action==InputEvent.Action.DOWN && local!=null && !resources.resizing && !resources.moving){
                        val head=viewer.get()
                        if(head!=null){resources.dragged=false;val ray=ray.value
                            val handSide=side.takeIf{it=="LEFT" || it=="RIGHT"} ?: tracking.pinched.filter{it in tracking.points}.singleOrNull()
                            val hand=handSide?.let{tracking.points[it]}
                            resources.drag.begin(active,head,ray.first,ray.second,transformPoint(active.pose,local),hand)
                            resources.dragHandSide=handSide.takeIf{hand!=null};resources.dragSide=side;resources.moveContact=uv;resources.moveStarted=tracking.nowMs;resources.moving=true;movingNow.value(true)}
                    }
                    if(event.action==InputEvent.Action.MOVE && resources.moving && side==resources.dragSide && resources.dragHandSide==null && !resources.resizing){
                        val head=viewer.get()
                        if(head!=null){val ray=ray.value;val proposed=resources.drag.move(head,ray.first,ray.second)
                            if(proposed!=null){if(!resources.dragged)movingNow.value(true);resources.dragged=true;resources.pending=angularMagnifierPlacement(active,head,units(),proposed)}
                        }
                    }
                    if((event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL) && side==resources.dragSide){
                        finishPlacement()
                        resources.drag.clear();resources.dragSide=null;resources.dragHandSide=null;resources.moving=false;movingNow.value(false)
                    }
                    if(event.action==InputEvent.Action.DOWN && !resources.moving){resources.dragged=false}
                }
                resources.listener=listener

            }
            val entity=resources.entity!!;val p=target.pose;val q=p.rotation
            val pixelSize=if(sphericalFit)dev.floatdesk.client.net.WindowCaptureFormat.size(
                RenderPolicy.WindowDimension,RenderPolicy.WindowDimension)
            else dev.floatdesk.client.net.WindowCaptureFormat.canvasSize(target.sourceWidth,target.sourceHeight)
            // Replacing an in-use native canvas texture can race its borrowed model material.
            // Keep the allocation stable as retained menu slots change their source bounds.
            if(!reusedSurface)entity.setSurfacePixelDimensions(IntSize2d(pixelSize.first,pixelSize.second))

            val targetPose=Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3]))
            resources.mesh=target.mesh?.takeIf{RenderPolicy.CustomMeshes && !it.planar}
            resources.rendered=target
            resources.authoredSize=authoredSize
            placementNow.value(target,false)
            val base=resources.baseGeometry ?: target
            entity.parent=root;entity.setPose(targetPose)
            if(savedPaneSize!=null && !sphericalFit)entity.setScale(Vector3(target.width/base.width,target.height/base.height,1f))
            logMagnifierSubmission(if(reusedSurface)"reuse" else "start",target,entity)
            if(receiveInput){
                val mesh=resources.mesh
                if(mesh!=null && !transientSurface){
                    if(resources.curvedInput==null){
                        val collider=CurvedMonitorInput.create(session,entity,
                            if(sphericalFit)mesh else base.mesh ?: mesh,6,4,
                            if(sphericalFit)target.width else base.width,if(sphericalFit)target.height else base.height,resources.listener!!)
                        resources.curvedInput=collider
                        collider.entity.setEnabled(false)
                    }else if(sphericalFit){
                        resources.curvedInput?.updateGeometry(mesh,target.width,target.height)
                    }
                }else if(resources.flatInput==null){
                    val component=NativeMonitorInput.create(session,resources.listener!!)
                    check(entity.addComponent(component))
                    resources.flatInput=component
                }
            }
            val generation=++resources.generation
            fun publishForeground(){if(topologyKey!=null && resources.visible)resources.rendered?.let{tracking.displayedPanes[resources]=it}}
            var shown=false
            if(!desktopMirror)resources.stream=connection.windowCaptures.attach(captureId ?: target.id,entity.getSurface(),{
                if(resources.generation==generation && !entity.isDisposed){shown=true;retryPolicy.reset();failureToken=0;resources.visible=true;resources.hasFrame=true;entity.setEnabled(true);resources.curvedInput?.entity?.setEnabled(true);publishForeground();android.util.Log.i("Floatdesk","pane ready key=$topologyKey id=${target.id}");displayedNow.value?.invoke(target.id);readyNow.value()}
            },{message->if(resources.generation==generation){resources.visible=false;tracking.displayedPanes.remove(resources);if(!retainLastFrame || !resources.hasFrame)entity.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover();errorNow.value(message);failureToken++}},regionId=regionId,previewBounds=previewBounds,transparentFit=sphericalFit)
            fun updateStreamFrame(pane:MagnifiedWindow){
                val canvasWidth=baseCanvas?.width ?: (pane.width+.01f*pane.units)
                val canvasHeight=baseCanvas?.height ?: (pane.height+.01f*pane.units)
                resources.stream?.update(if(showFrame).005f*pane.units/canvasWidth else 0f,
                    if(showFrame).005f*pane.units/canvasHeight else 0f,showFrame && pinNow.value,
                    if(sphericalFit)1f else pane.width/pane.height)
            }
            updateStreamFrame(target)
            var prior=target
            var inkAt=Long.MIN_VALUE
            val mirrorPresented=java.util.concurrent.atomic.AtomicBoolean(false)
            val mirrorInk=java.util.concurrent.atomic.AtomicReference(VideoAim())
            val mirror=if(desktopMirror)VideoMirror(entity.getSurface(),minFrameIntervalNs={
                if(connection.limitOptionalRendering)66_666_667L else 33_333_333L
            },presented={mirrorPresented.set(true)},ink=mirrorInk::get)else null
            while(isActive){
                withFrameNanos{ }
                if(entity.isDisposed)return@LaunchedEffect
                if(!session.lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){delay(100);continue}
                val now=android.os.SystemClock.uptimeMillis()
                if(mirror!=null){
                    val renderer=connection.windowRenderer()
                    if(renderer!==resources.mirrorRenderer){
                        resources.detachMirror()
                        mirrorPresented.set(false)
                        shown=false;resources.visible=false;tracking.displayedPanes.remove(resources);entity.setEnabled(false);hiddenNow.value()
                        resources.mirrorRenderer=renderer
                        renderer?.mirrors(listOf(mirror))
                    }
                    if(!shown && mirrorPresented.get()){
                        shown=true;resources.visible=true;entity.setEnabled(true);resources.curvedInput?.entity?.setEnabled(true);publishForeground();readyNow.value()
                    }
                }
                if(!shown || !connection.isVideoActive()){delay(80);continue}
                if(inkAt==Long.MIN_VALUE || now-inkAt>=32){
                inkAt=now
                val wire=connection.cursorWires[topologyKey]
                val renderedPane=resources.rendered
                fun surfaceCoordinates(values:FloatArray):FloatArray {
                    val pane=renderedPane ?: return values
                    val rect=magnifierContentRect(pane)
                    val padX=if(showFrame).005f*pane.units/(pane.width+.01f*pane.units)else 0f
                    val padY=if(showFrame).005f*pane.units/(pane.height+.01f*pane.units)else 0f
                    return FloatArray(values.size){i->if(i%2==0)padX+(rect.left+values[i]*(rect.right-rect.left))*(1-2*padX)
                        else padY+(rect.top+values[i]*(rect.bottom-rect.top))*(1-2*padY)}
                }
                val ink=surfaceCoordinates(wire ?: floatArrayOf())
                val typed=connection.typingLocked(now) && connection.typingWindow==renderedPane?.id
                val cue=if(typed)typingIndicator(FocusRect(0f,0f,1f,1f))else floatArrayOf()
                val colors=FloatArray((ink.size+cue.size)/4*3){i->
                    if(i/3<ink.size/4)listOf(.6f,.85f,1f)[i%3] else listOf(1f,.75f,.25f)[i%3]
                }
                val overlay=VideoAim(ink+cue,opacity=.85f,colors=colors)
                resources.stream?.overlay(overlay)
                if(mirror!=null){
                    val focus=mirrorOverlayNow.value?.invoke()
                    mirrorInk.set(if(focus==null)overlay else overlay.copy(
                        segments=overlay.segments+focus.segments,colors=overlay.colors+focus.colors))
                }
                }
                val head=viewer.get() ?: continue
                if(freeMode.value && !resources.resizing) {
                    val active=resources.rendered ?: prior
                    val current=MonitorPlacement("pane","pane",active.pose,active.width,active.height,0f,active.sourceWidth,active.sourceHeight,0)
                    val wasEngaged=resources.free.engaged
                    if(!wasEngaged && resources.freeContacts.keys.containsAll(listOf("LEFT","RIGHT")) &&
                        tracking.pinched.containsAll(listOf("LEFT","RIGHT"))) {
                        for(side in listOf("LEFT","RIGHT"))resources.free.acquire(side,resources.freeContacts.getValue(side),current,tracking,active.mesh)
                    }
                    resources.free.advance(tracking,current,smoothingMs=if(desktopMirror)42f else 0f)?.let { next ->
                        resources.pending=active.copy(pose=next.pose,width=next.physicalWidthM,height=next.physicalHeightM,freePose=true,mesh=null)
                        resources.dragged=true
                    }
                    if(wasEngaged!=resources.free.engaged){
                        if(wasEngaged)finishPlacement()
                        movingNow.value(resources.free.engaged)
                    }
                    if(topologyKey!=null){
                        val guide=resources.free.guide ?: resources.freeContacts.takeIf{it.size==2}?.let{
                            previewGrabGuide(current,it.getValue("LEFT"),it.getValue("RIGHT"),tracking,active.mesh)
                        }
                        if(guide==null)tracking.paneGuides.remove(topologyKey)else tracking.paneGuides[topologyKey]=guide
                    }
                } else {
                    topologyKey?.let { tracking.paneGuides.remove(it) }
                    if(resources.free.busy){resources.free.cancel();movingNow.value(false)}
                }
                if(resources.moving && (!moveMode.value ||
                    resources.dragHandSide?.let{tracking.nowMs-resources.moveStarted>=120 && it !in tracking.pinched}==true)){
                    finishPlacement()
                    resources.drag.clear();resources.dragHandSide=null;resources.dragSide=null;resources.moving=false;movingNow.value(false)
                }
                if(resources.moving && !resources.resizing){
                    // Mixed gaze input can arrive before the joint sampler confirms which hand pinched.
                    // Bind once it becomes unambiguous, without switching hands or jumping position.
                    if(resources.dragHandSide==null){
                        val side=resources.dragSide?.takeIf{it=="LEFT" || it=="RIGHT"} ?: tracking.pinched.filter{it in tracking.points}.singleOrNull()
                        val hand=side?.let{tracking.points[it]};val pane=windowNow.value;val uv=resources.moveContact
                        if(hand!=null && pane!=null && uv!=null){
                            val flat=floatArrayOf((uv.first-.5f)*pane.width,(.5f-uv.second)*pane.height,0f)
                            val contact=transformPoint(pane.pose,pane.mesh?.project(flat) ?: flat)
                            resources.drag.begin(pane,head,head.position,FloatArray(3){contact[it]-head.position[it]},contact,hand)
                            resources.dragHandSide=side
                        }
                    }
                    val hand=resources.dragHandSide?.let{tracking.points[it]}
                    val active=windowNow.value
                    if(hand!=null && active!=null){resources.drag.moveHand(head,hand)?.let{proposed->
                        resources.dragged=true
                        resources.pending=angularMagnifierPlacement(active,head,units(),proposed,updateMesh=false)
                    }}
                }
                // Popup and companion atlas regions get their curve on opening. Keep their native mesh/material fixed until the region
                // closes so pointer motion and head tracking cannot race material replacement.
                if(transientSurface)continue
                val current=(resources.pending ?: windowNow.value)?.let(::surfaceMode) ?: continue
                resources.pending=null
                val projectionEye=if(resources.moving || resources.resizing || resources.free.engaged)head else placementViewerNow.value ?: head
                val frame=if(projectFrame)magnifierFrame(current,prior,projectionEye,units(),false)else projectPaneAtPlacement(current,prior,projectionEye,false)
                val presented=placePaneInsideDesktop(frame,projectionEye,tracking.desktopSphereRadius,prior)
                val actualPose=baseCanvas?.mesh?.let{centerPaneSphere(presented.pose,it,projectionEye)} ?: presented.pose
                val next=presented.copy(pose=actualPose,width=prior.width,height=prior.height,mesh=prior.mesh)
                if(moveMode.value){
                    resources.corners.update(session,entity,next.width,next.height,units(),next.layerDistanceM,tracking,resources.hovering.isNotEmpty() && !resources.free.engaged){active,factor->
                        if(resources.resizeStart==null){resources.resizeStart=resources.rendered;resources.dragged=true}
                        resources.resizeStart?.let{resources.pending=resizedMagnifier(it,factor)}
                        resources.resizing=active
                        if(!active){finishPlacement();resources.resizeStart=null}
                        movingNow.value(active)
                    }
                }else{
                    resources.corners.cancel()
                    if(resources.resizing){resources.resizing=false;resources.resizeStart=null;movingNow.value(false)}
                }

                val poseChanged=(0..2).any{kotlin.math.abs(next.pose.position[it]-prior.pose.position[it])>.0005f} ||
                    (0..3).any{kotlin.math.abs(next.pose.rotation[it]-prior.pose.rotation[it])>.0005f}
                updateStreamFrame(next)
                if(!poseChanged)continue
                if(poseChanged){
                    val pose=next.pose;val q=pose.rotation
                    entity.setPose(Pose(Vector3(pose.position[0],pose.position[1],pose.position[2]),Quaternion(q[0],q[1],q[2],q[3])))
                }
                resources.rendered=next;prior=next;publishForeground();placementNow.value(next,resources.dragged && !resources.resizing)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close();errorNow.value(e.message ?: "Window magnifier unavailable");failureToken++}
        finally{tracking.displayedPanes.remove(resources);topologyKey?.let{tracking.paneGuides.remove(it)};resources.detachMirror();hiddenNow.value();resources.clearInteraction();movingNow.value(false);resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;if(!retainLastFrame || !resources.hasFrame)resources.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()}
    }

}
/** Bounded startup/transition evidence; never log or allocate diagnostics each frame. */
private fun logMagnifierSubmission(reason:String,window:MagnifiedWindow,entity:SurfaceEntity) {
    val geometry=framedMagnifierGeometry(window)
    val bounds=FloatArray(6){if(it<3)Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY}
    for(i in geometry.positions.indices){val axis=i%3;bounds[axis]=minOf(bounds[axis],geometry.positions[i]);bounds[axis+3]=maxOf(bounds[axis+3],geometry.positions[i])}
    android.util.Log.i("Floatdesk","magnifier geometry reason=$reason id=${window.id} curved=${window.spherical} fixed=${window.fixed} shape=${entity.shape.javaClass.simpleName} extent=${window.width}x${window.height} units=${window.units} scale=${entity.getScale(Space.ACTIVITY)} pose=${window.pose.position.contentToString()} eye=${window.mesh?.eye?.contentToString()} radius=${window.mesh?.radius} bounds=${bounds.contentToString()} vertices=${geometry.positions.size/3}")
}

@android.annotation.SuppressLint("RestrictedApi")
private class MagnifierResources:AutoCloseable{
    var entity:SurfaceEntity?=null;var stream:dev.floatdesk.client.net.WindowCapturePool.Lease?=null
    var mirrorRenderer:VideoCompositor?=null
    fun detachMirror(){
        val renderer=mirrorRenderer ?: return
        mirrorRenderer=null
        renderer.mirrors(emptyList())
        renderer.flushOutputs()
    }
    var flatInput:InteractableComponent?=null
    var curvedInput:CurvedMonitorInput?=null
    var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var authoredSize:Pair<Int,Int>?=null
    var baseGeometry:MagnifiedWindow?=null
    var rendered:MagnifiedWindow?=null;var pending:MagnifiedWindow?=null
    val free=TwoHandState();val freeContacts=mutableMapOf<String,FloatArray>()
    val corners=PaneCornerResize();var resizing=false;var resizeStart:MagnifiedWindow?=null
    val drag=MagnifierRayDrag();var dragSide:String?=null;var dragHandSide:String?=null;var moving=false;var dragged=false
    var moveContact:Pair<Float,Float>?=null
    var moveStarted=0L
    val hovering=mutableSetOf<String>()
    val capturedHands=mutableSetOf<String>()
    var visible=false;var hasFrame=false;var generation=0;val shapes=RetainedSurfaceShapes(128,16L*1024*1024)
    fun clearInteraction(){
        if(free.busy)free.cancel();freeContacts.clear();capturedHands.clear()
        corners.cancel();drag.clear();dragSide=null;dragHandSide=null;pending=null
        resizing=false;resizeStart=null;moving=false
        moveContact=null
    }
    fun retain(shape:SurfaceEntity.Shape){shapes.retain(shape)}
    fun detachSurface(){
        curvedInput?.detach();curvedInput=null
        val surface=entity
        entity=null
        if(surface!=null && !surface.isDisposed){
            flatInput?.let{surface.removeComponent(it)}
            surface.setEnabled(false)
            surface.parent=null
        }
        flatInput=null
        rendered=null
        baseGeometry=null
        mesh=null
        hasFrame=false
    }
    override fun close(){
        detachMirror();clearInteraction();visible=false;generation++
        val lease=stream;stream=null;lease?.close()
        corners.close()
        detachSurface()
    }
}
