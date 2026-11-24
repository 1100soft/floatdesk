#!/usr/bin/env python3
"""Focus pinch arbitration, window stacking, spacing and view-fit geometry without XR dependencies."""
from pathlib import Path
from production_sources import application_sources
import subprocess,sys,tempfile,re
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def body(path):return '\n'.join(l for l in (root/path).read_text().splitlines() if not l.startswith(('import ','package ')))
topology=body('CursorTopology.kt')
start=topology.index('    fun json():String {')
end=start;depth=0;opened=False
while end<len(topology):
    if topology[end]=='{':depth+=1;opened=True
    elif topology[end]=='}':depth-=1
    end+=1
    if opened and depth==0:break
topology=topology[:start]+topology[end:]
workspace=body('workspace/Workspace.kt');workspace=workspace[workspace.index('internal data class Pose'):workspace.index('internal class WorkspaceStore')]
metadata=body('net/WorkspaceInfo.kt');metadata=metadata[:metadata.index('    companion object')]+'}\n'
code='import kotlin.math.*\n'+body('MonitorMesh.kt')+body('PointerControl.kt')+body('PinchPolicy.kt')+body('MouseGestures.kt')+body('FocusInteraction.kt')+workspace+re.search(r'internal fun deskLayout\(.*?\n}',(root/'workspace/Workspace.kt').read_text(),re.S).group(0)+body('workspace/WorkspacePlacement.kt')+metadata+body('WindowMagnification.kt')+body('LayoutOrigin.kt')+body('LayoutAdjustment.kt')+body('LayoutGuideInk.kt')+body('WindowInteraction.kt').replace('dev.floatdesk.client.workspace.MonitorPlacement','MonitorPlacement')+body('WindowToolbarState.kt')+re.search(r'internal object WindowMenuLayout \{.*?\n}',(root/'WindowActionsMenu.kt').read_text(),re.S).group(0)+'\n'+topology+body('VideoGeometry.kt')+body('MagnifierContacts.kt')+body('BoundedHistory.kt')+body('RenderPolicy.kt')+body('net/WindowCaptureRetry.kt')+body('net/WindowCaptureFormat.kt').replace('dev.floatdesk.client.RenderPolicy','RenderPolicy')+''.join(re.search(r'internal fun '+name+r'\(.*?\n}',application_sources(),re.S).group(0) for name in ('transformPoint','rotateVector','quaternionBetween','multiplyQuaternion','normalize3','normalize4'))+re.search(r'internal fun distance\(a: FloatArray.*?\n}',application_sources(),re.S).group(0)+'\n'+re.search(r'internal fun subtract\(a: FloatArray[^\n]*\n[^\n]*',application_sources()).group(0)+'\n'+re.search(r'internal fun dot\(a: FloatArray[^\n]*',application_sources()).group(0)+'\n'+re.search(r'internal fun cross3\(.*',application_sources()).group(0)+'\n'+r'''
fun add(a:FloatArray,b:FloatArray)=FloatArray(3){a[it]+b[it]}
fun main() {
 val savedOrigin=Pose(floatArrayOf(.4f,.2f,-2f))
 val curvedOrigin=layoutOriginGuide(savedOrigin,Pose(),.9f,1.2f,true)!!
 val flatOrigin=layoutOriginGuide(savedOrigin,Pose(),.9f,1.2f,false)!!
 check(abs(distance(curvedOrigin.near,FloatArray(3))-.9f)<.00001f)
 check(abs(distance(curvedOrigin.far,FloatArray(3))-1.2f)<.00001f)
 check(abs(flatOrigin.near[2]+.9f)<.00001f && abs(flatOrigin.far[2]+1.2f)<.00001f)
 check(abs(curvedOrigin.gripRadius/curvedOrigin.nearRange-flatOrigin.gripRadius/flatOrigin.nearRange)<.00001f)
 val foreground=curvedOrigin.foreground(.55f,1f)
 check(foreground.farRange<.55f)
 check(abs(foreground.gripRadius/foreground.nearRange-curvedOrigin.gripRadius/curvedOrigin.nearRange)<.00001f)
 check(WindowMenuLayout.buttonCenter(0,false,.1f)<WindowMenuLayout.buttonCenter(2,false,.1f))
 check(WindowMenuLayout.buttonCenter(3,true,.1f)-WindowMenuLayout.buttonCenter(2,true,.1f)>1.2f)

 for(curved in listOf(false,true)){
    val axes=worldAxisGeometry(curved)
    check(axes.positions.all(Float::isFinite) && axes.indices.isNotEmpty())
    for(v in axes.positions.indices step 3){
        val point=axes.positions.sliceArray(v..v+2)
        if(curved)check(abs(dot(point,point)-1f)<.0001f) else check(point[2]==0f)
        val center=if(curved)floatArrayOf(0f,0f,-1f)else FloatArray(3)
        check(distance(point,center)>.022f)
    }
 }
 val boxes=listOf(LayoutBox(floatArrayOf(0f,0f,0f),4f,2f),
    LayoutBox(floatArrayOf(0f,0f,1f),1f,1f),LayoutBox(floatArrayOf(8f,.1f,2f),2f,1f))
 val snapped=snapLayoutBoxes(boxes)
 check(snapped[0].contentEquals(boxes[0].center))
 for(i in snapped.indices){
    check(snapped[i][2]==boxes[i].center[2])
    for(j in 0 until i)check(abs(snapped[i][0]-snapped[j][0])+1e-4f>=(boxes[i].width+boxes[j].width)*.5f ||
        abs(snapped[i][1]-snapped[j][1])+1e-4f>=(boxes[i].height+boxes[j].height)*.5f)
 }
 val sequence=snapLayoutBoxes(listOf(
    LayoutBox(floatArrayOf(0f,0f,0f),4f,4f),
    LayoutBox(floatArrayOf(8f,0f,2f),2f,2f),
    LayoutBox(floatArrayOf(1.8f,0f,1f),2f,2f)))
 check(sequence[2].contentEquals(floatArrayOf(3f,0f,1f)))
 check(sequence[1].contentEquals(floatArrayOf(5f,0f,2f)))
 val vertical=snapLayoutBoxes(listOf(
    LayoutBox(floatArrayOf(0f,0f,0f),8f,2f),
    LayoutBox(floatArrayOf(.4f,.7f,1f),2f,2f)))
 check(vertical[1].contentEquals(floatArrayOf(.4f,2f,1f)))
 val diagonal=snapLayoutBoxes(listOf(
    LayoutBox(floatArrayOf(0f,0f,0f),4f,4f),
    LayoutBox(floatArrayOf(4f,4f,1f),2f,2f)))
 check(abs(diagonal[1][0]-3f)<.0001f && abs(diagonal[1][1]-3f)<.0001f)
 val sphereGeometry=layoutOriginSphereGeometry()
 for(i in sphereGeometry.positions.indices step 3){
    val r=sphereGeometry.positions.sliceArray(i..i+2)
    check(abs(dot(r,r)-1f)<.00001f)
 }
 for(i in sphereGeometry.indices.indices step 3){
    fun vertex(n:Int)=sphereGeometry.positions.sliceArray(sphereGeometry.indices[i+n]*3..sphereGeometry.indices[i+n]*3+2)
    val a=vertex(0);val b=vertex(1);val c=vertex(2)
    check(dot(cross3(subtract(b,a),subtract(c,a)),a)>0f)
 }
 val layoutBase=MonitorPlacement("main","desktop",Pose(floatArrayOf(0f,0f,-1.2f)),1.2f,.8f,0f,1200,800,60)
 val layoutPane=FixedMagnifierPlacement("pane",Pose(floatArrayOf(3f,.08f,.4f)),.5f,.25f)
 val packedFile=WorkspaceFile(layoutOrigin=layoutBase.pose,layouts=mutableMapOf("Desk" to mutableListOf(layoutBase)),
    fixedMagnifiers=listOf(layoutPane)).adjustLayout(LayoutAdjustment.SNAP,Pose(),1f)
 val packedPane=packedFile.fixedMagnifiers.single()
 val logicalDepth=ViewLayers.DesktopDistanceM-packedPane.pose.position[2]
 val gapX=abs(packedPane.pose.position[0])/logicalDepth-(layoutBase.physicalWidthM/ViewLayers.DesktopDistanceM+.5f/logicalDepth)*.5f
 val gapY=abs(packedPane.pose.position[1])/logicalDepth-(layoutBase.physicalHeightM/ViewLayers.DesktopDistanceM+.25f/logicalDepth)*.5f
 check((abs(gapX)<.0001f && gapY<0f) || (abs(gapY)<.0001f && gapX<0f))
 val hidden=FixedMagnifierPlacement("disconnected-panel",Pose(floatArrayOf(.7f,0f,.3f)),4f,2f)
 val offCenter=Pose(floatArrayOf(.45f,.2f,.3f))
 val visibleLayout=WorkspaceFile(layoutOrigin=layoutBase.pose,layouts=mutableMapOf("Desk" to mutableListOf(layoutBase)),
    fixedMagnifiers=listOf(layoutPane),systemPanels=listOf(hidden))
 val adjusted=visibleLayout.adjustLayout(LayoutAdjustment.AUTO,offCenter,1f,visibleRecords=setOf("pane"))
 check(adjusted.systemPanels.single()===hidden && adjusted.cursorMapReference===offCenter)
 val placedPane=adjusted.fixedMagnifiers.single()
 val desktopPatch=cursorPatches("desktop",layoutBase.pose,1.2f,.8f,null,FocusRect(0f,0f,1200f,800f),offCenter).single().viewBounds
 val paneWorld=Pose(transformPoint(layoutBase.pose,placedPane.pose.position))
 val panePatch=cursorPatches("pane",paneWorld,.5f,.25f,null,FocusRect(0f,0f,500f,250f),offCenter).single().viewBounds
 check(abs(desktopPatch.right-panePatch.left)<.0001f || abs(desktopPatch.left-panePatch.right)<.0001f ||
       abs(desktopPatch.bottom-panePatch.top)<.0001f || abs(desktopPatch.top-panePatch.bottom)<.0001f)

 val originGrab=LayoutOriginGrab()
 originGrab.begin("LEFT",0L,savedOrigin,Pose(),floatArrayOf(0f,0f,-.4f),1f)
 val movedOrigin=originGrab.advance(Pose(),mapOf("LEFT" to floatArrayOf(.1f,0f,-.4f)),setOf("LEFT"),150L,false)!!
 check(movedOrigin.position[0]>savedOrigin.position[0] && abs(distance(movedOrigin.position,FloatArray(3))-distance(savedOrigin.position,FloatArray(3)))<.00001f)
 check(originGrab.advance(Pose(),emptyMap(),emptySet(),160L,false)==null && !originGrab.active)

 run {
   val local=FixedMagnifierPlacement("pane",Pose(floatArrayOf(.3f,-.1f,.2f)),.45f,.3f,freePose=true)
   val old=Pose(floatArrayOf(0f,0f,-1.2f))
   val moved=Pose(floatArrayOf(.6f,.4f,-1.8f),floatArrayOf(0f,.70710677f,0f,.70710677f))
   val updated=rebaseSupportingPlacement(local,old,moved,1f)
   val expected=transformPoint(old,local.pose.position)
   val result=transformPoint(moved,updated.pose.position)
   check((0..2).all{abs(expected[it]-result[it])<.0001f} && updated.freePose)
   val free=MagnifiedWindow(1,Pose(floatArrayOf(0f,0f,-2f)),.45f,.3f,450,300,freePose=true)
   val displaced=floatArrayOf(1.2f,0f,-1.6f)
   val angularResult=angularMagnifierPlacement(free,Pose(),1f,displaced)
   check(angularResult.freePose && angularResult.pose.position.contentEquals(displaced))
   val curved=placePaneInsideDesktop(projectPaneAtPlacement(free.copy(spherical=true),free,Pose(),true),Pose(),1.2f)
   val copied=projectPaneAtPlacement(curved.copy(id=99),curved,Pose(),true)
   check(curved.mesh!=null && curved.mesh!!.radius<1.2f && samePaneGeometry(curved,copied))
   check(!samePaneGeometry(curved,curved.copy(spherical=false)))
   val limited=placePaneInsideDesktop(curved,Pose(),.85f)
   val reframed=magnifierFrame(limited,limited,Pose(),1f,true)
   check(placePaneInsideDesktop(reframed,Pose(),.85f).width==limited.width)

   for(pane in listOf(free,free.copy(freePose=false))){
       val inside=placePaneInsideDesktop(pane,Pose(),1.2f)
       for(x in listOf(-inside.width*.5f,inside.width*.5f))for(y in listOf(-inside.height*.5f,inside.height*.5f)){
           val corner=transformPoint(inside.pose,floatArrayOf(x,y,0f))
           check(sqrt(corner.sumOf{(it*it).toDouble()})<1.2)
       }
   }
 }
 run {
   val retry=WindowCaptureRetry()
   check(List(7){retry.nextDelayMs()}==listOf(500L,1000L,2000L,4000L,5000L,5000L,5000L))
   retry.reset();check(retry.nextDelayMs()==500L)
   check(RenderPolicy.WindowFps==30 && RenderPolicy.WindowDimension==WindowCaptureFormat.MaxDimension && RenderPolicy.WindowPixels==WindowCaptureFormat.MaxPixels)
 }

 run {
   val pose=Pose(floatArrayOf(0f,0f,-2f))
   val rect=FocusRect(0f,0f,1000f,1000f)
   for(curved in listOf(false,true)){
     val mesh=if(curved)MonitorMesh(4f,4f,floatArrayOf(0f,0f,2f),focus=floatArrayOf(0f,0f))else null
     val patches=cursorPatches("desktop",pose,4f,4f,mesh,rect,Pose())
     check(patches.isNotEmpty())
     for(patch in patches){
       val expected=listOf(patch.source.left to patch.source.top,patch.source.right to patch.source.top,patch.source.right to patch.source.bottom,patch.source.left to patch.source.bottom)
       for(i in 0..3){val sample=patch.sourceAt(patch.points[i])!!;check(abs(sample.first-expected[i].first)<.01f && abs(sample.second-expected[i].second)<.01f)}
     }
   }
   val back=cursorPatches("desktop",pose,4f,4f,null,rect,Pose())
   val front=cursorPatches("window",Pose(floatArrayOf(0f,0f,-1f)),1f,1f,null,FocusRect(200f,200f,600f,600f),Pose(),front=true)
   val map=CursorTopology(back+front,cursorMapScale(back))
   check(map.wires().isNotEmpty() && map.wires().all{it.back=="desktop"})
   check(abs(map.scale.first-.002f)<.00001f)
   check(map.patches.all{it.points[0].second==it.points[1].second && it.points[0].first==it.points[3].first && it.points[2].first==it.points[1].first})
   val logical=map.logicalPixels();check(logical.scale==1f to 1f)
   check(logical.patches.first().points.first()==0f to 0f)
   val panel=front.map{it.copy(key="panel",depth=.5f)}
   val layers=CursorTopology(back+front+panel)
   check(layers.wires().any{it.back=="window"} && layers.wires().any{it.back=="desktop"})
   check(map.debugSegments().size==map.patches.size*20)
   check(cursorPatches("behind",Pose(floatArrayOf(0f,0f,2f)),1f,1f,null,rect,Pose()).isEmpty())
 }

 run {
   val source=DesktopWindow(77,100,100,200,200)
   val info=WorkspaceInfo(listOf(DesktopOutput("screen",1000,1000,0,0,false,true,3,emptyList())),0,emptyList(),0,8,1000000,60,"",false,windowFocus=true,windows=listOf(source))
   val drag=WindowGrabController{PinchThresholds.Default}
   fun step(at:Long,closed:Boolean,u:Float=.2f,v:Float=.2f,blocked:Boolean=false)=drag.update(at,true,blocked,
       mapOf("LEFT" to FingerPinch(if(closed).001f else .1f,.1f)),
       mapOf("LEFT" to info.hover(u,v)!!),info)
   step(0,false);step(1,true)
   check(step(100,true,.25f,.25f).preview==null)
   val moving=step(210,true,.25f,.25f);check(moving.move==null && moving.preview==WindowMove(77,150,150))
   check(step(220,false,.26f,.26f).move==WindowMove(77,150,150))
   check(!drag.engaged)
   step(300,true);check(step(1001,true)==WindowGrabUpdate());step(1101,false)
   val sized=DesktopWindow(77,500,0,500,500);val snapInfo=info.copy(windows=listOf(sized))
   check(currentSnapCell(sized,snapInfo)==SnapCell(2,2,1,0))
   check(currentSnapCell(sized.copy(x=510,width=490),snapInfo)==SnapCell(2,2,1,0))
   check(currentSnapCell(sized.copy(x=400,width=300),snapInfo)==null)
   check(currentSnapCell(DesktopWindow(77,0,0,1000,1000),snapInfo)==SnapCell(1,1,0,0))
   val bar=HoverToolbarState(120,900,200,600);val win=ToolbarTarget.Window(77);val other=ToolbarTarget.Window(78);val pane=ToolbarTarget.Magnifier("floating")
   bar.update(0,win,null,false,false);check(bar.highlight==null);bar.update(120,win,null,false,false);check(bar.highlight==win && bar.open==null)
   bar.update(500,other,win,false,false);bar.update(699,other,win,false,false);check(bar.highlight==win && bar.open==null)
   bar.update(700,other,win,false,false);check(bar.open==win)
   bar.update(1000,null,null,true,false);check(bar.open==win)
   bar.update(1600,win,null,false,false);check(bar.open==win);bar.update(1601,win,null,false,false);check(bar.open==null && bar.highlight==win)
   bar.update(1700,pane,null,false,false);bar.update(1820,pane,null,false,false);check(bar.highlight==pane)
   bar.update(2721,null,null,false,false);check(bar.highlight==null)
   bar.update(3000,win,null,false,false);bar.update(3120,win,null,false,false);bar.update(3200,null,win,false,true);check(bar.highlight==null && bar.open==null)
   val strip=toolbarStrip(Pose(floatArrayOf(0f,1f,-1f)),.05f,false);val ratio=WindowMenuLayout.gapM/WindowMenuLayout.cellM
   check(abs(strip.width-WindowMenuLayout.toolbarWidth(false,ratio)*.05f)<1e-5f && abs(strip.height-WindowMenuLayout.toolbarHeight(ratio)*.05f)<1e-5f)
   check(strip.pose.position[1]>1f && strip.contains(floatArrayOf(.05f,strip.pose.position[1],0f),floatArrayOf(0f,0f,-1f)))
   check(!strip.contains(floatArrayOf(0f,.98f,0f),floatArrayOf(0f,0f,-1f)) && !strip.contains(floatArrayOf(.2f,strip.pose.position[1],0f),floatArrayOf(0f,0f,-1f)))
   check(toolbarStrip(Pose(),.05f,true).width>strip.width)
   val y=strip.pose.position[1];check(strip.containsMarker(floatArrayOf(.035f,y,0f),floatArrayOf(0f,0f,-1f)) && !strip.containsMarker(floatArrayOf(.06f,y,0f),floatArrayOf(0f,0f,-1f)) && strip.contains(floatArrayOf(.06f,y,0f),floatArrayOf(0f,0f,-1f)))
   val delayed=WindowGrabController{PinchThresholds.Default}
   val opened=mapOf("LEFT" to FingerPinch(.1f,.1f));val pinched=mapOf("LEFT" to FingerPinch(.001f,.1f))
   delayed.update(0,true,false,opened,emptyMap(),info)
   delayed.update(1,true,false,pinched,emptyMap(),info)
   delayed.update(70,true,false,pinched,mapOf("LEFT" to info.hover(.2f,.2f)!!),info)
   check(delayed.engaged)
   delayed.update(80,true,true,pinched,emptyMap(),info);check(!delayed.engaged)
   check(snapWindowMove(source,2,104,info).x==0)
   check(snapWindowDrop(source,0,0,.01f,.01f,info)==WindowMove(77,0,0,500,500))
   check(snapWindowDrop(source,0,0,.2f,.2f,info)==WindowMove(77,0,0,500,500))
   check(snapWindowDrop(source,0,0,.99f,.5f,info)==WindowMove(77,500,0,500,1000))
 }

 run {
   val ray=WindowDragRay()
   val desktop=MonitorPlacement("d","desktop",Pose(floatArrayOf(0f,0f,-2f)),4f,4f,0f,1000,1000,30)
   val hand=HandAimPose(floatArrayOf(0f,0f,-.5f),floatArrayOf(0f,0f,0f,1f))
   ray.begin("LEFT",hand,PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f)),desktop,null,Pose(),DesktopWindow(42,250,250,500,500),intArrayOf(0,0,1000,1000),1f)
   val p=ray.point(mapOf("LEFT" to hand.copy(position=floatArrayOf(.02f,0f,-.5f))))!!.second
   check(abs(p.u-.52f)<.001f && abs(p.v-.5f)<.0001f)
   val rotated=ray.point(mapOf("LEFT" to hand.copy(rotation=floatArrayOf(.2f,.3f,.1f,.92f))))!!.second
   check(abs(rotated.u-.5f)<.0001f && abs(rotated.v-.5f)<.0001f)
   val frame=Pose(floatArrayOf(3f,1f,2f),floatArrayOf(0f,sin(.35f),0f,cos(.35f)))
   val movedDesk=desktop.copy(pose=Pose(transformPoint(frame,desktop.pose.position),multiplyQuaternion(frame.rotation,desktop.pose.rotation)))
   val other=WindowDragRay()
   val transformedHand=hand.copy(position=transformPoint(frame,hand.position))
   other.begin("RIGHT",transformedHand,PointerTarget(0,.5f,.5f,floatArrayOf(0f,0f,0f)),movedDesk,null,frame,DesktopWindow(42,250,250,500,500),intArrayOf(0,0,1000,1000),1f,"GAZE")
   val movedPoint=other.point(mapOf("RIGHT" to transformedHand.copy(position=transformPoint(frame,floatArrayOf(.02f,0f,-.5f)))))!!
   check(movedPoint.first=="GAZE" && abs(movedPoint.second.u-p.u)<.001f && abs(movedPoint.second.v-p.v)<.001f)
   ray.clear();check(ray.point(mapOf("LEFT" to hand))==null)
   val cue=typingIndicator(FocusRect(.2f,.3f,.8f,.9f));check(cue.size%4==0)
   check(cue.indices.all{if(it%2==0)cue[it] in .2f.. .8f else cue[it] in .3f.. .9f})
 }

 run {
   val start=magnifiedWindow(99,46,1600,Pose(),.6f,.5f,1f,false).copy(width=.4f,height=.6f)
   var fitted=magnifierFrame(start,start,Pose(),1f,true)
   check(abs(fitted.width/fitted.height-46f/1600)<.00001f)
   val width=fitted.width;val height=fitted.height
   repeat(100){fitted=magnifierFrame(fitted,fitted,Pose(),1f,false)}
   check(fitted.width==width && fitted.height==height)
   check(magnifierContentRect(fitted)==FocusRect(0f,0f,1f,1f))
   val pose=Pose(floatArrayOf(0f,0f,-2f),floatArrayOf(.3f,.4f,.1f,.86f))
   val rect=FocusRect(0f,0f,100f,200f)
   val angled=cursorPatches("a",pose,1f,2f,null,rect,Pose()).single()
   val straight=cursorPatches("a",pose.copy(rotation=floatArrayOf(0f,0f,0f,1f)),1f,2f,null,rect,Pose()).single()
   check(angled.points==straight.points)
   val yaw=Pose(floatArrayOf(0f,0f,-2f),floatArrayOf(0f,.38268343f,0f,.92387953f))
   val panel=cursorPatches("shell:1",yaw,1f,2f,null,rect,Pose(),projectBounds=true).single().viewBounds
   check(abs(panel.left - (-.3535534f/1.6464466f))<.00001f)
   check(abs(panel.right - (.3535534f/2.3535534f))<.00001f)
 }

 // Curved panes keep a constant radius; flat panes stay exactly coplanar.
 // Both use the same native mesh representation across transitions.
 val layerViewer=Pose()
 for(curved in listOf(false,true))for(distance in listOf(ViewLayers.MagnifierDistanceM,ViewLayers.ShellDistanceM)) for(tilted in listOf(false,true)) {
  val original=magnifiedWindow(1,1200,800,layerViewer,.7f,.5f,1f,curved,layerDistanceM=distance)
  val request=original.copy(width=distance*4,height=distance*2.5f,fixed=tilted,pose=if(tilted)original.pose.copy(rotation=normalize4(floatArrayOf(.3f,.4f,.1f,.86f)))else original.pose)
  val pane=magnifierFrame(request,original,layerViewer,1f,true)
  val geometry=pane.mesh!!
  for(i in geometry.positions.indices step 3){
   val point=transformPoint(pane.pose,floatArrayOf(geometry.positions[i],geometry.positions[i+1],geometry.positions[i+2]))
   if(curved)check(abs(kotlin.math.sqrt(point.sumOf{(it*it).toDouble()}).toFloat()-distance)<.0001f)
   else check(abs(geometry.positions[i+2])<.000001f)
  }
  val source=DesktopWindow(1,100,100,1200,800)
  val popup=DesktopWindow(2,1250,700,300,240)
  val note=stickyMagnifier(pane,source,popup,layerViewer)
  check(note.sourceWidth==300 && note.sourceHeight==240 && note.layerDistanceM<pane.layerDistanceM)
  val single=stickyMagnifier(pane,source,popup,layerViewer,viewHalfAngles=floatArrayOf(.45f,.35f))
  val wide=stickyMagnifier(pane,source,popup.copy(width=1800),layerViewer,viewHalfAngles=floatArrayOf(.45f,.35f))
  check(single.layerDistanceM==wide.layerDistanceM && wide.spherical==curved)
  check(abs(wide.width/wide.height-1800f/240f)<.0001f)
  check(wide.width<stickyMagnifier(pane,source,popup.copy(width=1800),layerViewer).width)
  val bar=DesktopWindow(3,0,950,1600,50,true)
  val anchor=640f to 975f
  val panel=pane.copy(sourceWidth=bar.width,sourceHeight=bar.height,width=1.6f,height=.05f,mesh=null)
  val one=stickyMagnifier(panel,bar,popup,layerViewer,viewHalfAngles=floatArrayOf(.45f,.35f),panelAnchor=anchor)
  val group=stickyMagnifier(panel,bar,popup.copy(x=400,width=1800),layerViewer,viewHalfAngles=floatArrayOf(.45f,.35f),panelAnchor=anchor)
  check(one.layerDistanceM==group.layerDistanceM && one.spherical==group.spherical)
  check(abs(viewerInMonitor(one.requestedPosition!!,panel.pose.position,panel.pose.rotation)[0]-viewerInMonitor(group.requestedPosition!!,panel.pose.position,panel.pose.rotation)[0])<.0001f)


  check(pane.sourceWidth==1200 && pane.width<=distance*4 && abs(pane.width/pane.height-1.5f)<.0001f)
  check(magnifierFrame(pane,pane,Pose(floatArrayOf(.02f,0f,0f)),1f,false).mesh===pane.mesh)
 }
 for(enabled in listOf(false,true))for(active in listOf(false,true))for(open in listOf(false,true)){
   check(magnifierSurfacesAllowed(enabled,active,open)==(enabled && active && !open))
 }
 val history=BoundedHistory<ByteArray>(4,10){it.size.toLong()}
 repeat(100){history.retain(ByteArray(4));check(history.bytes<=10 && history.size<=4)}
 val huge=ByteArray(30);history.retain(huge);check(history.size==1 && history.bytes==30L)
 history.retain(huge);check(history.size==1)
 val contacts=MagnifierContacts()
 contacts.update("first","LEFT",.2f to .3f,42)
 contacts.update("second","LEFT",.6f to .7f,43)
 contacts.update("first","LEFT",null,null)
 check(contacts["LEFT"]==(.6f to .7f) && contacts.window("LEFT")==43L)
 contacts.clear();check(contacts.isEmpty() && contacts.window("LEFT")==null)
 for((width,height) in listOf(800 to 600,7680 to 4320,2048 to 2048,1 to 100000)){
   val size=WindowCaptureFormat.size(width,height)
   check(WindowCaptureFormat.valid(size.first,size.second) && size.first%2==0 && size.second%2==0)
   val canvas=WindowCaptureFormat.canvasSize(width,height)
   check(WindowCaptureFormat.valid(canvas.first,canvas.second) && canvas.first>=size.first && canvas.second>=size.second)
   check(canvas.first.toLong()*canvas.second<=RenderPolicy.WindowPixels && maxOf(size.first,size.second)<=RenderPolicy.WindowDimension)
 }
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

 // A cold curved start and entering curved from flat use identical fitting and projection.
 fun sameGeometry(a:MagnifiedWindow,b:MagnifiedWindow){
   check(abs(a.width-b.width)<.00001f && abs(a.height-b.height)<.00001f)
   for(axis in 0..2)check(abs(a.pose.position[axis]-b.pose.position[axis])<.00001f)
   for(axis in 0..3)check(abs(a.pose.rotation[axis]-b.pose.rotation[axis])<.00001f)
   check((a.mesh==null)==(b.mesh==null))
   if(a.mesh!=null){
     check(a.mesh!!.positions.size==b.mesh!!.positions.size)
     a.mesh!!.positions.indices.forEach{check(abs(a.mesh!!.positions[it]-b.mesh!!.positions[it])<.00001f)}
   }
 }
 for(head in listOf(Pose(),Pose(floatArrayOf(.4f,-.2f,.3f),floatArrayOf(0f,sin(.3f),0f,cos(.3f)))))for(unit in listOf(.7f,1f,1.8f)){
   var floating=magnifiedWindow(42,1600,900,head,.6f,.5f,unit,false)
   repeat(10){
     floating=magnifiedWindow(42,1600,900,head,.6f,.5f,unit,true,previous=floating)
     sameGeometry(floating,magnifiedWindow(42,1600,900,head,.6f,.5f,unit,true))
     floating=magnifiedWindow(42,1600,900,head,.6f,.5f,unit,false,previous=floating)
     sameGeometry(floating,magnifiedWindow(42,1600,900,head,.6f,.5f,unit,false))
   }
   val resized=resizedMagnifier(floating,1.4f)
   val moved=magnifierGeometry(resized,head,unit,transformPoint(head,floatArrayOf(.2f*unit,.1f*unit,-.85f*unit)))
   val switched=magnifiedWindow(42,1600,900,head,.6f,.5f,unit,true,previous=moved)
   val cold=resizedMagnifier(magnifiedWindow(42,1600,900,head,.6f,.5f,unit,true),1.4f)
   sameGeometry(switched,magnifierGeometry(cold.copy(headOffset=moved.headOffset),head,unit))
   val desktop=MonitorPlacement("desktop","desktop",Pose(floatArrayOf(.2f,.1f,-1.2f)),1.2f,.7f,0f,1600,900,60)
   val saved=saveFixedMagnifier("fixed",moved,desktop,unit)
   val source=DesktopWindow(42,0,0,1600,900)
   var fixed=fixedMagnifier(saved,desktop,source,head,unit,false)
   val laterHead=head.copy(position=FloatArray(3){head.position[it]+floatArrayOf(.2f,-.1f,.15f)[it]*unit})
   val afterViewerChange=magnifierFrame(fixed.copy(spherical=true),fixed,laterHead,unit,true)
   sameGeometry(afterViewerChange,fixedMagnifier(saved,desktop,source,laterHead,unit,true))
   val relocated=desktop.copy(pose=desktop.pose.copy(position=FloatArray(3){desktop.pose.position[it]+floatArrayOf(.4f,.2f,-.3f)[it]*unit}))
   val pending=fixedMagnifier(saved,relocated,source,head,unit,false)
   sameGeometry(magnifierFrame(pending.copy(spherical=true),pending,laterHead,unit,true),fixedMagnifier(saved,relocated,source,laterHead,unit,true))
   repeat(10){
     fixed=fixedMagnifier(saved,desktop,source,head,unit,true,previous=fixed)
     sameGeometry(fixed,fixedMagnifier(saved,desktop,source,head,unit,true))
     fixed=fixedMagnifier(saved,desktop,source,head,unit,false,previous=fixed)
     sameGeometry(fixed,fixedMagnifier(saved,desktop,source,head,unit,false))
   }
 }
 // Switch live projection in both directions while the ordinary update budget is closed.
 for(fixed in listOf(false,true)){
   var rendered=magnifiedWindow(42,1600,900,Pose(),.6f,.5f,1f,false).copy(fixed=fixed)
   repeat(30){
     val curved=magnifierFrame(rendered.copy(spherical=true),rendered,Pose(),1f,false)
     check(curved.spherical && curved.mesh!=null)
     check(curved.id==rendered.id && curved.width==rendered.width && curved.height==rendered.height)
     val flat=magnifierFrame(curved.copy(spherical=false),curved,Pose(),1f,false)
     check(!flat.spherical && flat.mesh?.planar==true && flat.id==curved.id)
     rendered=flat
   }
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
 // Flat and curved rendering both submit mesh geometry, including the exterior padding.
 val flatPane=magnifiedWindow(42,1600,900,Pose(),.6f,.5f,1f,false)
 val flatGeometry=framedMagnifierGeometry(flatPane)
 check(flatPane.mesh?.planar==true)
 check(flatGeometry.positions.copyOfRange(0,flatPane.mesh!!.positions.size).contentEquals(flatPane.mesh!!.positions))
 check(flatGeometry.indices.size>6 && flatGeometry.texCoords.min()==0f && flatGeometry.texCoords.max()==1f)
 val eyeFlat=flatPane.mesh!!.eye
 for(i in flatPane.mesh!!.positions.indices step 3){
  val p=flatPane.mesh!!.positions.copyOfRange(i,i+3)
  val t=-eyeFlat[2]/(p[2]-eyeFlat[2])
  val x=eyeFlat[0]+(p[0]-eyeFlat[0])*t;val y=eyeFlat[1]+(p[1]-eyeFlat[1])*t
  val vertex=i/3
  check(abs(x-(flatPane.mesh!!.texCoords[vertex*2]-.5f)*flatPane.width)<.0001f)
  check(abs(y-(.5f-flatPane.mesh!!.texCoords[vertex*2+1])*flatPane.height)<.0001f)
 }
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
 check(abs(shifted.pose.position[0]-restored.pose.position[0]-.5f)<.00001f)
 val radial=fixedMagnifierGeometry(shifted,Pose(),FloatArray(3){shifted.pose.position[it]*4f})
 for(axis in 0..2)check(abs(radial.pose.position[axis]-shifted.pose.position[axis])<.00001f)
 val normal=rotateVector(shifted.pose.rotation,0f,0f,1f)
 val toEye=FloatArray(3){-shifted.pose.position[it]/radius}
 check((0..2).sumOf{(normal[it]*toEye[it]).toDouble()}>.99999)
 check(fixedMagnifierGeometry(shifted,Pose()).mesh===shifted.mesh)
 // Flat panes do not re-aim when the head translates; explicit movement does re-aim.
 val fixedFlat=fixedMagnifier(record,deskAnchor,DesktopWindow(42,0,0,1600,900),Pose(),1f,false)
 val sideHead=Pose(floatArrayOf(.3f,.1f,0f))
 val passiveFlat=fixedMagnifierGeometry(fixedFlat,sideHead)
 check(passiveFlat.pose.rotation.contentEquals(fixedFlat.pose.rotation))
 val movedFlat=fixedMagnifierGeometry(fixedFlat,sideHead,floatArrayOf(.2f,0f,-.8f))
 val movedNormal=rotateVector(movedFlat.pose.rotation,0f,0f,1f)
 val toHead=normalize3(FloatArray(3){sideHead.position[it]-movedFlat.pose.position[it]})
 check((0..2).sumOf{(movedNormal[it]*toHead[it]).toDouble()}>.99999)
 val floatFlat=magnifierGeometry(magnifiedWindow(42,1600,900,Pose(),.6f,.5f,1f,false),Pose(),1f,floatArrayOf(.3f,.1f,-.8f))
 val floatingSide=magnifierGeometry(floatFlat,sideHead,1f)
 check(floatingSide.pose.rotation.contentEquals(floatFlat.pose.rotation))
 // Flat input triangles are exactly coplanar and cover the same content rectangle.
 val flatPatches=interactionPatches(1.2f,.7f,null,6,4)
 check(flatPatches.size==48 && flatPatches.all{abs(it.center[2])<.000001f && it.scale.all{v->v.isFinite() && v>0f}})
 check(abs(flatPatches.minOf{it.center[0]}+.6f)<.00001f && abs(flatPatches.maxOf{it.center[1]}-.35f)<.00001f)
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
 // Distance gain comes from the actual pinch anchor, for either hand and any scene scale.
 for(units in listOf(.5f,1f,2f))for(handX in listOf(-.1f,.1f)){
   val panel=zoom.copy(units=units,pose=Pose(floatArrayOf(0f,0f,-.85f*units)),width=zoom.width*units,height=zoom.height*units)
   val hand=floatArrayOf(handX*units,0f,-.35f*units)
   val pixel=floatArrayOf(0f,0f,-.85f*units)
   drag.begin(panel,Pose(),origin,floatArrayOf(0f,0f,-1f),pixel,hand)
   val handRange=sqrt(hand.sumOf{(it*it).toDouble()}).toFloat()
   check(abs(drag.gain-.85f*units/handRange)<.00001f)
   check(drag.moveHand(Pose(),hand)==null)
   check(drag.moveHand(Pose(),floatArrayOf(hand[0]+.002f*units,hand[1],hand[2]))==null)
   val result=drag.moveHand(Pose(),floatArrayOf(hand[0]+.05f*units,hand[1],hand[2]))!!
   check(abs(result[0]/-result[2]-(.05f*units/handRange))<.00001f)
   check(abs(sqrt(result.sumOf{(it*it).toDouble()}).toFloat()-.85f*units)<.00001f)
 }
 val immediate=magnifierGeometry(zoom,Pose(),1f,floatArrayOf(.2f,0f,-.85f),updateMesh=false)
 check(immediate.pose.position[0]>0f && immediate.mesh===zoom.mesh)
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
 val panel=hit.copy(spaceKey="shell:42")
 val paneChoice=pointer.choose(false,null,mapOf("LEFT" to panel,"RIGHT" to hit),false,0,
     previewHands=mapOf("RIGHT" to hit))
 check(paneChoice.mode==PointerMode.HAND && paneChoice.target?.spaceKey=="shell:42")
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
  check(abs(p[0]-goal[0])<.0001f && abs(p[1]-goal[1])<.0001f && abs(goal[2]-p[2]-ViewLayers.DesktopDistanceM*1.785f)<.0001f)
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
