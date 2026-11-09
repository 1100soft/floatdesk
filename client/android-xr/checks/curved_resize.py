#!/usr/bin/env python3
"""Check curved resize-frame projection, plane ordering and affordance lifecycle."""
from pathlib import Path
import subprocess
import sys
import tempfile
folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
sources=[];imports=[]
for name in ['MonitorMesh.kt','ResizeFrameGeometry.kt','CurvedResizeHandles.kt','MonitorMoveHandle.kt']:
    lines=(folder/name).read_text().splitlines()
    imports += [l for l in lines if l.startswith('import kotlin.')]
    sources.append('\n'.join(l for l in lines if not l.startswith(('package ','import ')) and not l.strip().startswith('@OptIn(')))
stubs='''
internal data class FloatSize3d(val width:Float,val height:Float,val depth:Float)
internal data class Vector3(val x:Float=0f,val y:Float=0f,val z:Float=0f)
internal data class Quaternion(val normal:Vector3=Vector3(0f,0f,1f),val up:Vector3=Vector3(0f,1f,0f)) {
    companion object { fun fromLookTowards(normal:Vector3,up:Vector3)=Quaternion(normal,up) }
}
internal data class Pose(val translation:Vector3=Vector3(),val rotation:Quaternion=Quaternion()) {
    fun compose(other:Pose)=Pose(Vector3(translation.x+other.translation.x,translation.y+other.translation.y,translation.z+other.translation.z),other.rotation)
}
internal class Session { val entities=mutableListOf<Entity>() }
internal class ResizeEvent(val resizeState:ResizeState,val newSize:FloatSize3d) { enum class ResizeState { START,ONGOING,END } }
internal open class Entity {
    var parent:Entity?=null
    @JvmField var pose=Pose(); @JvmField var scale=1f; @JvmField var enabled=true
    var resize:ResizableComponent?=null
    var move:MovableComponent?=null
    fun getPose()=pose
    fun setPose(p:Pose) { pose=p }
    fun getScale()=scale
    fun setScale(s:Float) { scale=s }
    fun setEnabled(e:Boolean) { enabled=e }
    fun addComponent(c:ResizableComponent):Boolean { resize=c; return true }
    fun addComponent(c:MovableComponent):Boolean { move=c; return true }
    fun removeComponent(c:MovableComponent) { if(move===c) move=null }
    fun removeComponent(c:ResizableComponent) { if(resize===c) resize=null }
    companion object {
        fun create(session:Session,name:String,pose:Pose=Pose(),parent:Entity?)=Entity().also { it.pose=pose; it.parent=parent; session.entities.add(it) }
    }
}
internal class ResizableComponent(val listener:java.util.function.Consumer<ResizeEvent>) {
    var isFixedAspectRatioEnabled=false
    var isAutoHideContentWhileResizingEnabled=true
    var affordanceSize=FloatSize3d(1f,1f,1f)
    var minimumEntitySize=FloatSize3d(0f,0f,0f)
    var maximumEntitySize=FloatSize3d(10f,10f,10f)
    companion object {
        fun create(session:Session,resizeEventListener:java.util.function.Consumer<ResizeEvent>)=ResizableComponent(resizeEventListener)
    }
}
'''
stubs += """
internal annotation class SuppressLint(vararg val value:String)
internal data class FloatSize2d(val width:Float,val height:Float)
internal data class IntSize2d(val width:Int,val height:Int)
internal class Ray
internal object Color { const val TRANSPARENT=0; fun rgb(r:Int,g:Int,b:Int)=1 }
internal object PorterDuff { enum class Mode { CLEAR } }
internal class Paint(flags:Int) {
    var color=0;var textSize=0f;var textAlign=Align.CENTER
    enum class Align { CENTER }
    companion object { const val ANTI_ALIAS_FLAG=1 }
}
internal class Canvas {
    fun drawColor(color:Int,mode:PorterDuff.Mode) {}
    fun drawRoundRect(a:Float,b:Float,c:Float,d:Float,e:Float,f:Float,paint:Paint) {}
    fun drawText(text:String,x:Float,y:Float,paint:Paint) {}
}
internal class Surface {
    fun lockCanvas(rect:Any?)=Canvas()
    fun unlockCanvasAndPost(canvas:Canvas) {}
}
internal class SurfaceEntity(var shape:Shape):Entity() {
    enum class SuperSampling { NONE }
    enum class MediaBlendingMode { TRANSPARENT }
    var mediaBlendingMode=MediaBlendingMode.TRANSPARENT
    private val surface=Surface()
    fun getSurface()=surface
    fun setSurfacePixelDimensions(size:IntSize2d) {}
    interface Shape { class Quad(val size:FloatSize2d):Shape }
    companion object {
        fun create(session:Session,shape:Shape,parent:Entity?,superSampling:SuperSampling)=SurfaceEntity(shape).also { it.parent=parent;session.entities.add(it) }
    }
}
internal interface EntityMoveListener {
    fun onMoveStart(entity:Entity,initialInputRay:Ray,initialPose:Pose,initialScale:Float,initialParent:Entity) {}
    fun onMoveUpdate(entity:Entity,currentInputRay:Ray,currentPose:Pose,currentScale:Float) {}
    fun onMoveEnd(entity:Entity,finalInputRay:Ray,finalPose:Pose,finalScale:Float,updatedParent:Entity?) {}
}
internal class MovableComponent(val listener:EntityMoveListener,val scaleInZ:Boolean) {
    var size=FloatSize3d(0f,0f,0f)
    companion object {
        fun createCustomMovable(session:Session,scaleInZ:Boolean,executor:Any?,entityMoveListener:EntityMoveListener)=MovableComponent(entityMoveListener,scaleInZ)
    }
}
"""
checks='''
fun main() {
    fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf { (a[it]*b[it]).toDouble() }.toFloat()
    fun unit(v:FloatArray):FloatArray { val n=kotlin.math.sqrt(dot(v,v)); return FloatArray(3){v[it]/n} }
    fun near(a:Float,b:Float)=check(kotlin.math.abs(a-b)<.00002f) { "$a != $b" }
    for((label,mesh) in listOf(
        "left-middle" to MonitorMesh(1.4094946f,.8809341f,floatArrayOf(-.3641987f,-.037884656f,1.4811001f)),
        "bottom" to MonitorMesh(1.1701335f,.73133343f,floatArrayOf(.00200787f,-.6641777f,1.0608732f)))) {
        val frame=curvedResizeFrame(mesh)
        val oldMin=(0 until mesh.positions.size/3).minOf { mesh.positions[it*3+2] }
        val minGap=(0 until mesh.positions.size/3).minOf { vertex ->
            dot(FloatArray(3){mesh.positions[vertex*3+it]-frame.center[it]},frame.normal)
        }
        check(oldMin<-.01f && minGap>0f)
        val sample=if(label=="left-middle") floatArrayOf(-mesh.width*.25f,0f,0f) else floatArrayOf(0f,-mesh.height*.25f,0f)
        check(mesh.project(sample)[2]<0f)
        for(x in listOf(-.5f,.5f)) for(y in listOf(-.5f,.5f)) {
            val corner=mesh.project(floatArrayOf(x*mesh.width,y*mesh.height,0f))
            val frameCorner=FloatArray(3){frame.center[it]+x*frame.width*mesh.tangentX[it]+y*frame.height*mesh.tangentY[it]}
            val imageRay=unit(FloatArray(3){corner[it]-mesh.eye[it]})
            val handleRay=unit(FloatArray(3){frameCorner[it]-mesh.eye[it]})
            for(i in 0..2) near(imageRay[i],handleRay[i])
        }
        println("$label: old plane min z=$oldMin; aligned frame minimum clearance=$minGap; all four corner rays match")
    }
    val mesh=MonitorMesh(.8f,.5f,floatArrayOf(-.2f,-.3f,1.2f)); val frame=curvedResizeFrame(mesh)
    val session=Session(); val parent=Entity(); val video=Entity().also { it.parent=parent; it.pose=Pose(Vector3(.2f,0f,0f)); it.scale=.8f }
    val handles=CurvedResizeHandles(); val events=mutableListOf<Float>()
    handles.update(session,video,mesh,true) { _,factor -> events.add(factor) }
    val root=session.entities.single(); val component=root.resize!!
    check(root!==video && root.parent===parent && root.scale==.8f)
    near(root.pose.translation.x,.2f+frame.center[0]*.8f)
    near(root.pose.rotation.normal.x,frame.normal[0]); near(root.pose.rotation.normal.y,frame.normal[1])
    check(component.isFixedAspectRatioEnabled && !component.isAutoHideContentWhileResizingEnabled)
    near(component.affordanceSize.width,frame.width)
    fun event(state:ResizeEvent.ResizeState,factor:Float)=ResizeEvent(state,FloatSize3d(frame.width*factor,frame.height*factor,.001f))
    component.listener.accept(event(ResizeEvent.ResizeState.ONGOING,1.1f)); check(events.isEmpty())
    component.listener.accept(event(ResizeEvent.ResizeState.START,1f))
    component.listener.accept(event(ResizeEvent.ResizeState.ONGOING,1.2f))
    component.listener.accept(event(ResizeEvent.ResizeState.END,1.5f))
    check(events.size==3);near(events[0],1f);near(events[1],1.2f);near(events[2],1.5f)
    component.listener.accept(event(ResizeEvent.ResizeState.START,2f))
    component.listener.accept(event(ResizeEvent.ResizeState.END,2.4f))
    near(events[3],1f);near(events[4],1.2f) // Start anchors cancel any native size-unit scale.
    video.pose=Pose(Vector3(.4f,0f,0f));video.scale=1.2f
    handles.update(session,video,mesh,false) { _,factor -> events.add(factor) }
    check(session.entities.size==1 && !root.enabled && root.scale==1.2f)
    near(root.pose.translation.x,.4f+frame.center[0]*1.2f)
    handles.update(session,null,null,true) { _,_ -> error("stale resize") }
    check(root.parent==null && root.resize==null)
    handles.update(session,video,mesh,true) { _,factor -> events.add(factor) }
    component.listener.accept(event(ResizeEvent.ResizeState.START,1f));check(events.size==5)
    check(session.entities.size==2)
    handles.close();check(session.entities.last().parent==null)
    val moveSession=Session();val moveParent=Entity();val moveVideo=Entity().also { it.parent=moveParent }
    val moveHandle=MonitorMoveHandle();val phases=mutableListOf<MoveHandlePhase>()
    moveHandle.update(moveSession,moveVideo,Pose(),null,.8f,.5f,1f,true) { phase,_ -> phases.add(phase) }
    val grip=moveSession.entities.single() as SurfaceEntity
    val move=grip.move!!
    check(moveVideo.move==null && grip.parent===moveParent && !move.scaleInZ)
    check(grip.pose.translation.y+move.size.height*.5f<-.25f) // Only a small grip below the image is movable.
    val originalPose=grip.pose
    move.listener.onMoveStart(grip,Ray(),originalPose,1f,moveParent)
    val dragged=Pose(Vector3(.1f,originalPose.translation.y,0f))
    move.listener.onMoveUpdate(grip,Ray(),dragged,1f)
    moveHandle.update(moveSession,moveVideo,Pose(),null,.8f,.5f,1f,true) { phase,_ -> phases.add(phase) }
    check(grip.pose==dragged) // Per-frame placement must not fight an active drag.
    move.listener.onMoveEnd(grip,Ray(),dragged,1f,null)
    check(phases==listOf(MoveHandlePhase.START,MoveHandlePhase.UPDATE,MoveHandlePhase.END))
    moveHandle.close();check(grip.parent==null && grip.move==null)
    moveHandle.update(moveSession,moveVideo,Pose(),mesh,.8f,.5f,1f,true) { phase,_ -> phases.add(phase) }
    move.listener.onMoveStart(grip,Ray(),originalPose,1f,moveParent);check(phases.size==3)
    moveHandle.close()
    println("Passed: explicit below-image move grip, no whole-body movement, active-drag ownership and stale move callback rejection")
    println("Passed: behind-image tangent frame, projected corner alignment, independent resize ownership, source scale factors, transform synchronization and stale callback rejection")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-resize-') as temp:
    file=Path(temp)/'CurvedResizeCheck.kt';file.write_text('\n'.join(sorted(set(imports)))+'\n'+stubs+'\n'.join(sources)+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'CurvedResizeCheckKt'],check=True)
