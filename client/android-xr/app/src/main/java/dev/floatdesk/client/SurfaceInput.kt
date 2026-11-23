package dev.floatdesk.client

import android.util.Log
import androidx.xr.scenecore.SurfaceEntity

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
