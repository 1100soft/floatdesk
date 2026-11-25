package dev.floatdesk.client

import android.util.Log
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene

/** SceneCore 1.0 omits the surface collider switch from the public wrapper. */
internal object SurfaceInput {
    private val runtimeGetter by lazy { SurfaceEntity::class.java.getMethod("getRtEntity") }
    private val colliderMethods=mutableMapOf<Class<*>,java.lang.reflect.Method>()

    fun disableCollider(surface:SurfaceEntity):Boolean=runCatching {
        val runtime=runtimeGetter.invoke(surface) ?: error("Surface runtime unavailable")
        val method=colliderMethods.getOrPut(runtime.javaClass){
            runtime.javaClass.getMethod("setColliderEnabled",Boolean::class.javaPrimitiveType)
        }
        method.invoke(runtime,false)
        true
    }.getOrElse{
        Log.w("Floatdesk","Cannot make the guide surface input-transparent",it)
        false
    }
}

/** SceneCore may omit HitInfo after DOWN. The captured event still carries the ray. */
internal fun surfaceInputRay(session:androidx.xr.runtime.Session,root:androidx.xr.scenecore.Entity,
                             event:androidx.xr.scenecore.InputEvent):Pair<FloatArray,FloatArray>{
    fun point(v:androidx.xr.runtime.math.Vector3):FloatArray{
        val p=session.scene.activitySpace.transformPoseTo(androidx.xr.runtime.math.Pose(v),root).translation
        return floatArrayOf(p.x,p.y,p.z)
    }
    val origin=point(event.origin)
    return origin to subtract(point(event.origin+event.direction),origin)
}

internal fun surfaceInputHit(ray:Pair<FloatArray,FloatArray>,pose:dev.floatdesk.client.workspace.Pose,
                             width:Float,height:Float,mesh:MonitorMesh?):FloatArray?{
    val origin=viewerInMonitor(ray.first,pose.position,pose.rotation)
    val end=viewerInMonitor(add(ray.first,ray.second),pose.position,pose.rotation)
    return monitorRayHit(origin,subtract(end,origin),width,height,mesh)
}

internal fun updateSurfaceAimingIcon(entity:androidx.xr.scenecore.Entity?,gaze:Boolean) {
    val icon=if(gaze)androidx.xr.scenecore.SpatialPointerIcon.NONE else androidx.xr.scenecore.SpatialPointerIcon.DEFAULT
    entity?.getComponentsOfType(androidx.xr.scenecore.SpatialPointerComponent::class.java)?.forEach {
        if(it.spatialPointerIcon!=icon)it.spatialPointerIcon=icon
    }
}


internal fun pointerEventContinues(action:androidx.xr.scenecore.InputEvent.Action)=when(action){
    androidx.xr.scenecore.InputEvent.Action.MOVE,androidx.xr.scenecore.InputEvent.Action.UP,
    androidx.xr.scenecore.InputEvent.Action.CANCEL->true
    else->false
}
