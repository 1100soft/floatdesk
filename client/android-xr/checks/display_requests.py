#!/usr/bin/env python3
"""Exercise production display retries/reconciliation using a deterministic handler clock."""
from pathlib import Path
import subprocess
import sys
import tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
protocol=(root/'net/Protocol.kt').read_text().split('\n',1)[1]
source=(root/'net/DeskConnection.kt').read_text()
method=source[source.index('    fun requestDisplays('):source.index('    fun requestFocus(')]
code=protocol+'''
class State<T>(var value:T)
class Handler {
 data class Task(val at:Long,val run:()->Unit)
 var now=0L;val tasks=mutableListOf<Task>()
 fun post(run:()->Unit){tasks.add(Task(now,run))}
 fun postDelayed(run:()->Unit,delay:Long){tasks.add(Task(now+delay,run))}
 fun advance(until:Long) {
  while(true) {
   val task=tasks.filter {it.at<=until}.minByOrNull {it.at} ?: break
   tasks.remove(task);now=task.at;task.run()
  }
  now=until
 }
}
internal class Connection {
 val main=Handler();val outgoing:Handler?=main
 val monitorPending=java.util.concurrent.atomic.AtomicInteger()
 val monitorSequence=java.util.concurrent.atomic.AtomicInteger()
 val displayBusy=State(false)
 val displayState=State(Packet.MonitorStatus(0,true,0,0,""))
 val sent=mutableListOf<ByteArray>()
 fun send(packet:ByteArray){sent.add(packet)}
 fun monitorCommand(id:Int,action:Int,index:Int,width:Int,height:Int)=Protocol.monitorCommand(id,action,index,width,height)
'''+method+'''
}
fun main() {
 val c=Connection()
 c.requestDisplays(1);c.main.advance(24000)
 check(c.displayBusy.value && c.sent.size==9)
 fun id(p:ByteArray)=java.nio.ByteBuffer.wrap(p,16,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
 check(c.sent.all {it[20].toInt()==1 && id(it)==1}) // Mutations retain one operation identity.
 c.main.advance(25000)
 check(c.monitorPending.get()==2 && c.displayBusy.value)
 check(c.sent.last()[20].toInt()==0 && id(c.sent.last())==2) // Uncertain state reconciles read-only.
 check(!c.monitorPending.compareAndSet(1,0)) // Late mutation reply cannot cancel the query.
 c.main.advance(49000)
 check(c.sent.count {it[20].toInt()==1}==9)
 c.monitorPending.set(0)
 val count=c.sent.size;c.main.advance(60000)
 check(c.sent.size==count) // Completed/disconnected requests do not keep retrying.
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-display-requests-') as temp:
 file=Path(temp)/'DisplayRequests.kt';file.write_text(code)
 subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
 subprocess.run(['java','-cp',temp+':'+str(stdlib),'DisplayRequestsKt'],check=True)
print('Passed: same-ID retries, automatic read-only reconciliation, late-reply isolation and cancellation')
