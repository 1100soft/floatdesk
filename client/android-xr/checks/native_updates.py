#!/usr/bin/env python3
"""App-owned node submissions suppress no-ops, and invalidate around system movement."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source='\n'.join(l for l in (root/'NativeNodeState.kt').read_text().splitlines() if not l.startswith(('package ','import ','@android.annotation')))
stubs='''
internal data class Vector3(val x:Float,val y:Float,val z:Float)
internal data class Pose(val x:Float=0f)
internal class Entity{
 var parentWrites=0;var poseWrites=0;var scaleWrites=0;var alphaWrites=0;var enabledWrites=0
 var parent:Entity?=null;set(v){field=v;parentWrites++}
 fun setPose(p:Pose){poseWrites++}
 fun setScale(v:Float){scaleWrites++}
 fun setScale(v:Vector3){scaleWrites++}
 fun setAlpha(v:Float){alphaWrites++}
 fun setEnabled(v:Boolean){enabledWrites++}
}
'''
main='''
fun main(){
 val e=Entity();val parent=Entity();val s=NativeNodeState(e)
 repeat(1000){s.parent(parent);s.pose(Pose());s.scale(1f);s.alpha(.5f);s.enabled(true)}
 check(e.parentWrites==1 && e.poseWrites==1 && e.scaleWrites==1 && e.alphaWrites==1 && e.enabledWrites==1)
 s.pose(Pose(1f));s.scale(Vector3(1f,2f,1f));s.enabled(false)
 check(e.poseWrites==2 && e.scaleWrites==2 && e.enabledWrites==2)
 s.invalidate();s.parent(parent);s.pose(Pose(1f));s.scale(Vector3(1f,2f,1f));s.alpha(.5f);s.enabled(false)
 check(e.parentWrites==2 && e.poseWrites==3 && e.scaleWrites==3 && e.alphaWrites==2 && e.enabledWrites==3)
 println("PASS identical node updates submit once; real changes and native-manipulation invalidation still submit")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-native-updates-') as tmp:
 f=Path(tmp)/'Updates.kt';f.write_text(stubs+source+main)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',tmp,str(f)],check=True)
 subprocess.run(['java','-cp',tmp+':'+str(stdlib),'UpdatesKt'],check=True)
