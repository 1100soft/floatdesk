package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi
import androidx.xr.scenecore.SurfaceEntity

/** Retained world-space media meshes. Only their transforms change with tracking. */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class WorldLayoutAxes(private val session:Session):AutoCloseable{
    private data class Surface(val entity:SurfaceEntity,val state:NativeNodeState,val inputTransparent:Boolean)
    private val planes=mutableMapOf<Boolean,MutableList<Surface>>()
    private var nextAllocation=0L
    private var connector:Surface?=null
    private val cyan=Color.rgb(88,241,255)
    private val violet=Color.rgb(200,125,255)

    private fun vector(point:FloatArray)=Vector3(point[0],point[1],point[2])

    private fun surface(geometry:VideoGeometry,color:Int?=null):Surface{
        val entity=SurfaceEntity.create(session,shape=nativeVideoMesh(geometry),parent=null,
            superSampling=SurfaceEntity.SuperSampling.NONE,mediaBlendingMode=SurfaceEntity.MediaBlendingMode.OPAQUE)
        entity.setEnabled(false)
        val inputTransparent=SurfaceInput.disableCollider(entity)
        entity.setSurfacePixelDimensions(IntSize2d(64,4))
        val output=entity.getSurface()
        val canvas=output.lockCanvas(null)
        try{
            if(color!=null)canvas.drawColor(color)
            else {
                val paint=Paint()
                paint.shader=LinearGradient(0f,0f,canvas.width.toFloat(),0f,cyan,violet,Shader.TileMode.CLAMP)
                canvas.drawPaint(paint)
            }
        }finally{output.unlockCanvasAndPost(canvas)}
        return Surface(entity,NativeNodeState(entity),inputTransparent)
    }

    fun update(parent:Entity,guide:LayoutOriginGuide?){
        planes.forEach{(mode,items)->if(mode!=guide?.spherical)items.forEach{it.state.enabled(false)}}
        if(guide==null){connector?.state?.enabled(false);return}
        val now=android.os.SystemClock.uptimeMillis()
        val axes=planes.getOrPut(guide.spherical){mutableListOf()}
        if(axes.size<2 && now>=nextAllocation){
            axes.add(surface(worldAxisGeometry(guide.spherical),if(axes.isEmpty())cyan else violet))
            nextAllocation=now+90L
        }
        val q=guide.rotation
        val rotation=Quaternion(q[0],q[1],q[2],q[3])
        axes.forEachIndexed{layer,axis->
            if(!axis.inputTransparent)return@forEachIndexed
            val radius=if(layer==0)guide.nearRange else guide.farRange
            val center=if(guide.spherical)guide.viewer.position else if(layer==0)guide.near else guide.far
            axis.state.apply{
                this.parent(parent)
                pose(Pose(vector(center),rotation))
                scale(radius)
                enabled(true)
            }
        }
        val a=vector(guide.near)
        val b=vector(guide.far)
        val delta=b-a
        val length=delta.length
        if(length<=guide.gripRadius*2f){connector?.state?.enabled(false);return}
        if(connector==null && (axes.size<2 || now<nextAllocation))return
        val beam=connector ?: surface(VideoGeometry(
            floatArrayOf(0f,-.5f,0f,0f,.5f,0f,1f,-.5f,0f,1f,.5f,0f),
            floatArrayOf(0f,0f,0f,1f,1f,0f,1f,1f),
            intArrayOf(0,1,2,1,3,2,0,2,1,1,2,3))).also{connector=it}
        if(!beam.inputTransparent)return
        val direction=delta.toNormalized()
        beam.state.apply{
            this.parent(parent)
            pose(Pose(a+direction*guide.gripRadius,Quaternion.fromRotation(Vector3.Right,direction)))
            scale(Vector3(length-guide.gripRadius*2f,guide.gripRadius*.12f,1f))
            enabled(true)
        }
    }

    override fun close(){
        (planes.values.flatten()+listOfNotNull(connector)).forEach{if(!it.entity.isDisposed){it.state.enabled(false);it.state.parent(null)}}
        planes.clear()
        connector=null
    }
}
