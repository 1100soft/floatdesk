#!/usr/bin/env python3
"""Sphere/tangent geometry, inverse input and tracking recovery without XR dependencies."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
code='\n'.join((root/name).read_text().split('\n',1)[1] for name in ['MonitorMesh.kt','TrackingGuard.kt'])+'''
fun main() {
 fun length(a:FloatArray)=kotlin.math.sqrt(a.sumOf {(it*it).toDouble()}).toFloat()
 fun minus(a:FloatArray,b:FloatArray)=FloatArray(3){a[it]-b[it]}
 fun near(a:FloatArray,b:FloatArray,tolerance:Float=.0002f) {check(length(minus(a,b))<tolerance){"${a.contentToString()} != ${b.contentToString()}"}}
 for(eye in listOf(floatArrayOf(0f,0f,1.2f),floatArrayOf(.4f,-.3f,.8f))) {
  val reference=MonitorMesh(1.8f,1.1f,eye)
  for(focus in listOf(floatArrayOf(0f,0f),floatArrayOf(.45f,.22f),floatArrayOf(-.45f,-.22f))) {
   val mesh=MonitorMesh(1.8f,1.1f,eye,focus=focus)
   val flat=floatArrayOf(focus[0],focus[1],0f)
   val anchor=mesh.project(flat)
   near(anchor,reference.project(flat)) // No feedback drift of the viewed point.
   for(i in mesh.positions.indices step 3) check(kotlin.math.abs(length(minus(mesh.positions.copyOfRange(i,i+3),eye))-mesh.radius)<.00001f)
   val dx=minus(mesh.project(floatArrayOf(focus[0]+.0002f,focus[1],0f)),anchor)
   val dy=minus(mesh.project(floatArrayOf(focus[0],focus[1]+.0002f,0f)),anchor)
   check(kotlin.math.abs(length(dx)/length(dy)-1)<.03f)
   check(kotlin.math.abs((0..2).sumOf {(dx[it]*dy[it]).toDouble()}/(length(dx)*length(dy)))<.03)
   for(x in listOf(-.899f,-.45f,0f,.45f,.899f)) for(y in listOf(-.549f,0f,.549f)) {
    val pixel=floatArrayOf(x,y,0f);val hit=mesh.project(pixel)
    near(mesh.unproject(hit)!!,pixel)
    near(monitorRayHit(eye,minus(hit,eye),mesh.width,mesh.height,mesh)!!,hit)
   }
  }
 }
 val recovery=TrackingRecovery();val p=floatArrayOf(0f,0f,0f);val q=floatArrayOf(0f,0f,0f,1f)
 check(recovery.update(0,true,p,q,1f)==null)
 check(recovery.update(20,false,null,null,1f)==null)
 check(recovery.update(100,true,p,q,1f)==null) // A short gap is harmless.
 recovery.update(120,false,null,null,1f)
 check(recovery.update(500,true,floatArrayOf(.01f,0f,0f),q,1f)==null) // Small drift stays world locked.
 recovery.update(520,false,null,null,1f)
 val restored=recovery.update(900,true,floatArrayOf(2f,0f,0f),q,1f)!!
 near(restored.first,floatArrayOf(.01f,0f,0f))
 check(recovery.update(920,true,floatArrayOf(2f,0f,0f),q,1f)==null)
 recovery.update(940,false,null,null,1f)
 check(recovery.update(1300,true,floatArrayOf(2f,0f,0f),floatArrayOf(0f,.70710677f,0f,.70710677f),1f)!=null)
 println("PASS constant sphere radius, stable viewed anchor, local square-pixel aspect, full-field inverse/ray hits, tracking gap/jump recovery")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-projection-') as temp:
 p=Path(temp)/'ProjectionCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'ProjectionCheckKt'],check=True)
