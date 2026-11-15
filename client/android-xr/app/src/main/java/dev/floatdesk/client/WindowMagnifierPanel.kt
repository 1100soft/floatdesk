package dev.floatdesk.client

import androidx.compose.runtime.*
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.*
import androidx.xr.scenecore.*
import dev.floatdesk.client.media.VideoCompositor
import dev.floatdesk.client.net.DeskConnection
import java.util.concurrent.atomic.AtomicBoolean

/** A near, flat video quad; no custom mesh churn and no extra desktop transform. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
@Composable
internal fun WindowMagnifierPanel(session:Session,root:Entity,window:MagnifiedWindow,connection:DeskConnection,
    onReady:()->Unit,onError:(String)->Unit,onHover:(String,Pair<Float,Float>?)->Unit){
    val readyNow=rememberUpdatedState(onReady);val errorNow=rememberUpdatedState(onError);val hoverNow=rememberUpdatedState(onHover)
    DisposableEffect(session,root,window.id){
        val alive=AtomicBoolean(true);val shown=AtomicBoolean(false);val main=android.os.Handler(android.os.Looper.getMainLooper())
        val cleanup=mutableListOf<()->Unit>()
        try {
        val scale=minOf(1.0,2048.0/window.sourceWidth,2048.0/window.sourceHeight,kotlin.math.sqrt(2_097_152.0/(window.sourceWidth.toDouble()*window.sourceHeight)))
        val w=((window.sourceWidth*scale).toInt()/2*2).coerceAtLeast(32);val h=((window.sourceHeight*scale).toInt()/2*2).coerceAtLeast(32)
        val p=window.pose;val q=p.rotation
        val entity=SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(window.width,window.height)),
            pose=androidx.xr.runtime.math.Pose(Vector3(p.position[0],p.position[1],p.position[2]),Quaternion(q[0],q[1],q[2],q[3])),parent=root,superSampling=SurfaceEntity.SuperSampling.NONE)
        cleanup.add{entity.dispose()}
        entity.setSurfacePixelDimensions(IntSize2d(w,h));entity.setEnabled(false)
        val pointer=SpatialPointerComponent.create(session);check(entity.addComponent(pointer));pointer.spatialPointerIcon=SpatialPointerIcon.NONE
        val input=NativeMonitorInput.create(session){event->
            if(alive.get() && event.source==InputEvent.Source.HANDS){
                val side=event.pointerType.toString();val hit=event.hitInfoList.firstOrNull{it.inputEntity===entity}
                val point=if(event.action==InputEvent.Action.HOVER_EXIT || event.action==InputEvent.Action.CANCEL)null else hit?.hitPosition?.let{
                    eventMatrixHit(floatArrayOf(it.x,it.y,it.z),hit.transform.data,window.width,window.height,null,entity.getScale(Space.ACTIVITY))
                }
                hoverNow.value(side,point?.let{it[0]/window.width+.5f to (.5f-it[1]/window.height)})
            }
        };check(entity.addComponent(input))
        cleanup.add{entity.removeComponent(input);entity.removeComponent(pointer)}
        val video=VideoCompositor(w,h)
        cleanup.add{video.close()}
        video.onOutputPresented{if(shown.compareAndSet(false,true))main.post{if(alive.get()){entity.setEnabled(true);readyNow.value()}}}
        video.output(entity.getSurface())
        val stream=connection.openWindowVideo(window.id,video){message->main.post{if(alive.get())errorNow.value(message)}}
        cleanup.add{stream?.close()}
        } catch(error:Exception) {
            alive.set(false)
            cleanup.asReversed().forEach{runCatching{it()}};cleanup.clear()
            main.post{errorNow.value(error.message ?: "Window magnifier unavailable")}
        }
        onDispose{
            alive.set(false);cleanup.asReversed().forEach{runCatching{it()}}
            hoverNow.value("LEFT",null);hoverNow.value("RIGHT",null)
        }
    }
}
