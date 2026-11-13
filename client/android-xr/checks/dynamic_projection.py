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
 fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf {(a[it]*b[it]).toDouble()}.toFloat()
 for(eye in listOf(floatArrayOf(0f,0f,1.2f),floatArrayOf(.4f,-.3f,.8f))) {
  var previous:FloatArray?=null
  for(focus in listOf(floatArrayOf(-.9f,0f),floatArrayOf(0f,0f),floatArrayOf(.9f,0f),floatArrayOf(.6f,.4f),floatArrayOf(0f,.55f),floatArrayOf(0f,-.55f))) {
   val mesh=MonitorMesh(1.8f,1.1f,eye,focus=focus)
   check(previous==null || !previous.contentEquals(mesh.positions));previous=mesh.positions.copyOf()
   near(mesh.project(floatArrayOf(0f,0f,0f)),floatArrayOf(0f,0f,0f)) // The desktop center does not drift while looking around.
   for(i in mesh.positions.indices step 3) check(kotlin.math.abs(length(minus(mesh.positions.copyOfRange(i,i+3),eye))-mesh.radius)<.00001f)
   // The viewed axes become straight great circles, while peripheral edges retain curvature.
   fun camera(p:FloatArray):FloatArray {val ray=minus(p,eye);val z=-dot(ray,mesh.frameNormal);return floatArrayOf(dot(ray,mesh.frameTangentX)/z,dot(ray,mesh.frameTangentY)/z)}
   for(axis in 0..1) {
    val points=(0..20).map {i->val t=i/20f;val x=if(axis==0) focus[0] else -.9f+1.8f*t;val y=if(axis==1) focus[1] else -.55f+1.1f*t;camera(mesh.project(floatArrayOf(x,y,0f)))}
    for(p in points) check(kotlin.math.abs(p[axis])<.002f) // All visible points lie on the focused axis.
   }
   if(focus[0]==0f && focus[1]==0f) {
    val top=camera(mesh.project(floatArrayOf(.9f,.5f,0f)))
    val mid=camera(mesh.project(floatArrayOf(.9f,0f,0f)))
    check(kotlin.math.abs(top[0]-mid[0])>.01f) // A full planar-looking gnomonic map must fail this test.
   }
   val patches=interactionPatches(mesh);check(patches.size==192)
   fun rotate(v:FloatArray,q:FloatArray):FloatArray {
    val t=floatArrayOf(2*(q[1]*v[2]-q[2]*v[1]),2*(q[2]*v[0]-q[0]*v[2]),2*(q[0]*v[1]-q[1]*v[0]))
    return floatArrayOf(v[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],v[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2],v[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0])
   }
   for(row in 0 until 8) for(col in 0 until 12) for(part in 0..1) {
    fun p(x:Int,y:Int)=mesh.project(floatArrayOf(((col+x)/12f-.5f)*mesh.width,(.5f-(row+y)/8f)*mesh.height,0f))
    val expected=if(part==0) listOf(p(0,0),p(0,1),p(1,0)) else listOf(p(1,0),p(0,1),p(1,1))
    val patch=patches[(row*12+col)*2+part]
    for((index,v) in listOf(floatArrayOf(0f,0f,0f),floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f)).withIndex()) {
     val child=rotate(v,patch.childRotation);val scaled=FloatArray(3){child[it]*patch.scale[it]};val parent=rotate(scaled,patch.rotation)
     near(FloatArray(3){patch.center[it]+parent[it]},expected[index])
    }
   }
   val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
   for(x in listOf(-.89f,-.45f,0f,.45f,.89f)) for(y in listOf(-.54f,0f,.54f)) {
    val pixel=floatArrayOf(x,y,0f);val hit=mesh.project(pixel);near(mesh.unproject(hit)!!,pixel)
    // A coarse patch hit on the same hand ray must resolve the exact visible contact.
    val origin=floatArrayOf(.15f,-.1f,eye[2]-.1f);val direction=minus(hit,origin)
    val patchHit=FloatArray(3){hit[it]-.005f*direction[it]}
    near(eventMatrixHit(patchHit,identity,mesh.width,mesh.height,mesh,rayDirection=direction)!!,hit)
   }
  }
 }
 val focusReference=MonitorMesh(1.8f,1.1f,floatArrayOf(0f,0f,1.2f),focus=floatArrayOf(0f,0f))
 for(x in listOf(-.9f,0f,.9f)) {
  val direction=minus(focusReference.project(floatArrayOf(x,0f,0f)),focusReference.eye)
  val selected=sphericalViewFocus(focusReference,direction)!!
  check(kotlin.math.abs(selected[0]-x)<.00002f && kotlin.math.abs(selected[1])<.00002f)
 }
 val outward=sphericalViewFocus(focusReference,floatArrayOf(1f,0f,-.5f))!!
 check(outward[0]==.9f) // Looking past the old boundary still focuses the visible edge.
 val centerMesh=MonitorMesh(1.8f,1.1f,floatArrayOf(0f,0f,1.2f),focus=floatArrayOf(0f,0f))
 val edgeMesh=MonitorMesh(1.8f,1.1f,floatArrayOf(0f,0f,1.2f),focus=floatArrayOf(.9f,0f))
 fun topChord(m:MonitorMesh)=length(minus(m.project(floatArrayOf(-.9f,.55f,0f)),m.project(floatArrayOf(.9f,.55f,0f))))
 check(kotlin.math.abs(topChord(centerMesh)-topChord(edgeMesh))>.0005f) // Real deformation, not a rigid panel rotation.
 for(w in listOf(20f,60f)) for(sx in listOf(-1f,1f)) for(sy in listOf(-1f,1f)) {
  val m=MonitorMesh(w,w*.625f,floatArrayOf(0f,0f,.2f),focus=floatArrayOf(sx*w*.5f,sy*w*.625f*.5f))
  for(i in m.positions.indices step 3) {
   val ray=FloatArray(3){m.positions[i+it]-m.eye[it]}
   val f=m.focus!!
   val x=(m.texCoords[i/3*2]-.5f)*m.width-f[0]
   val y=(.5f-m.texCoords[i/3*2+1])*m.height-f[1]
   check(length(ray).isFinite() && kotlin.math.abs(length(ray)-m.radius)<.0001f)
   check(-dot(ray,m.frameNormal)/m.radius > kotlin.math.cos(Math.PI-.009).toFloat()) // Before the antipodal overlap.
   check(m.focus!!.contentEquals(floatArrayOf(sx*w*.5f,sy*w*.625f*.5f)))
  }
 }
 val formerlyLimited=MonitorMesh(3.8809047f,2.4255652f,floatArrayOf(0f,0f,1.9066529f),focus=floatArrayOf(0f,0f))
 check(kotlin.math.abs(formerlyLimited.radiansPerMeter*formerlyLimited.radius-1f)<.00001f) // Recorded release crossed the obsolete 2.2-radian bound.
 val nearWrap=MonitorMesh(5f,3.125f,floatArrayOf(0f,0f,1f),focus=floatArrayOf(0f,0f))
 check(kotlin.math.abs(nearWrap.radiansPerMeter-1f)<.00001f) // Beyond the old hemisphere cap, still before overlap.
 val atLimit=MonitorMesh(50f,31.25f,floatArrayOf(0f,0f,1f),focus=floatArrayOf(25f,15.625f))
 check(atLimit.radiansPerMeter==1f && atLimit.focus!!.contentEquals(floatArrayOf(25f,15.625f))) // No gaze clamp or global scale collapse.
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
 println("PASS changing sphere vertices, straight viewed axes with curved peripheral edges, constant eye radius, reusable interaction patches and exact hand-ray resolution, tracking gap/jump recovery")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-projection-') as temp:
 p=Path(temp)/'ProjectionCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'ProjectionCheckKt'],check=True)
