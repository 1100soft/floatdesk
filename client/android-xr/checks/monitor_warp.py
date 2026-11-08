#!/usr/bin/env python3
"""Exercise production monitor display/input lifecycle with SceneCore value/entity substitutes."""
from pathlib import Path
import subprocess
import sys
import tempfile

folder = Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client'
mesh = (folder/'MonitorMesh.kt').read_text().split('\n', 1)[1]
warp = (folder/'MonitorWarp.kt').read_text()
warp = warp[warp.index('/** Deform the original Compose-owned'):].replace('@SuppressLint("RestrictedApi")', '')
stubs = '''
internal class Surface
internal data class Vector3(val x: Float, val y: Float, val z: Float) {
    val length get() = kotlin.math.sqrt(x*x+y*y+z*z)
}
internal data class Quaternion(val x: Float=0f,val y: Float=0f,val z: Float=0f,val w: Float=1f)
internal data class Pose(val translation: Vector3 = Vector3(0f,0f,0f),val rotation: Quaternion=Quaternion())
internal enum class Space { PARENT, ACTIVITY }
internal object Log { fun i(tag: String,message: String) {} }
internal data class FloatSize2d(val width: Float, val height: Float)
internal data class MonitorPlacement(val physicalWidthM: Float, val physicalHeightM: Float)
internal open class Entity {
    var parent: Entity? = null
    var enabled = true
    var component: InteractableComponent? = null
    fun addComponent(value: InteractableComponent): Boolean { component=value; return true }
    fun removeComponent(value: InteractableComponent) { if(component === value) component=null }
    private var opacity = 1f
    private var pose = Pose()
    private var scale = 1f
    fun getAlpha() = opacity
    fun setAlpha(value: Float) { opacity = value }
    fun getPose(space: Space = Space.PARENT) = pose
    fun setPose(value: Pose) { pose = value }
    fun getScale(space: Space = Space.PARENT) = scale
    fun setScale(value: Float) { scale = value }
    fun transformVectorTo(v: Vector3,other: Entity) = v
    fun transformPoseTo(p: Pose, other: Entity): Pose {
        val a = p.translation; val b = other.getPose().translation
        val source = getPose().translation
        return Pose(Vector3(a.x+source.x-b.x,a.y+source.y-b.y,a.z+source.z-b.z))
    }
}
internal class Session { val scene = Scene() }
internal class PixelDensity { fun convertPixelsToMeters(v: Float) = v/1000f; fun convertMetersToPixels(v: Float) = v*1000f }
internal class SpatialInputEvent(val origin: Vector3,val direction: Vector3,val source: Int=0,val pointerType: String="LEFT",val action: InputEvent.Action=InputEvent.Action.DOWN,val timestamp: Long=0,val hitPosition: Vector3?=null)
internal class Matrix4(val data: FloatArray) { val inverse get() = this }
internal class InputEvent(val action: Action,val source: Int,val pointerType: String,val timestamp: Long,val origin: Vector3,val direction: Vector3,val hitInfoList: List<HitInfo>) {
    enum class Action { DOWN, UP, CANCEL, MOVE }
    class HitInfo(val inputEntity: Entity,val hitPosition: Vector3?,val transform: Matrix4)
}
internal class InteractableComponent(val listener: (InputEvent)->Unit) {
    companion object { fun create(session: Session,listener: (InputEvent)->Unit) = InteractableComponent(listener) }
}
internal class Scene {
    val activitySpace = Entity()
    val perceptionSpace = Entity()
    val entities = mutableListOf<SurfaceEntity>()
    fun getEntitiesOfType(type: Class<SurfaceEntity>) = entities.toList()
}
internal class SurfaceEntity(var shape: Shape): Entity() {
    private val surface = Surface()
    fun getSurface() = surface
    var mediaBlendingMode = MediaBlendingMode.TRANSPARENT
    enum class MediaBlendingMode { TRANSPARENT, OPAQUE }
    interface Shape {
        class Quad(val extents: FloatSize2d): Shape
        class TriangleMesh(val positions: java.nio.FloatBuffer, val texCoords: java.nio.FloatBuffer, val indices: java.nio.IntBuffer)
        class CustomMesh(val mesh: TriangleMesh): Shape
    }
    companion object {
        fun create(session: Session, shape: Shape) = SurfaceEntity(shape).also { session.scene.entities.add(it) }
    }
}
'''
checks = '''
fun main() {
    val session = Session(); val root = Entity(); val screen = MonitorPlacement(.8f,.45f)
    val input = SurfaceEntity.create(session,SurfaceEntity.Shape.Quad(FloatSize2d(.8f,.45f)))
    input.parent = root; input.setPose(Pose(Vector3(.1f,0f,-1f))); input.setAlpha(.8f)
    val warp = MonitorWarp()
    val output = warp.attach(session,input.getSurface(),screen)
    val video = session.scene.entities.single { it.getSurface() === output }
    check(video === input && output === input.getSurface())
    check(input.enabled && input.getAlpha() == .8f)
    warp.update(session,root,floatArrayOf(0f,0f,0f),screen,true,false)
    val curved = video.shape as SurfaceEntity.Shape.CustomMesh
    check(curved.mesh.texCoords[1] == 1f)
    check(warp.mesh != null && input.enabled)
    warp.update(session,root,floatArrayOf(.3f,0f,0f),screen,true,true)
    check(video.shape === curved && input.enabled)
    warp.update(session,root,floatArrayOf(.3f,0f,0f),screen,true,false)
    check(video.shape !== curved && video.getSurface() === output)
    warp.update(session,root,floatArrayOf(.3f,0f,0f),screen,false,false)
    check(video.shape is SurfaceEntity.Shape.Quad && warp.mesh == null)
    warp.close(); warp.close()
    check(input.enabled && input.getAlpha() == .8f && input.parent === root)
    session.scene.activitySpace.setPose(Pose(Vector3(.3f,-.5f,1.2f)))
    val events = mutableListOf<SpatialInputEvent>()
    warp.attach(session,input.getSurface(),screen,PixelDensity()) { events.add(it) }
    val component = input.component!!
    input.setScale(1.785f)
    val ip = input.getPose().translation
    val matrix = Matrix4(floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, -ip.x,-ip.y,-ip.z,1f))
    fun emit(side: String, action: InputEvent.Action) {
        component.listener(InputEvent(action,0,side,0,Vector3(0f,0f,1f),Vector3(0f,0f,-1f),
            listOf(InputEvent.HitInfo(input,Vector3((ip.x+(if(side=="LEFT") -.2f else .2f))*1.785f,ip.y*1.785f,ip.z*1.785f),matrix))))
    }
    emit("LEFT",InputEvent.Action.DOWN); emit("RIGHT",InputEvent.Action.DOWN)
    emit("LEFT",InputEvent.Action.UP); emit("RIGHT",InputEvent.Action.UP)
    check(events.map { it.pointerType } == listOf("LEFT","RIGHT","LEFT","RIGHT"))
    check(kotlin.math.abs(events[0].hitPosition!!.x+200f) < .001f && kotlin.math.abs(events[1].hitPosition!!.x-200f) < .001f)
    warp.close(); check(input.component == null && input.parent === root && input.enabled)
    println("Passed: native matrix localization and independent left/right sequences, original Compose surface/input/alpha/parent preserved, curvature freezes while held, unchanged decoder surface, video UVs, cleanup")
}
'''
code = 'import java.nio.FloatBuffer\nimport java.nio.IntBuffer\n' + mesh + stubs + warp + checks
lib = Path(sys.argv[1]); stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-warp-') as temp:
    test = Path(temp)/'MonitorWarpChecks.kt'; test.write_text(code)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'MonitorWarpChecksKt'],check=True)
