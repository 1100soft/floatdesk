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
    onPlacement:(MagnifiedWindow,Boolean)->Unit,onMoving:(Boolean)->Unit,onContext:()->Unit={},onHidden:()->Unit={},captureId:Long?=null,regionId:Long?=null,allowManipulation:Boolean=true,showFrame:Boolean=true){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    val tapNow=rememberUpdatedState(onTap);val windowNow=rememberUpdatedState(window);val placementNow=rememberUpdatedState(onPlacement);val movingNow=rememberUpdatedState(onMoving)
    val pinNow=rememberUpdatedState(pinned)
    val contextNow=rememberUpdatedState(onContext)
    val hiddenNow=rememberUpdatedState(onHidden)
    val resources=remember(session,root){MagnifierResources()}
    DisposableEffect(resources){onDispose{hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);hiddenNow.value();movingNow.value(false);resources.close()}}
    LaunchedEffect(resources,window?.id,captureId,regionId){
        hiddenNow.value();resources.visible=false;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false)
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)
        val initial=window ?: return@LaunchedEffect
        val target=magnifierFrame(initial,initial,viewer.get() ?: return@LaunchedEffect,units(),true)
        try{
            fun shape(surface:MagnifiedWindow):SurfaceEntity.Shape{
                // Keep the native canvas type identical across cold starts and mode changes.
                return nativeVideoMesh(if(showFrame)framedMagnifierGeometry(surface)else surface.mesh?.let{VideoGeometry(it.positions,it.videoTexCoords,it.indices)} ?: clippedVideoGeometry(surface.width,surface.height,null,floatArrayOf(0f,0f,1f,1f),0f))
            }
            val reusedSurface=resources.entity!=null
            if(resources.entity==null){
                delay(32) // Let the existing desktop's pending mesh commands flush before allocating a surface.
                val initialShape=shape(target);resources.retain(initialShape)
                val entity=SurfaceEntity.create(session,shape=initialShape,parent=root,superSampling=SurfaceEntity.SuperSampling.NONE)
                resources.entity=entity;entity.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE;entity.setEnabled(false);entity.setSurfacePixelDimensions(IntSize2d(2048,1536))
                val pointer=SpatialPointerComponent.create(session);entity.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                val listener:(InputEvent)->Unit=listener@{event->
                    val active=resources.rendered?.takeIf{resources.visible} ?: return@listener
                    val expected=resources.curved?.entity
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
                        if(event.action==InputEvent.Action.UP && !resources.dragged && !resources.contextShown && !resources.tapCommitted){resources.moveContact?.let{resources.tapCommitted=true;resources.taps.clear();tapNow.value(it)}}
                        resources.drag.clear();resources.dragSide=null;resources.dragHandSide=null;resources.moving=false;movingNow.value(false)
                    }
                    if(event.action==InputEvent.Action.DOWN){resources.holdAt=if(allowManipulation)android.os.SystemClock.uptimeMillis()else 0L;resources.contextShown=false;if(!resources.moving){resources.dragged=false;resources.tapCommitted=false}}
                    if(event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL || event.action==InputEvent.Action.HOVER_EXIT && !resources.moving)resources.holdAt=0
                    if(!resources.dragged)resources.taps.update(side,event.action.toString(),uv)?.let{if(!resources.tapCommitted){resources.tapCommitted=true;tapNow.value(it)}}
                }
                resources.listener=listener

            }
            val entity=resources.entity!!;val p=target.pose;val q=p.rotation
            val pixelSize=dev.floatdesk.client.net.WindowCaptureFormat.canvasSize(target.sourceWidth,target.sourceHeight)
            // Replacing an in-use native canvas texture can race its borrowed model material.
            // Keep the allocation stable as retained menu slots change their source bounds.
            if(!reusedSurface)entity.setSurfacePixelDimensions(IntSize2d(pixelSize.first,pixelSize.second))

            val targetPose=Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3]))
            resources.mesh=target.mesh;resources.rendered=target
            logMagnifierSubmission("start",target,entity)
            if(reusedSurface){val nextShape=shape(target);resources.retain(nextShape);entity.shape=nextShape}
            syncMagnifierInput(session,resources,entity,target.mesh,target.width,target.height)
            entity.parent=root;entity.setPose(targetPose)
            val moveTarget=entity
            if(allowManipulation){
            if(resources.resize==null){
                resources.resize=ResizableComponent.create(session,resizeEventListener=java.util.function.Consumer{event->
                    if(!resources.visible)return@Consumer
                    if(event.resizeState==ResizeEvent.ResizeState.START){
                        resources.resizing=true;resources.resizeStart=windowNow.value;resources.resizeStartWidth=event.newSize.width
                        resources.dragged=true;resources.taps.clear();movingNow.value(true)
                    }
                    val original=resources.resizeStart
                    if(original!=null && resources.resizeStartWidth>0f){
                        val factor=event.newSize.width/resources.resizeStartWidth
                        if(factor.isFinite() && factor>0f)resources.pending=resizedMagnifier(original,factor)
                    }
                    if(event.resizeState==ResizeEvent.ResizeState.END){resources.pending?.let{placementNow.value(it,resources.dragged || resources.resizing)};resources.resizing=false;resources.resizeStart=null;movingNow.value(resources.moving)}
                }).also{it.isFixedAspectRatioEnabled=true;it.isAutoHideContentWhileResizingEnabled=false;it.shouldAutoUpdateOverlay=true;it.isAlwaysShowOverlayEnabled=false}
            }
            if(resources.resizeTarget!==moveTarget){resources.resizeTarget?.removeComponent(resources.resize!!);check(moveTarget.addComponent(resources.resize!!));resources.resizeTarget=moveTarget}
            val maxWidth=(2*Math.PI.toFloat()-.04f)*target.layerDistanceM*units()/kotlin.math.sqrt(1f+target.height*target.height/(target.width*target.width))
            val minimumWidth=.05f*units()*minOf(1f,target.width/target.height)
            resources.resize!!.minimumEntitySize=FloatSize3d(minimumWidth,minimumWidth*target.height/target.width,.001f)
            resources.resize!!.maximumEntitySize=FloatSize3d(maxWidth,maxWidth*target.height/target.width,.001f)
            resources.resize!!.affordanceSize=FloatSize3d(target.width,target.height,.001f)
            }
            val generation=++resources.generation
            var shown=false
            resources.stream=connection.windowCaptures.attach(captureId ?: target.id,entity.getSurface(),{
                if(resources.generation==generation && !entity.isDisposed){shown=true;resources.visible=true;entity.setEnabled(true);resources.curved?.entity?.setEnabled(true);readyNow.value()}
            },{message->if(resources.generation==generation){resources.visible=false;entity.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);errorNow.value(message)}},regionId=regionId)
            resources.stream?.update(if(showFrame).005f*target.units/(target.width+.01f*target.units)else 0f,if(showFrame).005f*target.units/(target.height+.01f*target.units)else 0f,showFrame && pinNow.value,target.width/target.height)
            var prior=target
            var geometryAt=0L
            while(isActive){
                withFrameNanos{ }
                if(entity.isDisposed)return@LaunchedEffect
                if(!session.lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){delay(100);continue}
                val now=android.os.SystemClock.uptimeMillis()
                if(!shown)continue
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
                val next=magnifierFrame(current,prior,head,units(),updateGeometry)
                val modeChanged=next.spherical!=prior.spherical
                val previous=prior.mesh;val mesh=next.mesh
                val poseChanged=(0..2).any{kotlin.math.abs(next.pose.position[it]-prior.pose.position[it])>.0005f} ||
                    (0..3).any{kotlin.math.abs(next.pose.rotation[it]-prior.pose.rotation[it])>.0005f}
                val meshChanged=mesh!==previous && mesh!=null && (previous==null || mesh.positions.indices.any{kotlin.math.abs(mesh.positions[it]-previous.positions[it])>.0005f})
                val sizeChanged=next.width!=prior.width || next.height!=prior.height
                resources.stream?.update(if(showFrame).005f*next.units/(next.width+.01f*next.units)else 0f,if(showFrame).005f*next.units/(next.height+.01f*next.units)else 0f,showFrame && pinNow.value,next.width/next.height)
                if(next.fixed && resources.holdAt>0 && !resources.dragged && !resources.resizing && !resources.contextShown && now-resources.holdAt>=700){
                    resources.contextShown=true;resources.tapCommitted=true;resources.taps.clear();contextNow.value()
                }
                if(!resources.resizing && resources.resize?.affordanceSize?.width!=next.width){resources.resize?.affordanceSize=FloatSize3d(next.width,next.height,.001f)}
                if(!modeChanged && !poseChanged && !meshChanged && !sizeChanged)continue
                val pose=next.pose;val rotation=pose.rotation
                entity.setPose(Pose(Vector3(pose.position[0],pose.position[1],pose.position[2]),Quaternion(rotation[0],rotation[1],rotation[2],rotation[3])))
                if(modeChanged){
                    resources.clearInteraction();movingNow.value(false)
                    hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)
                }
                if(modeChanged || meshChanged || sizeChanged){
                    val nextShape=shape(next);resources.retain(nextShape);entity.shape=nextShape;geometryAt=android.os.SystemClock.uptimeMillis()
                    syncMagnifierInput(session,resources,entity,mesh,next.width,next.height)
                    if(modeChanged)logMagnifierSubmission("mode",next,entity)
                    if(!resources.resizing){
                        resources.resize?.affordanceSize=FloatSize3d(next.width,next.height,.001f)
                    }
                }
                resources.mesh=mesh;resources.rendered=next;prior=next;placementNow.value(next,resources.dragged || resources.resizing)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close();errorNow.value(e.message ?: "Window magnifier unavailable")}
        finally{hiddenNow.value();resources.clearInteraction();movingNow.value(false);resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)}
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
    var curved:CurvedMonitorInput?=null;var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var rendered:MagnifiedWindow?=null;var pending:MagnifiedWindow?=null
    var resize:ResizableComponent?=null;var resizeTarget:Entity?=null;var resizing=false;var resizeStart:MagnifiedWindow?=null;var resizeStartWidth=0f
    val drag=MagnifierRayDrag();var dragSide:String?=null;var dragHandSide:String?=null;var moving=false;var dragged=false;var tapCommitted=false
    var lastContact:Pair<Float,Float>?=null;var moveContact:Pair<Float,Float>?=null
    var holdAt=0L;var contextShown=false
    var visible=false;var generation=0;val taps=NativeSurfaceTap();val shapes=RetainedSurfaceShapes(128,16L*1024*1024)
    fun clearInteraction(){
        drag.clear();dragSide=null;dragHandSide=null;pending=null
        resizing=false;resizeStart=null;holdAt=0;moving=false
        taps.clear();lastContact=null;moveContact=null
    }
    fun retain(shape:SurfaceEntity.Shape){shapes.retain(shape)}
    override fun close(){
        clearInteraction();visible=false;generation++
        val lease=stream;stream=null;lease?.close()
        val target=resizeTarget;val component=resize;resize=null;resizeTarget=null
        if(target!=null && !target.isDisposed && component!=null)target.removeComponent(component)
        val input=curved;curved=null;input?.close()
        val surface=entity;entity=null
        if(surface!=null && !surface.isDisposed)surface.dispose()
    }
}
