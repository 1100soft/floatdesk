#!/usr/bin/env python3
"""Verify opaque geometry holes and viewer-relative restart persistence math."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'MonitorMesh.kt').read_text().split('\n',1)[1]+'\n'+(root/'VideoGeometry.kt').read_text().split('\n',1)[1]
w=(root/'workspace/Workspace.kt').read_text()
source+='\n'+w[w.index('internal data class Pose'):w.index('internal class WorkspaceStore')]
source+='\n'+(root/'workspace/WorkspacePlacement.kt').read_text().split('\n',1)[1]
code=source+'''
fun main() {
 val full=floatArrayOf(0f,0f,.5f,1f,.5f,0f,1f,1f)
 check(videoCoverageIsFull(full,0f))
 check(!videoCoverageIsFull(full,.1f))
 val l=floatArrayOf(0f,0f,.5f,1f,.5f,.5f,1f,1f)
 check(!videoCoverageIsFull(l,0f))
 for(sphere in listOf<MonitorMesh?>(null,MonitorMesh(2f,2f,floatArrayOf(0f,0f,2f)))) {
  val geometry=clippedVideoGeometry(2f,2f,sphere,l,0f)
  var area=0f
  for(i in geometry.indices.indices step 3) {
   val a=geometry.indices[i]*2;val b=geometry.indices[i+1]*2;val c=geometry.indices[i+2]*2;val uv=geometry.texCoords
   val u=(uv[a]+uv[b]+uv[c])/3;val v=1-(uv[a+1]+uv[b+1]+uv[c+1])/3
   check(u<=.50001f || v>=.49999f)
   area+=kotlin.math.abs((uv[b]-uv[a])*(uv[c+1]-uv[a+1])-(uv[c]-uv[a])*(uv[b+1]-uv[a+1]))*.5f
  }
  check(kotlin.math.abs(area-.75f)<.0001f)
  if(sphere!=null) check(clippedVideoGeometry(2f,2f,sphere,full,0f).positions===sphere.positions)
 }
 val original=MonitorPlacement("a","existing-0",Pose(floatArrayOf(1f,0f,-2f)),1.2f,.8f,0f,1920,1080,60)
 val old=WorkspaceFile(layouts=mutableMapOf("Desk" to mutableListOf(original)),viewerReference=Pose())
 val turned=Pose(floatArrayOf(10f,2f,5f),floatArrayOf(0f,.70710677f,0f,.70710677f))
 val restored=old.restoreViewer(turned).layouts["Desk"]!!.single()
 check(kotlin.math.abs(restored.pose.position[0]-8f)<.00001f)
 check(kotlin.math.abs(restored.pose.position[1]-2f)<.00001f)
 check(kotlin.math.abs(restored.pose.position[2]-4f)<.00001f)
 check(restored.physicalWidthM==original.physicalWidthM)
 val again=old.restoreViewer(turned).restoreViewer(Pose()).layouts["Desk"]!!.single()
 check((0..2).all {kotlin.math.abs(again.pose.position[it]-original.pose.position[it])<.00001f})
 check(old.copy(viewerReference=null).restoreViewer(turned).viewerReference===turned)
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-workspace-regressions-') as temp:
 p=Path(temp)/'WorkspaceChecks.kt';p.write_text(code)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-cp',temp+':'+str(stdlib),'WorkspaceChecksKt'],check=True)
print('PASS opaque video coverage, exact flat/curved mesh holes, full-coverage fast path, viewer-relative restart rotation/translation and retained dimensions')
