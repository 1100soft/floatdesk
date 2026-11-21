package dev.floatdesk.client

import androidx.compose.runtime.*
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.*
import androidx.xr.scenecore.*
import dev.floatdesk.client.media.*
import dev.floatdesk.client.net.DeskConnection
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
    onReady:()->Unit,onError:(String)->Unit,onHover:(String,Pair<Float,Float>?)->Unit,onTap:(Pair<Float,Float>)->Unit,
    onPlacement:(MagnifiedWindow,Boolean)->Unit,onMoving:(Boolean)->Unit,onPointerHover:(hands:Boolean,gaze:Boolean)->Unit={_,_->},onHidden:()->Unit={},captureId:Long?=null,regionId:Long?=null,allowManipulation:Boolean=true,showFrame:Boolean=true,topologyKey:String?=null,receiveInput:Boolean=true,projectFrame:Boolean=true){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    val tapNow=rememberUpdatedState(onTap);val windowNow=rememberUpdatedState(window);val placementNow=rememberUpdatedState(onPlacement);val movingNow=rememberUpdatedState(onMoving)
    val pinNow=rememberUpdatedState(pinned)
    val pointerHoverNow=rememberUpdatedState(onPointerHover)
    val hiddenNow=rememberUpdatedState(onHidden)
    val resources=remember(session,root){MagnifierResources()}
    fun clearPointerHover(){resources.hovering.clear();pointerHoverNow.value(false,false)}
    val captureEpoch by connection.windowCaptureEpoch
    var failureToken by remember(resources){mutableIntStateOf(0)}
    var retryToken by remember(resources){mutableIntStateOf(0)}
    val retryPolicy=remember(resources){dev.floatdesk.client.net.WindowCaptureRetry()}
    LaunchedEffect(failureToken,window?.id){
        if(failureToken>0 && window!=null){delay(retryPolicy.nextDelayMs());retryToken++}
    }
    DisposableEffect(resources){onDispose{hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover();hiddenNow.value();movingNow.value(false);resources.close()}}
    LaunchedEffect(resources,window?.id,captureId,regionId,captureEpoch,retryToken){
        hiddenNow.value();resources.visible=false;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false)
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()
        val initial=window?.takeIf{it.id>0L && it.sourceWidth>0 && it.sourceHeight>0} ?: return@LaunchedEffect
        var initialViewer=viewer.get()
        while(initialViewer==null){withFrameNanos{};initialViewer=viewer.get()}
        val target=if(projectFrame)magnifierFrame(initial,initial,initialViewer,units(),true)else initial
        try{
            fun shape(surface:MagnifiedWindow):SurfaceEntity.Shape{
                if(!RenderPolicy.CustomMeshes){
                    val border=if(showFrame).01f*surface.units else 0f
                    return SurfaceEntity.Shape.Quad(FloatSize2d(surface.width+border,surface.height+border))
                }
                return nativeVideoMesh(if(showFrame)framedMagnifierGeometry(surface)else surface.mesh?.let{VideoGeometry(it.positions,it.videoTexCoords,it.indices)} ?: clippedVideoGeometry(surface.width,surface.height,null,floatArrayOf(0f,0f,1f,1f),0f))
            }
            val reusedSurface=resources.entity!=null
            if(resources.entity==null){
                delay(32) // Let the existing desktop's pending mesh commands flush before allocating a surface.
                val initialShape=shape(target);resources.retain(initialShape)
                val entity=SurfaceEntity.create(session,shape=initialShape,parent=root,superSampling=SurfaceEntity.SuperSampling.NONE)
                resources.entity=entity;entity.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE;entity.setEnabled(false)
                val pointer=SpatialPointerComponent.create(session);entity.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                val listener:(InputEvent)->Unit=listener@{event->
                    val active=resources.rendered?.takeIf{resources.visible} ?: return@listener
                    val expected=if(!RenderPolicy.CustomMeshes)resources.entity else resources.curved?.entity
                    val hit=event.hitInfoList.firstOrNull{it.inputEntity===expected}
                    val mesh=resources.mesh
                    val local=hit?.hitPosition?.let{eventMatrixHit(floatArrayOf(it.x,it.y,it.z),hit.transform.data,active.width,active.height,mesh,entity.getScale(Space.ACTIVITY),rayDirection=if(mesh!=null)floatArrayOf(event.direction.x,event.direction.y,event.direction.z)else null,rayOrigin=floatArrayOf(event.origin.x,event.origin.y,event.origin.z))}
                    val flat=local?.let{mesh?.unproject(it) ?: it}
                    val uv=flat?.let{it[0]/active.width+.5f to (.5f-it[1]/active.height)}
                    val side=event.pointerType.toString()
                    if(event.source==InputEvent.Source.HANDS){
                        if(event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL)hoverNow.value(side,null)
                        else if(uv!=null)hoverNow.value(side,uv)
                    }
                    if(uv!=null)resources.lastContact=uv
                    val pointerKey=(if(event.source==InputEvent.Source.GAZE_AND_GESTURE)"gaze:" else "hand:")+side
                    val pointing=uv!=null && event.action!=InputEvent.Action.HOVER_EXIT && event.action!=InputEvent.Action.CANCEL
                    if(if(pointing)resources.hovering.add(pointerKey)else resources.hovering.remove(pointerKey))
                        pointerHoverNow.value(resources.hovering.any{it.startsWith("hand:")},resources.hovering.any{it.startsWith("gaze:")})
                    fun commitTap(point:Pair<Float,Float>){
                        tapNow.value(point)
                        val key=topologyKey ?: return
                        val source=connection.windowCaptures.source(active.id) ?: return
                        val bounds=connection.workspaceInfo.value?.bounds() ?: return
                        val uv=magnifierContentPoint(active,point)
                        connection.claimCursor(key,(source.x+uv.first*source.width-bounds[0])/bounds[2],(source.y+uv.second*source.height-bounds[1])/bounds[3])
                    }
                    fun rootRay():Pair<FloatArray,FloatArray>{
                        fun point(v:Vector3):FloatArray{val p=session.scene.activitySpace.transformPoseTo(Pose(v),root).translation;return floatArrayOf(p.x,p.y,p.z)}
                        val origin=point(event.origin)
                        val end=point(Vector3(event.origin.x+event.direction.x,event.origin.y+event.direction.y,event.origin.z+event.direction.z))
                        return origin to FloatArray(3){end[it]-origin[it]}
                    }
                    if(allowManipulation && event.action==InputEvent.Action.DOWN && local!=null && !resources.resizing && !resources.moving){
                        val head=viewer.get()
                        if(head!=null){resources.dragged=false;resources.tapCommitted=false;resources.taps.clear();val ray=rootRay()
                            val handSide=side.takeIf{it=="LEFT" || it=="RIGHT"} ?: tracking.pinched.filter{it in tracking.points}.singleOrNull()
                            val hand=handSide?.let{tracking.points[it]}
                            resources.drag.begin(active,head,ray.first,ray.second,transformPoint(active.pose,local),hand)
                            resources.dragHandSide=handSide.takeIf{hand!=null};resources.dragSide=side;resources.moveContact=uv;resources.moving=true;movingNow.value(true)}
                    }
                    if(event.action==InputEvent.Action.MOVE && resources.moving && side==resources.dragSide && resources.dragHandSide==null && !resources.resizing){
                        val head=viewer.get()
                        if(head!=null){val ray=rootRay();val proposed=resources.drag.move(head,ray.first,ray.second)
                            if(proposed!=null){if(!resources.dragged)movingNow.value(true);resources.dragged=true;resources.taps.clear();resources.pending=if(active.fixed)fixedMagnifierGeometry(active,head,proposed)else magnifierGeometry(active,head,units(),proposed)}
                        }
                    }
                    if((event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL) && side==resources.dragSide){
                        resources.pending?.let{placementNow.value(it,resources.dragged || resources.resizing)}
                        if(event.action==InputEvent.Action.UP && !resources.dragged && !resources.tapCommitted){resources.moveContact?.let{resources.tapCommitted=true;resources.taps.clear();commitTap(it)}}
                        resources.drag.clear();resources.dragSide=null;resources.dragHandSide=null;resources.moving=false;movingNow.value(false)
                    }
                    if(event.action==InputEvent.Action.DOWN && !resources.moving){resources.dragged=false;resources.tapCommitted=false}
                    if(!resources.dragged)resources.taps.update(side,event.action.toString(),uv)?.let{if(!resources.tapCommitted){resources.tapCommitted=true;commitTap(it)}}
                }
                resources.listener=listener

            }
            val entity=resources.entity!!;val p=target.pose;val q=p.rotation
            val pixelSize=dev.floatdesk.client.net.WindowCaptureFormat.canvasSize(target.sourceWidth,target.sourceHeight)
            // Replacing an in-use native canvas texture can race its borrowed model material.
            // Keep the allocation stable as retained menu slots change their source bounds.
            if(!reusedSurface)entity.setSurfacePixelDimensions(IntSize2d(pixelSize.first,pixelSize.second))

            val targetPose=Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3]))
            resources.mesh=target.mesh
            logMagnifierSubmission("start",target,entity)
            if(reusedSurface && resources.rendered?.let{it.width!=target.width || it.height!=target.height || it.mesh!==target.mesh}!=false){val nextShape=shape(target);resources.retain(nextShape);entity.shape=nextShape}
            resources.rendered=target
            if(receiveInput)syncMagnifierInput(session,resources,entity,target.mesh,target.width,target.height)
            entity.parent=root;entity.setPose(targetPose)
            val generation=++resources.generation
            var shown=false
            resources.stream=connection.windowCaptures.attach(captureId ?: target.id,entity.getSurface(),{
                if(resources.generation==generation && !entity.isDisposed){shown=true;retryPolicy.reset();failureToken=0;resources.visible=true;entity.setEnabled(true);resources.curved?.entity?.setEnabled(true);android.util.Log.i("Floatdesk","pane ready key=$topologyKey id=${target.id}");readyNow.value()}
            },{message->if(resources.generation==generation){resources.visible=false;entity.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover();errorNow.value(message);failureToken++}},regionId=regionId)
            resources.stream?.update(if(showFrame).005f*target.units/(target.width+.01f*target.units)else 0f,if(showFrame).005f*target.units/(target.height+.01f*target.units)else 0f,showFrame && pinNow.value,target.width/target.height)
            var prior=target
            var geometryAt=0L
            while(isActive){
                withFrameNanos{ }
                if(entity.isDisposed)return@LaunchedEffect
                if(!session.lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){delay(100);continue}
                val now=android.os.SystemClock.uptimeMillis()
                if(!shown)continue
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
                resources.stream?.overlay(VideoAim(ink+cue+glow,opacity=.85f,colors=colors))
                val head=viewer.get() ?: continue
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
                        resources.dragged=true;resources.taps.clear()
                        resources.pending=if(active.fixed)fixedMagnifierGeometry(active,head,proposed,updateMesh=false)else magnifierGeometry(active,head,units(),proposed,updateMesh=false)
                    }}
                }
                val updateGeometry=now-geometryAt>=if(windowNow.value?.fixed==true)100 else 50
                val current=resources.pending ?: windowNow.value ?: continue
                resources.pending=null
                val next=if(projectFrame)magnifierFrame(current,prior,head,units(),updateGeometry)else current
                if(allowManipulation){
                    resources.corners.update(session,entity,next.width,next.height,units(),tracking){active,factor->
                        if(resources.resizeStart==null){resources.resizeStart=resources.rendered;resources.dragged=true;resources.taps.clear()}
                        resources.resizeStart?.let{resources.pending=resizedMagnifier(it,factor)}
                        resources.resizing=active
                        if(!active){resources.pending?.let{placementNow.value(it,true)};resources.resizeStart=null}
                        movingNow.value(active)
                    }
                }

                val modeChanged=next.spherical!=prior.spherical
                val previous=prior.mesh;val mesh=next.mesh
                val poseChanged=(0..2).any{kotlin.math.abs(next.pose.position[it]-prior.pose.position[it])>.0005f} ||
                    (0..3).any{kotlin.math.abs(next.pose.rotation[it]-prior.pose.rotation[it])>.0005f}
                val meshChanged=mesh!==previous && mesh!=null && (previous==null || mesh.positions.indices.any{kotlin.math.abs(mesh.positions[it]-previous.positions[it])>.0005f})
                val sizeChanged=next.width!=prior.width || next.height!=prior.height
                resources.stream?.update(if(showFrame).005f*next.units/(next.width+.01f*next.units)else 0f,if(showFrame).005f*next.units/(next.height+.01f*next.units)else 0f,showFrame && pinNow.value,next.width/next.height)
                if(!modeChanged && !poseChanged && !meshChanged && !sizeChanged)continue
                val pose=next.pose;val rotation=pose.rotation
                entity.setPose(Pose(Vector3(pose.position[0],pose.position[1],pose.position[2]),Quaternion(rotation[0],rotation[1],rotation[2],rotation[3])))
                if(modeChanged){
                    resources.clearInteraction();movingNow.value(false)
                    hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()
                }
                if(modeChanged || meshChanged || sizeChanged){
                    val nextShape=shape(next);resources.retain(nextShape);entity.shape=nextShape;geometryAt=android.os.SystemClock.uptimeMillis()
                    if(receiveInput)syncMagnifierInput(session,resources,entity,mesh,next.width,next.height)
                    if(modeChanged)logMagnifierSubmission("mode",next,entity)

                }
                resources.mesh=if(RenderPolicy.CustomMeshes)mesh else null;resources.rendered=next;prior=next;placementNow.value(next,resources.dragged || resources.resizing)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close();errorNow.value(e.message ?: "Window magnifier unavailable")}
        finally{hiddenNow.value();resources.clearInteraction();movingNow.value(false);resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);clearPointerHover()}
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
private suspend fun syncMagnifierInput(session:Session,resources:MagnifierResources,entity:SurfaceEntity,mesh:MonitorMesh?,width:Float,height:Float) {
    if(!RenderPolicy.CustomMeshes){
        resources.mesh=null
        if(resources.flatInput==null){
            resources.flatInput=NativeMonitorInput.create(session,resources.listener!!)
            check(entity.addComponent(resources.flatInput!!))
        }
        return
    }

    if(resources.curved==null) {
        val generation=resources.generation
        val next=CurvedMonitorInput.create(session,entity,mesh,6,4,width,height,resources.listener!!)
        if(resources.entity!==entity || resources.generation!=generation) {
            next.close()
            throw kotlinx.coroutines.CancellationException("Magnifier input owner changed")
        }
        resources.curved=next
    } else resources.curved!!.updateGeometry(mesh,width,height)
    resources.curved!!.entity.let {
        it.parent=entity;it.setPose(Pose());it.setEnabled(resources.visible)
    }
}

@android.annotation.SuppressLint("RestrictedApi")
private class MagnifierResources:AutoCloseable{
    var entity:SurfaceEntity?=null;var stream:dev.floatdesk.client.net.WindowCapturePool.Lease?=null
    var flatInput:InteractableComponent?=null
    var curved:CurvedMonitorInput?=null;var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var rendered:MagnifiedWindow?=null;var pending:MagnifiedWindow?=null
    val corners=PaneCornerResize();var resizing=false;var resizeStart:MagnifiedWindow?=null
    val drag=MagnifierRayDrag();var dragSide:String?=null;var dragHandSide:String?=null;var moving=false;var dragged=false;var tapCommitted=false
    var lastContact:Pair<Float,Float>?=null;var moveContact:Pair<Float,Float>?=null
    val hovering=mutableSetOf<String>()
    var visible=false;var generation=0;val taps=NativeSurfaceTap();val shapes=RetainedSurfaceShapes(128,16L*1024*1024)
    fun clearInteraction(){
        corners.cancel();drag.clear();dragSide=null;dragHandSide=null;pending=null
        resizing=false;resizeStart=null;moving=false
        taps.clear();lastContact=null;moveContact=null
    }
    fun retain(shape:SurfaceEntity.Shape){shapes.retain(shape)}
    override fun close(){
        clearInteraction();visible=false;generation++
        val lease=stream;stream=null;lease?.close()
        flatInput?.let{component->entity?.takeUnless{it.isDisposed}?.removeComponent(component)};flatInput=null
        corners.close()
        val input=curved;curved=null;input?.close()
        val surface=entity;entity=null
        if(surface!=null && !surface.isDisposed)surface.dispose()
    }
}
