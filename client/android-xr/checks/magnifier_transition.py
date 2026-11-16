#!/usr/bin/env python3
"""Run the production magnifier projection/input transition with native substitutes."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client'
s = (root / 'WindowMagnifierPanel.kt').read_text()
a = s.index('private suspend fun syncMagnifierInput(')
b = s.index('\n}\n', a) + 2
helper = s[a:b].replace('kotlinx.coroutines.CancellationException', 'java.util.concurrent.CancellationException')
code = r'''
class Session
class Pose
class MonitorMesh(val id:Int)
class Input
open class Entity {
 var parent:Entity?=null
 @JvmField var enabled=false
 fun setPose(value:Pose){}
 fun setEnabled(value:Boolean){enabled=value}
}
class SurfaceEntity:Entity(){
 val components=mutableSetOf<Input>()
 var added=0;var removed=0
 fun addComponent(input:Input):Boolean {check(components.add(input));added++;return true}
 fun removeComponent(input:Input){check(components.remove(input));removed++}
}
class CurvedMonitorInput(var mesh:MonitorMesh){
 val entity=Entity();var closed=false
 fun update(next:MonitorMesh){check(!closed);mesh=next}
 fun close(){check(!closed);closed=true;entity.enabled=false}
 companion object{
  var creates=0;var last:CurvedMonitorInput?=null;var afterCreate:(()->Unit)?=null
  suspend fun create(session:Session,parent:SurfaceEntity,mesh:MonitorMesh,columns:Int,rows:Int,listener:()->Unit):CurvedMonitorInput{
   creates++;return CurvedMonitorInput(mesh).also{last=it;afterCreate?.invoke()}
  }
 }
}
class MagnifierResources{
 var curved:CurvedMonitorInput?=null
 var flatInputAttached=true
 var input:Input?=Input()
 var generation=0
 var entity:SurfaceEntity?=null
 var visible=true
 var listener:(()->Unit)?={}
}
''' + helper + r'''
suspend fun main(){
 val session=Session();val surface=SurfaceEntity();val resources=MagnifierResources()
 resources.entity=surface;surface.addComponent(resources.input!!)
 repeat(50){cycle->
  syncMagnifierInput(session,resources,surface,null)
  check(resources.flatInputAttached && surface.components.size==1)
  check(resources.curved?.entity?.enabled!=true)
  val mesh=MonitorMesh(cycle)
  syncMagnifierInput(session,resources,surface,mesh)
  check(!resources.flatInputAttached && surface.components.isEmpty())
  check(resources.curved!!.mesh===mesh && resources.curved!!.entity.enabled)
  check(resources.curved!!.entity.parent===surface)
 }
 check(CurvedMonitorInput.creates==1)
 resources.visible=false
 syncMagnifierInput(session,resources,surface,MonitorMesh(100))
 check(!resources.curved!!.entity.enabled)
 syncMagnifierInput(session,resources,surface,null)
 check(resources.flatInputAttached && surface.components.size==1)
 val late=MagnifierResources();val lateSurface=SurfaceEntity();late.entity=lateSurface;lateSurface.addComponent(late.input!!)
 CurvedMonitorInput.afterCreate={late.generation++}
 try{syncMagnifierInput(session,late,lateSurface,MonitorMesh(101));error("stale completion accepted")}
 catch(expected:java.util.concurrent.CancellationException){}
 check(CurvedMonitorInput.last!!.closed && late.curved==null)
 println("PASS repeated flat/curved input handoff, stable collider ownership, hidden state and cancelled creation")
}
'''
lib = Path(sys.argv[1]);stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-magnifier-transition-') as tmp:
    file = Path(tmp) / 'TransitionCheck.kt';file.write_text(code)
    subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',tmp,str(file)],check=True)
    subprocess.run(['java','-cp',tmp+':'+str(stdlib),'TransitionCheckKt'],check=True)
