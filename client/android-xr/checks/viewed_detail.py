#!/usr/bin/env python3
"""View-dependent crop coverage and screen-spacing inversion without Android dependencies."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def body(name):return '\n'.join(l for l in (root/name).read_text().splitlines() if not l.startswith(('package ','import ')))
focus=body('FocusInteraction.kt').split('internal class FocusGestures')[0]
code='import kotlin.math.*\n'+body('MonitorMesh.kt')+focus+body('ViewedRegion.kt')+body('media/ScreenPresentation.kt')+r'''
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
 val spaced=ScreenPresentation(floatArrayOf(0f,0f,.4f,1f,.6f,0f,1f,1f),.1f)
 check(spaced.desktopBounds(FocusRect(.45f,0f,.55f,1f))==null)
 val source=spaced.desktopBounds(FocusRect(.02f,.05f,.38f,.95f))!!
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
