#!/usr/bin/env python3
"""Exercise production outgoing methods with real UDP and a UI-thread-rejecting socket."""
from pathlib import Path
import subprocess
import sys
import tempfile

folder = Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client/net'
protocol = (folder/'Protocol.kt').read_text().split('\n',1)[1]
connection = (folder/'DeskConnection.kt').read_text()
methods = connection[connection.index('    fun movePointer('):]
stub = '''
private val uiThread = Thread.currentThread()
private object Looper {
    private val main = Any()
    fun getMainLooper() = main
    fun myLooper(): Any? = if(Thread.currentThread() === uiThread) main else null
}
private object Log { fun w(tag: String,message: String,error: Throwable) { throw error } }
private class Handler {
    val gate = java.util.concurrent.CountDownLatch(1)
    val pool = java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>())
    fun post(block: ()->Unit) { pool.execute { gate.await(); block() } }
}
private class CheckedSocket: java.net.DatagramSocket() {
    override fun send(packet: java.net.DatagramPacket) {
        check(Thread.currentThread() !== uiThread) { "Network send attempted on the UI thread" }
        super.send(packet)
    }
}
private class Box<T>(val value: T)
private class Sender(port: Int) {
    val outgoing = Handler()
    val monitors = Box(listOf(MonitorHello(0,2560,1600,48000,1,"test")))
    val control: java.net.DatagramSocket = CheckedSocket()
    val pendingRemote = java.net.InetSocketAddress("127.0.0.1",port)
    val pointerSeq = java.util.concurrent.atomic.AtomicInteger()
    val pendingPointer = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    val pointerFlushPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    var running = true
    fun requestKeyframe() { send(Protocol.keyframeRequest(0)) }
'''
checks = '''
fun main() {
    java.net.DatagramSocket(0).use { receiver ->
        receiver.soTimeout=3000
        val sender=Sender(receiver.localPort)
        try {
            // Hold the worker so a burst must coalesce instead of queuing stale positions.
            repeat(1000) { sender.movePointer(0,it/1000f,.5f) }
            check(sender.outgoing.pool.queue.size <= 1)
            sender.requestKeyframe()
            sender.outgoing.gate.countDown()
            val kinds=mutableSetOf<Int>()
            repeat(2) {
                val datagram=java.net.DatagramPacket(ByteArray(256),256)
                receiver.receive(datagram)
                val bytes=datagram.data
                kinds.add(bytes[5].toInt())
                if(bytes[5].toInt()==8) {
                    val payload=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    check(payload.getInt(25)==1000)
                    check(kotlin.math.abs(payload.getFloat(17)-.999f)<.00001f)
                }
            }
            check(kinds==setOf(4,8))
        } finally {
            sender.running=false
            sender.control.close()
            sender.outgoing.pool.shutdownNow()
        }
    }
    println("Passed: real UDP pointer/keyframe dispatch off UI thread, bounded coalescing, latest target delivery")
}
'''
lib = Path(sys.argv[1])
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-dispatch-') as temp:
    source = Path(temp)/'PointerDispatchCheck.kt'
    source.write_text('import java.net.DatagramPacket\n'+protocol+stub+methods+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(source)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'PointerDispatchCheckKt'],check=True)
