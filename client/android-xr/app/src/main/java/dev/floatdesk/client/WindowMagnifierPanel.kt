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
    onReady:()->Unit,onError:(String)->Unit,onHover:(String,Pair<Float,Float>?)->Unit,onTap:(Pair<Float,Float>)->Unit,onMesh:(MonitorMesh?)->Unit){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    val tapNow=rememberUpdatedState(onTap);val windowNow=rememberUpdatedState(window);val meshNow=rememberUpdatedState(onMesh)
    val resources=remember(session,root){MagnifierResources()}
    DisposableEffect(resources){onDispose{resources.close()}}
    LaunchedEffect(resources,window?.id,window?.spherical,window?.sourceWidth,window?.sourceHeight){
        resources.visible=false;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false)
        hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)
        val target=window ?: return@LaunchedEffect
        try{
            if(resources.entity==null){
                delay(32) // Let the existing desktop's pending mesh commands flush before allocating a surface.
                val entity=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(.1f,.1f)),parent=root,superSampling=SurfaceEntity.SuperSampling.NONE)
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
                    resources.taps.update(side,event.action.toString(),uv)?.let{tapNow.value(it)}
                }
                resources.listener=listener
                val input=NativeMonitorInput.create(session,listener);resources.input=input;entity.addComponent(input);resources.flatInputAttached=true
                resources.video=VideoCompositor(2048,1536).also{it.output(entity.getSurface())}
            }
            val entity=resources.entity!!;val p=target.pose;val q=p.rotation
            val scale=minOf(1.0,2048.0/target.sourceWidth,2048.0/target.sourceHeight,kotlin.math.sqrt(2_097_152.0/(target.sourceWidth.toDouble()*target.sourceHeight)))
            entity.setSurfacePixelDimensions(IntSize2d(((target.sourceWidth*scale).toInt()/2*2).coerceAtLeast(32),((target.sourceHeight*scale).toInt()/2*2).coerceAtLeast(32)))
            resources.video!!.output(entity.getSurface(),force=true)
            entity.setPose(androidx.xr.runtime.math.Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3])))
            resources.mesh=target.mesh;meshNow.value(target.mesh)
            fun shape(mesh:MonitorMesh?):SurfaceEntity.Shape=if(mesh==null)SurfaceEntity.Shape.Quad(FloatSize2d(target.width,target.height)) else SurfaceEntity.Shape.CustomMesh(SurfaceEntity.Shape.TriangleMesh(FloatBuffer.wrap(mesh.positions),FloatBuffer.wrap(mesh.videoTexCoords),IntBuffer.wrap(mesh.indices)))
            entity.shape=shape(target.mesh)
            if(target.mesh!=null && resources.flatInputAttached){resources.input?.let{entity.removeComponent(it)};resources.flatInputAttached=false}
            if(target.mesh==null && !resources.flatInputAttached){resources.input?.let{entity.addComponent(it)};resources.flatInputAttached=true}
            if(target.mesh!=null){
                if(resources.curved==null){delay(32);resources.curved=CurvedMonitorInput.create(session,entity,target.mesh,resources.listener!!)}
                else resources.curved!!.update(target.mesh)
            }
            val generation=++resources.generation
            var shown=false
            val firstFrame=java.util.concurrent.atomic.AtomicBoolean(false)
            resources.video!!.onBasePresented{if(firstFrame.compareAndSet(false,true))android.os.Handler(android.os.Looper.getMainLooper()).post{
                if(resources.generation==generation && !shown){shown=true;resources.visible=true;entity.setEnabled(true);resources.curved?.entity?.setEnabled(target.spherical);readyNow.value()}
            }}
            resources.stream=connection.openWindowVideo(target.id,resources.video!!){message->android.os.Handler(android.os.Looper.getMainLooper()).post{if(resources.generation==generation)errorNow.value(message)}}
            var prior=target.mesh
            while(isActive){
                delay(100)
                if(!target.spherical || !shown)continue
                val head=viewer.get() ?: continue
                val eye=viewerInMonitor(head.position,target.pose.position,target.pose.rotation)
                val dir=rotateVector(head.rotation,0f,0f,-1f)
                val end=viewerInMonitor(FloatArray(3){head.position[it]+dir[it]},target.pose.position,target.pose.rotation)
                val reference=target.mesh!!
                val focus=sphericalViewFocus(reference,FloatArray(3){end[it]-eye[it]}) ?: continue
                if(prior!=null && (0..2).all{kotlin.math.abs(eye[it]-prior!!.eye[it])<.01f} && (0..1).all{kotlin.math.abs(focus[it]-prior!!.focus!![it])<.004f})continue
                val mesh=MonitorMesh(target.width,target.height,eye,columns=32,rows=20,focus=floatArrayOf(focus[0].coerceIn(-target.width*.5f,target.width*.5f),focus[1].coerceIn(-target.height*.5f,target.height*.5f)))
                entity.shape=shape(mesh);resources.shapes.addLast(entity.shape);while(resources.shapes.size>16)resources.shapes.removeFirst()
                resources.curved?.update(mesh);resources.mesh=mesh;prior=mesh;meshNow.value(mesh)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){resources.close();errorNow.value(e.message ?: "Window magnifier unavailable")}
        finally{resources.visible=false;resources.generation++;resources.stream?.close();resources.stream=null;resources.entity?.setEnabled(false);resources.curved?.entity?.setEnabled(false);resources.taps.clear()}
    }
    LaunchedEffect(resources,pinned,window?.id){resources.video?.overlay(if(pinned)VideoAim(floatArrayOf(.006f,.006f,.994f,.006f,.994f,.006f,.994f,.994f,.994f,.994f,.006f,.994f,.006f,.994f,.006f,.006f),thickness=6f,opacity=1f,colors=FloatArray(12){if(it%3==0)1f else if(it%3==1).65f else .02f}) else VideoAim())}
}
@android.annotation.SuppressLint("RestrictedApi")
private class MagnifierResources:AutoCloseable{
    var entity:SurfaceEntity?=null;var video:VideoCompositor?=null;var stream:dev.floatdesk.client.net.WindowVideo?=null
    var curved:CurvedMonitorInput?=null;var mesh:MonitorMesh?=null;var listener:((InputEvent)->Unit)?=null
    var input:InteractableComponent?=null;var flatInputAttached=false
    var visible=false;var generation=0;val taps=NativeSurfaceTap();val shapes=java.util.ArrayDeque<SurfaceEntity.Shape>()
    override fun close(){visible=false;generation++;stream?.close();video?.close();curved?.close();entity?.dispose();entity=null;curved=null;video=null}
}
