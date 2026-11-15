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
    units:()->Float,
    onReady:()->Unit,onError:(String)->Unit,onHover:(String,Pair<Float,Float>?)->Unit,onTap:(Pair<Float,Float>)->Unit,
    onPlacement:(MagnifiedWindow)->Unit,onMoving:(Boolean)->Unit){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    val tapNow=rememberUpdatedState(onTap);val windowNow=rememberUpdatedState(window);val placementNow=rememberUpdatedState(onPlacement);val movingNow=rememberUpdatedState(onMoving)
    val pinNow=rememberUpdatedState(pinned)
    val resources=remember(session,root){MagnifierResources()}
    DisposableEffect(resources){onDispose{resources.close()}}
    LaunchedEffect(resources,window?.id,window?.spherical,window?.sourceWidth,window?.sourceHeight){
        resources.visible=false;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false)
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)
        val target=window ?: return@LaunchedEffect
        try{
            fun shape(surface:MagnifiedWindow):SurfaceEntity.Shape{
                if(surface.mesh==null)return SurfaceEntity.Shape.Quad(FloatSize2d(surface.width,surface.height))
                val geometry=clippedVideoGeometry(surface.width,surface.height,surface.mesh,floatArrayOf(0f,0f,1f,1f),0f)
                return SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(FloatBuffer.wrap(geometry.positions),FloatBuffer.wrap(geometry.texCoords),IntBuffer.wrap(geometry.indices)))
            }
            val reusedSurface=resources.entity!=null
            if(resources.entity==null){
                delay(32) // Let the existing desktop's pending mesh commands flush before allocating a surface.
                val initialShape=shape(target);resources.retain(initialShape)
                val entity=SurfaceEntity.create(session,shape=initialShape,parent=root,superSampling=SurfaceEntity.SuperSampling.NONE)
                resources.entity=entity;entity.setEnabled(false);entity.setSurfacePixelDimensions(IntSize2d(2048,1536))
                val pointer=SpatialPointerComponent.create(session);entity.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                val listener:(InputEvent)->Unit=listener@{event->
                    val active=windowNow.value?.takeIf{resources.visible} ?: return@listener
                    val expected=if(active.spherical)resources.curved?.entity else entity
                    val hit=event.hitInfoList.firstOrNull{it.inputEntity===expected}
                    val mesh=resources.mesh
                    val local=hit?.hitPosition?.let{eventMatrixHit(floatArrayOf(it.x,it.y,it.z),hit.transform.data,active.width,active.height,mesh,entity.getScale(Space.ACTIVITY))}
                    val flat=local?.let{mesh?.unproject(it) ?: it}
                    val uv=flat?.let{it[0]/active.width+.5f to (.5f-it[1]/active.height)}
                    val side=event.pointerType.toString()
                    if(event.source==InputEvent.Source.HANDS)hoverNow.value(side,if(event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL)null else uv)
                    if(uv!=null)resources.lastContact=uv
                    if(event.action==InputEvent.Action.DOWN && !resources.moving){resources.dragged=false;resources.tapCommitted=false}
                    if(!resources.dragged)resources.taps.update(side,event.action.toString(),uv)?.let{if(!resources.tapCommitted){resources.tapCommitted=true;tapNow.value(it)}}
                }
                resources.listener=listener
                val input=NativeMonitorInput.create(session,listener);resources.input=input;entity.addComponent(input);resources.flatInputAttached=true
                resources.video=VideoCompositor(2048,1536).also{it.output(entity.getSurface())}
            }
            val entity=resources.entity!!;val p=target.pose;val q=p.rotation
            val scale=minOf(1.0,2048.0/target.sourceWidth,2048.0/target.sourceHeight,kotlin.math.sqrt(2_097_152.0/(target.sourceWidth.toDouble()*target.sourceHeight)))
            entity.setSurfacePixelDimensions(IntSize2d(((target.sourceWidth*scale).toInt()/2*2).coerceAtLeast(32),((target.sourceHeight*scale).toInt()/2*2).coerceAtLeast(32)))
            resources.video!!.output(entity.getSurface(),force=true)
            val targetPose=Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3]))
            resources.mesh=target.mesh;resources.rendered=target
            if(reusedSurface){val nextShape=shape(target);resources.retain(nextShape);entity.shape=nextShape}
            if(target.mesh!=null && resources.flatInputAttached){resources.input?.let{entity.removeComponent(it)};resources.flatInputAttached=false}
            if(target.mesh==null && !resources.flatInputAttached){resources.input?.let{entity.addComponent(it)};resources.flatInputAttached=true}
            if(target.mesh!=null){
                if(resources.curved==null){delay(32);resources.curved=CurvedMonitorInput.create(session,root,target.mesh,resources.listener!!)}
                else resources.curved!!.update(target.mesh)
            }
            // Keep the movable body's parent fixed. Moving a collider's parent instead
            // would feed our previous movement back into the next native pose.
            if(target.spherical){
                val body=resources.curved!!.entity
                body.parent=root;body.setPose(targetPose)
                entity.parent=body;entity.setPose(Pose())
            }else{entity.parent=root;entity.setPose(targetPose)}
            if(resources.move==null){
                resources.move=MovableComponent.createCustomMovable(session,scaleInZ=false,executor=null,entityMoveListener=object:EntityMoveListener{
                    private var start:FloatArray?=null
                    private fun rootPosition(entity:Entity,pose:Pose):FloatArray{
                        val parent=entity.parent ?: root
                        val p=parent.transformPoseTo(pose,root).translation
                        return floatArrayOf(p.x,p.y,p.z)
                    }
                    override fun onMoveStart(entity:Entity,initialInputRay:Ray,initialPose:Pose,initialScale:Float,initialParent:Entity){
                        if(!resources.visible || resources.resizing)return
                        resources.moving=true;resources.dragged=false;resources.tapCommitted=false;resources.moveContact=resources.lastContact;start=rootPosition(entity,initialPose)
                        movingNow.value(true)
                    }
                    override fun onMoveUpdate(entity:Entity,currentInputRay:Ray,currentPose:Pose,currentScale:Float){
                        if(!resources.moving || !resources.visible || resources.resizing)return
                        val head=viewer.get() ?: return
                        val current=windowNow.value ?: return
                        val proposed=rootPosition(entity,currentPose)
                        if(!proposed.all{it.isFinite()})return
                        val first=start ?: proposed
                        val moved=kotlin.math.sqrt((0..2).sumOf{val d=proposed[it]-first[it];(d*d).toDouble()})>.008*units()
                        if(moved || resources.dragged){
                            resources.dragged=true;resources.taps.clear()
                            val next=magnifierGeometry(current,head,units(),proposed)
                            resources.pending=next;movingNow.value(true)
                        }
                    }
                    override fun onMoveEnd(entity:Entity,finalInputRay:Ray,finalPose:Pose,finalScale:Float,updatedParent:Entity?){
                        if(!resources.visible || !resources.moving)return
                        onMoveUpdate(entity,finalInputRay,finalPose,finalScale)
                        resources.moving=false;movingNow.value(false)
                        if(!resources.dragged && !resources.tapCommitted)resources.moveContact?.let{resources.tapCommitted=true;resources.taps.clear();tapNow.value(it)}
                    }
                })
            }
            val moveTarget=if(target.spherical)resources.curved!!.entity else entity
            if(resources.moveTarget!==moveTarget){
                resources.moveTarget?.removeComponent(resources.move!!)
                check(moveTarget.addComponent(resources.move!!));resources.moveTarget=moveTarget
            }
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
                    if(event.resizeState==ResizeEvent.ResizeState.END){resources.resizing=false;resources.resizeStart=null;movingNow.value(resources.moving)}
                }).also{it.isFixedAspectRatioEnabled=true;it.isAutoHideContentWhileResizingEnabled=false;it.shouldAutoUpdateOverlay=true;it.isAlwaysShowOverlayEnabled=false}
            }
            if(resources.resizeTarget!==moveTarget){resources.resizeTarget?.removeComponent(resources.resize!!);check(moveTarget.addComponent(resources.resize!!));resources.resizeTarget=moveTarget}
            val maxWidth=(2*Math.PI.toFloat()-.04f)*MagnifierDistanceM*units()/kotlin.math.sqrt(1f+target.height*target.height/(target.width*target.width))
            resources.resize!!.minimumEntitySize=FloatSize3d(.05f*units(),.05f*units()*target.height/target.width,.001f)
            resources.resize!!.maximumEntitySize=FloatSize3d(maxWidth,maxWidth*target.height/target.width,.001f)
            resources.resize!!.affordanceSize=FloatSize3d(target.width,target.height,.001f)
            resources.move!!.size=FloatSize3d(target.width,target.height,.001f)
            val generation=++resources.generation
            var shown=false
            val firstFrame=java.util.concurrent.atomic.AtomicBoolean(false)
            resources.video!!.onBasePresented{if(firstFrame.compareAndSet(false,true))android.os.Handler(android.os.Looper.getMainLooper()).post{
                if(resources.generation==generation && !shown){shown=true;resources.visible=true;entity.setEnabled(true);resources.curved?.entity?.setEnabled(target.spherical);readyNow.value()}
            }}
            resources.stream=connection.openWindowVideo(target.id,resources.video!!,failed={message->android.os.Handler(android.os.Looper.getMainLooper()).post{if(resources.generation==generation)errorNow.value(message)}})
            var prior=target
            var geometryAt=0L
            while(isActive){
                withFrameNanos{ }
                val now=android.os.SystemClock.uptimeMillis()
                if(!shown)continue
                val head=viewer.get() ?: continue
                val updateGeometry=now-geometryAt>=50
                val current=(if(updateGeometry)resources.pending ?: windowNow.value else windowNow.value) ?: continue
                if(updateGeometry)resources.pending=null
                val next=magnifierGeometry(current,head,units())
                val previous=prior.mesh;val mesh=next.mesh
                val poseChanged=(0..2).any{kotlin.math.abs(next.pose.position[it]-prior.pose.position[it])>.0005f} ||
                    (0..3).any{kotlin.math.abs(next.pose.rotation[it]-prior.pose.rotation[it])>.0005f}
                val meshChanged=mesh!==previous && mesh!=null && (previous==null || mesh.positions.indices.any{kotlin.math.abs(mesh.positions[it]-previous.positions[it])>.0005f})
                val sizeChanged=next.width!=prior.width || next.height!=prior.height
                resources.border?.update(resources.moveTarget!!,next,pinNow.value)
                if(!resources.resizing && resources.resize?.affordanceSize?.width!=next.width){resources.move?.size=FloatSize3d(next.width,next.height,.001f);resources.resize?.affordanceSize=FloatSize3d(next.width,next.height,.001f)}
                if(!poseChanged && !meshChanged && !sizeChanged)continue
                val pose=next.pose;val rotation=pose.rotation
                resources.moveTarget?.setPose(Pose(Vector3(pose.position[0],pose.position[1],pose.position[2]),Quaternion(rotation[0],rotation[1],rotation[2],rotation[3])))
                if(meshChanged || sizeChanged){
                    val nextShape=shape(next);resources.retain(nextShape);entity.shape=nextShape;geometryAt=now
                    if(mesh!=null)resources.curved?.update(mesh)
                    if(!resources.resizing){
                        resources.move?.size=FloatSize3d(next.width,next.height,.001f)
                        resources.resize?.affordanceSize=FloatSize3d(next.width,next.height,.001f)
                    }
                }
                resources.mesh=mesh;resources.rendered=next;prior=next;placementNow.value(next)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close();errorNow.value(e.message ?: "Window magnifier unavailable")}
        finally{resources.pending=null;resources.resizing=false;resources.resizeStart=null;resources.moving=false;movingNow.value(false);resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false);resources.taps.clear();resources.lastContact=null;resources.moveContact=null}
    }
    LaunchedEffect(resources,pinned,window?.id){
        if(pinned && resources.border==null)resources.border=MagnifierPinFrame.create(session)
        val body=resources.moveTarget;val active=window
        if(body!=null && active!=null)resources.border?.update(body,active,pinned)
    }
}
@android.annotation.SuppressLint("RestrictedApi")
private class MagnifierResources:AutoCloseable{
    var border:MagnifierPinFrame?=null
    var entity:SurfaceEntity?=null;var video:VideoCompositor?=null;var stream:dev.floatdesk.client.net.WindowVideo?=null
    var curved:CurvedMonitorInput?=null;var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var input:InteractableComponent?=null;var flatInputAttached=false
    var rendered:MagnifiedWindow?=null;var pending:MagnifiedWindow?=null
    var resize:ResizableComponent?=null;var resizeTarget:Entity?=null;var resizing=false;var resizeStart:MagnifiedWindow?=null;var resizeStartWidth=0f
    var move:MovableComponent?=null;var moveTarget:Entity?=null;var moving=false;var dragged=false;var tapCommitted=false
    var lastContact:Pair<Float,Float>?=null;var moveContact:Pair<Float,Float>?=null
    var visible=false;var generation=0;val taps=NativeSurfaceTap();val shapes=java.util.ArrayDeque<SurfaceEntity.Shape>()
    fun retain(shape:SurfaceEntity.Shape){shapes.addLast(shape);while(shapes.size>16)shapes.removeFirst()}
    override fun close(){moving=false;visible=false;generation++;stream?.close();video?.close();border?.close();border=null;resize?.let{resizeTarget?.removeComponent(it)};resize=null;resizeTarget=null;move?.let{moveTarget?.removeComponent(it)};move=null;moveTarget=null;curved?.close();entity?.dispose();entity=null;curved=null;video=null}
}
