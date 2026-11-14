#!/usr/bin/env python3
"""Focus pinch arbitration, window stacking, spacing and view-fit geometry without XR dependencies."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def body(path):return '\n'.join(l for l in (root/path).read_text().splitlines() if not l.startswith(('import ','package ')))
workspace=body('workspace/Workspace.kt');workspace=workspace[workspace.index('internal data class Pose'):workspace.index('internal class WorkspaceStore')]
metadata=body('net/WorkspaceInfo.kt');metadata=metadata[:metadata.index('    companion object')]+'}\n'
code='import kotlin.math.*\n'+body('MonitorMesh.kt')+body('PointerControl.kt')+body('PinchPolicy.kt')+body('MouseGestures.kt')+body('FocusInteraction.kt')+workspace+body('workspace/WorkspacePlacement.kt')+metadata+r'''
fun main() {
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
 // Contact capture survives an incidental chord and does not become a middle action.
 check(frame(.01f,.05f)==null);check(frame(.01f,.01f)==null);check(frame(.05f,.01f)==window)
 frame(.05f,.05f)
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
