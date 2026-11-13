#!/usr/bin/env python3
"""Check display wire compatibility and stable layout reconciliation using production Kotlin."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
root = Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client'
protocol = (root / 'net/Protocol.kt').read_text().split('\n', 1)[1]
workspace = (root / 'workspace/Workspace.kt').read_text()
# Compile the pure data/layout portion without Android or JSON persistence.
data = workspace[workspace.index('internal data class Pose'):workspace.index('internal class WorkspaceStore')]
layout = workspace[workspace.index('internal fun deskLayout'):workspace.index('internal fun WorkspaceFile.monitorPlaced')]
reconcile = workspace[workspace.index('/** Reconcile stable source'):]
code = protocol + '\n' + (root/'PinchPolicy.kt').read_text().split('\n',1)[1] + '\n' + data + layout.replace('max(', 'kotlin.math.max(') + reconcile + '''
fun main(args:Array<String>) {
    java.io.File(args[0]).writeBytes(Protocol.monitorCommand(-1,1,0,1920,1080))
    val status=java.io.File(args[1]).readBytes()
    val decoded=Protocol.decode(status) as Packet.MonitorStatus
    check(decoded.requestId==7 && decoded.available==3 && decoded.owned==2 && decoded.ok && decoded.screens==listOf(1920 to 1080,1920 to 1080) && decoded.positions==listOf(0 to 0,1920 to 0) && decoded.names==listOf("eDP-1","Virtual-1-1") && decoded.roles==listOf(3,1) && decoded.primary==2)
    check(Protocol.decode(status.copyOf(status.size-1))==null)
    val first=WorkspaceFile().reconcileMonitors(listOf(Triple("existing-0",2560,1600)))
    val original=first.layouts["Desk"]!!.single().copy(pose=Pose(floatArrayOf(.2f,.3f,-2f)),physicalWidthM=1.1f)
    first.layouts["Desk"]!![0]=original
    val added=first.reconcileMonitors(listOf(Triple("existing-0",2560,1600),Triple("Virtual-1-1",1920,1080),Triple("Virtual-1-2",1920,1080)))
    check(added.layouts["Desk"]!![0].pose===original.pose)
    val second=added.layouts["Desk"]!![2]
    val removed=added.reconcileMonitors(listOf(Triple("existing-0",2560,1600),Triple("Virtual-1-2",1280,720)))
    val retained=removed.layouts["Desk"]!![1]
    check(retained.pose===second.pose && retained.logicalMonitorUuid==second.logicalMonitorUuid)
    check(retained.pixelWidth==1280 && retained.physicalWidthM==second.physicalWidthM)
    check(removed.reconcileMonitors(listOf(Triple("existing-0",2560,1600),Triple("Virtual-1-2",1280,720)))===removed)
}
'''
lib = Path(sys.argv[1])
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-monitors-') as temp:
    path = Path(temp)
    (path/'MonitorManagement.kt').write_text(code)
    payload = struct.pack('<IBBBB',7,1,3,2,5)+b'Ready'+struct.pack('<BHHHHhhhh',2,1920,1080,1920,1080,0,0,1920,0)+bytes([1,2,3,5])+b'eDP-1'+bytes([1,11])+b'Virtual-1-1'
    (path/'status.bin').write_bytes(b'FDC1'+struct.pack('<BBHII',1,12,0,len(payload),0)+payload)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(path/'MonitorManagement.kt')],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'MonitorManagementKt',str(path/'command.bin'),str(path/'status.bin')],check=True)
    assert (path/'command.bin').read_bytes()==b'FDC1'+struct.pack('<BBHII',1,11,0,10,0)+struct.pack('<IBBHH',0xffffffff,1,0,1920,1080)
print('Passed: monitor wire fixtures, malformed status, stable poses across add/remove/resize')
