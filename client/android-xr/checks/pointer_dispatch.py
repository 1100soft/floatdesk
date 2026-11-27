#!/usr/bin/env python3
"""Exercise production outgoing methods with real UDP and a UI-thread-rejecting socket."""
from pathlib import Path
import subprocess
import sys
import tempfile
from production_sources import marker

folder = Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client/net'
protocol = (folder/'Protocol.kt').read_text().split('\n',1)[1]
connection = (folder/'DeskConnection.kt').read_text()
methods = connection[marker(connection,'    fun movePointer('):]
stub = '''
private val uiThread = Thread.currentThread()
private object Looper {
    private val main = Any()
    fun getMainLooper() = main
    fun myLooper(): Any? = if(Thread.currentThread() === uiThread) main else null
}
private object Log { fun w(tag: String,message: String,error: Throwable) { throw error }; fun i(tag: String,message: String) = 0 }
private class Handler {
    val gate = java.util.concurrent.CountDownLatch(1)
    val pool = java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>())
    fun postDelayed(block: ()->Unit,delay: Long) { post(block) }
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
    var lastAcceptedHello = 1L
    val inputEpoch = java.util.concurrent.atomic.AtomicInteger()
    var pointerDragging=false
    val clickSeq=java.util.concurrent.atomic.AtomicInteger()
    val pointerSeq = java.util.concurrent.atomic.AtomicInteger()
    val pendingPointer = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    val pointerFlushPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    val padSeq=java.util.concurrent.atomic.AtomicInteger()
    val pendingPad=java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    val padFlushPosted=java.util.concurrent.atomic.AtomicBoolean(false)
    var padFingers=0
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
    java.net.DatagramSocket(0).use { receiver ->
        receiver.soTimeout=3000
        val sender=Sender(receiver.localPort)
        try {
            sender.movePointer(0,.2f,.3f,true)
            repeat(1000) { sender.movePointer(0,it/1000f,.5f,true) }
            sender.movePointer(0,.8f,.7f,false)
            sender.outgoing.gate.countDown()
            for((index,down) in listOf(1,0).withIndex()) {
                val datagram=java.net.DatagramPacket(ByteArray(256),256)
                receiver.receive(datagram)
                check(datagram.data[29].toInt()==down)
                val u=java.nio.ByteBuffer.wrap(datagram.data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(17)
                check(kotlin.math.abs(u-(if(index==0) .2f else .8f))<.00001f)
            }
            sender.clickPointer(0,.1f,.2f,3)
            repeat(3) {
                val datagram=java.net.DatagramPacket(ByteArray(256),256)
                receiver.receive(datagram)
                check(datagram.data[5].toInt()==9 && datagram.data[25].toInt()==3)
                check(java.nio.ByteBuffer.wrap(datagram.data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(26)==1)
            }
        } finally {
            sender.running=false; sender.control.close(); sender.outgoing.pool.shutdownNow()
        }
    }
    java.net.DatagramSocket(0).use { receiver ->
        receiver.soTimeout=3000
        val sender=Sender(receiver.localPort)
        try {
            sender.touchpad(2,.6f,.5f)
            repeat(1000) { sender.touchpad(2,.6f,.6f) }
            sender.touchpad(0,.6f,.6f)
            sender.outgoing.gate.countDown()
            repeat(4) { n ->
                val packet=java.net.DatagramPacket(ByteArray(256),256)
                receiver.receive(packet)
                check(packet.data[5].toInt()==10)
                check(packet.data[16].toInt()==if(n==0) 2 else 0)
            }
        } finally { sender.running=false; sender.control.close(); sender.outgoing.pool.shutdownNow() }
    }
    java.net.DatagramSocket(0).use { receiver ->
        receiver.soTimeout=1000
        val sender=Sender(receiver.localPort)
        try {
            sender.clickPointer(0,.1f,.2f,3)
            sender.movePointer(0,.1f,.2f,true)
            sender.touchpad(2,.1f,.2f)
            sender.inputEpoch.incrementAndGet()
            sender.requestKeyframe()
            sender.outgoing.pool.shutdown()
            sender.outgoing.gate.countDown()
            check(sender.outgoing.pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))
            val packet=java.net.DatagramPacket(ByteArray(256),256)
            receiver.receive(packet)
            check(packet.data[5].toInt()==4) // Only the new-connection message reaches the host.
            receiver.soTimeout=100
            check(runCatching {receiver.receive(packet)}.exceptionOrNull() is java.net.SocketTimeoutException)
        } finally { sender.running=false; sender.control.close(); sender.outgoing.pool.shutdownNow() }
    }
    java.net.DatagramSocket(0).use { receiver ->
        receiver.soTimeout=3000
        val sender=Sender(receiver.localPort)
        try {
            sender.movePointer(0,.2f,.3f,true,"shell:panel")
            sender.movePointer(0,.8f,.7f,false,"shell:panel")
            sender.outgoing.gate.countDown()
            for((index,down) in listOf(true,false).withIndex()){
                val packet=java.net.DatagramPacket(ByteArray(256),256)
                receiver.receive(packet)
                check(packet.data[5].toInt()==15)
                val json=packet.data.decodeToString(16,packet.length)
                check(json=="""{"type":"handPointer","monitor":0,"u":${if(index==0)0.2f else 0.8f},"v":${if(index==0)0.3f else 0.7f},"sequence":${index+1},"dragging":$down,"key":"shell:panel"}""")
            }
        } finally { sender.running=false; sender.control.close(); sender.outgoing.pool.shutdownNow() }
    }
    println("Passed: real UDP pointer/keyframe dispatch off UI thread, bounded coalescing, latest target delivery, uncoalesced drag edges, atomic click retries, old input discarded on reconnect")
}
'''
lib = Path(sys.argv[1])
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-dispatch-') as temp:
    source = Path(temp)/'PointerDispatchCheck.kt'
    source.write_text('import java.net.DatagramPacket\n'+protocol+stub+methods+checks)
    # Android supplies JSONObject at runtime. Substitute the scalar values used by this check.
    json_stub = Path(temp)/'JSONObject.kt'
    json_stub.write_text(r"""
package org.json
class JSONObject {
    private val fields = linkedMapOf<String,Any>()
    fun put(key:String,value:Any):JSONObject { fields[key]=value;return this }
    override fun toString():String = fields.entries.joinToString(",", "{", "}") { (key,value) ->
        val encoded = if(value is String) "\"$value\"" else value.toString()
        "\"$key\":$encoded"
    }
}
""")
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(source),str(json_stub)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'PointerDispatchCheckKt'],check=True)
