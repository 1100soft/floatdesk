#!/usr/bin/env python3
"""The scene reads only required joints, without materializing the SDK map entries."""
from pathlib import Path
import re,subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
text=(root/'HandJointSnapshot.kt').read_text()
required=set(re.findall(r'HandJointType\.([A-Z_]+)',(root/'WorkspaceRuntime.kt').read_text()+(root/'WorkspaceInteraction.kt').read_text()))
selected=set(re.findall(r'HandJointType\.([A-Z_]+)',text))
assert required<=selected,(required-selected)
source='\n'.join(l for l in text.splitlines() if not l.startswith(('package ','import ')))
code='''
internal enum class HandJointType { PALM,WRIST,THUMB_TIP,INDEX_PROXIMAL,INDEX_TIP,MIDDLE_PROXIMAL,MIDDLE_TIP,RING_TIP,LITTLE_PROXIMAL,LITTLE_TIP,UNUSED }
internal enum class TrackingState { TRACKING,PAUSED }
internal data class Pose(val id:Int)
internal class Hand { class State(val trackingState:TrackingState,val handJoints:Map<HandJointType,Pose>) }
'''+source+'''
fun main(){
 val reads=mutableListOf<HandJointType>()
 val source=object:AbstractMap<HandJointType,Pose>(){
   override val entries:Set<Map.Entry<HandJointType,Pose>> get()=error("SDK entries must never be enumerated")
   override fun get(key:HandJointType):Pose?{reads.add(key);return Pose(key.ordinal)}
 }
 val result=snapshotHandJoints(Hand.State(TrackingState.TRACKING,source))
 check(reads==sceneHandJoints && result.keys==sceneHandJoints.toSet() && reads.size==10)
 for((key,pose) in result)check(pose.id==key.ordinal)
 reads.clear();check(snapshotHandJoints(Hand.State(TrackingState.PAUSED,source)).isEmpty() && reads.isEmpty())
 println("PASS required-joint coverage, direct scalar-map reads, exact snapshot values and paused-state idleness")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-hand-snapshot-') as tmp:
 f=Path(tmp)/'Snapshot.kt';f.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',tmp,str(f)],check=True)
 subprocess.run(['java','-cp',tmp+':'+str(stdlib),'SnapshotKt'],check=True)
