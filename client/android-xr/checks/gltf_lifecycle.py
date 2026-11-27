#!/usr/bin/env python3
"""Exercise production curved glTF teardown against native-lifetime substitutes."""
from pathlib import Path
import subprocess
import sys
import tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
sources=[]
for name in ['OwnedGltf.kt','CurvedMonitorInput.kt']:
    sources.append('\n'.join(line for line in (root/name).read_text().splitlines() if not line.startswith(('package ','import ')) and '@android.annotation.SuppressLint' not in line and '@SuppressLint' not in line))
sources=[s.replace('androidx.lifecycle.LifecycleEventObserver','LifecycleEventObserver').replace('androidx.lifecycle.Lifecycle.Event','Lifecycle.Event').replace('android.os.Handler(android.os.Looper.getMainLooper()).post','postAfterDestroy') for s in sources]
stubs='''
internal class Session {val lifecycleOwner=LifecycleOwner()}
internal class LifecycleOwner {val lifecycle=Lifecycle();val lifecycleScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined+kotlinx.coroutines.SupervisorJob())}
internal fun interface LifecycleEventObserver {fun changed(owner:Any?,event:Lifecycle.Event)}
internal class Lifecycle {
 enum class Event {ON_DESTROY}
 private val observers=mutableListOf<LifecycleEventObserver>()
 fun addObserver(observer:LifecycleEventObserver){observers.add(observer)}
 fun destroy(){observers.forEach{it.changed(null,Event.ON_DESTROY)}}
}
internal fun postAfterDestroy(block:()->Unit){block()}
internal class MonitorMesh(val width:Float=1f,val height:Float=1f)
internal class Vector3(val x:Float,val y:Float,val z:Float)
internal class Quaternion(val x:Float,val y:Float,val z:Float,val w:Float)
internal class Pose(val position:Vector3,val rotation:Quaternion)
internal data class InteractionPatch(val center:FloatArray=floatArrayOf(0f,0f,0f),val rotation:FloatArray=floatArrayOf(0f,0f,0f,1f),val scale:FloatArray=floatArrayOf(1f,1f),val childRotation:FloatArray=floatArrayOf(0f,0f,0f,1f))
internal fun interactionPatches(width:Float,height:Float,mesh:MonitorMesh?,columns:Int=12,rows:Int=8)=List(columns*rows*2){InteractionPatch()}
internal fun interactionGridGlb(pixel:ByteArray,columns:Int=12,rows:Int=8)=pixel
internal class InputEvent
internal class InteractableComponent
internal enum class SpatialPointerIcon { NONE }
internal class SpatialPointerComponent {
 var spatialPointerIcon=SpatialPointerIcon.NONE
 companion object {fun create(s:Session)=SpatialPointerComponent()}
}
internal object NativeMonitorInput {fun create(s:Session,l:(InputEvent)->Unit)=InteractableComponent()}
internal fun frozenFrameGlb(pixel:ByteArray,mesh:MonitorMesh,interactionOnly:Boolean)=pixel
internal open class Entity {
 var parent:Entity?=null
 var disposed=false
 var shown=true
 val isDisposed get()=disposed
 fun setEnabled(value:Boolean){check(!disposed);shown=value}
 open fun dispose(){check(!disposed);disposed=true}
}
internal class GltfModel {
 var instances=0; var closed=false
 fun close(){check(instances==0) {"Material freed with a live native instance"};check(!closed);closed=true}
 companion object {
  val all=mutableListOf<GltfModel>()
  var loading:kotlinx.coroutines.CompletableDeferred<Unit>?=null
  var attempts=0
  suspend fun create(s:Session,data:ByteArray,key:String):GltfModel {attempts++;loading?.await();return GltfModel().also{all.add(it)}}
 }
}
internal class GltfModelEntity(val model:GltfModel):Entity() {
 class Node {var localScale=Vector3(1f,1f,1f);var localPose=Pose(Vector3(0f,0f,0f),Quaternion(0f,0f,0f,1f))}
 val nodes=List(384){Node()}
 init {model.instances++}
 override fun dispose(){super.dispose();model.instances--}
 fun removeComponent(c:Any){}
 fun addComponent(c:Any)=!failComponent
 companion object {
  var failComponent=false
  fun create(s:Session,m:GltfModel,parent:Entity?=null)=GltfModelEntity(m).also {it.parent=parent}
 }
}
'''
main='''
suspend fun main() {
 val session=Session();val parent=Entity()
 repeat(1000) {
  val patch=CurvedMonitorInput.create(session,parent,MonitorMesh()) {}
  patch.close();patch.close()
  check(patch.entity.disposed)
 }
 val deadSession=Session()
 val tornDown=CurvedMonitorInput.create(deadSession,parent,MonitorMesh()) {}
 tornDown.entity.dispose();GltfModel.all.last().close() // XR session disposed resources first.
 deadSession.lifecycleOwner.lifecycle.destroy();tornDown.close();tornDown.close()
 val flat=CurvedMonitorInput.create(session,parent,null,width=2f,height=1f) {}
 val original=flat.entity
 repeat(20){
  flat.updateGeometry(MonitorMesh(2f,1f),2f,1f)
  flat.updateGeometry(null,3f,2f)
  check(flat.entity===original && flat.mesh==null)
 }
 flat.close();flat.close();check(original.disposed)
 val retained=CurvedMonitorInput.create(session,parent,MonitorMesh()) {}
 retained.detach();retained.detach();retained.close()
 check(!retained.entity.disposed && !retained.entity.shown && retained.entity.parent==null && retained.entity.model.instances>0) // Detach borrows the material until the scene retires the entity.
 retained.entity.dispose()
 GltfModelEntity.failComponent=true
 check(runCatching {CurvedMonitorInput.create(session,parent,MonitorMesh()) {}}.isFailure)
 check(GltfModel.all.size==2 && GltfModel.all.all {it.instances==0})
 check(!GltfModel.all.first().closed) // Shared material remains alive through menu churn.
 GltfModel.all.filter{!it.closed}.forEach{it.close()}
 session.lifecycleOwner.lifecycle.destroy()
 check(GltfModel.all.all{it.closed})
 GltfModelEntity.failComponent=false
 kotlinx.coroutines.coroutineScope {
  val concurrent=Session();val aParent=Entity();val bParent=Entity()
  val gate=kotlinx.coroutines.CompletableDeferred<Unit>();GltfModel.loading=gate
  val attempts=GltfModel.attempts
  val a=launch{CurvedMonitorInput.create(concurrent,aParent,MonitorMesh()) {}}
  kotlinx.coroutines.yield()
  val b=async{CurvedMonitorInput.create(concurrent,bParent,MonitorMesh()) {}}
  kotlinx.coroutines.yield()
  a.cancelAndJoin();aParent.dispose();gate.complete(Unit)
  b.await().close();GltfModel.loading=null
  check(GltfModel.attempts==attempts+1 && GltfModel.all.last().instances==0)
  GltfModel.all.last().close();concurrent.lifecycleOwner.lifecycle.destroy()
 }
 parent.dispose()
 check(runCatching{CurvedMonitorInput.create(session,parent,MonitorMesh()) {}}.isFailure)
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'));coroutines=next(lib.glob('kotlinx-coroutines-core-jvm-*.jar'));classpath=str(stdlib)+':'+str(coroutines)
with tempfile.TemporaryDirectory(prefix='floatdesk-gltf-') as temp:
 path=Path(temp);code=path/'GltfLifecycle.kt';code.write_text('import kotlinx.coroutines.*\n'+stubs+'\n'.join(sources)+main)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',classpath,'-d',temp,str(code)],check=True)
 subprocess.run(['java','-cp',temp+':'+classpath,'GltfLifecycleKt'],check=True)
print('Passed: 1000 input instances reuse session materials, repeated close, detach without dispose, session-first teardown and failed setup cleanup')
