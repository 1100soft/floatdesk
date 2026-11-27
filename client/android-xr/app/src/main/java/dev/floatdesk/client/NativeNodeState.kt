package dev.floatdesk.client

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity

/** Cache only app-owned submissions. Invalidate before/after native system manipulation. */
@android.annotation.SuppressLint("RestrictedApi")
internal class NativeNodeState(private val entity:Entity,private val tag:NativeTag?=null,private val target:String="node"){
    private var parentKnown=false
    private var lastParent:Entity?=null
    private var lastPose:Pose?=null
    private var lastScale:Vector3?=null
    private var uniformScale=Float.NaN
    private var alpha=Float.NaN
    private var enabled:Boolean?=null
    fun invalidate(){parentKnown=false;lastPose=null;lastScale=null;uniformScale=Float.NaN;alpha=Float.NaN;enabled=null}
    fun parent(value:Entity?){if(!parentKnown || lastParent!==value){tag?.let{NativeJournal.record(it,"parent","target=$target attached=${value!=null}")};entity.parent=value;lastParent=value;parentKnown=true}}
    fun pose(value:Pose){if(lastPose!=value){entity.setPose(value);lastPose=value}}
    fun scale(value:Vector3){if(lastScale!=value || uniformScale.isFinite()){entity.setScale(value);lastScale=value;uniformScale=Float.NaN}}
    fun scale(value:Float){if(uniformScale!=value || lastScale!=null){entity.setScale(value);uniformScale=value;lastScale=null}}
    fun alpha(value:Float){if(alpha!=value){entity.setAlpha(value);alpha=value}}
    fun enabled(value:Boolean){if(enabled!=value){tag?.let{NativeJournal.noteEnabled(it,target,value)};entity.setEnabled(value);enabled=value}}
}

/** Keep the pinned SDK's restricted lifetime query behind one checked boundary. */
@android.annotation.SuppressLint("RestrictedApi")
internal fun Entity?.liveOrNull():Entity? = this?.takeUnless{it.isDisposed}
