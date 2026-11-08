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
private data class WorkspaceFile(val activeLayout: String="Desk",val layouts: Map<String,List<MonitorPlacement>> = emptyMap())
private data class Vector3(val x: Float, val y: Float, val z: Float)
private class PixelDensity { fun convertPixelsToMeters(x: Float) = x / 1000f }
private class InputEvent { enum class Action { DOWN, MOVE, UP, CANCEL }; enum class Source { HANDS, GAZE_AND_GESTURE } }
private data class SpatialInputEvent(val pointerType: String, val action: InputEvent.Action, val hitPosition: Vector3?, val source: InputEvent.Source = InputEvent.Source.HANDS)
private object Log { fun i(tag: String, message: String) {} }
'''
guide_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/GrabGuideRenderer.kt').read_text()
stub += guide_source[guide_source.index('internal data class GrabGuide'):guide_source.index('/** Native meshes')]
mesh_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/MonitorMesh.kt').read_text().split('\n', 1)[1]
guard_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/TrackingGuard.kt').read_text().split('\n',1)[1]
pointer_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/PointerControl.kt').read_text().split('\n',1)[1]
code = mesh_source + guard_source + stub + pointer_source + section('private const val PALM_WIDTH', '@Composable\nprivate fun PalmControls')
code += section('private fun palmRayPoint(', '/** Direct fingertip contact')
code += section('private class PalmPoke', '/** Find the parent')
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
    val control=PointerControl()
    val gaze=PointerTarget(0,.9f,.9f,floatArrayOf(.3f,-.1f,0f))
    check(control.choose(false,gaze,emptyMap(),false).mode == PointerMode.OFF)
    check(control.choose(true,gaze,emptyMap(),false).target === gaze)
    control.hover(0,"LEFT",l,l,.8f,.45f)
    val one=control.hands(setOf("LEFT","RIGHT"))
    check(control.choose(true,gaze,one,false).mode == PointerMode.HAND)
    check(control.choose(false,gaze,one,false).mode == PointerMode.HAND)
    check(control.choose(true,gaze,one,false).target!!.u != gaze.u)
    control.hover(0,"RIGHT",r,r,.8f,.45f)
    val two=control.hands(setOf("LEFT","RIGHT"))
    check(control.choose(true,gaze,two,false) == PointerChoice(PointerMode.PREVIEW))
    check(control.choose(true,gaze,one,true) == PointerChoice(PointerMode.GRAB))
    check(control.choose(true,gaze,one,false,10).target == null)
    check(control.choose(true,gaze,one,false,200).mode == PointerMode.HAND)
    control.hover(0,"LEFT",null,null,.8f,.45f,10)
    check(control.hands(setOf("LEFT","RIGHT"),50).size == 2)
    check(control.hands(setOf("LEFT","RIGHT"),100).size == 1)
    check(control.hands(setOf("RIGHT"),100).size == 1)
    control.hover(1,"RIGHT",r,r,.8f,.45f)
    control.hover(0,"RIGHT",null,null,.8f,.45f)
    check(control.hands(setOf("RIGHT")).getValue("RIGHT").monitor == 1)
    control.hover(1,"RIGHT",floatArrayOf(Float.NaN,0f,0f),floatArrayOf(Float.NaN,0f,0f),.8f,.45f)
    check(control.hands(setOf("RIGHT")).isEmpty())
    check(control.choose(true,null,emptyMap(),false).target == null)
    control.hover(0,"LEFT",l,l,.8f,.45f,200)
    check(control.hands(emptySet(),220).size == 1)
    check(control.hands(emptySet(),300).isEmpty())
    val still=HandAimPose(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f,1f))
    val idle=PointerControl()
    idle.hands(setOf("LEFT"),0,mapOf("LEFT" to still))
    idle.hover(0,"LEFT",l,l,.8f,.45f,0)
    idle.hover(0,"LEFT",null,null,.8f,.45f,10)
    check(idle.hands(setOf("LEFT"),3_600_000,mapOf("LEFT" to still)).size==1)
    check(idle.hands(setOf("LEFT"),3_600_020,mapOf("LEFT" to still.copy(position=floatArrayOf(.03f,0f,0f)))).isEmpty())
    idle.hands(setOf("LEFT"),0,mapOf("LEFT" to still))
    idle.hover(0,"LEFT",l,l,.8f,.45f,0)
    idle.hover(0,"LEFT",null,null,.8f,.45f,10)
    check(idle.hands(setOf("LEFT"),100,mapOf("LEFT" to still.copy(rotation=floatArrayOf(0f,.1f,0f,.9949874f)))).isEmpty())
    val rayScreen=MonitorPlacement(Pose(floatArrayOf(0f,0f,-1f)))
    val rayWorkspace=WorkspaceFile(layouts=mapOf("Desk" to listOf(rayScreen)))
    val gazeCenter=gazePointerTarget(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,-1f),rayWorkspace,emptyMap())!!
    check(kotlin.math.abs(gazeCenter.u-.5f)<.00001f && kotlin.math.abs(gazeCenter.v-.5f)<.00001f)
    check(gazePointerTarget(floatArrayOf(0f,0f,0f),floatArrayOf(1f,0f,-1f),rayWorkspace,emptyMap()) == null)
    val preview=previewGrabGuide(initial,l,r,tracking,null)!!
    near(preview.pinches[0],startL); near(preview.pinches[1],startR)
    val grab = TwoHandState(); lock(grab); check(grab.engaged)
    for(i in preview.corners.indices) near(preview.corners[i],grab.guide!!.corners[i])
    check(previewGrabGuide(initial,l,l,tracking,null) == null)
    val locked = grab.advance(tracking,initial)!!
    near(locked.pose.position,initial.pose.position)
    near(point(locked,l),point(initial,l)); near(point(locked,r),point(initial,r))
    guides(grab,locked)
    val gain = distance(l,r) / distance(startL,startR)
    val align = quaternionBetween(subtract(startR,startL),subtract(point(initial,r),point(initial,l)))
    val delta = floatArrayOf(0.05f,-0.04f,-0.08f)
    tracking.points["LEFT"] = add(startL,delta); tracking.points["RIGHT"] = add(startR,delta)
    tracking.nowMs = 60
    val moved = grab.advance(tracking,locked)!!
    val expectedDelta = rotateVector(align,delta[0]*gain,delta[1]*gain,delta[2]*gain)
    near(moved.pose.position,add(initial.pose.position,expectedDelta))
    guides(grab,moved)
    check(distance(moved.pose.position,initial.pose.position) > distance(delta,floatArrayOf(0f,0f,0f))*1.9f)
    // Independent depth on either hand maps both corresponding pixels exactly, not just the center.
    val dl = floatArrayOf(-0.03f,0.04f,-0.1f); val dr = floatArrayOf(0.04f,-0.02f,0.08f)
    tracking.points["LEFT"] = add(startL,dl); tracking.points["RIGHT"] = add(startR,dr)
    tracking.nowMs = 120
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
    tracking.nowMs = 0
    lock(grab); check(grab.engaged)
    // Missing raw joints freezes immediately and permanently cancels after 120ms.
    tracking.points.remove("LEFT"); tracking.nowMs = 80
    check(grab.advance(tracking,initial) == null); check(grab.engaged)
    tracking.nowMs = 121; tracking.points["LEFT"] = startL
    check(grab.advance(tracking,initial) == null); check(!grab.engaged); check(!grab.blocksNative); check(grab.guide == null)
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
    check(!close.engaged); check(!close.blocksNative); check(close.guide == null)
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
        val videoUV = mesh.videoTexCoords
        check(videoUV[0] == 0f && videoUV[1] == 1f) // video top-left
        check(videoUV[videoUV.size-2] == 1f && videoUV.last() == 0f) // video bottom-right
        check(mesh.texCoords[1] == 0f && mesh.texCoords.last() == 1f) // PNG orientation unchanged
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
    val planarGrab = TwoHandState()
    planarGrab.onEvent(event("LEFT",InputEvent.Action.DOWN,l),initial,PixelDensity(),tracking,curved,planarInput=true)
    planarGrab.onEvent(event("RIGHT",InputEvent.Action.DOWN,r),initial,PixelDensity(),tracking,curved,planarInput=true)
    check(planarGrab.engaged)
    near(planarGrab.guide!!.contacts[0],point(initial,curvedL))
    near(planarGrab.guide!!.contacts[1],point(initial,curvedR))
    planarGrab.onEvent(event("LEFT",InputEvent.Action.UP),initial,PixelDensity(),tracking,curved,planarInput=true)
    check(!planarGrab.engaged && planarGrab.guide == null)
    planarGrab.onEvent(event("RIGHT",InputEvent.Action.UP),initial,PixelDensity(),tracking,curved,planarInput=true)
    planarGrab.onEvent(event("LEFT",InputEvent.Action.DOWN,l),initial,PixelDensity(),tracking,curved,planarInput=true)
    planarGrab.onEvent(event("RIGHT",InputEvent.Action.DOWN,r),initial,PixelDensity(),tracking,curved,planarInput=true)
    check(planarGrab.engaged)
    planarGrab.cancel()
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
    check(sphericalActionLabel(false) == "Enable spherical")
    check(sphericalActionLabel(true) == "Disable spherical")
    // Ray contacts ignore incorrect SDK-localized coordinates and stay on actual visible bounds.
    near(monitorRayHit(floatArrayOf(0f,0f,1f),floatArrayOf(.1f,0f,-1f),.8f,.45f,null)!!,floatArrayOf(.1f,0f,0f))
    check(monitorRayHit(floatArrayOf(0f,0f,1f),floatArrayOf(1f,0f,-1f),.8f,.45f,null) == null)
    val desired = curved.project(l)
    val rayOrigin = floatArrayOf(.1f,.02f,.5f)
    near(monitorRayHit(rayOrigin,subtract(desired,rayOrigin),curved.width,curved.height,curved)!!,desired)
    val badSdkHit = event("LEFT",InputEvent.Action.DOWN,floatArrayOf(10f,10f,-5f))
    val fromRay = TwoHandState()
    fromRay.onEvent(badSdkHit,initial,PixelDensity(),tracking,curved,selectedHit=curvedL)
    fromRay.onEvent(event("RIGHT",InputEvent.Action.DOWN,r),initial,PixelDensity(),tracking,curved,selectedHit=curvedR)
    check(fromRay.engaged)
    val guard = TrackingGuard()
    val orientation = floatArrayOf(0f,0f,0f,1f)
    check(!guard.update(0,startL,orientation,1f))
    for (t in 20L..280L step 20) check(!guard.update(t,startL,orientation,1f))
    check(guard.update(300,startL,orientation,1f))
    check(!guard.update(320,null,null,1f))
    check(!guard.update(340,startL,orientation,1f))
    for (t in 360L..640L step 20) guard.update(t,startL,orientation,1f)
    check(guard.update(660,startL,orientation,1f))
    check(!guard.update(680,add(startL,floatArrayOf(1f,0f,0f)),orientation,1f))
    // Measure actual central angles between neighboring rays, not just longitude increments.
    for (angularEye in listOf(floatArrayOf(0f,0f,1.2f),floatArrayOf(.4f,-.3f,.8f))) {
        val angularMesh = MonitorMesh(1.8f,1.1f,angularEye)
        fun pointAt(row: Int,col: Int)=angularMesh.positions.copyOfRange((row*(angularMesh.columns+1)+col)*3,(row*(angularMesh.columns+1)+col)*3+3)
        val chordX=2f*angularMesh.radius*kotlin.math.sin(angularMesh.width*angularMesh.radiansPerMeter/angularMesh.columns/2f)
        val chordY=2f*angularMesh.radius*kotlin.math.sin(angularMesh.height*angularMesh.radiansPerMeter/angularMesh.rows/2f)
        for(row in 0..angularMesh.rows) for(col in 0..angularMesh.columns) {
            val p=pointAt(row,col)
            if(col<angularMesh.columns) check(kotlin.math.abs(distance(p,pointAt(row,col+1))-chordX)<.000002f)
            if(row<angularMesh.rows) check(kotlin.math.abs(distance(p,pointAt(row+1,col))-chordY)<.000002f)
        }
    }
    val wide = MonitorMesh(20f,12.5f,floatArrayOf(0f,0f,.2f))
    check(kotlin.math.abs((wide.width*wide.radiansPerMeter)/(wide.height*wide.radiansPerMeter)-1.6f) < .000001f)
    // Actual pixel-sized ray steps remain uniform within finite tessellation/float precision.
    val pixelMesh = MonitorMesh(1.8f,1.125f,floatArrayOf(0f,0f,1.2f))
    val pixelStep = pixelMesh.width/2560f
    val pixelChord = 2f*pixelMesh.radius*kotlin.math.sin(pixelStep*pixelMesh.radiansPerMeter/2f)
    for(y in listOf(-.53f,-.27f,.001f,.263f,.531f)) for(x in listOf(-.87f,-.453f,.002f,.447f,.861f)) {
        val pixel = pixelMesh.project(floatArrayOf(x,y,0f))
        val adjacentX = pixelMesh.project(floatArrayOf(x+pixelStep,y,0f))
        val adjacentY = pixelMesh.project(floatArrayOf(x,y+pixelStep,0f))
        check(kotlin.math.abs(distance(pixel,adjacentX)/pixelChord-1f)<.003f)
        check(kotlin.math.abs(distance(pixel,adjacentY)/pixelChord-1f)<.003f)
    }
    // Native Start may report an unrelated absolute pose; its zero delta must preserve the monitor.
    val nativeStart = Pose(floatArrayOf(20f,-10f,4f),normalize4(floatArrayOf(.1f,.2f,.3f,.9f)))
    val identityMove = nativeRelativePose(nativeStart,nativeStart,initial.pose)
    near(identityMove.position,initial.pose.position)
    near(identityMove.rotation,initial.pose.rotation)
    val nativeTurn = nativeStart.copy(rotation=normalize4(floatArrayOf(.2f,.2f,.3f,.9f)))
    near(nativeRelativePose(nativeStart,nativeTurn,initial.pose).position,initial.pose.position)
    val nativeNext = nativeStart.copy(position=add(nativeStart.position,floatArrayOf(.03f,-.02f,.01f)))
    near(nativeRelativePose(nativeStart,nativeNext,initial.pose).position,add(initial.pose.position,floatArrayOf(.03f,-.02f,.01f)))
    val collisionMatrix = floatArrayOf(2f,0f,0f,0f, 0f,3f,0f,0f, 0f,0f,4f,0f, 10f,-7f,5f,1f)
    val collisionInverse = floatArrayOf(.5f,0f,0f,0f, 0f,1f/3f,0f,0f, 0f,0f,.25f,0f, -5f,7f/3f,-1.25f,1f)
    near(eventMatrixHit(floatArrayOf(10.2f,-6.94f,5f),collisionInverse,.8f,.45f,null)!!,floatArrayOf(.1f,.02f,0f))
    near(eventMatrixHit(floatArrayOf(10.2f,-6.94f,5f),collisionInverse,.8f,.45f,null)!!,floatArrayOf(.1f,.02f,0f))
    near(eventMatrixHit(floatArrayOf(10.2f*1.785f,-6.94f*1.785f,5f*1.785f),collisionInverse,.8f,.45f,null,1.785f)!!,floatArrayOf(.1f,.02f,0f))
    // Recorded headset sample: missing root-scale conversion left the hit far off the plane.
    val recorded = floatArrayOf(.9988924f,.045443997f,-.012190729f,0f, -.046424866f,.99406743f,-.09835988f,0f, .0076485127f,.09881693f,.9950761f,0f, -.044733673f,.05198337f,-.10078907f,1f)
    val recordedHit = eventMatrixHit(floatArrayOf(-.13956094f,-.11049867f,.16816789f),recorded,.8f,.45f,null,1.7850144f)!!
    check(kotlin.math.abs(recordedHit[2]) < .0002f)
    // The actual render center must be the binocular viewer, in the same desk units as monitor size.
    val viewerRotation=normalize4(floatArrayOf(.2f,-.3f,.1f,.9f))
    val viewerPosition=floatArrayOf(.4f,-.2f,-1.3f)
    val expectedEye=floatArrayOf(.1f,.15f,1.4f)
    val eyeInDesk=add(viewerPosition,rotateVector(viewerRotation,expectedEye[0],expectedEye[1],expectedEye[2]))
    near(viewerInMonitor(eyeInDesk,viewerPosition,viewerRotation),expectedEye)
    val verifiedSphere=MonitorMesh(.8f,.5f,viewerInMonitor(eyeInDesk,viewerPosition,viewerRotation))
    val centerR=distance(eyeInDesk,viewerPosition)
    for(i in verifiedSphere.positions.indices step 3) {
        val local=verifiedSphere.positions.copyOfRange(i,i+3)
        val displayed=add(viewerPosition,rotateVector(viewerRotation,local[0],local[1],local[2]))
        check(kotlin.math.abs(distance(displayed,eyeInDesk)-centerR)<.00001f)
    }
    val centeredSphere=MonitorMesh(.8f,.5f,floatArrayOf(0f,0f,1.4f))
    check(distance(centeredSphere.project(floatArrayOf(.4f,0f,0f)),floatArrayOf(.4f,0f,0f)) > .01f)
    check(distance(centeredSphere.project(floatArrayOf(0f,.25f,0f)),floatArrayOf(0f,.25f,0f)) > .01f)
    val panel = PalmPanelState("LEFT",Pose(floatArrayOf(0f,0f,0f)))
    val panelRay=palmRayPoint(panel,floatArrayOf(-.06f,.06f,1f),floatArrayOf(0f,0f,-1f))!!
    check(kotlin.math.abs(panelRay[0]-.25f)<.00001f && kotlin.math.abs(panelRay[1]-.25f)<.00001f)
    check(palmRayPoint(panel,floatArrayOf(0f,0f,-1f),floatArrayOf(0f,0f,1f))==null)
    val scaledRay=palmRayPoint(panel.copy(unitsPerMeter=5f),floatArrayOf(-.3f,.3f,1f),floatArrayOf(0f,0f,-1f))!!
    check(kotlin.math.abs(scaledRay[0]-.25f)<.00001f)
    val gate=PalmVisibilityGate()
    check(gate.update(0,panel,false)==null)
    check(gate.update(120,panel,false)==null)
    check(gate.update(240,panel,false)!=null)
    check(gate.update(260,panel.copy(pose=Pose(floatArrayOf(.2f,0f,0f))),true)==null)
    check(gate.update(280,null,false)==null)
    check(!panelOpenPalm(floatArrayOf(0f,0f,0f),List(4){floatArrayOf(0f,.14f,0f)},.1f))
    check(panelOpenPalm(floatArrayOf(0f,0f,0f),List(3){floatArrayOf(0f,.17f,0f)}+listOf(floatArrayOf(0f,.13f,0f)),.1f))
    val poke = PalmPoke()
    check(poke.update(panel,floatArrayOf(-.06f,.06f,.04f)) == null)
    check(poke.update(panel,floatArrayOf(-.06f,.06f,-.02f)) == PalmAction.FOLLOW)
    check(poke.update(panel,floatArrayOf(-.06f,.06f,-.01f)) == null)
    check(poke.update(panel,null) == null)
    val scaledPanel=panel.copy(unitsPerMeter=5f)
    check(poke.update(scaledPanel,floatArrayOf(-.3f,.3f,.2f)) == null)
    check(poke.update(scaledPanel,floatArrayOf(-.3f,.3f,-.1f)) == PalmAction.FOLLOW)
    for((x,y,expected) in listOf(Triple(-.06f,-.04f,PalmAction.WARP),Triple(.06f,-.04f,PalmAction.EYES),Triple(0f,-.09f,PalmAction.GESTURES))) {
        poke.clear()
        check(poke.update(panel,floatArrayOf(x,y,.04f)) == null)
        check(poke.update(panel,floatArrayOf(x,y,-.02f)) == expected)
    }
    println("Passed: mouse priority/off/tracking loss, gaze hit bounds, observational grab preview, mouse palm toggle, equal actual neighboring-ray angles and angular aspect, video/PNG orientation separation, planar-input curved grabbing and regrab, action labels, spherical radius/inverse projection, curved grab/guide geometry, spherical palm button, virtual-copy gain, 3D endpoints, depth, close-contact and close-hand rejection, DEFAULT release, physical release without UP, tracking-loss cancellation, rearm, discontinuity rejection, miniature wireframe and endpoint alignment, guide cleanup, view-ray visibility offset, rotation, palm and fist")
}
'''
lib = Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='floatdesk-gestures-') as temp:
    test = Path(temp) / 'GestureChecks.kt'; test.write_text(code)
    stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'GestureChecksKt'],check=True)
