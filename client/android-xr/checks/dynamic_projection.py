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
  val mesh=MonitorMesh(1.8f,1.1f,eye)
  val positions=mesh.positions.copyOf();val uv=mesh.texCoords.copyOf()
  for(focus in listOf(floatArrayOf(0f,0f),floatArrayOf(.45f,.22f),floatArrayOf(-.45f,-.22f))) {
   for(rects in listOf(floatArrayOf(0f,0f,1f,1f),floatArrayOf(0f,0f,.5f,.5f,.5f,.5f,1f,1f))) {
    val map=ViewProjection.create(mesh,focus,rects,0f);mesh.projection=map
    check(mesh.positions.contentEquals(positions) && mesh.texCoords.contentEquals(uv))
    for(i in mesh.positions.indices step 3) check(kotlin.math.abs(length(minus(mesh.positions.copyOfRange(i,i+3),eye))-mesh.radius)<.00001f)
    for(i in 0..30) for(j in 0..20) {
     val u=i/30f;val v=j/20f;val mapped=map.source(u,v);val inverse=map.inverse(mapped[0],mapped[1])
     check(kotlin.math.abs(inverse[0]-u)<.00002f && kotlin.math.abs(inverse[1]-v)<.00002f)
     check(kotlin.math.abs(mapped[0]-u)<=.01401f && kotlin.math.abs(mapped[1]-v)<=.01401f)
    }
    for(k in rects.indices step 4) for(t in listOf(0f,.25f,.5f,.75f,1f)) {
     val x=rects[k]+t*(rects[k+2]-rects[k]);val y=rects[k+1]+t*(rects[k+3]-rects[k+1])
     for(p in listOf(floatArrayOf(x,rects[k+1]),floatArrayOf(x,rects[k+3]),floatArrayOf(rects[k],y),floatArrayOf(rects[k+2],y))) {
      val mapped=map.source(p[0],p[1]);check(kotlin.math.abs(mapped[0]-p[0])<.000001f && kotlin.math.abs(mapped[1]-p[1])<.000001f)
     }
    }
    for(x in listOf(-.85f,-.45f,0f,.45f,.85f)) for(y in listOf(-.5f,0f,.5f)) {
     val pixel=floatArrayOf(x,y,0f);val hit=mesh.project(pixel)
     near(mesh.unproject(hit)!!,pixel)
     near(monitorRayHit(eye,minus(hit,eye),mesh.width,mesh.height,mesh)!!,hit)
    }
   }
  }
 }
 val thin=MonitorMesh(1.8f,1.1f,floatArrayOf(0f,0f,1.2f))
 val strips=FloatArray(17*4) {i->when(i%4){0->(i/4)/17f;1->0f;2->(i/4+1)/17f;else->1f}}
 val thinMap=ViewProjection.create(thin,floatArrayOf(.5f,.2f),strips,.15f)
 for(k in thinMap.bounds.indices step 4) for(t in 0..20) {
  val u=thinMap.bounds[k]+t/20f*(thinMap.bounds[k+2]-thinMap.bounds[k]);val v=.4f
  val mapped=thinMap.source(u,v)
  check(mapped[0]>=thinMap.bounds[k]-.000001f && mapped[0]<=thinMap.bounds[k+2]+.000001f)
  val inverse=thinMap.inverse(mapped[0],mapped[1]);check(kotlin.math.abs(inverse[0]-u)<.00002f)
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
 println("PASS fixed sphere/UV geometry, bounded GPU lookup, exact screen boundaries, inverse mapping and ray hits, tracking gap/jump recovery")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-projection-') as temp:
 p=Path(temp)/'ProjectionCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'ProjectionCheckKt'],check=True)
