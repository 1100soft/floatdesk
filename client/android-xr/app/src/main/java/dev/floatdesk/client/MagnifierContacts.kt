package dev.floatdesk.client

/** One owner per pointer. Disposing an old pane must not clear a newer pane's hover. */
internal class MagnifierContacts(private val points:MutableMap<String,Pair<Float,Float>> = linkedMapOf()):Map<String,Pair<Float,Float>> by points {
    private val owners=mutableMapOf<String,String>()
    private val windows=mutableMapOf<String,Long>()
    private val contactTimes=mutableMapOf<String,Long>()
    fun contactTime(side:String?)=contactTimes[side]
    fun update(owner:String,side:String,point:Pair<Float,Float>?,window:Long?,now:Long=0L){
        if(point==null){if(owners[side]==owner){points.remove(side);contactTimes.remove(side);owners.remove(side);windows.remove(side)}}
        else {points[side]=point;contactTimes[side]=now;owners[side]=owner;if(window!=null)windows[side]=window else windows.remove(side)}
    }
    fun owner(side:String)=owners[side]
    fun window(side:String)=windows[side]
    fun clear(){points.clear();contactTimes.clear();owners.clear();windows.clear()}
}

/** Presentation and hit routing share this policy, including pinned panes. */
internal fun magnifierSurfacesAllowed(enabled:Boolean,sceneActive:Boolean,workspaceOpen:Boolean)=enabled && sceneActive && !workspaceOpen
