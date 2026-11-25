package dev.floatdesk.client

import androidx.compose.runtime.*
import dev.floatdesk.client.net.DeskConnection
import kotlinx.coroutines.*

/** Reuse the desktop's compositor, geometry and input. No additional scene surface. */
@Composable
internal fun InPlaceWindowDetail(connection:DeskConnection,id:Long?,onReady:()->Unit,onError:(String)->Unit){
    val ready=rememberUpdatedState(onReady);val failed=rememberUpdatedState(onError)
    LaunchedEffect(connection,id){
        if(id==null)return@LaunchedEffect
        while(isActive){
            val video=connection.windowRenderer()
            if(video==null){delay(50);continue}
            val main=android.os.Handler(android.os.Looper.getMainLooper())
            var live=true
            val output=try{video.windowDestination{main.post{if(live)ready.value()}}}catch(e:Exception){failed.value(e.message ?: "Window detail unavailable");return@LaunchedEffect}
            val stream=connection.windowCaptures.attach(id,output.surface,{if(live)ready.value()},{message->if(live)failed.value(message)},output)
            try{
                while(isActive && connection.windowRenderer()===video)delay(100)
            }finally{live=false;stream?.close();output.clear()}
        }
    }
}

/** Present the same decoded source and native canvas size as the magnifier. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
@Composable
internal fun DirectWindowPatch(
    connection:DeskConnection,
    session:androidx.xr.runtime.Session,
    root:androidx.xr.scenecore.Entity,
    source:dev.floatdesk.client.net.DesktopWindow?,
    region:FocusRect?,
    tracking:GrabTracking,
    placement:()->dev.floatdesk.client.workspace.MonitorPlacement?,
    mesh:()->MonitorMesh?,
){
    val desktopNow=rememberUpdatedState(placement)
    val meshNow=rememberUpdatedState(mesh)
    LaunchedEffect(connection,session,root,source,region){
        if(!RenderPolicy.CustomMeshes || source==null || region==null)return@LaunchedEffect
        // Briefly coalesce gaze switches before allocating a native video output.
        delay(180)
        var entity:androidx.xr.scenecore.SurfaceEntity?=null
        var lease:dev.floatdesk.client.net.WindowCapturePool.Lease?=null
        var hole:VideoCutout?=null
        try{
            val layer=androidx.xr.scenecore.SurfaceEntity.create(session,
                shape=androidx.xr.scenecore.SurfaceEntity.Shape.Quad(androidx.xr.runtime.math.FloatSize2d(.0001f,.0001f)),
                parent=root,superSampling=androidx.xr.scenecore.SurfaceEntity.SuperSampling.NONE,
                mediaBlendingMode=androidx.xr.scenecore.SurfaceEntity.MediaBlendingMode.OPAQUE)
            entity=layer
            check(SurfaceInput.disableCollider(layer))
            val pixels=dev.floatdesk.client.net.WindowCaptureFormat.canvasSize(source.width,source.height)
            layer.setSurfacePixelDimensions(androidx.xr.runtime.math.IntSize2d(pixels.first,pixels.second))
            val state=NativeNodeState(layer)
            val retained=RetainedSurfaceShapes()
            var hasFrame=false
            val stream=connection.windowCaptures.attach(source.id,layer.getSurface(),{hasFrame=true},{
                hasFrame=false
                tracking.directPatchCutout=null
                state.enabled(false)
            })
            lease=stream
            var lastDesk:dev.floatdesk.client.workspace.MonitorPlacement?=null
            var lastMesh:MonitorMesh?=null
            var lastHoles=emptyList<VideoCutout>()
            while(isActive){
                val desk=desktopNow.value()
                val currentMesh=meshNow.value()
                val holes=tracking.desktopCutouts
                if(desk!=null && (desk!==lastDesk || currentMesh!==lastMesh || holes!=lastHoles)){
                    val clipped=clippedVideoGeometry(desk.physicalWidthM,desk.physicalHeightM,currentMesh,
                        floatArrayOf(region.left,region.top,region.right,region.bottom),0f,holes)
                    if(clipped.indices.isEmpty()){
                        if(tracking.directPatchCutout===hole)tracking.directPatchCutout=null
                        state.enabled(false)
                        delay(65);continue
                    }
                    val coordinates=FloatArray(clipped.texCoords.size){i->
                        val uv=clipped.texCoords[i]
                        if(i%2==0)(uv-region.left)/(region.right-region.left) else
                            (uv-(1f-region.bottom))/(region.bottom-region.top)
                    }
                    if(connection.tryNativeMeshUpdate{stream.tryShapeUpdate{
                        val shape=nativeVideoMesh(clipped.copy(texCoords=coordinates))
                        retained.retain(shape);layer.shape=shape
                    }}){
                        state.pose(desk.pose.toRuntimePose())
                        lastDesk=desk;lastMesh=currentMesh;lastHoles=holes
                    }
                }
                if(hasFrame && lastDesk!=null){
                    state.enabled(true)
                    if(hole==null || tracking.directPatchCutout!==hole){
                        withFrameNanos{}
                        if(!hasFrame)continue
                        hole=textureCutout(region.left,region.top,region.right,region.bottom)
                        tracking.directPatchCutout=hole
                    }
                }
                delay(65)
            }
        }catch(error:CancellationException){
            throw error
        }catch(error:Exception){
            android.util.Log.w("Floatdesk","Using composited window patch",error)
        }finally{
            if(tracking.directPatchCutout===hole)tracking.directPatchCutout=null
            lease?.close()
            entity?.let{runCatching{it.setEnabled(false);it.parent=null}}
        }
    }
}
