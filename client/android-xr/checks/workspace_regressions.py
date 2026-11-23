#!/usr/bin/env python3
"""Verify opaque geometry holes and viewer-relative restart persistence math."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'MonitorMesh.kt').read_text().split('\n',1)[1]+'\n'+(root/'VideoGeometry.kt').read_text().split('\n',1)[1]
w=(root/'workspace/Workspace.kt').read_text()
source+='\n'+(root/'PinchPolicy.kt').read_text().split('\n',1)[1]
source+='\n'+w[w.index('internal data class Pose'):w.index('internal class WorkspaceStore')]
source+='\n'+'\n'.join(line for line in (root/'workspace/WorkspacePlacement.kt').read_text().splitlines() if not line.startswith(('package ','import ')))
code=source+'''
fun main() {
 check(ViewLayers.DiagnosticsDistanceM<ViewLayers.ControlsDistanceM && ViewLayers.ControlsDistanceM<ViewLayers.DesktopDistanceM)
 val viewer0=Pose()
 val depthBase=MonitorPlacement("depth","existing-0",Pose(floatArrayOf(.3f,.2f,-2.4f)),2f,1f,0f,5120,2560,60)
 val locked=fixedDesktopDepth(depthBase,viewer0,1f)
 fun radius(p:FloatArray)=kotlin.math.sqrt(p.sumOf{(it*it).toDouble()}).toFloat()
 check(kotlin.math.abs(radius(locked.pose.position)-ViewLayers.DesktopDistanceM)<.00001f)
 check(kotlin.math.abs(locked.pose.position[0]/-locked.pose.position[2]-.3f/2.4f)<.00001f)
 check(kotlin.math.abs(locked.physicalWidthM/-locked.pose.position[2]-2f/2.4f)<.00001f)
 check(fixedDesktopDepth(locked,viewer0,1f)===locked)
 val farther=depthBase.copy(pose=Pose(floatArrayOf(.3f,.2f,-4.8f)))
 check(fixedDesktopDepth(farther,viewer0,1f).physicalWidthM<locked.physicalWidthM)
 val tilted=depthBase.copy(pose=Pose(floatArrayOf(0f,0f,-1.2f),floatArrayOf(0f,.5f,0f,.8660254f)),physicalWidthM=100f,physicalHeightM=60f)
 val flatDepth=fixedDesktopDepth(tilted,viewer0,1f,false)
 val n=floatArrayOf(.8660254f,0f,.5f)
 check(kotlin.math.abs((0..2).sumOf{(flatDepth.pose.position[it]*n[it]).toDouble()}.toFloat())>=ViewLayers.DesktopDistanceM-.0001f)
 check(kotlin.math.abs(flatDepth.physicalWidthM/-flatDepth.pose.position[2]-tilted.physicalWidthM/1.2f)<.001f)
 val focused=depthBase.copy(viewCenterU=.9f,viewCenterV=.2f)
 val f=fixedDesktopDepth(focused,viewer0,1f)
 val focus=floatArrayOf(f.pose.position[0]+(.9f-.5f)*f.physicalWidthM,f.pose.position[1]+(.5f-.2f)*f.physicalHeightM,f.pose.position[2])
 check(kotlin.math.abs(radius(focus)-ViewLayers.DesktopDistanceM)<.00001f)
 val rotated=depthBase.copy(pose=Pose(floatArrayOf(.3f,.2f,-2.4f),floatArrayOf(0f,.70710677f,0f,.70710677f)),viewCenterU=.9f,viewCenterV=.5f)
 val r=fixedDesktopDepth(rotated,viewer0,1f)
 check(kotlin.math.abs(radius(floatArrayOf(r.pose.position[0],r.pose.position[1],r.pose.position[2]-.4f*r.physicalWidthM))-ViewLayers.DesktopDistanceM)<.00001f)
 check(fixedDesktopDepth(depthBase.copy(pose=Pose()),viewer0,1f).pose.position.all{it.isFinite()})
 val rooted=fixedDesktopDepth(depthBase,Pose(floatArrayOf(1f,0f,0f)),1.785f)
 check(kotlin.math.abs(radius(FloatArray(3){rooted.pose.position[it]-(if(it==0)1f else 0f)})-ViewLayers.DesktopDistanceM*1.785f)<.00001f)
 val rigid=fixedDesktopDepth(focused,viewer0,1f,preserveAngularSize=false)
 check(rigid.physicalWidthM==focused.physicalWidthM && rigid.physicalHeightM==focused.physicalHeightM)
 check(kotlin.math.abs(radius(floatArrayOf(rigid.pose.position[0]+.4f*rigid.physicalWidthM,rigid.pose.position[1]+.3f*rigid.physicalHeightM,rigid.pose.position[2]))-ViewLayers.DesktopDistanceM)<.00001f)
 val stepped=fixedDesktopDepth(rigid,Pose(floatArrayOf(.2f,0f,0f)),1f,preserveAngularSize=false)
 check(stepped.physicalWidthM==rigid.physicalWidthM && kotlin.math.abs(radius(floatArrayOf(stepped.pose.position[0]+.4f*stepped.physicalWidthM-.2f,stepped.pose.position[1]+.3f*stepped.physicalHeightM,stepped.pose.position[2]))-ViewLayers.DesktopDistanceM)<.00001f)
 val flatRigid=fixedDesktopDepth(tilted,viewer0,1f,false,preserveAngularSize=false)
 check(flatRigid.physicalWidthM==tilted.physicalWidthM && kotlin.math.abs(kotlin.math.abs((0..2).sumOf{(flatRigid.pose.position[it]*n[it]).toDouble()}.toFloat())-ViewLayers.DesktopDistanceM)<.0001f)
 check(kotlin.math.abs(ViewLayers.DiagnosticsDistanceM-.9f)<.00001f && kotlin.math.abs(ViewLayers.DesktopDistanceM-1.2f)<.00001f)
 val layers=listOf(ViewLayers.DiagnosticsDistanceM,ViewLayers.ControlsDistanceM,ViewLayers.ShellDistanceM,ViewLayers.MagnifierDistanceM,ViewLayers.DesktopDistanceM)
 val gaps=layers.zipWithNext().map{(a,b)->1f/a-1f/b}
 check(gaps.all{it>0f && kotlin.math.abs(it-gaps[0])<.0001f})
 ViewLayers.configure(.2f,9f)
 check(ViewLayers.nearestM==.6f && ViewLayers.farthestM==5f)
 ViewLayers.configure(4.9f,4.9f)
 check(ViewLayers.nearestM==4.7f && ViewLayers.farthestM==5f)
 ViewLayers.configure(1f,1.1f)
 check(kotlin.math.abs(ViewLayers.farthestM-1.3f)<.00001f && ViewLayers.sizeScale(0)>1f && ViewLayers.PalmMenuDistanceM<ViewLayers.DiagnosticsDistanceM)
 ViewLayers.configure(ViewLayers.DefaultNearestM,ViewLayers.DefaultFarthestM)
 check(kotlin.math.abs(ViewLayers.sizeScale(4)-1f)<.00001f)

 val full=floatArrayOf(0f,0f,.5f,1f,.5f,0f,1f,1f)
 check(videoCoverageIsFull(full,0f))
 check(!videoCoverageIsFull(full,.1f))
 val l=floatArrayOf(0f,0f,.5f,1f,.5f,.5f,1f,1f)
 check(!videoCoverageIsFull(l,0f))
 for(sphere in listOf<MonitorMesh?>(null,MonitorMesh(2f,2f,floatArrayOf(0f,0f,2f)))) {
  val geometry=clippedVideoGeometry(2f,2f,sphere,l,0f)
  var area=0f
  for(i in geometry.indices.indices step 3) {
   val a=geometry.indices[i]*2;val b=geometry.indices[i+1]*2;val c=geometry.indices[i+2]*2;val uv=geometry.texCoords
   val u=(uv[a]+uv[b]+uv[c])/3;val v=1-(uv[a+1]+uv[b+1]+uv[c+1])/3
   check(u<=.50001f || v>=.49999f)
   area+=kotlin.math.abs((uv[b]-uv[a])*(uv[c+1]-uv[a+1])-(uv[c]-uv[a])*(uv[b+1]-uv[a+1]))*.5f
  }
  check(kotlin.math.abs(area-.75f)<.0001f)
  if(sphere!=null) check(clippedVideoGeometry(2f,2f,sphere,full,0f).positions===sphere.positions)
 }
 for(spherical in listOf(false,true)) for(aspect in listOf(.25f,1.6f,8f)) for(units in listOf(1f,1.785f)) {
  val source=MonitorPlacement("fit","existing-0",Pose(floatArrayOf(5f,-3f,-9f)),20f,20f/aspect,0f,8000,(8000/aspect).toInt(),60)
  val viewer=Pose(floatArrayOf(10f,2f,-4f),floatArrayOf(0f,.70710677f,0f,.70710677f))
  val h=.65f;val v=.5f
  val fitted=fitDesktopInView(listOf(source),viewer,h,v,units,spherical).single()
  val center=viewerInMonitor(fitted.pose.position,viewer.position,viewer.rotation)
  check(kotlin.math.abs(center[0])<.00001f && kotlin.math.abs(center[1])<.00001f && kotlin.math.abs(center[2]+ViewLayers.DesktopDistanceM*units)<.00001f)
  check(kotlin.math.abs(fitted.physicalWidthM/fitted.physicalHeightM-aspect)<.00001f)
  check(fitted.pixelWidth==source.pixelWidth && fitted.pixelHeight==source.pixelHeight)
  val eye=viewerInMonitor(viewer.position,fitted.pose.position,fitted.pose.rotation)
  val mesh=if(spherical)MonitorMesh(fitted.physicalWidthM,fitted.physicalHeightM,eye,focus=floatArrayOf(0f,0f)) else null
  for(x in listOf(-.5f,0f,.5f))for(y in listOf(-.5f,0f,.5f)) {
   val point=floatArrayOf(x*fitted.physicalWidthM,y*fitted.physicalHeightM,0f)
   val p=mesh?.project(point) ?: point
   val depth=eye[2]-p[2]
   check(depth>0f && kotlin.math.abs(kotlin.math.atan2(p[0]-eye[0],depth))<=h*.85f+.0001f)
   check(kotlin.math.abs(kotlin.math.atan2(p[1]-eye[1],depth))<=v*.85f+.0001f)
  }
 }
 check(fitDesktopInView(emptyList(),Pose(),0f,0f,1f,false).isEmpty())
 val fallback=MonitorPlacement("fallback","existing-0",Pose(),1f,1f,0f,1000,1000,60)
 check(fitDesktopInView(listOf(fallback),Pose(),Float.NaN,0f,Float.NaN,true).single().physicalWidthM.isFinite())
 val original=MonitorPlacement("a","existing-0",Pose(floatArrayOf(1f,0f,-2f)),1.2f,.8f,0f,1920,1080,60)
 val angular=WorkspaceFile(handMouse=true,grabToMove=true,freeManipulation=false)
 check(!angular.handMouseActive && !angular.focusMode && !angular.windowGrabMode)
 check(angular.copy(grabToMove=false).handMouseActive)
 check(angular.copy(grabToMove=false,handMouse=false).focusMode)
 val old=WorkspaceFile(layoutOrigin=original.pose,layouts=mutableMapOf("Desk" to mutableListOf(original)),viewerReference=Pose())
 val turned=Pose(floatArrayOf(10f,2f,5f),floatArrayOf(0f,.70710677f,0f,.70710677f))
 val restoredWorkspace=old.restoreViewer(turned)
 val restored=restoredWorkspace.layouts["Desk"]!!.single()
 check(restoredWorkspace.layoutOrigin!!.position.contentEquals(restored.pose.position))
 check(kotlin.math.abs(restored.pose.position[0]-8f)<.00001f)
 check(kotlin.math.abs(restored.pose.position[1]-2f)<.00001f)
 check(kotlin.math.abs(restored.pose.position[2]-4f)<.00001f)
 check(restored.physicalWidthM==original.physicalWidthM)
 val scaled=old.copy(referenceUnitsPerMeter=2f).restoreViewer(Pose(),1f)
 check(scaled.layouts["Desk"]!!.single().physicalWidthM==original.physicalWidthM*.5f)
 check(scaled.layoutOrigin!!.position[2]==original.pose.position[2]*.5f)
 val spread=FixedMagnifierPlacement("offscreen",Pose(floatArrayOf(8f,-7f,-2f)),3f,2f,true)
 val placed=old.copy(fixedMagnifiers=listOf(spread),systemPanels=listOf(spread),referencePlacement=spread,
     layoutOrigin=Pose(floatArrayOf(0f,0f,-2f)))
 val target=Pose(floatArrayOf(0f,0f,-1.2f),floatArrayOf(0f,.70710677f,0f,.70710677f))
 val reset=placed.moveLayoutOrigin(target)
 check(reset.layoutOrigin===target)
 val movedDesk=reset.layouts["Desk"]!!.single()
 val offset=viewerInMonitor(movedDesk.pose.position,target.position,target.rotation)
 check(kotlin.math.abs(offset[0]-1f)<.00001f && kotlin.math.abs(offset[2])<.00001f)
 check(movedDesk.physicalWidthM==original.physicalWidthM)
 check(reset.fixedMagnifiers==placed.fixedMagnifiers && reset.systemPanels==placed.systemPanels && reset.referencePlacement==spread)
 val large=MonitorMesh(32f,18f,floatArrayOf(0f,0f,1.2f),columns=32,rows=20,focus=floatArrayOf(0f,0f))
 check(large.innerRadius in 0f..large.radius)
 for(i in large.indices.indices step 3){
     val center=FloatArray(3){axis->(0..2).sumOf{large.positions[large.indices[i+it]*3+axis].toDouble()}.toFloat()/3f}
     check(radius(FloatArray(3){center[it]-large.eye[it]})>=large.innerRadius-.00001f)
 }
 val again=old.restoreViewer(turned).restoreViewer(Pose()).layouts["Desk"]!!.single()
 check((0..2).all {kotlin.math.abs(again.pose.position[it]-original.pose.position[it])<.00001f})
 check(old.copy(viewerReference=null).restoreViewer(turned).viewerReference===turned)
 val legacyDesk=MonitorPlacement("legacy","existing-0",Pose(floatArrayOf(0f,0f,-1.5f)),.6f,.375f,0f,3456,2160,60)
 val record=FixedMagnifierPlacement("m",Pose(floatArrayOf(.1f,.05f,.2f)),.3f,.2f)
 val legacy=WorkspaceFile(layouts=mutableMapOf("Desk" to mutableListOf(legacyDesk)),fixedMagnifiers=listOf(record),systemPanels=listOf(record),nearestDistanceM=.9f,farthestDistanceM=1.5f)
 val relayered=legacy.withViewingDistances(.9f,1.2f,Pose(),1f)
 val desk=relayered.layouts["Desk"]!!.single()
 check(kotlin.math.abs(desk.pose.position[2]+1.2f)<.00001f && kotlin.math.abs(desk.physicalWidthM-.48f)<.00001f)
 val magnifierRatio=ViewLayers.layer(3,.9f,1.2f)/ViewLayers.layer(3,.9f,1.5f)
 val moved=relayered.fixedMagnifiers.single()
 check(kotlin.math.abs(moved.widthM-.3f*magnifierRatio)<.00001f && kotlin.math.abs(moved.pose.position[0]-.08f)<.00001f)
 check(kotlin.math.abs(relayered.systemPanels.single().widthM-.3f*ViewLayers.layer(2,.9f,1.2f)/ViewLayers.layer(2,.9f,1.5f))<.00001f)
 check(relayered.nearestDistanceM==.9f && relayered.farthestDistanceM==1.2f)
 val back=relayered.withViewingDistances(.9f,1.5f,Pose(),1f)
 check(kotlin.math.abs(back.layouts["Desk"]!!.single().physicalWidthM-.6f)<.0001f && kotlin.math.abs(back.fixedMagnifiers.single().widthM-.3f)<.0001f)
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-workspace-regressions-') as temp:
 p=Path(temp)/'WorkspaceChecks.kt';p.write_text(code)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-cp',temp+':'+str(stdlib),'WorkspaceChecksKt'],check=True)
print('PASS opaque video coverage, exact flat/curved mesh holes, full-coverage fast path, viewer-relative restart rotation/translation and retained dimensions')
