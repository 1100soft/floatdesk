#!/usr/bin/env python3
"""Focus pinch arbitration, window stacking, spacing and view-fit geometry without XR dependencies."""
from pathlib import Path
import subprocess,sys,tempfile,re
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def body(path):return '\n'.join(l for l in (root/path).read_text().splitlines() if not l.startswith(('import ','package ')))
workspace=body('workspace/Workspace.kt');workspace=workspace[workspace.index('internal data class Pose'):workspace.index('internal class WorkspaceStore')]
metadata=body('net/WorkspaceInfo.kt');metadata=metadata[:metadata.index('    companion object')]+'}\n'
code='import kotlin.math.*\n'+body('MonitorMesh.kt')+body('PointerControl.kt')+body('PinchPolicy.kt')+body('MouseGestures.kt')+body('FocusInteraction.kt')+workspace+re.search(r'internal fun deskLayout\(.*?\n}',(root/'workspace/Workspace.kt').read_text(),re.S).group(0)+body('workspace/WorkspacePlacement.kt')+metadata+body('WindowMagnification.kt')+body('VideoGeometry.kt')+''.join(re.search(r'internal fun '+name+r'\(.*?\n}',(root/'MainActivity.kt').read_text(),re.S).group(0) for name in ('transformPoint','rotateVector'))+r'''
fun main() {
 val tap=NativeSurfaceTap()
 check(tap.update("LEFT","DOWN",.5f to .4f)==null)
 check(tap.update("LEFT","UP",null)==(.5f to .4f))
 check(tap.update("LEFT","UP",null)==null)
 tap.update("LEFT","DOWN",.5f to .4f);tap.update("LEFT","HOVER_EXIT",null)
 check(tap.update("LEFT","UP",null)==null)
 tap.update("RIGHT","DOWN",.2f to .2f);tap.update("RIGHT","HOVER_MOVE",.6f to .5f)
 check(tap.update("RIGHT","UP",null)==null)
 tap.update("LEFT","DOWN",.5f to .4f);tap.clear();check(tap.update("LEFT","UP",null)==null)

 val zoom=magnifiedWindow(42,1600,900,Pose(),.6f,.5f,1f,true)
 val sphere=zoom.mesh!!
 for(i in sphere.positions.indices step 3){val radius=sqrt((0..2).sumOf{val d=sphere.positions[i+it]-sphere.eye[it];(d*d).toDouble()}).toFloat();check(abs(radius-sphere.radius)<.00001f)}
 for(uv in listOf(.5f to .5f,.05f to .05f,.95f to .95f)){
   val contact=sphere.project(floatArrayOf((uv.first-.5f)*zoom.width,(.5f-uv.second)*zoom.height,0f))
   val point=transformPoint(zoom.pose,contact)
   val result=magnifierPoint(zoom,floatArrayOf(0f,0f,0f),point)!!
   check(abs(result.first-uv.first)<.002f && abs(result.second-uv.second)<.002f)
 }

 // Magnifier uses precisely the desktop's central fit at its own distance.
 val base=deskLayout(listOf(Triple("Window",1600,900)))
 val desktopFit=fitDesktopInView(base,Pose(),.6f,.5f,1f,true,centralView=true).single()
 check(abs(zoom.width/desktopFit.physicalWidthM-MagnifierDistanceM/ViewLayers.DesktopDistanceM)<.00001f)
 val limits=focusViewHalfAngles(.6f,.5f)
 for(vertex in sphere.outline){val world=transformPoint(zoom.pose,vertex)
   check(abs(atan2(world[0],-world[2]))<=limits.first+.00001f)
   check(abs(atan2(world[1],-world[2]))<=limits.second+.00001f)
 }
 // Eye translation, head rotation, root scale and native depth changes cannot change radial distance.
 for(units in listOf(.56f,1f,2f)){
   val head=Pose(floatArrayOf(.15f,-.04f,.12f),floatArrayOf(0f,sin(.3f),0f,cos(.3f)))
   val moved=magnifierGeometry(zoom,head,units,floatArrayOf(.4f,.1f,-3f))
   val m=moved.mesh!!
   for(j in m.positions.indices step 3){val world=transformPoint(moved.pose,m.positions.copyOfRange(j,j+3))
     val r=sqrt((0..2).sumOf{val delta=world[it]-head.position[it];(delta*delta).toDouble()}).toFloat()
     check(abs(r-MagnifierDistanceM*units)<.00001f)
   }
   val face=rotateVector(moved.pose.rotation,0f,0f,1f)
   val distance=MagnifierDistanceM*units
   check((0..2).sumOf{(face[it]*(head.position[it]-moved.pose.position[it])/distance).toDouble()}>.9999)
   check(abs(moved.width/zoom.width-units)<.00001f)
 }
 // Head-relative pose and head-driven curvature reuse geometry through ordinary head motion.
 val anchored=magnifierGeometry(zoom,Pose(),1f,floatArrayOf(.3f,.1f,-.85f))
 val headMoved=Pose(floatArrayOf(.4f,.2f,.3f),floatArrayOf(0f,sin(.4f),0f,cos(.4f)))
 val followed=magnifierGeometry(anchored,headMoved,1f)
 check(followed.mesh===anchored.mesh)
 check(magnifierGeometry(anchored,headMoved,1.000001f).mesh===anchored.mesh)
 val offset=viewerInMonitor(followed.pose.position,headMoved.position,headMoved.rotation)
 for(axis in 0..2)check(abs(offset[axis]-anchored.headOffset[axis]*MagnifierDistanceM)<.00001f)
 val enlarged=magnifierGeometry(resizedMagnifier(zoom,1.5f),Pose(),1f)
 check(abs(enlarged.width/zoom.width-1.5f)<.00001f && enlarged.sizeScale==1.5f)
 check(magnifiedWindow(42,1600,900,Pose(),.6f,.5f,1f,true).sizeScale==1f)
 for(point in magnifierPinOutline(zoom))check(zoom.mesh!!.unproject(point)==null)
 // The exterior pin bezel must preserve every content vertex and triangle exactly.
 val framed=framedMagnifierGeometry(zoom)
 check(framed.positions.copyOfRange(0,sphere.positions.size).contentEquals(sphere.positions))
 check(framed.indices.copyOfRange(0,sphere.indices.size).contentEquals(sphere.indices))
 check(framed.positions.size>sphere.positions.size)
 val deskAnchor=MonitorPlacement("desk","desktop",Pose(floatArrayOf(.2f,.1f,-1.2f)),1.2f,.7f,0f,1600,900,60)
 val record=saveFixedMagnifier("pane",zoom,deskAnchor,1f)
 val restored=fixedMagnifier(record,deskAnchor,DesktopWindow(42,0,0,1600,900),Pose(),1f,true)
 for(axis in 0..2)check(abs(restored.pose.position[axis]-zoom.pose.position[axis])<.00001f)
 check(abs(restored.width-zoom.width)<.00001f)
 val shifted=fixedMagnifier(record,deskAnchor.copy(pose=Pose(floatArrayOf(.7f,.1f,-1.2f))),DesktopWindow(42,0,0,1600,900),Pose(),1f,true)
 check(shifted.pose.position[0]>restored.pose.position[0])
 val radius=sqrt(shifted.pose.position.sumOf{(it*it).toDouble()}).toFloat()
 check(abs(radius-MagnifierDistanceM)<.00001f)
 val radial=fixedMagnifierGeometry(shifted,Pose(),FloatArray(3){shifted.pose.position[it]*4f})
 for(axis in 0..2)check(abs(radial.pose.position[axis]-shifted.pose.position[axis])<.00001f)
 val normal=rotateVector(shifted.pose.rotation,0f,0f,1f)
 val toEye=FloatArray(3){-shifted.pose.position[it]/radius}
 check((0..2).sumOf{(normal[it]*toEye[it]).toDouble()}>.99999)
 check(fixedMagnifierGeometry(shifted,Pose()).mesh===shifted.mesh)
 // Native gaze/hand rays drag from any contact without a generated movement handle.
 val drag=MagnifierRayDrag()
 val origin=floatArrayOf(0f,0f,0f);val rayContact=floatArrayOf(0f,0f,-MagnifierDistanceM)
 drag.begin(zoom,Pose(),origin,floatArrayOf(0f,0f,-1f),rayContact)
 check(drag.move(Pose(),origin,floatArrayOf(0f,0f,-1f))==null)
 val movedByRay=drag.move(Pose(),origin,floatArrayOf(.3f,.2f,-1f))!!
 check(movedByRay[0]>0f && movedByRay[1]>0f)
 check(abs(sqrt(movedByRay.sumOf{(it*it).toDouble()}).toFloat()-MagnifierDistanceM)<.00001f)
 // Offset-reference regression: an unchanged MOVE must be identity even if the hit is above the ray.
 val offsetOrigin=floatArrayOf(.1f,-.2f,.15f)
 drag.begin(zoom,Pose(),offsetOrigin,floatArrayOf(0f,0f,-1f),floatArrayOf(0f,.25f,-.85f))
 repeat(5){check(drag.move(Pose(),offsetOrigin,floatArrayOf(0f,0f,-1f))==null)}
 check(drag.move(Pose(),offsetOrigin,floatArrayOf(.2f,0f,-1f))!=null)
 val level=facingMagnifierRotation(movedByRay,Pose(),floatArrayOf(0f,0f,0f,1f))
 check(abs(rotateVector(level,1f,0f,0f)[1])<.00001f)
 drag.clear();check(drag.move(Pose(),origin,floatArrayOf(.5f,0f,-1f))==null)
 drag.begin(zoom,Pose(),origin,floatArrayOf(0f,0f,-1f),rayContact)
 check(drag.move(Pose(),origin,floatArrayOf(0f,0f,-1f))==null)
 val held=WindowMagnification()
 held.update(1,true,false,42,false,false,retain=true)
 check(held.update(601,true,false,42,false,false,retain=true)==42L)
 check(held.update(1000,true,false,null,false,true,retain=true)==42L)
 check(held.update(5000,true,false,null,false,true,retain=true)==42L)
 val whole=WorkspaceInfo(listOf(DesktopOutput("screen",1000,1000,0,0,false,true,3,emptyList())),0,emptyList(),0,8,8000000,60,"",false,windowFocus=true,windows=listOf(DesktopWindow(42,0,0,1000,1000)))
 val surface=MonitorPlacement("desk","desktop",Pose(floatArrayOf(0f,0f,-1.2f)),1.2f,1.2f,0f,1000,1000,60)
 for(curved in listOf(false,true)){
   val m=if(curved)MonitorMesh(1.2f,1.2f,floatArrayOf(0f,0f,1.2f),focus=floatArrayOf(0f,0f)) else null
   check(inPlaceWindowRect(whole,42,surface,m,Pose(),.65f,.65f,1f,0f)!=null)
   check(inPlaceWindowRect(whole,42,surface.copy(physicalWidthM=.3f,physicalHeightM=.3f),null,Pose(),.65f,.65f,1f,0f)==null)
   check(inPlaceWindowRect(whole,42,surface.copy(pose=Pose(floatArrayOf(1f,0f,-1.2f))),m,Pose(),.65f,.65f,1f,0f)==null)
   val occluded=whole.copy(windows=listOf(DesktopWindow(43,100,100,100,100))+whole.windows)
   check(inPlaceWindowRect(occluded,42,surface,m,Pose(),.65f,.65f,1f,0f)==null)
 }
 for(curved in listOf(false,true)){
   val w=magnifiedWindow(42,1600,900,Pose(),.6f,.5f,1f,curved)
   val edge=floatArrayOf(w.width*.5f,0f,0f)
   val ray=transformPoint(w.pose,w.mesh?.project(edge) ?: edge)
   fun outside(degrees:Double):FloatArray{val half=Math.toRadians(-degrees).toFloat()/2;return rotateVector(floatArrayOf(0f,sin(half),0f,cos(half)),ray[0],ray[1],ray[2])}
   check(magnifierContainsGaze(w,floatArrayOf(0f,0f,0f),outside(1.0)))
   check(!magnifierContainsGaze(w,floatArrayOf(0f,0f,0f),outside(3.5)))
 }

 val magnify=WindowMagnification()
 check(magnify.update(1,true,false,42,false,false)==null)
 check(magnify.update(601,true,false,42,false,false)==42L)
 check(magnify.update(1000,true,false,43,false,false)==42L)
 check(magnify.update(1200,true,false,43,false,true)==42L)
 check(magnify.update(2099,true,false,43,false,true)==42L)
 // A blink neither dismisses the image nor completes a pending dwell.
 check(magnify.update(2500,true,false,null,false,true,validGaze=false)==42L)
 check(magnify.update(3000,true,false,43,false,true)==42L)
 check(magnify.update(3600,true,false,43,false,true)==43L) // Direct switch, no intermediate close.
 check(magnify.update(3700,true,false,42,true,true)==43L)
 check(magnify.update(5500,true,false,42,false,true,hold=true)==43L && !magnify.pinned) // A grab holds presentation, never pins it.
 magnify.togglePin();check(magnify.pinned)
 check(magnify.update(6000,true,false,42,false,true)==43L)
 check(magnify.update(9000,true,false,42,false,true)==43L)
 magnify.togglePin();check(!magnify.pinned)
 check(magnify.update(9100,true,false,null,false,true)==43L)
 check(magnify.update(9600,true,false,null,false,true,validGaze=false)==43L)
 check(magnify.update(10000,true,false,null,false,true)==43L)
 check(magnify.update(11000,true,false,null,false,true)==null) // One second of active distant gaze.
 check(magnify.update(11001,true,false,42,false,false)==null)
 check(magnify.update(11601,true,false,42,false,false)==42L) // Dwell alone, no cooldown.
 check(magnify.update(11602,true,true,42,true,true)==null)

 val routing=PointerControl();routing.hover(0,"GAZE",floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f),2f,2f,1)
 val eye=routing.systemGaze()!!
 check(routing.systemGazeEnabled)
 val recovery=PointerControl()
 recovery.hover(0,"GAZE",floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f),2f,2f,1)
 recovery.nativeManipulation(0,true)
 recovery.choose(true,null,emptyMap(),true,100)
 recovery.clear() // Scene recovery clears coordinates/gesture locks, not the observed OS aiming mode.
 check(recovery.systemGaze()==null && recovery.systemGazeEnabled && !recovery.nativeManipulating)
 val fine=PointerTarget(0,.3f,.4f,floatArrayOf())
 check(recovery.choose(recovery.systemGazeEnabled,fine,emptyMap(),false,101).target===fine)
 check(focusAimContacts(emptyMap(),if(recovery.systemGazeEnabled)fine else null)["GAZE"]===fine)
 recovery.clear(resetAiming=true) // A real lifecycle pause can change the OS input configuration.
 check(!recovery.systemGazeEnabled)

 check(routing.hands(emptySet(),2).isEmpty())
 check(routing.choose(false,eye,emptyMap(),false,2).mode==PointerMode.OFF) // System gaze cannot enable mouse mode.
 check(focusAimContacts(emptyMap(),eye).keys==setOf("GAZE"))
 val manual=mapOf("LEFT" to eye.copy(u=.2f))
 check(focusAimContacts(manual,eye)===manual)
 routing.hover(0,"LEFT",floatArrayOf(0f,0f,0f),floatArrayOf(-.3f,0f,0f),2f,2f,1)
 val retained=routing.hands(setOf("LEFT"),2)
 routing.hover(0,"GAZE",floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f),2f,2f,3)
 check(routing.focusHands(retained).isEmpty()) // A retained mouse beam must not suppress native gaze focus.
 check(routing.choose(false,null,retained,false,3).mode==PointerMode.HAND) // Mouse retains its original behavior.
 routing.hover(0,"LEFT",floatArrayOf(0f,0f,0f),floatArrayOf(-.3f,0f,0f),2f,2f,4)
 check(routing.focusHands(retained).keys==setOf("LEFT"))
 routing.hover(0,"GAZE",floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f),2f,2f,4)
 check(routing.focusHands(retained).isEmpty()) // Distinct events can share a millisecond.
 routing.hover(0,"LEFT",floatArrayOf(0f,0f,0f),floatArrayOf(-.3f,0f,0f),2f,2f,4)
 check(routing.focusHands(retained).keys==setOf("LEFT"))

 routing.hover(0,"GAZE",null,null,2f,2f,5);check(routing.systemGaze()==null)

 val auto=GazeWindowFocus()
 check(auto.update(0,true,false,42)==null)
 for(t in 50L..200L step 50)check(auto.update(t,true,false,42)==null)
 check(auto.update(250,true,false,42)==42L)
 for(t in 300L..500L step 50)check(auto.update(t,true,false,42)==null)
 check(auto.update(550,true,false,43)==null);check(auto.update(600,true,false,42)==null)
 for(t in 650L..800L step 50)check(auto.update(t,true,false,42)==null)
 check(auto.update(850,true,false,42)==null) // A brief glance elsewhere did not change focus.
 check(auto.update(900,true,true,43)==null)
 check(auto.update(950,true,false,43)==null)
 for(t in 1000L..1150L step 50)check(auto.update(t,true,false,43)==null)
 check(auto.update(1200,true,false,43)==43L)
 check(auto.update(1250,false,false,44)==null)
 check(auto.update(1300,true,false,44)==null)
 check(auto.update(2000,true,false,44)==null) // Tracking/pause gap starts a fresh dwell.
 for(t in 2050L..2200L step 50)check(auto.update(t,true,false,44)==null)
 check(auto.update(2250,true,false,44)==44L)
 check(auto.update(2300,true,false,null)==null)
 val screen=FocusTarget(FocusRect(0f,0f,.5f,1f),"screen")
 val window=FocusTarget(FocusRect(.1f,.2f,.3f,.5f),"screen",42)
 val hover=mapOf("LEFT" to FocusHover(screen,window));val input=FocusGestures()
 val gazeInput=FocusGestures();val gazeHover=mapOf("GAZE" to FocusHover(screen,window))
 gazeInput.update(true,false,mapOf("RIGHT" to FingerPinch(.05f,.05f)),gazeHover)
 gazeInput.update(true,false,mapOf("RIGHT" to FingerPinch(.001f,.05f)),gazeHover)
 check(gazeInput.update(true,false,mapOf("RIGHT" to FingerPinch(.05f,.05f)),gazeHover)==window)

 fun frame(index:Float,middle:Float,enabled:Boolean=true,blocked:Boolean=false)=input.update(enabled,blocked,mapOf("LEFT" to FingerPinch(index,middle)),hover)
 check(frame(.05f,.05f)==null)
 check(frame(.01f,.05f)==null);check(frame(.01f,.05f)==null);check(frame(.05f,.05f)==window)
 check(frame(.05f,.01f)==null);check(frame(.05f,.05f)==screen)
 // The shared aiming context reserves two rays for grabbing in either interaction mode.
 val pointer=PointerControl();val hit=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
 val preview=pointer.choose(false,null,mapOf("LEFT" to hit,"RIGHT" to hit),false,0)
 check(preview.mode.reservesDesktop)
 check(frame(.01f,.05f,blocked=preview.mode.reservesDesktop)==null)
 check(frame(.05f,.05f,blocked=preview.mode.reservesDesktop)==null)
 val single=pointer.choose(false,null,mapOf("LEFT" to hit),false,1)
 check(!single.mode.reservesDesktop)
 check(frame(.01f,.05f,blocked=single.mode.reservesDesktop)==null)
 check(frame(.05f,.05f,blocked=single.mode.reservesDesktop)==window)
 check(pointer.choose(false,null,emptyMap(),true,2).mode.reservesDesktop)
 // A resting index contact does not veto a new middle pinch.
 check(frame(.01f,.05f,false)==null);check(frame(.01f,.01f)==null);check(frame(.01f,.05f)==screen)
 // Opens while blocked rearm normally; no extra wide opening after the block ends.
 check(frame(.05f,.05f,blocked=true)==null);check(frame(.01f,.05f)==null);check(frame(.05f,.05f)==window)
 // A second finger closing during the owned contact upgrades to desktop Reset.
 check(frame(.01f,.05f)==null);check(frame(.01f,.01f)==null);check(frame(.05f,.01f)?.resetDesktop==true)
 check(frame(.01f,.01f)==null);check(frame(.01f,.05f)==null) // Swapping held fingers cannot emit a trailing selection.
 frame(.05f,.05f)
 // Simultaneous contact and middle-first overlap both reset without a hold timer.
 check(frame(.01f,.01f)==null);check(frame(.05f,.05f)?.resetDesktop==true)
 check(frame(.05f,.01f)==null);check(frame(.01f,.01f)==null);check(frame(.01f,.05f)?.resetDesktop==true)
 check(frame(.05f,.05f)==null)
 check(frame(.01f,.01f,blocked=true)==null);check(frame(.05f,.05f)==null)
 val fallback=FocusGestures();fallback.update(true,false,mapOf("RIGHT" to FingerPinch(.05f,.05f)),mapOf("RIGHT" to FocusHover(screen)))
 check(fallback.update(true,false,mapOf("RIGHT" to FingerPinch(.01f,.05f)),mapOf("RIGHT" to FocusHover(screen)))==null)
 check(fallback.update(true,false,mapOf("RIGHT" to FingerPinch(.05f,.05f)),emptyMap())==screen)
 val cross=FocusGestures();cross.update(true,false,mapOf("RIGHT" to FingerPinch(.05f,.05f)),hover)
 check(cross.update(true,false,mapOf("RIGHT" to FingerPinch(.01f,.05f)),hover)==null)
 check(cross.update(true,false,mapOf("RIGHT" to FingerPinch(.05f,.05f)),hover)==window)
 val contactWindow=window.copy(contactU=.17f,contactV=.29f)
 val contact=FocusGestures()
 contact.update(true,false,mapOf("LEFT" to FingerPinch(.05f,.05f)),mapOf("LEFT" to FocusHover(screen,contactWindow)))
 contact.update(true,false,mapOf("LEFT" to FingerPinch(.01f,.05f)),mapOf("LEFT" to FocusHover(screen,contactWindow)))
 check(contact.update(true,false,mapOf("LEFT" to FingerPinch(.05f,.05f)),hover)==contactWindow)
 val profile=PinchThresholds(4f,5f,6f,7f)
 check(profile.valid() && !profile.copy(leftIndexMm=Float.NaN).valid())
 val configured=FocusGestures {profile}
 configured.update(true,false,mapOf("LEFT" to FingerPinch(.05f,.05f)),hover)
 check(configured.update(true,false,mapOf("LEFT" to FingerPinch(.009f,.05f)),hover)==null)
 check(configured.update(true,false,mapOf("LEFT" to FingerPinch(.05f,.05f)),hover)==null) // Default-only contact must not select.
 configured.update(true,false,mapOf("LEFT" to FingerPinch(.003f,.05f)),hover)
 check(configured.update(true,false,mapOf("LEFT" to FingerPinch(.005f,.05f)),hover)==window)
 val mouse=MouseGestures(thresholds={profile});val target=PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f))
 mouse.update(0,true,false,mapOf("LEFT" to FingerPinch(.05f,.05f)),target)
 check(mouse.update(1,true,false,mapOf("LEFT" to FingerPinch(.009f,.05f)),target).click==null)
 check(mouse.update(2,true,false,mapOf("LEFT" to FingerPinch(.05f,.05f)),target).click==null)
 mouse.update(3,true,false,mapOf("LEFT" to FingerPinch(.003f,.05f)),target)
 check(mouse.update(4,true,false,mapOf("LEFT" to FingerPinch(.005f,.05f)),target).click?.button==1)
 val info=WorkspaceInfo(listOf(DesktopOutput("screen",1920,1080,0,0,false,true,3,emptyList()),DesktopOutput("virtual",1920,1080,1920,0,true,false,1,emptyList())),0,emptyList(),0,8,8000000,60,"",false,windowFocus=true,
  windows=listOf(DesktopWindow(42,100,100,400,400),DesktopWindow(43,0,0,1000,1000)))
 check(info.hover(.05f,.2f)!!.window!!.windowId==42L) // Topmost window wins.
 check(info.hover(.8f,.3f)!!.screen.screen=="virtual")
 check(info.copy(windowFocus=false).hover(.05f,.2f)!!.window==null)
 val visible=info.visibleRects(FocusRect(.4f,.1f,.6f,.9f),.1f)
 check(visible.size==2 && visible[0].right<visible[1].left)
 val viewer=Pose(floatArrayOf(2f,1f,3f),floatArrayOf(0f,.70710677f,0f,.70710677f))
 val original=MonitorPlacement("desk","desktop",Pose(),4f,2.5f,0f,3840,2400,60)
 for(spherical in listOf(false,true))for(rect in listOf(floatArrayOf(0f,0f,.5f,1f),floatArrayOf(.8f,.7f,.95f,.9f),floatArrayOf(0f,0f,1f,1f))) {
  val fitted=focusDesktopRegion(original,rect,viewer,.65f,.5f,1.785f,spherical)
  val center=floatArrayOf((fitted.viewCenterU-.5f)*fitted.physicalWidthM,(.5f-fitted.viewCenterV)*fitted.physicalHeightM,0f)
  val eye=viewerInMonitor(viewer.position,fitted.pose.position,fitted.pose.rotation)
  val mesh=if(spherical)MonitorMesh(fitted.physicalWidthM,fitted.physicalHeightM,eye,focus=center.copyOf(2),center=center) else null
  val p=mesh?.project(center) ?: center
  val goal=viewerInMonitor(viewer.position,fitted.pose.position,fitted.pose.rotation)
  check(abs(p[0]-goal[0])<.0001f && abs(p[1]-goal[1])<.0001f && abs(goal[2]-p[2]-1.2f*1.785f)<.0001f)
  if(mesh!=null) {
   val selected=sphericalViewFocus(mesh,floatArrayOf(0f,0f,-1f))!!
   check(abs(selected[0]-center[0])<.001f && abs(selected[1]-center[1])<.001f)
  }
  for(u in listOf(rect[0],rect[2]))for(v in listOf(rect[1],rect[3])) {
   val flat=floatArrayOf((u-.5f)*fitted.physicalWidthM,(.5f-v)*fitted.physicalHeightM,0f)
   val p=mesh?.project(flat) ?: flat;val depth=eye[2]-p[2]
   check(depth>0 && abs(atan2(p[0]-eye[0],depth))<=Math.toRadians(18.0).toFloat()+.003f && abs(atan2(p[1]-eye[1],depth))<=Math.toRadians(12.0).toFloat()+.003f)
  }
  check(fitted.pixelWidth==original.pixelWidth && fitted.pixelHeight==original.pixelHeight)
 }
 println("PASS shared pinch edges, window/screen selection, disabled/blocked behavior, topmost window, fallback, spacing and selected-region fitting in flat/spherical modes")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-focus-') as temp:
 p=Path(temp)/'FocusCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'FocusCheckKt'],check=True)
