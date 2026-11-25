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
    onPlacement:(MagnifiedWindow,Boolean)->Unit,onMoving:(Boolean)->Unit,onPointerHover:(hands:Boolean,gaze:Boolean)->Unit={_,_->},onHidden:()->Unit={},captureId:Long?=null,regionId:Long?=null,allowManipulation:Boolean=true,showFrame:Boolean=true,topologyKey:String?=null,receiveInput:Boolean=true,projectFrame:Boolean=true,freeManipulation:Boolean=false,desktopMirror:Boolean=false,mirrorOverlay:(()->VideoAim)?=null,onDisplayed:((Long)->Unit)?=null,projectionMode:Boolean=window?.spherical==true,transientSurface:Boolean=false,retainLastFrame:Boolean=false,previewBounds:dev.floatdesk.client.net.DesktopWindow?=null,placementViewer:dev.floatdesk.client.workspace.Pose?=null){
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
    DisposableEffect(owners){onDispose{owners.values.forEach{it.close(detachOnly=transientSurface)}}}
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
    LaunchedEffect(resources,window?.id,captureId,regionId,captureEpoch,retryToken,desktopMirror){
        hiddenNow.value();resources.visible=false;resources.stream?.close();resources.stream=null
        if(!retainLastFrame || window==null){
            resources.entity?.setEnabled(false);resources.hasFrame=false
        }
        resources.curved?.entity?.setEnabled(false)
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()
        fun surfaceMode(value:MagnifiedWindow)=if(value.spherical==projectionMode)value else value.copy(spherical=projectionMode,mesh=null)
        val initial=window?.takeIf{(desktopMirror || it.id>0L) && it.sourceWidth>0 && it.sourceHeight>0}?.let(::surfaceMode) ?: return@LaunchedEffect
        if(transientSurface)delay(80) // Let a reused shell window settle on its new size.
        while(connection.thermalPaused.value)delay(100)
        var initialViewer=viewer.get()
        while(initialViewer==null){withFrameNanos{};initialViewer=viewer.get()}
        val projectionEye=placementViewerNow.value ?: initialViewer
        val initialFrame=if(projectFrame)magnifierFrame(initial,initial,projectionEye,units(),true)else projectPaneAtPlacement(initial,initial,projectionEye,true)
        val target=placePaneInsideDesktop(initialFrame,projectionEye,tracking.desktopSphereRadius)
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
                val initialShape=shape(target);resources.retain(initialShape)
                val entity=SurfaceEntity.create(session,shape=initialShape,parent=null,superSampling=SurfaceEntity.SuperSampling.NONE,
                    mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE)
                resources.entity=entity;entity.setEnabled(false)
                if(!receiveInput)SurfaceInput.disableCollider(entity)
                val pointer=SpatialPointerComponent.create(session);entity.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                val listener:(InputEvent)->Unit=listener@{event->
                    val active=resources.rendered?.takeIf{resources.visible} ?: return@listener
                    val expected=if(transientSurface || resources.mesh==null)resources.entity else resources.curved?.entity
                    val hit=event.hitInfoList.firstOrNull{it.inputEntity===expected}
                    val mesh=resources.mesh
                    val capturedMove=event.action==InputEvent.Action.MOVE || event.action==InputEvent.Action.UP
                    val ray=lazy{surfaceInputRay(session,root,event)}
                    val local=if(transientSurface && mesh!=null && hit!=null)
                        surfaceInputHit(ray.value,active.pose,active.width,active.height,mesh)
                    else hit?.hitPosition?.let{eventMatrixHit(floatArrayOf(it.x,it.y,it.z),hit.transform.data,active.width,active.height,mesh,entity.getScale(Space.ACTIVITY),rayDirection=if(mesh!=null)floatArrayOf(event.direction.x,event.direction.y,event.direction.z)else null,rayOrigin=floatArrayOf(event.origin.x,event.origin.y,event.origin.z))}
                        ?: if(capturedMove)surfaceInputHit(ray.value,active.pose,active.width,active.height,mesh) else null
                    val flat=local?.let{mesh?.unproject(it) ?: it}
                    val uv=flat?.let{it[0]/active.width+.5f to (.5f-it[1]/active.height)}
                    val side=event.pointerType.toString()
                    if(hit!=null)updateSurfaceAimingIcon(expected,event.source==InputEvent.Source.GAZE_AND_GESTURE)
                    if(event.source==InputEvent.Source.HANDS){
                        if(event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL)hoverNow.value(side,null)
                        else if(uv!=null || transientSurface)hoverNow.value(side,uv)
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
            val pixelSize=dev.floatdesk.client.net.WindowCaptureFormat.canvasSize(target.sourceWidth,target.sourceHeight)
            // Replacing an in-use native canvas texture can race its borrowed model material.
            // Keep the allocation stable as retained menu slots change their source bounds.
            if(!reusedSurface)entity.setSurfacePixelDimensions(IntSize2d(pixelSize.first,pixelSize.second))

            val targetPose=Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3]))
            resources.mesh=target.mesh?.takeIf{RenderPolicy.CustomMeshes && !it.planar}
            if(reusedSurface && resources.rendered?.let{!samePaneGeometry(it,target)}!=false){
                val nextShape=shape(target)
                while(!connection.tryNativeMeshUpdate{resources.retain(nextShape);entity.shape=nextShape;true})withFrameNanos{}
            }
            resources.rendered=target
            placementNow.value(target,false)
            entity.parent=root;entity.setPose(targetPose)
            logMagnifierSubmission("start",target,entity)
            if(receiveInput)syncMagnifierInput(session,resources,entity,target.mesh,target.width,target.height,direct=transientSurface)
            val generation=++resources.generation
            fun publishForeground(){if(topologyKey!=null && resources.visible)resources.rendered?.let{tracking.displayedPanes[resources]=it}}
        var shown=false
            if(!desktopMirror)resources.stream=connection.windowCaptures.attach(captureId ?: target.id,entity.getSurface(),{
                if(resources.generation==generation && !entity.isDisposed){shown=true;retryPolicy.reset();failureToken=0;resources.visible=true;resources.hasFrame=true;entity.setEnabled(true);publishForeground();resources.curved?.entity?.setEnabled(resources.mesh!=null);android.util.Log.i("Floatdesk","pane ready key=$topologyKey id=${target.id}");displayedNow.value?.invoke(target.id);readyNow.value()}
            },{message->if(resources.generation==generation){resources.visible=false;tracking.displayedPanes.remove(resources);if(!retainLastFrame || !resources.hasFrame)entity.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover();errorNow.value(message);failureToken++}},regionId=regionId,previewBounds=previewBounds)
            resources.stream?.update(if(showFrame).005f*target.units/(target.width+.01f*target.units)else 0f,if(showFrame).005f*target.units/(target.height+.01f*target.units)else 0f,showFrame && pinNow.value,target.width/target.height)
            var prior=target
            var geometryAt=0L
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
                        shown=false;resources.visible=false;tracking.displayedPanes.remove(resources);entity.setEnabled(false);resources.curved?.entity?.setEnabled(false);hiddenNow.value()
                        resources.mirrorRenderer=renderer
                        renderer?.mirrors(listOf(mirror))
                    }
                    if(!shown && mirrorPresented.get()){
                        shown=true;resources.visible=true;entity.setEnabled(true);publishForeground();resources.curved?.entity?.setEnabled(resources.mesh!=null);readyNow.value()
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
                val location=connection.cursorLocation?.takeIf{it.key==topologyKey}
                val glow=surfaceCoordinates(location?.let{loc->renderedPane?.let{p->cursorGlow(loc.u,loc.v,p.width,p.height,p.layerDistanceM*p.units)}} ?: floatArrayOf())
                val colors=FloatArray((ink.size+cue.size+glow.size)/4*3){i->if(i/3<ink.size/4)listOf(.6f,.85f,1f)[i%3]else if(i/3<(ink.size+cue.size)/4)listOf(1f,.75f,.25f)[i%3]else listOf(0f,1f,1f)[i%3]}
                val overlay=VideoAim(ink+cue+glow,opacity=.85f,colors=colors)
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
                val geometryPeriod=if(connection.limitOptionalRendering)200 else 100
                val updateGeometry=now-geometryAt>=geometryPeriod
                if(updateGeometry)geometryAt=now
                // A popup gets its curve on opening. Keep its native mesh/material fixed until it
                // closes so pointer motion and head tracking cannot race material replacement.
                if(transientSurface)continue
                val current=(resources.pending ?: windowNow.value)?.let(::surfaceMode) ?: continue
                resources.pending=null
                val projectionEye=if(resources.moving || resources.resizing || resources.free.engaged)head else placementViewerNow.value ?: head
                val frame=if(projectFrame)magnifierFrame(current,prior,projectionEye,units(),updateGeometry)else projectPaneAtPlacement(current,prior,projectionEye,updateGeometry)
                val next=placePaneInsideDesktop(frame,projectionEye,tracking.desktopSphereRadius,prior)
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

                val modeChanged=next.spherical!=prior.spherical
                val mesh=next.mesh
                val poseChanged=(0..2).any{kotlin.math.abs(next.pose.position[it]-prior.pose.position[it])>.0005f} ||
                    (0..3).any{kotlin.math.abs(next.pose.rotation[it]-prior.pose.rotation[it])>.0005f}
                val geometryChanged=!samePaneGeometry(prior,next)
                resources.stream?.update(if(showFrame).005f*next.units/(next.width+.01f*next.units)else 0f,if(showFrame).005f*next.units/(next.height+.01f*next.units)else 0f,showFrame && pinNow.value,next.width/next.height)
                if(!poseChanged && !geometryChanged)continue
                if(geometryChanged){
                    val submit = {
                        val nextShape=shape(next)
                        resources.retain(nextShape)
                        entity.shape=nextShape
                    }
                    val submitted=connection.tryNativeMeshUpdate {
                        resources.stream?.tryShapeUpdate(submit)
                            ?: resources.mirrorRenderer?.tryShapeUpdate(submit)
                            ?: run {submit();true}
                    }
                    if(!submitted)continue
                    if(receiveInput)syncMagnifierInput(session,resources,entity,mesh,next.width,next.height)
                    if(modeChanged)logMagnifierSubmission("mode",next,entity)
                }
                if(poseChanged){
                    val pose=next.pose;val q=pose.rotation
                    entity.setPose(Pose(Vector3(pose.position[0],pose.position[1],pose.position[2]),Quaternion(q[0],q[1],q[2],q[3])))
                }
                if(modeChanged){
                    resources.clearInteraction();movingNow.value(false)
                    hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()
                }
                resources.mesh=mesh?.takeIf{RenderPolicy.CustomMeshes && !it.planar};resources.rendered=next;publishForeground();prior=next;placementNow.value(next,resources.dragged || resources.resizing)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close(detachOnly=transientSurface);errorNow.value(e.message ?: "Window magnifier unavailable");failureToken++}
        finally{tracking.displayedPanes.remove(resources);topologyKey?.let{tracking.paneGuides.remove(it)};resources.detachMirror();hiddenNow.value();resources.clearInteraction();movingNow.value(false);resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;if(!retainLastFrame || !resources.hasFrame)resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()}
    }

}
/** Bounded startup/transition evidence; never log or allocate diagnostics each frame. */
private fun logMagnifierSubmission(reason:String,window:MagnifiedWindow,entity:SurfaceEntity) {
    val geometry=framedMagnifierGeometry(window)
    val bounds=FloatArray(6){if(it<3)Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY}
    for(i in geometry.positions.indices){val axis=i%3;bounds[axis]=minOf(bounds[axis],geometry.positions[i]);bounds[axis+3]=maxOf(bounds[axis+3],geometry.positions[i])}
    android.util.Log.i("Floatdesk","magnifier geometry reason=$reason id=${window.id} curved=${window.spherical} fixed=${window.fixed} shape=${entity.shape.javaClass.simpleName} extent=${window.width}x${window.height} units=${window.units} scale=${entity.getScale(Space.ACTIVITY)} pose=${window.pose.position.contentToString()} eye=${window.mesh?.eye?.contentToString()} radius=${window.mesh?.radius} bounds=${bounds.contentToString()} vertices=${geometry.positions.size/3}")
}

/** Bind input to the geometry actually rendered, without reopening the capture lease. */
@android.annotation.SuppressLint("RestrictedApi")
private suspend fun syncMagnifierInput(session:Session,resources:MagnifierResources,entity:SurfaceEntity,mesh:MonitorMesh?,width:Float,height:Float,direct:Boolean=false) {
    val curvedMesh=mesh?.takeIf{RenderPolicy.CustomMeshes && !it.planar}
    resources.mesh=curvedMesh
    if(curvedMesh==null || direct){
        resources.curved?.entity?.setEnabled(false)
        if(resources.flatInput==null){
            resources.flatInput=NativeMonitorInput.create(session,resources.listener!!)
            check(entity.addComponent(resources.flatInput!!))
        }
        return
    }

    resources.flatInput?.let{entity.removeComponent(it);resources.flatInput=null}
    if(resources.curved==null) {
        val generation=resources.generation
        val next=CurvedMonitorInput.create(session,entity,curvedMesh,6,4,width,height,resources.listener!!)
        if(resources.entity!==entity || resources.generation!=generation) {
            next.close()
            throw kotlinx.coroutines.CancellationException("Magnifier input owner changed")
        }
        resources.curved=next
    } else resources.curved!!.updateGeometry(curvedMesh,width,height)
    resources.curved!!.entity.let {
        it.parent=entity;it.setPose(Pose());it.setEnabled(resources.visible)
    }
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
    var curved:CurvedMonitorInput?=null;var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var rendered:MagnifiedWindow?=null;var pending:MagnifiedWindow?=null
    val free=TwoHandState();val freeContacts=mutableMapOf<String,FloatArray>()
    val corners=PaneCornerResize();var resizing=false;var resizeStart:MagnifiedWindow?=null
    val drag=MagnifierRayDrag();var dragSide:String?=null;var dragHandSide:String?=null;var moving=false;var dragged=false
    var moveContact:Pair<Float,Float>?=null
    var moveStarted=0L
    val hovering=mutableSetOf<String>()
    var visible=false;var hasFrame=false;var generation=0;val shapes=RetainedSurfaceShapes(128,16L*1024*1024)
    fun clearInteraction(){
        if(free.busy)free.cancel();freeContacts.clear()
        corners.cancel();drag.clear();dragSide=null;dragHandSide=null;pending=null
        resizing=false;resizeStart=null;moving=false
        moveContact=null
    }
    fun retain(shape:SurfaceEntity.Shape){shapes.retain(shape)}
    override fun close()=close(detachOnly=false)
    fun close(detachOnly:Boolean){
        detachMirror();clearInteraction();visible=false;hasFrame=false;generation++
        val lease=stream;stream=null;lease?.close()
        flatInput?.let{component->entity?.takeUnless{it.isDisposed}?.removeComponent(component)};flatInput=null
        corners.close()
        val input=curved;curved=null;input?.close()
        val surface=entity;entity=null
        if(surface!=null && !surface.isDisposed){
            if(detachOnly){surface.setEnabled(false);surface.parent=null}else surface.dispose()
        }
    }
}
