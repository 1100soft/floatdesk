#!/usr/bin/env python3
"""View-dependent crop coverage and screen-spacing inversion without Android dependencies."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def body(name):return '\n'.join(l for l in (root/name).read_text().splitlines() if not l.startswith(('package ','import ')))
focus=body('FocusInteraction.kt').split('internal class FocusGestures')[0]
code='import kotlin.math.*\n'+body('MonitorMesh.kt')+focus+body('PointerControl.kt')+body('GazeStabilizer.kt')+body('ViewedRegion.kt')+body('media/ScreenPresentation.kt')+r'''
fun main(){
 val half=.35f
 val center=viewedRegion(8f,4f,null,half,half){p->floatArrayOf(p[0],p[1],-2f)}!!
 check(center.left>.2f && center.right<.8f && center.top>0f && center.bottom<1f)
 val right=viewedRegion(8f,4f,null,half,half){p->floatArrayOf(p[0]-2f,p[1],-2f)}!!
 check(right.centerU>center.centerU && right.contains(.75f,.5f))
 check(viewedRegion(8f,4f,null,half,half){p->floatArrayOf(p[0],p[1],2f)}==null)
 val whole=viewedRegion(1f,1f,null,.7f,.7f){p->floatArrayOf(p[0],p[1],-2f)}!!
 check(whole==FocusRect(0f,0f,1f,1f))
 val mesh=MonitorMesh(8f,4f,floatArrayOf(0f,0f,2f),focus=floatArrayOf(0f,0f))
 val curved=viewedRegion(8f,4f,mesh,.35f,.35f){p->floatArrayOf(p[0],p[1],p[2]-2f)}!!
 check(curved.contains(.5f,.5f) && curved.left>0f && curved.right<1f)
 // Large off-center spheres must not include far eye-plane-crossing cells wholesale.
 val huge=MonitorMesh(50f,30f,floatArrayOf(0f,0f,1f),focus=floatArrayOf(20f,10f))
 val corner=viewedRegion(50f,30f,huge,.5f,.4f){p->
  val d=FloatArray(3){p[it]-huge.eye[it]}
  floatArrayOf((0..2).sumOf{(d[it]*huge.frameTangentX[it]).toDouble()}.toFloat(),(0..2).sumOf{(d[it]*huge.frameTangentY[it]).toDouble()}.toFloat(),(0..2).sumOf{(d[it]*huge.frameNormal[it]).toDouble()}.toFloat())
 }!!
 check(corner.right-corner.left<.2f && corner.bottom-corner.top<.3f)
 check(corner.contains(.9f,.5f-10f/30f))
 val flatOutside=extendedQualityTarget(floatArrayOf(0f,0f,1f),floatArrayOf(1.2f,0f,-1f),2f,2f,null)!!
 check(abs(flatOutside.first-1.1f)<.0001f)
 fun gazeRay(mesh:MonitorMesh,u:Float,v:Float):FloatArray {
  val focus=mesh.focus ?: mesh.center;val x=((u-.5f)*mesh.width-focus[0])*mesh.radiansPerMeter;val y=((.5f-v)*mesh.height-focus[1])*mesh.radiansPerMeter
  val a=sqrt(x*x+y*y);val span=Math.PI.toFloat()-.01f-1.5f;val angle=if(a<=1.5f)a else 1.5f+span*(1-exp(-(a-1.5f)/span))
  val sinc=if(a<1e-6f)1f else sin(angle)/a
  return FloatArray(3){-cos(angle)*mesh.frameNormal[it]+sinc*(x*mesh.frameTangentX[it]+y*mesh.frameTangentY[it])}
 }
 val edgeMesh=MonitorMesh(2f,1.2f,floatArrayOf(.1f,0f,1f),focus=floatArrayOf(.3f,.1f))
 for(u in listOf(.95f,1f,1.05f,1.2f,-.2f)){
  val direction=gazeRay(edgeMesh,u,.4f);val target=extendedQualityTarget(edgeMesh.eye,direction,edgeMesh.width,edgeMesh.height,edgeMesh)!!
  check(abs(target.first-u)<.001f && abs(target.second-.4f)<.001f)
 }
 val held=QualityGazeTracker();check(held.update(null,0)==null)
 held.update(1.05f to .4f,10);val before=held.center!!;check(held.update(null,1000)==before && before.first>1f)
 check(qualityOutputSize(5120,3200)==(5120 to 3200))
 val nativeFraction=qualityDenseFraction(.5f,FocusRect(0f,0f,1f,1f),5120,3200)
 check(abs(nativeFraction-.32f)<.00001f)
 check(1024f/(3200*nativeFraction)>=.9999f && 2048f/(5120*nativeFraction)>=.9999f)
 check(qualityDenseFraction(.5f,FocusRect(.2f,.2f,.6f,.6f),5120,3200)==.5f)
 check(qualityDenseFraction(.2f,FocusRect(0f,0f,1f,1f),5120,3200)==.2f)
 val capped=qualityOutputSize(32000,18000);check(capped.first<=8192 && capped.second<=8192 && capped.first.toLong()*capped.second<=16777216)
 val spaced=ScreenPresentation(floatArrayOf(0f,0f,.4f,1f,.6f,0f,1f,1f),.1f)
 check(spaced.desktopBounds(FocusRect(.45f,0f,.55f,1f))==null)
 val source=spaced.desktopBounds(FocusRect(.02f,.05f,.38f,.95f))!!
 check(spaced.extendedDesktopPoint(1.05f,.5f)!!.first>1f)
 for(point in listOf(.1f to .2f,.9f to .8f,1.05f to .5f)){
  val display=spaced.extendedDisplayPoint(point.first,point.second)!!
  val restored=spaced.extendedDesktopPoint(display.first,display.second)!!
  check(abs(restored.first-point.first)<.0001f && abs(restored.second-point.second)<.0001f)
 }
 check(abs(source.left)<.0001f && abs(source.right-.4f)<.0001f && abs(source.top)<.0001f && abs(source.bottom-1f)<.0001f)
 println("PASS viewed crop follows head framing, flat/curved coverage, behind-view rejection and gap/spacing inversion")
}
'''
# The prefix contains only focus value types, so no gesture-controller stubs are needed.
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-detail-check-') as temp:
 p=Path(temp)/'ViewedCheck.kt';p.write_text(code.replace('dev.floatdesk.client.FocusRect','FocusRect'))
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'ViewedCheckKt'],check=True)
