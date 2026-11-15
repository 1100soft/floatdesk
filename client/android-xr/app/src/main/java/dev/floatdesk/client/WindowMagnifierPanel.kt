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
    onPlacement:(MagnifiedWindow)->Unit,onMoving:(Boolean)->Unit,onContext:()->Unit={}){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    val tapNow=rememberUpdatedState(onTap);val windowNow=rememberUpdatedState(window);val placementNow=rememberUpdatedState(onPlacement);val movingNow=rememberUpdatedState(onMoving)
    val pinNow=rememberUpdatedState(pinned)
    val contextNow=rememberUpdatedState(onContext)
    val resources=remember(session,root){MagnifierResources()}
    DisposableEffect(resources){onDispose{resources.close()}}
    LaunchedEffect(resources,window?.id,window?.spherical,window?.sourceWidth,window?.sourceHeight){
        resources.visible=false;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false)
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)
        val target=window ?: return@LaunchedEffect
        try{
            fun shape(surface:MagnifiedWindow):SurfaceEntity.Shape{
                if(surface.mesh==null)return SurfaceEntity.Shape.Quad(FloatSize2d(surface.width+.01f*surface.units,surface.height+.01f*surface.units))
                val geometry=framedMagnifierGeometry(surface)
                return SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(java.nio.ByteBuffer.allocateDirect(geometry.positions.size*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply{put(geometry.positions);rewind()},java.nio.ByteBuffer.allocateDirect(geometry.texCoords.size*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply{put(geometry.texCoords);rewind()},java.nio.ByteBuffer.allocateDirect(geometry.indices.size*4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().apply{put(geometry.indices);rewind()}))
            }
            val reusedSurface=resources.entity!=null
            if(resources.entity==null){
                delay(32) // Let the existing desktop's pending mesh commands flush before allocating a surface.
                val initialShape=shape(target);resources.retain(initialShape)
                val entity=SurfaceEntity.create(session,shape=initialShape,parent=root,superSampling=SurfaceEntity.SuperSampling.NONE)
                resources.entity=entity;entity.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE;entity.setEnabled(false);entity.setSurfacePixelDimensions(IntSize2d(2048,1536))
                val pointer=SpatialPointerComponent.create(session);entity.addComponent(pointer);pointer.spatialPointerIcon=SpatialPointerIcon.NONE
                val listener:(InputEvent)->Unit=listener@{event->
                    val active=windowNow.value?.takeIf{resources.visible} ?: return@listener
                    val expected=if(active.spherical)resources.curved?.entity else entity
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
                    if(event.action==InputEvent.Action.DOWN && local!=null && !resources.resizing && !resources.moving){
                        val head=viewer.get()
                        if(head!=null){resources.dragged=false;resources.tapCommitted=false;resources.taps.clear();val ray=rootRay();resources.drag.begin(active,head,ray.first,ray.second,transformPoint(active.pose,local));resources.dragSide=side;resources.moving=true;movingNow.value(true)}
                    }
                    if(event.action==InputEvent.Action.MOVE && resources.moving && side==resources.dragSide && !resources.resizing){
                        val head=viewer.get()
                        if(head!=null){val ray=rootRay();val proposed=resources.drag.move(head,ray.first,ray.second)
                            if(proposed!=null){resources.dragged=true;resources.taps.clear();resources.pending=if(active.fixed)fixedMagnifierGeometry(active,head,proposed)else magnifierGeometry(active,head,units(),proposed)}
                        }
                    }
                    if((event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL) && side==resources.dragSide){
                        resources.pending?.let{placementNow.value(it)};resources.drag.clear();resources.dragSide=null;resources.moving=false;movingNow.value(false)
                    }
                    if(event.action==InputEvent.Action.DOWN){resources.holdAt=android.os.SystemClock.uptimeMillis();resources.contextShown=false;if(!resources.moving){resources.dragged=false;resources.tapCommitted=false}}
                    if(event.action==InputEvent.Action.UP || event.action==InputEvent.Action.CANCEL || event.action==InputEvent.Action.HOVER_EXIT && !resources.moving)resources.holdAt=0
                    if(!resources.dragged)resources.taps.update(side,event.action.toString(),uv)?.let{if(!resources.tapCommitted){resources.tapCommitted=true;tapNow.value(it)}}
                }
                resources.listener=listener
                val input=NativeMonitorInput.create(session,listener);resources.input=input;entity.addComponent(input);resources.flatInputAttached=true

            }
            val entity=resources.entity!!;val p=target.pose;val q=p.rotation
            val scale=minOf(1.0,2048.0/target.sourceWidth,2048.0/target.sourceHeight,kotlin.math.sqrt(2_097_152.0/(target.sourceWidth.toDouble()*target.sourceHeight)))
            entity.setSurfacePixelDimensions(IntSize2d(((target.sourceWidth*scale).toInt()/2*2).coerceAtLeast(32),((target.sourceHeight*scale).toInt()/2*2).coerceAtLeast(32)))

            val targetPose=Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3]))
            resources.mesh=target.mesh;resources.rendered=target
            if(reusedSurface){val nextShape=shape(target);resources.retain(nextShape);entity.shape=nextShape}
            if(target.mesh!=null && resources.flatInputAttached){resources.input?.let{entity.removeComponent(it)};resources.flatInputAttached=false}
            if(target.mesh==null && !resources.flatInputAttached){resources.input?.let{entity.addComponent(it)};resources.flatInputAttached=true}
            if(target.mesh!=null){
                if(resources.curved==null){delay(32);resources.curved=CurvedMonitorInput.create(session,entity,target.mesh,resources.listener!!,6,4)}
                else resources.curved!!.update(target.mesh)
            }
            // Keep the movable body's parent fixed. Moving a collider's parent instead
            // would feed our previous movement back into the next native pose.
            entity.parent=root;entity.setPose(targetPose)
            resources.curved?.entity?.let{it.parent=entity;it.setPose(Pose())}
            val moveTarget=entity
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
                    if(event.resizeState==ResizeEvent.ResizeState.END){resources.pending?.let{placementNow.value(it)};resources.resizing=false;resources.resizeStart=null;movingNow.value(resources.moving)}
                }).also{it.isFixedAspectRatioEnabled=true;it.isAutoHideContentWhileResizingEnabled=false;it.shouldAutoUpdateOverlay=true;it.isAlwaysShowOverlayEnabled=false}
            }
            if(resources.resizeTarget!==moveTarget){resources.resizeTarget?.removeComponent(resources.resize!!);check(moveTarget.addComponent(resources.resize!!));resources.resizeTarget=moveTarget}
            val maxWidth=(2*Math.PI.toFloat()-.04f)*MagnifierDistanceM*units()/kotlin.math.sqrt(1f+target.height*target.height/(target.width*target.width))
            resources.resize!!.minimumEntitySize=FloatSize3d(.05f*units(),.05f*units()*target.height/target.width,.001f)
            resources.resize!!.maximumEntitySize=FloatSize3d(maxWidth,maxWidth*target.height/target.width,.001f)
            resources.resize!!.affordanceSize=FloatSize3d(target.width,target.height,.001f)
            val generation=++resources.generation
            var shown=false
            resources.stream=connection.windowCaptures.attach(target.id,entity.getSurface(),{
                if(resources.generation==generation){shown=true;resources.visible=true;entity.setEnabled(true);resources.curved?.entity?.setEnabled(target.spherical);readyNow.value()}
            },{message->if(resources.generation==generation){resources.visible=false;entity.setEnabled(false);resources.curved?.entity?.setEnabled(false);hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null);errorNow.value(message)}})
            resources.stream?.update(.005f*target.units/(target.width+.01f*target.units),.005f*target.units/(target.height+.01f*target.units),pinNow.value,target.width/target.height)
            var prior=target
            var geometryAt=0L
            while(isActive){
                withFrameNanos{ }
                val now=android.os.SystemClock.uptimeMillis()
                if(!shown)continue
                val head=viewer.get() ?: continue
                val updateGeometry=now-geometryAt>=if(windowNow.value?.fixed==true)100 else 50
                val current=(if(updateGeometry)resources.pending ?: windowNow.value else windowNow.value) ?: continue
                if(updateGeometry)resources.pending=null
                val next=if(current.fixed){if(updateGeometry)fixedMagnifierGeometry(current,head)else current.copy(mesh=prior.mesh)} else magnifierGeometry(current,head,units())
                val previous=prior.mesh;val mesh=next.mesh
                val poseChanged=(0..2).any{kotlin.math.abs(next.pose.position[it]-prior.pose.position[it])>.0005f} ||
                    (0..3).any{kotlin.math.abs(next.pose.rotation[it]-prior.pose.rotation[it])>.0005f}
                val meshChanged=mesh!==previous && mesh!=null && (previous==null || mesh.positions.indices.any{kotlin.math.abs(mesh.positions[it]-previous.positions[it])>.0005f})
                val sizeChanged=next.width!=prior.width || next.height!=prior.height
                resources.stream?.update(.005f*next.units/(next.width+.01f*next.units),.005f*next.units/(next.height+.01f*next.units),pinNow.value,next.width/next.height)
                if(next.fixed && resources.holdAt>0 && !resources.dragged && !resources.resizing && !resources.contextShown && now-resources.holdAt>=700){
                    resources.contextShown=true;resources.tapCommitted=true;resources.taps.clear();contextNow.value()
                }
                if(!resources.resizing && resources.resize?.affordanceSize?.width!=next.width){resources.resize?.affordanceSize=FloatSize3d(next.width,next.height,.001f)}
                if(!poseChanged && !meshChanged && !sizeChanged)continue
                val pose=next.pose;val rotation=pose.rotation
                entity.setPose(Pose(Vector3(pose.position[0],pose.position[1],pose.position[2]),Quaternion(rotation[0],rotation[1],rotation[2],rotation[3])))
                if(meshChanged || sizeChanged){
                    val nextShape=shape(next);resources.retain(nextShape);entity.shape=nextShape;geometryAt=android.os.SystemClock.uptimeMillis()
                    if(mesh!=null)resources.curved?.update(mesh)
                    if(!resources.resizing){
                        resources.resize?.affordanceSize=FloatSize3d(next.width,next.height,.001f)
                    }
                }
                resources.mesh=mesh;resources.rendered=next;prior=next;placementNow.value(next)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close();errorNow.value(e.message ?: "Window magnifier unavailable")}
        finally{resources.drag.clear();resources.dragSide=null;resources.pending=null;resources.resizing=false;resources.resizeStart=null;resources.holdAt=0;resources.moving=false;movingNow.value(false);resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false);resources.taps.clear();resources.lastContact=null;resources.moveContact=null;hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)}
    }

}
@android.annotation.SuppressLint("RestrictedApi")
private class MagnifierResources:AutoCloseable{
    var entity:SurfaceEntity?=null;var stream:dev.floatdesk.client.net.WindowCapturePool.Lease?=null
    var curved:CurvedMonitorInput?=null;var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var input:InteractableComponent?=null;var flatInputAttached=false
    var rendered:MagnifiedWindow?=null;var pending:MagnifiedWindow?=null
    var resize:ResizableComponent?=null;var resizeTarget:Entity?=null;var resizing=false;var resizeStart:MagnifiedWindow?=null;var resizeStartWidth=0f
    val drag=MagnifierRayDrag();var dragSide:String?=null;var moving=false;var dragged=false;var tapCommitted=false
    var lastContact:Pair<Float,Float>?=null;var moveContact:Pair<Float,Float>?=null
    var holdAt=0L;var contextShown=false
    var visible=false;var generation=0;val taps=NativeSurfaceTap();val shapes=java.util.ArrayDeque<SurfaceEntity.Shape>()
    fun retain(shape:SurfaceEntity.Shape){shapes.addLast(shape);while(shapes.size>128)shapes.removeFirst()}
    override fun close(){moving=false;visible=false;generation++;stream?.close();resize?.let{resizeTarget?.removeComponent(it)};resize=null;resizeTarget=null;drag.clear();dragSide=null;curved?.close();entity?.dispose();entity=null;curved=null}
}
