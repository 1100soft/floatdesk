package dev.floatdesk.client

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.*
import androidx.xr.scenecore.*
import java.nio.file.Paths

/** Exterior, noninteractive glTF beams. Never draw a pin stroke into captured window pixels. */
internal class MagnifierPinFrame private constructor(private val session:Session,private val model:GltfModel):AutoCloseable {
    private val parts=mutableListOf<GltfModelEntity>()
    private var previous:MonitorMesh?=null;private var size=0f to 0f;private var parent:Entity?=null;private var visible=false
    fun update(body:Entity,window:MagnifiedWindow,enabled:Boolean){
        if(!enabled){if(visible)parts.forEach{it.setEnabled(false)};visible=false;return}
        if(visible && parent===body && previous===window.mesh && size==(window.width to window.height))return
        val outline=magnifierPinOutline(window)
        while(parts.size<outline.size)parts.add(GltfModelEntity.create(session,model,parent=body))
        for((i,part)in parts.withIndex()){
            part.setEnabled(i<outline.size);if(i>=outline.size)continue
            part.parent=body
            val a=outline[i];val b=outline[(i+1)%outline.size]
            val start=Vector3(a[0],a[1],a[2]);val end=Vector3(b[0],b[1],b[2]);val delta=end-start
            part.setPose(Pose((start+end)*.5f,Quaternion.fromRotation(Vector3.Right,delta)))
            part.nodes.first().localScale=Vector3(delta.length,.002f*window.units,.002f*window.units)
        }
        visible=true;parent=body;previous=window.mesh;size=window.width to window.height
    }
    override fun close(){parts.forEach{it.parent=null};parts.clear();model.close()}
    companion object {suspend fun create(session:Session)=MagnifierPinFrame(session,GltfModel.create(session,Paths.get("grab-guides/gold-beam.glb")))}
}
