#!/usr/bin/env python3
"""Exercise production monitor display/input lifecycle with SceneCore value/entity substitutes."""
from pathlib import Path
import subprocess
import sys
import tempfile

folder = Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client'
mesh = (folder/'MonitorMesh.kt').read_text().split('\n', 1)[1]
warp = (folder/'MonitorWarp.kt').read_text()
warp = warp[warp.index('/** Keep input enabled'):].replace('@SuppressLint("RestrictedApi")', '')
stubs = '''
internal class Surface
internal data class Vector3(val x: Float, val y: Float, val z: Float) {
    val length get() = kotlin.math.sqrt(x*x+y*y+z*z)
}
internal data class Pose(val translation: Vector3 = Vector3(0f,0f,0f))
internal data class FloatSize2d(val width: Float, val height: Float)
internal data class MonitorPlacement(val physicalWidthM: Float, val physicalHeightM: Float)
internal open class Entity {
    var parent: Entity? = null
    var enabled = true
    private var opacity = 1f
    private var pose = Pose()
    private var scale = 1f
    fun getAlpha() = opacity
    fun setAlpha(value: Float) { opacity = value }
    fun getPose() = pose
    fun setPose(value: Pose) { pose = value }
    fun getScale() = scale
    fun setScale(value: Float) { scale = value }
    fun transformVectorTo(v: Vector3,other: Entity) = v
    fun transformPoseTo(p: Pose, other: Entity): Pose {
        val a = p.translation; val b = other.getPose().translation
        return Pose(Vector3(a.x-b.x,a.y-b.y,a.z-b.z))
    }
}
internal class Session { val scene = Scene() }
internal class PixelDensity { fun convertPixelsToMeters(v: Float) = v/1000f }
internal class SpatialInputEvent(val origin: Vector3,val direction: Vector3)
internal class Scene {
    val activitySpace = Entity()
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
    check(video !== input && video.parent === input.parent)
    check(input.enabled && input.getAlpha() == 0f)
    check(video.getPose() == input.getPose())
    val flatHit = warp.hit(session,SpatialInputEvent(Vector3(0f,0f,0f),Vector3(100f,0f,-1000f)),PixelDensity(),screen)!!
    check(kotlin.math.abs(flatHit[0]) < .0001f && kotlin.math.abs(flatHit[2]) < .0001f)
    warp.update(session,root,floatArrayOf(0f,0f,0f),screen,true,false)
    val curved = video.shape as SurfaceEntity.Shape.CustomMesh
    check(curved.mesh.texCoords[1] == 1f)
    check(warp.mesh != null && input.shape === curved && input.enabled)
    // Simulate the frozen snapshot hiding just the display. Input must keep working.
    video.enabled = false
    input.setPose(Pose(Vector3(.2f,.1f,-.8f)))
    warp.update(session,root,floatArrayOf(.3f,0f,0f),screen,true,true)
    check(video.shape === curved && video.getPose() == input.getPose())
    check(input.enabled && input.shape === curved)
    video.enabled = true
    warp.update(session,root,floatArrayOf(.3f,0f,0f),screen,true,false)
    check(video.shape !== curved && video.getSurface() === output)
    warp.update(session,root,floatArrayOf(.3f,0f,0f),screen,false,false)
    check(video.shape is SurfaceEntity.Shape.Quad && warp.mesh == null)
    check(input.shape is SurfaceEntity.Shape.Quad && input.enabled && video.getSurface() === output)
    warp.close(); warp.close()
    check(input.enabled && input.getAlpha() == .8f && video.parent == null)
    println("Passed: independent video/input surfaces, matched enabled input/video geometry through warp/freeze/release, unchanged decoder surface, video UVs, pose synchronization, cleanup")
}
'''
code = 'import java.nio.FloatBuffer\nimport java.nio.IntBuffer\n' + mesh + stubs + warp + checks
lib = Path(sys.argv[1]); stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-warp-') as temp:
    test = Path(temp)/'MonitorWarpChecks.kt'; test.write_text(code)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'MonitorWarpChecksKt'],check=True)
