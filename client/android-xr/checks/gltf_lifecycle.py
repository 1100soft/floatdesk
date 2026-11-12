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
stubs='''
internal class Session
internal class MonitorMesh(val width:Float=1f,val height:Float=1f)
internal class Vector3(val x:Float,val y:Float,val z:Float)
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
 open fun dispose(){check(!disposed);disposed=true}
}
internal class GltfModel {
 var instances=0; var closed=false
 fun close(){check(instances==0) {"Material freed with a live native instance"};check(!closed);closed=true}
 companion object {
  val all=mutableListOf<GltfModel>()
  suspend fun create(s:Session,data:ByteArray,key:String)=GltfModel().also {all.add(it)}
 }
}
internal class GltfModelEntity(val model:GltfModel):Entity() {
 class Node {var localScale=Vector3(1f,1f,1f)}
 val nodes=listOf(Node())
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
 GltfModelEntity.failComponent=true
 check(runCatching {CurvedMonitorInput.create(session,parent,MonitorMesh()) {}}.isFailure)
 check(GltfModel.all.all {it.closed && it.instances==0})
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-gltf-') as temp:
 path=Path(temp);code=path/'GltfLifecycle.kt';code.write_text(stubs+'\n'.join(sources)+main)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(code)],check=True)
 subprocess.run(['java','-cp',temp+':'+str(stdlib),'GltfLifecycleKt'],check=True)
print('Passed: 1000 curved patches release entities before materials, repeated close and failed setup cleanup')
