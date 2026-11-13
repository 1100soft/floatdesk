#!/usr/bin/env python3
"""Retained aiming rays must use resized live geometry and stay in front of the screen."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'MonitorMesh.kt').read_text().split('\n',1)[1]+'\n'+(root/'PointerControl.kt').read_text().split('\n',1)[1]
guide=(root/'GrabGuideRenderer.kt').read_text();source+='\n'+guide[guide.index('internal fun visibleGrabContact'):guide.index('/** Native meshes')]
code=source+'''
fun main() {
 fun distance(a:FloatArray,b:FloatArray)=kotlin.math.sqrt((0..2).sumOf {val d=a[it]-b[it];(d*d).toDouble()}).toFloat()
 val eye=floatArrayOf(0f,0f,1.4f)
 for(width in listOf(.8f,2.4f,3.328259f,6.656518f,16f)) for(f in listOf(-.5f,0f,.5f)) {
  val mesh=MonitorMesh(width,width*.625f,eye,focus=floatArrayOf(f*width,0f))
  for(u in listOf(.01f,.25f,.75f,.99f)) for(v in listOf(.01f,.5f,.99f)) {
   // This stored hit is stale and behind the live image after a large grab/release.
   val stale=PointerTarget(0,u,v,floatArrayOf(5f,3f,-4f))
   val current=pointerSurfacePoint(stale,width*3,width*3,mesh) // Canonical state can be ahead of the renderer.
   val pixel=mesh.unproject(current)!!
   check(kotlin.math.abs(pixel[0]/mesh.width+.5f-u)<.00001f)
   check(kotlin.math.abs(.5f-pixel[1]/mesh.height-v)<.00001f)
   val visible=visibleGrabContact(current,eye,1f)
   check(distance(visible,eye)<mesh.radius-.001f)
   val hand=floatArrayOf(.12f,-.15f,1.1f)
   for(i in 0..30) {
    val point=FloatArray(3){hand[it]+(visible[it]-hand[it])*i/30f}
    check(distance(point,eye)<mesh.radius) // Entire ray stays in front, including its screen-overlap portion.
   }
   check(stale.local.contentEquals(floatArrayOf(5f,3f,-4f))) // Visual correction never rewrites the logical target.
  }
 }
 val flat=PointerTarget(0,.75f,.25f,floatArrayOf(9f,9f,-5f))
 check(pointerSurfacePoint(flat,8f,5f,null).contentEquals(floatArrayOf(2f,1.25f,0f)))
 val lifted=visibleGrabContact(pointerSurfacePoint(flat,8f,5f,null),eye,1f)
 check(lifted[2]>0f)
 println("PASS stale-hit recovery across large resize/release, exact live UVs, full-ray foreground depth and unchanged logical targets in flat/geodesic modes")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-resize-beam-') as temp:
 p=Path(temp)/'ResizeBeamCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'ResizeBeamCheckKt'],check=True)
