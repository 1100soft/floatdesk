#!/usr/bin/env python3
"""Check curved resize-frame projection, plane ordering and affordance lifecycle."""
from pathlib import Path
import subprocess
import sys
import tempfile
folder=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
sources=[];imports=[]
for name in ['MonitorMesh.kt','ResizeFrameGeometry.kt','MonitorSystemControls.kt']:
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
    var isAlwaysShowOverlayEnabled=false
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
internal object Color { const val TRANSPARENT=0; fun rgb(r:Int,g:Int,b:Int)=1;fun argb(a:Int,r:Int,g:Int,b:Int)=1 }
internal object PorterDuff { enum class Mode { CLEAR } }
internal class Paint(flags:Int) {
    var color=0;var textSize=0f;var textAlign=Align.CENTER;var style=Style.STROKE;var strokeWidth=1f
    enum class Style { STROKE }
    enum class Align { CENTER }
    companion object { const val ANTI_ALIAS_FLAG=1 }
}
internal class Canvas {
    fun drawColor(color:Int,mode:PorterDuff.Mode) {}
    fun drawRoundRect(a:Float,b:Float,c:Float,d:Float,e:Float,f:Float,paint:Paint) {}
    fun drawText(text:String,x:Float,y:Float,paint:Paint) {}
    fun drawRect(a:Float,b:Float,c:Float,d:Float,paint:Paint) {}
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
internal class MovableComponent(val scaleInZ:Boolean) {
    lateinit var listener:EntityMoveListener
    fun addMoveListener(value:EntityMoveListener) { listener=value }
    var size=FloatSize3d(0f,0f,0f)
    companion object {
        fun createSystemMovable(session:Session,scaleInZ:Boolean)=MovableComponent(scaleInZ)
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
        "bottom" to MonitorMesh(1.1701335f,.73133343f,floatArrayOf(.00200787f,-.6641777f,1.0608732f)),
        "dynamic" to MonitorMesh(1.4f,.88f,floatArrayOf(-.36f,-.04f,1.48f),focus=floatArrayOf(.7f,0f)))) {
        val frame=curvedResizeFrame(mesh)
        val oldMin=(0 until mesh.positions.size/3).minOf { mesh.positions[it*3+2] }
        val minGap=(0 until mesh.positions.size/3).minOf { vertex ->
            dot(FloatArray(3){mesh.positions[vertex*3+it]-frame.center[it]},frame.normal)
        }
        check(minGap>0f)
        if(label!="dynamic") check(oldMin<-.01f)
        val sample=if(label=="left-middle") floatArrayOf(-mesh.width*.25f,0f,0f) else floatArrayOf(0f,-mesh.height*.25f,0f)
        if(label!="dynamic") check(mesh.project(sample)[2]<0f)
        for(x in listOf(-.5f,.5f)) for(y in listOf(-.5f,.5f)) {
            val corner=mesh.project(floatArrayOf(x*mesh.width,y*mesh.height,0f))
            val frameCorner=FloatArray(3){frame.center[it]+x*frame.width*mesh.frameTangentX[it]+y*frame.height*mesh.frameTangentY[it]}
            val imageRay=unit(FloatArray(3){corner[it]-mesh.eye[it]})
            val handleRay=unit(FloatArray(3){frameCorner[it]-mesh.eye[it]})
            if(label!="dynamic") for(i in 0..2) near(imageRay[i],handleRay[i])
        }
        if(label=="dynamic") {
            for(i in mesh.positions.indices step 3) {
                val ray=FloatArray(3){mesh.positions[i+it]-mesh.eye[it]}
                val t=dot(FloatArray(3){frame.center[it]-mesh.eye[it]},frame.normal)/dot(ray,frame.normal)
                check(t>0f)
                val onPlane=FloatArray(3){mesh.eye[it]+ray[it]*t-frame.center[it]}
                check(kotlin.math.abs(dot(onPlane,mesh.tangentX))<=frame.width*.5f+.00001f)
                check(kotlin.math.abs(dot(onPlane,mesh.tangentY))<=frame.height*.5f+.00001f)
            }
        }
        println("$label: aligned backing frame clearance=$minGap; full dynamic outline is contained")
    }
    val mesh=MonitorMesh(.8f,.5f,floatArrayOf(-.2f,-.3f,1.2f)); val frame=curvedResizeFrame(mesh)
    val session=Session();val parent=Entity();val video=Entity().also { it.parent=parent;it.pose=Pose(Vector3(.2f,0f,0f));it.scale=.8f }
    val handles=MonitorSystemControls();val events=mutableListOf<Float>();val phases=mutableListOf<SystemMovePhase>()
    fun update(m:MonitorMesh?=mesh,enabled:Boolean=true) = handles.update(session,video,m,.8f,.5f,mesh.eye,1f,enabled,
        onResize={ _,factor -> events.add(factor) },onMove={ phase,_ -> phases.add(phase) })
    update()
    val root=session.entities.single() as SurfaceEntity;val component=root.resize!!;val move=root.move!!
    check(root!==video && root.parent===parent && root.scale==.8f && video.move==null && !move.scaleInZ)
    near(root.pose.translation.x,.2f+frame.center[0]*.8f)
    check(component.isFixedAspectRatioEnabled && !component.isAutoHideContentWhileResizingEnabled && component.isAlwaysShowOverlayEnabled)
    near(component.affordanceSize.width,frame.width)
    fun event(state:ResizeEvent.ResizeState,factor:Float)=ResizeEvent(state,FloatSize3d(frame.width*factor,frame.height*factor,.001f))
    component.listener.accept(event(ResizeEvent.ResizeState.ONGOING,1.1f));check(events.isEmpty())
    component.listener.accept(event(ResizeEvent.ResizeState.START,1f))
    component.listener.accept(event(ResizeEvent.ResizeState.ONGOING,1.2f))
    val initialPose=root.pose;val initialShape=root.shape
    val proposed=FloatSize3d(frame.width*1.2f,frame.height*1.2f,.001f)
    component.affordanceSize=proposed // Native controller updates its proposal.
    video.pose=Pose(Vector3(.4f,0f,0f));video.scale=1.2f
    update(MonitorMesh(1f,.625f,mesh.eye))
    check(root.pose==initialPose && root.shape===initialShape && root.scale==.8f)
    check(component.affordanceSize==proposed) // App must not reset native size during drag: flicker regression.
    component.listener.accept(event(ResizeEvent.ResizeState.END,1.5f))
    check(events.size==3);near(events[0],1f);near(events[1],1.2f);near(events[2],1.5f)
    update()
    near(root.pose.translation.x,.4f+frame.center[0]*1.2f);check(root.scale==1.2f)
    component.listener.accept(event(ResizeEvent.ResizeState.START,2f))
    component.listener.accept(event(ResizeEvent.ResizeState.END,2.4f))
    near(events[3],1f);near(events[4],1.2f)
    val beforeMove=root.pose
    move.listener.onMoveStart(root,Ray(),beforeMove,1.2f,parent)
    val dragged=Pose(Vector3(.7f,.1f,0f));root.pose=dragged // System controller applies its proposal.
    move.listener.onMoveUpdate(root,Ray(),dragged,1.2f)
    update();check(root.pose==dragged) // Native move owns pose until release.
    move.listener.onMoveEnd(root,Ray(),dragged,1.2f,null)
    check(phases==listOf(SystemMovePhase.START,SystemMovePhase.UPDATE,SystemMovePhase.END))
    update(null)
    check(root.pose.translation.z<0f && root.move===move) // Same system rectangle supports flat mode.
    val flat=flatResizeFrame(.8f,.5f,mesh.eye)
    for(x in listOf(-.5f,.5f)) for(y in listOf(-.5f,.5f)) {
        val imageRay=unit(floatArrayOf(x*.8f-mesh.eye[0],y*.5f-mesh.eye[1],-mesh.eye[2]))
        val handleRay=unit(floatArrayOf(flat.center[0]+x*flat.width-mesh.eye[0],flat.center[1]+y*flat.height-mesh.eye[1],flat.center[2]-mesh.eye[2]))
        for(i in 0..2) near(imageRay[i],handleRay[i])
    }
    handles.close();check(root.parent==null && root.resize==null && root.move==null)
    update();check(session.entities.size==2)
    component.listener.accept(event(ResizeEvent.ResizeState.START,1f));check(events.size==5)
    move.listener.onMoveStart(root,Ray(),beforeMove,1f,parent);check(phases.size==3)
    val active=session.entities.last() as SurfaceEntity
    active.move!!.listener.onMoveStart(active,Ray(),active.pose,1f,parent)
    update(enabled=false)
    check(active.parent==null && active.move==null && active.resize==null)
    handles.close()
    println("Passed: native backing rectangle in both modes, no app pose/size writes during active resize/move, projected flat corners and stale callback rejection")
    println("Passed: behind-image tangent frame, projected corner alignment, independent resize ownership, source scale factors, transform synchronization and stale callback rejection")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-resize-') as temp:
    file=Path(temp)/'CurvedResizeCheck.kt';file.write_text('\n'.join(sorted(set(imports)))+'\n'+stubs+'\n'.join(sources)+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'CurvedResizeCheckKt'],check=True)
