#!/usr/bin/env python3
"""Check the headset's user-facing connection problem reports."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'net/ConnectionHealth.kt').read_text().split('\n',1)[1]
source+=(root/'net/ThermalVideoGate.kt').read_text().split('\n',1)[1]
code=source+'''
fun main() {
 val wifi=HeadsetNetwork.WIFI
 check(connectionProblem(2_000,0,0,0,wifi)==null) // Startup grace.
 val never=connectionProblem(4_000,0,0,0,wifi)!!
 check(never.startsWith("Can't reach the computer (4 s).") && "no packets are arriving" in never)
 check(connectionProblem(10_000,0,9_000,0,wifi)==null) // Host heartbeat is 1 s.
 val lost=connectionProblem(15_000,0,9_000,0,wifi)!!
 check(lost.startsWith("No reply from the computer for 6 s.") && "Reconnecting" in lost)
 check("visible on the network but is not answering" in connectionProblem(15_000,0,9_000,14_000,wifi)!!)
 check("no network connection" in connectionProblem(15_000,0,9_000,14_000,HeadsetNetwork.NONE)!!)
 check("not on Wi-Fi" in connectionProblem(15_000,0,0,0,HeadsetNetwork.OTHER)!!)
 check(connectionProblem(21_000,20_000,9_000,0,wifi)==null) // A restart resets the clock.
 val cooling=ThermalVideoGate()
 check(!cooling.update(3,0))
 check(cooling.update(4,1000))
 check(cooling.update(2,2000) && cooling.update(2,2_999))
 check(cooling.update(3,3_000)) // A reheating sample restarts the cooldown.
 check(cooling.update(2,4_000) && cooling.update(2,5_999))
 check(!cooling.update(2,6_000))
 println("PASS thermal critical pause and sustained recovery")
 println("PASS connection problem: startup grace, never reached, lost after replies, beacon-only host, no network, non-Wi-Fi, restart reset")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-health-') as temp:
 p=Path(temp)/'HealthCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'HealthCheckKt'],check=True)
