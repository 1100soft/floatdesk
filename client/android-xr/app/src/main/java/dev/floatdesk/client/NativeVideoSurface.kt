package dev.floatdesk.client

internal enum class NativeSurfaceState { Unallocated, Allocating, TextureInit, Attached, Presented, Hidden, Retiring, Released }

/** SceneCore side of one video surface. Each call releases one dependency of the native canvas. */
internal interface NativeVideoPort {
    fun detachProducer()
    fun detachInput()
    fun releaseGeometry()
}

/**
 * Enforces one native video surface's lifetime: Allocating, TextureInit, Attached, then Presented and Hidden
 * on the same resources, then Retiring and Released. Producers and input attach only once texture
 * initialization has settled, and retirement always detaches the producer and input before the geometry.
 * An out-of-order request is refused and recorded. Platform-free so the order can be checked on the JVM.
 */
internal class NativeSurfaceLifecycle(private val tag:()->NativeTag){
    var state=NativeSurfaceState.Unallocated;private set
    var producerAttached=false;private set
    var inputAttached=false;private set
    var refusals=0;private set
    private val live get()=state==NativeSurfaceState.Attached || state==NativeSurfaceState.Presented || state==NativeSurfaceState.Hidden

    private fun refuse(request:String):Boolean{refusals++;NativeJournal.record(tag(),"refused","request=$request state=$state");return false}
    private fun move(request:String,to:NativeSurfaceState,vararg from:NativeSurfaceState):Boolean{
        if(state !in from)return refuse(request)
        state=to;return true
    }

    fun allocate()=move("allocate",NativeSurfaceState.Allocating,NativeSurfaceState.Unallocated,NativeSurfaceState.Released)
    fun allocated()=move("allocated",NativeSurfaceState.TextureInit,NativeSurfaceState.Allocating)
    /** Creation threw before a native surface existed; nothing references it. */
    fun allocationFailed()=move("allocation-failed",NativeSurfaceState.Released,NativeSurfaceState.Allocating)
    fun textureSettled()=move("texture-settled",NativeSurfaceState.Attached,NativeSurfaceState.TextureInit)
    fun attachProducer():Boolean{if(!live)return refuse("attach-producer");producerAttached=true;return true}
    fun attachInput():Boolean{if(!live)return refuse("attach-input");inputAttached=true;return true}
    fun producerDetached(){producerAttached=false}
    fun inputDetached(){inputAttached=false}
    fun present():Boolean{if(!producerAttached)return refuse("present");return move("present",NativeSurfaceState.Presented,NativeSurfaceState.Attached,NativeSurfaceState.Hidden,NativeSurfaceState.Presented)}

    /** Visibility is always applied by the caller; only a presented or attached surface changes state. */
    fun hide(){if(state==NativeSurfaceState.Presented || state==NativeSurfaceState.Attached)state=NativeSurfaceState.Hidden}

    fun retire(port:NativeVideoPort):Boolean{
        if(!live && state!=NativeSurfaceState.TextureInit)return refuse("retire")
        state=NativeSurfaceState.Retiring
        NativeJournal.record(tag(),"retire","producer=$producerAttached input=$inputAttached")
        if(producerAttached){port.detachProducer();producerAttached=false}
        if(inputAttached){port.detachInput();inputAttached=false}
        port.releaseGeometry()
        state=NativeSurfaceState.Released
        return true
    }
}
