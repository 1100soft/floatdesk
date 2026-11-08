#!/usr/bin/env python3
"""Run the production gesture controller/math on the JVM without Android/Gradle.

Pass the Gradle lib directory containing kotlin-compiler-embeddable and kotlin-stdlib.
Only platform input/value types are substituted; gesture functions are extracted unchanged.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/MainActivity.kt').read_text()
def section(start, end):
    return source[source.index(start):source.index(end)]

stub = '''
private class TestState<T>(var value: T) {
    operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>) = value
    operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, next: T) { value = next }
}
private fun <T> mutableStateOf(value: T) = TestState(value)
private data class Pose(val position: FloatArray, val rotation: FloatArray = floatArrayOf(0f,0f,0f,1f))
private data class MonitorPlacement(val pose: Pose, val physicalWidthM: Float = 0.8f, val physicalHeightM: Float = 0.45f)
private data class Vector3(val x: Float, val y: Float, val z: Float)
private class PixelDensity { fun convertPixelsToMeters(x: Float) = x / 1000f }
private class InputEvent { enum class Action { DOWN, MOVE, UP, CANCEL }; enum class Source { HANDS, GAZE_AND_GESTURE } }
private data class SpatialInputEvent(val pointerType: String, val action: InputEvent.Action, val hitPosition: Vector3?, val source: InputEvent.Source = InputEvent.Source.HANDS)
private object Log { fun i(tag: String, message: String) {} }
'''
guide_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/GrabGuideRenderer.kt').read_text()
stub += guide_source[guide_source.index('internal data class GrabGuide'):guide_source.index('/** Native meshes')]
mesh_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/MonitorMesh.kt').read_text().split('\n', 1)[1]
code = mesh_source + stub + section('private const val PALM_WIDTH', '@Composable\nprivate fun PalmControls')
code += section('private class PalmPoke', '/** The pinned SceneCore')
code += section('private class GrabTracking', 'private class FollowLock(')
code += section('private fun palmNormal(', 'private fun openViewpoint(')
code += section('private fun openPalm(', 'private fun readHand(')
code += section('private fun dot(a: FloatArray', 'private fun Pose.toRuntimePose(')
code += section('private fun rotateVector(', 'private fun subtract(')
code += source[source.index('private fun subtract('):]
code += '''
private fun near(a: FloatArray, b: FloatArray) { check(distance(a,b) < 0.0001f) { "${a.contentToString()} != ${b.contentToString()}" } }
private fun point(m: MonitorPlacement, local: FloatArray, scale: Float = 1f) = transformPoint(m.pose, local, scale)
fun main() {
    val l = floatArrayOf(-0.21f,0.06f,0f); val r = floatArrayOf(0.19f,-0.04f,0f)
    val initial = MonitorPlacement(Pose(floatArrayOf(0.4f,0.2f,-1.3f), normalize4(floatArrayOf(0.2f,0.3f,0.1f,0.9f))))
    fun event(side: String, action: InputEvent.Action, hit: FloatArray? = null) = SpatialInputEvent(side,action,hit?.let{Vector3(it[0]*1000,it[1]*1000,0f)})
    val tracking = GrabTracking().apply {
        points["LEFT"] = floatArrayOf(-0.1f,0f,-0.4f)
        points["RIGHT"] = floatArrayOf(0.1f,0f,-0.4f)
        pinched.addAll(listOf("LEFT","RIGHT"))
    }
    val startL = tracking.points.getValue("LEFT").copyOf(); val startR = tracking.points.getValue("RIGHT").copyOf()
    fun guides(grab: TwoHandState, monitor: MonitorPlacement) {
        val guide = grab.guide ?: error("Missing active guide")
        check(guide.corners.size == 4 && guide.contacts.size == 2 && guide.pinches.size == 2)
        for ((i, local) in listOf(l,r).withIndex()) {
            near(guide.pinches[i],tracking.points.getValue(if(i==0) "LEFT" else "RIGHT"))
            near(guide.contacts[i],point(monitor,local,monitor.physicalWidthM / initial.physicalWidthM))
            val u = local[0] / initial.physicalWidthM + 0.5f
            val v = 0.5f - local[1] / initial.physicalHeightM
            val top = subtract(guide.corners[1],guide.corners[0]).map{it*u}.toFloatArray()
            val down = subtract(guide.corners[3],guide.corners[0]).map{it*v}.toFloatArray()
            near(add(add(guide.corners[0],top),down),guide.pinches[i])
            check(kotlin.math.abs(screenLocal(monitor.pose,guide.contacts[i])[2]) < 0.00001f)
        }
    }
    fun lock(grab: TwoHandState, screen: MonitorPlacement = initial, localL: FloatArray = l, localR: FloatArray = r) {
        grab.onEvent(event("LEFT",InputEvent.Action.DOWN,localL),screen,PixelDensity(),tracking)
        grab.onEvent(event("RIGHT",InputEvent.Action.DOWN,localR),screen,PixelDensity(),tracking)
    }
    val grab = TwoHandState(); lock(grab); check(grab.engaged)
    val locked = grab.advance(tracking,initial)!!
    near(locked.pose.position,initial.pose.position)
    near(point(locked,l),point(initial,l)); near(point(locked,r),point(initial,r))
    guides(grab,locked)
    val gain = distance(l,r) / distance(startL,startR)
    val align = quaternionBetween(subtract(startR,startL),subtract(point(initial,r),point(initial,l)))
    val delta = floatArrayOf(0.05f,-0.04f,-0.08f)
    tracking.points["LEFT"] = add(startL,delta); tracking.points["RIGHT"] = add(startR,delta)
    val moved = grab.advance(tracking,locked)!!
    val expectedDelta = rotateVector(align,delta[0]*gain,delta[1]*gain,delta[2]*gain)
    near(moved.pose.position,add(initial.pose.position,expectedDelta))
    guides(grab,moved)
    check(distance(moved.pose.position,initial.pose.position) > distance(delta,floatArrayOf(0f,0f,0f))*1.9f)
    // Independent depth on either hand maps both corresponding pixels exactly, not just the center.
    val dl = floatArrayOf(-0.03f,0.04f,-0.1f); val dr = floatArrayOf(0.04f,-0.02f,0.08f)
    tracking.points["LEFT"] = add(startL,dl); tracking.points["RIGHT"] = add(startR,dr)
    val tilted = grab.advance(tracking,moved)!!
    val scale = tilted.physicalWidthM / initial.physicalWidthM
    guides(grab,tilted)
    near(point(tilted,l,scale),add(point(initial,l),rotateVector(align,dl[0]*gain,dl[1]*gain,dl[2]*gain)))
    near(point(tilted,r,scale),add(point(initial,r),rotateVector(align,dr[0]*gain,dr[1]*gain,dr[2]*gain)))
    // DEFAULT UP must release too; MOVE while raised cannot resurrect a grab.
    grab.onEvent(event("DEFAULT",InputEvent.Action.UP),tilted,PixelDensity(),tracking)
    check(!grab.engaged); check(!grab.busy); check(grab.guide == null)
    grab.onEvent(event("LEFT",InputEvent.Action.MOVE,l),initial,PixelDensity(),tracking)
    check(!grab.engaged)
    tracking.points["LEFT"] = startL; tracking.points["RIGHT"] = startR
    lock(grab); check(grab.engaged)
    // Missing raw joints freezes immediately and permanently cancels after 120ms.
    tracking.points.remove("LEFT"); tracking.nowMs = 80
    check(grab.advance(tracking,initial) == null); check(grab.engaged)
    tracking.nowMs = 121; tracking.points["LEFT"] = startL
    check(grab.advance(tracking,initial) == null); check(!grab.engaged); check(grab.blocksNative); check(grab.guide == null)
    grab.onEvent(event("LEFT",InputEvent.Action.MOVE,l),initial,PixelDensity(),tracking); check(!grab.engaged)
    // Both hands must open, followed by a new pair of DOWNs, to rearm after lost tracking.
    tracking.pinched.clear(); grab.advance(tracking,initial); check(!grab.blocksNative)
    tracking.pinched.addAll(listOf("LEFT","RIGHT")); lock(grab); check(grab.engaged)
    // A physical release must end it even if UP was lost.
    tracking.nowMs = 221; tracking.pinched.remove("LEFT"); check(grab.advance(tracking,initial) == null)
    tracking.nowMs = 261; check(grab.advance(tracking,initial) == null); check(!grab.engaged)
    // Contacts too close or physical hands too close never enter the transform.
    val close = TwoHandState()
    tracking.nowMs = 300; tracking.pinched.addAll(listOf("LEFT","RIGHT"))
    lock(close,initial,floatArrayOf(0f,0f,0f),floatArrayOf(0.03f,0f,0f))
    check(!close.engaged); check(close.blocksNative); check(close.guide == null)
    close.onEvent(event("RIGHT",InputEvent.Action.MOVE,r),initial,PixelDensity(),tracking)
    check(!close.engaged)
    val closeHands = TwoHandState(); tracking.points["RIGHT"] = add(startL,floatArrayOf(0.03f,0f,0f))
    lock(closeHands); check(!closeHands.engaged)
    // Do not accept a single stale held hand after a pair ends; both must release.
    tracking.points["RIGHT"] = startR
    val fresh = TwoHandState(); lock(fresh); check(fresh.engaged)
    fresh.onEvent(event("LEFT",InputEvent.Action.UP),initial,PixelDensity(),tracking)
    fresh.onEvent(event("LEFT",InputEvent.Action.DOWN,l),initial,PixelDensity(),tracking)
    check(!fresh.engaged)
    fresh.onEvent(event("RIGHT",InputEvent.Action.UP),initial,PixelDensity(),tracking)
    lock(fresh); check(fresh.engaged)
    // A large tracking discontinuity must cancel rather than fling the monitor.
    tracking.points["LEFT"] = add(startL,floatArrayOf(1f,0f,0f))
    check(fresh.advance(tracking,initial) == null); check(!fresh.engaged)
    // Carry the span continuously through a full 360-degree turn in depth.
    val a = floatArrayOf(-0.2f,0f,0f); val b = floatArrayOf(0.2f,0f,0f)
    var monitor = MonitorPlacement(Pose(floatArrayOf(0f,0f,-1f)))
    for (degrees in 0..360 step 5) {
        val rad = Math.toRadians(degrees.toDouble()); val end = floatArrayOf((0.4*kotlin.math.cos(rad)).toFloat(),0f,(0.4*kotlin.math.sin(rad)).toFloat())
        val t1 = floatArrayOf(-end[0]/2,0f,-1-end[2]/2); val t2 = floatArrayOf(end[0]/2,0f,-1+end[2]/2)
        val next = placeGrabbed(a,b,t1,t2,monitor.pose.rotation,0.8f,0.45f,monitor)!!
        near(point(next,a),t1); near(point(next,b),t2)
        val oldUp = rotateVector(monitor.pose.rotation,0f,1f,0f); val newUp = rotateVector(next.pose.rotation,0f,1f,0f)
        check(distance(oldUp,newUp) < 0.1f); monitor = next
    }
    // A scaled/rotated Compose root must not gear down physical hand displacement.
    for (rootScale in listOf(0.2f, 0.5f, 2f)) {
        val rootRotation = normalize4(floatArrayOf(0.1f,-0.3f,0.2f,0.9f))
        val rootPose = Pose(floatArrayOf(0.8f,-0.5f,1.2f),rootRotation)
        val physicalDelta = floatArrayOf(0.11f,-0.07f,-0.21f)
        val rootDelta = screenLocal(Pose(floatArrayOf(0f,0f,0f),rootRotation),physicalDelta).map { it / rootScale }.toFloatArray()
        val start = MonitorPlacement(Pose(floatArrayOf(0f,0f,-1.2f)))
        val tl = add(point(start,a), rootDelta); val tr = add(point(start,b),rootDelta)
        val moved = placeGrabbed(a,b,tl,tr,start.pose.rotation,start.physicalWidthM,start.physicalHeightM,start)!!
        near(subtract(transformPoint(rootPose,moved.pose.position,rootScale), transformPoint(rootPose,start.pose.position,rootScale)),physicalDelta)
    }
    // No size cap may prevent a held pixel following its target.
    val large = MonitorPlacement(Pose(floatArrayOf(0f,0f,-1f)))
    val bigLeft = floatArrayOf(-1.2f,0.4f,-1.5f); val bigRight = floatArrayOf(1.2f,0.4f,-1.5f)
    val expanded = placeGrabbed(a,b,bigLeft,bigRight,large.pose.rotation,large.physicalWidthM,large.physicalHeightM,large)!!
    near(point(expanded,a,expanded.physicalWidthM / large.physicalWidthM),bigLeft)
    near(point(expanded,b,expanded.physicalWidthM / large.physicalWidthM),bigRight)
    // A biased runtime joint orientation must not tilt a flat anatomical palm plane.
    val flatNormal = palmNormal(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0.09f,0f),floatArrayOf(-0.04f,0.08f,0f),floatArrayOf(0.04f,0.07f,0f),floatArrayOf(-0.2f,0f,0.98f))
    near(flatNormal,floatArrayOf(0f,0f,1f))
    val wrist = floatArrayOf(0f,0f,0f); val palm = floatArrayOf(0f,0.05f,0f)
    check(!firmFist(wrist,palm,List(4){floatArrayOf(0f,0.115f,0.025f)},0.1f))
    check(openPalm(wrist,List(4){floatArrayOf(0f,0.145f,0.025f)},0.1f))
    check(!openPalm(wrist,List(4){floatArrayOf(0f,0.07f,0.02f)},0.1f))
    check(firmFist(wrist,palm,List(4){floatArrayOf(0f,0.07f,0.02f)},0.1f))
    check(!firmFist(wrist,palm,List(3){floatArrayOf(0f,0.07f,0.02f)} + listOf(floatArrayOf(0f,0.15f,0f)),0.1f))
    val eye = floatArrayOf(0.2f,0.1f,0f)
    val contact = floatArrayOf(0.8f,-0.3f,-2f)
    val visible = visibleGrabContact(contact,eye,0.5f)
    check(kotlin.math.abs(distance(contact,visible) - 0.004f) < 0.000001f)
    near(normalize3(subtract(visible,eye)),normalize3(subtract(contact,eye)))
    check(distance(visible,eye) < distance(contact,eye))
    near(visibleGrabContact(contact,null,1f),contact)
    // Every sampled point is on a viewer-centered sphere; image pixels remain invertible.
    for (eye in listOf(floatArrayOf(0f,0f,1.2f), floatArrayOf(.4f,-.3f,.8f), floatArrayOf(-.7f,.2f,2.4f))) {
        val mesh = MonitorMesh(1.8f,1.1f,eye)
        for (i in mesh.positions.indices step 3) {
            val p = mesh.positions.copyOfRange(i,i+3)
            check(kotlin.math.abs(distance(p,eye) - mesh.radius) < .00001f)
            val flat = mesh.unproject(p)!!
            near(flat,floatArrayOf((mesh.texCoords[i/3*2]-.5f)*mesh.width,(.5f-mesh.texCoords[i/3*2+1])*mesh.height,0f))
        }
        near(mesh.project(floatArrayOf(0f,0f,0f)),floatArrayOf(0f,0f,0f))
        check(mesh.indices.all { it in 0 until mesh.positions.size/3 })
        check(mesh.outline.size == 2*(mesh.columns+mesh.rows)/4)
    }
    // Curved contact depth survives capture and obeys the same endpoint constraints.
    tracking.points["LEFT"] = startL.copyOf(); tracking.points["RIGHT"] = startR.copyOf()
    tracking.pinched.addAll(listOf("LEFT","RIGHT")); tracking.nowMs = 5000
    val curved = MonitorMesh(initial.physicalWidthM,initial.physicalHeightM,floatArrayOf(0f,0f,.6f))
    val curvedL = curved.project(l); val curvedR = curved.project(r)
    fun curvedEvent(side: String, hit: FloatArray) = SpatialInputEvent(side, InputEvent.Action.DOWN,Vector3(hit[0]*1000,hit[1]*1000,hit[2]*1000))
    val curvedGrab = TwoHandState()
    curvedGrab.onEvent(curvedEvent("LEFT",curvedL),initial,PixelDensity(),tracking,curved)
    curvedGrab.onEvent(curvedEvent("RIGHT",curvedR),initial,PixelDensity(),tracking,curved)
    check(curvedGrab.engaged && curvedGrab.frozenMesh === curved)
    val curvedGuide = curvedGrab.guide!!
    near(curvedGuide.contacts[0],point(initial,curvedL)); near(curvedGuide.contacts[1],point(initial,curvedR))
    check(curvedGuide.outline.size > 4)
    val shift = floatArrayOf(.02f,.01f,-.04f)
    tracking.points["LEFT"] = add(startL,shift); tracking.points["RIGHT"] = add(startR,shift)
    tracking.nowMs += 20
    val curvedMoved = curvedGrab.advance(tracking,initial)!!
    check(curvedMoved.pose.position[2] != initial.pose.position[2])
    near(curvedGrab.guide!!.contacts[0],point(curvedMoved,curvedL,curvedMoved.physicalWidthM/initial.physicalWidthM))
    curvedGrab.cancel(); check(curvedGrab.frozenMesh == null)
    val panel = PalmPanelState("LEFT",Pose(floatArrayOf(0f,0f,0f)))
    val poke = PalmPoke()
    check(poke.update(panel,floatArrayOf(-0.06f,0.02f,0.04f)) == null)
    check(poke.update(panel,floatArrayOf(-0.06f,0.02f,-0.02f)) == PalmAction.FOLLOW)
    check(poke.update(panel,floatArrayOf(-0.06f,0.02f,-0.01f)) == null)
    check(poke.update(panel,null) == null)
    val scaledPanel = panel.copy(unitsPerMeter = 5f)
    check(poke.update(scaledPanel,floatArrayOf(-0.3f,0.1f,0.2f)) == null)
    check(poke.update(scaledPanel,floatArrayOf(-0.3f,0.1f,-0.1f)) == PalmAction.FOLLOW)
    poke.clear()
    check(poke.update(panel,floatArrayOf(0f,-.074f,.04f)) == null)
    check(poke.update(panel,floatArrayOf(0f,-.074f,-.02f)) == PalmAction.WARP)
    println("Passed: spherical radius/inverse projection, curved grab/guide geometry, spherical palm button, virtual-copy gain, 3D endpoints, depth, close-contact and close-hand rejection, DEFAULT release, physical release without UP, tracking-loss cancellation, rearm, discontinuity rejection, miniature wireframe and endpoint alignment, guide cleanup, view-ray visibility offset, rotation, palm and fist")
}
'''
lib = Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='floatdesk-gestures-') as temp:
    test = Path(temp) / 'GestureChecks.kt'; test.write_text(code)
    stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'GestureChecksKt'],check=True)
