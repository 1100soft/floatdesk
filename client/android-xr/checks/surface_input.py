#!/usr/bin/env python3
"""Rendered-triangle ray selection across coarse native curved colliders."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
code=(root/'MonitorMesh.kt').read_text().split('\n',1)[1]+r'''
fun main() {
 val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
 var formerlyRejected=0
 for(width in listOf(2f,7.3f,16f)) for(focus in listOf(0f,.4f*width)) {
  val mesh=MonitorMesh(width,width*.625f,floatArrayOf(0f,0f,1.5f),focus=floatArrayOf(focus,0f))
  // The native grid has 12 x 8 cells, much coarser than the video mesh.
  for(row in 0 until 8) for(col in 0 until 12) {
   fun p(x:Int,y:Int)=mesh.project(floatArrayOf(((col+x)/12f-.5f)*mesh.width,(.5f-(row+y)/8f)*mesh.height,0f))
   val a=p(0,0);val b=p(1,0);val c=p(0,1)
   val coarse=FloatArray(3){(a[it]+b[it]+c[it])/3f}
   val ray=FloatArray(3){coarse[it]-mesh.eye[it]}
   val range=kotlin.math.sqrt(ray.sumOf{(it*it).toDouble()}).toFloat()
   if(kotlin.math.abs(range-mesh.radius)>maxOf(.02f,mesh.radius*.02f))formerlyRejected++
   val resolved=eventMatrixHit(coarse,identity,mesh.width,mesh.height,mesh,rayDirection=ray,rayOrigin=mesh.eye) ?: error("Rejected valid coarse hit width=$width focus=$focus row=$row col=$col")
   val exact=meshRayHit(mesh,mesh.eye,ray)!!
   check((0..2).sumOf{val d=resolved[it]-exact[it];(d*d).toDouble()}<1e-8)
   check(mesh.unproject(resolved)!=null)
  }
 }
 check(formerlyRejected>0){"Regression must exercise the old false rejection"}
 println("PASS actual rendered-triangle rays across large curved colliders; $formerlyRejected valid contacts rejected by old sphere tolerance")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-surface-input-') as temp:
 p=Path(temp)/'SurfaceInput.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'SurfaceInputKt'],check=True)
