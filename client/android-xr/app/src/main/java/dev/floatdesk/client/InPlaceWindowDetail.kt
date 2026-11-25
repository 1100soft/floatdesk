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

/** Draw an unobstructed focused window directly from the shared window stream. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
@Composable
internal fun NativeFocusedWindow(
    session: androidx.xr.runtime.Session,
    root: androidx.xr.scenecore.Entity,
    connection: DeskConnection,
    source: dev.floatdesk.client.net.DesktopWindow?,
    region: FocusRect?,
    placement: () -> dev.floatdesk.client.workspace.MonitorPlacement?,
    projection: () -> MonitorMesh?,
    eye: () -> FloatArray?,
    cutouts: () -> List<VideoCutout>,
    onPresented: (Long) -> Unit,
) {
    val latestPlacement = rememberUpdatedState(placement)
    val latestProjection = rememberUpdatedState(projection)
    val latestEye = rememberUpdatedState(eye)
    val latestCutouts = rememberUpdatedState(cutouts)
    val presented = rememberUpdatedState(onPresented)
    LaunchedEffect(session, root, source, region) {
        if (source == null || region == null || !RenderPolicy.CustomMeshes) return@LaunchedEffect
        val r = region
        val entity = runCatching{androidx.xr.scenecore.SurfaceEntity.create(
            session, shape = androidx.xr.scenecore.SurfaceEntity.Shape.Quad(androidx.xr.runtime.math.FloatSize2d(.001f,.001f)),
            parent = root, superSampling = androidx.xr.scenecore.SurfaceEntity.SuperSampling.NONE,
            mediaBlendingMode = androidx.xr.scenecore.SurfaceEntity.MediaBlendingMode.OPAQUE,
        )}.getOrElse{
            android.util.Log.w("Floatdesk","Native window patch unavailable",it)
            return@LaunchedEffect
        }
        val state = NativeNodeState(entity)
        val retained = RetainedSurfaceShapes()
        var lease:dev.floatdesk.client.net.WindowCapturePool.Lease?=null
        try {
            val pixels=dev.floatdesk.client.net.WindowCaptureFormat.canvasSize(source.width,source.height)
            entity.setSurfacePixelDimensions(androidx.xr.runtime.math.IntSize2d(pixels.first,pixels.second))
            state.enabled(false)
            var ready=false
            val stream=connection.windowCaptures.attach(source.id,entity.getSurface(),
                {ready=true},{ready=false;state.enabled(false);presented.value(-1)})
            lease=stream
            var prior:dev.floatdesk.client.workspace.MonitorPlacement?=null
            var priorMesh:MonitorMesh?=null
            var priorCutouts=emptyList<VideoCutout>()
            while(isActive){
                val desk=latestPlacement.value()
                val mesh=latestProjection.value()
                val viewer=latestEye.value()
                val holes=latestCutouts.value()
                if(desk!=null && viewer!=null){
                    if(prior!==desk || mesh!==priorMesh || holes!=priorCutouts){
                        val clipped=clippedVideoGeometry(desk.physicalWidthM,desk.physicalHeightM,mesh,
                            floatArrayOf(r.left,r.top,r.right,r.bottom),0f,holes)
                        if(clipped.indices.isEmpty()){state.enabled(false);delay(80);continue}
                        val localEye=viewerInMonitor(viewer,desk.pose.position,desk.pose.rotation)
                        val vertices=FloatArray(clipped.positions.size){i->
                            val v=clipped.positions[i]
                            v+(localEye[i%3]-v)*.003f
                        }
                        val uv=FloatArray(clipped.texCoords.size){i->
                            val value=clipped.texCoords[i]
                            if(i%2==0)(value-r.left)/(r.right-r.left) else (value-(1f-r.bottom))/(r.bottom-r.top)
                        }
                        val shape=nativeVideoMesh(clipped.copy(positions=vertices,texCoords=uv))
                        if(connection.tryNativeMeshUpdate{stream.tryShapeUpdate{retained.retain(shape);entity.shape=shape}}){
                            prior=desk;priorMesh=mesh;priorCutouts=holes
                            state.pose(desk.pose.toRuntimePose())
                        }
                    }
                    val visible=ready && prior!=null
                    state.enabled(visible)
                    if(visible)presented.value(source.id)
                }else state.enabled(false)
                delay(80)
            }
        } catch(cancelled:CancellationException) {
            throw cancelled
        } catch(error:Exception) {
            presented.value(-1)
            android.util.Log.w("Floatdesk","Native window patch unavailable",error)
        } finally {
            lease?.close()
            runCatching{state.enabled(false);state.parent(null)}
        }
    }
}
