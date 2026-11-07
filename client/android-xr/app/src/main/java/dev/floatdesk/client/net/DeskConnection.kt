package dev.floatdesk.client.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.mutableStateOf
import dev.floatdesk.client.media.MonitorDecoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class DeskConnection(
    private val dumpFile: java.io.File? = null,
) {
    val status = mutableStateOf("Looking for your computer…")
    val monitors = mutableStateOf<List<MonitorHello>>(emptyList())
    val cursors = mutableStateOf<Map<Int, CursorView>>(emptyMap())

    private val main = Handler(Looper.getMainLooper())
    private val assembler = CursorAssembler()
    private val decoders = HashMap<Int, MonitorDecoder>()
    private val pendingCursors = ConcurrentHashMap<Int, CursorView>()
    private val cursorSeq = AtomicInteger()
    private val cursorFlushPosted = AtomicBoolean(false)
    private var lastGapAsk = 0L
    private var control: DatagramSocket? = null
    private var beacon: DatagramSocket? = null
    private var videos: List<DatagramSocket> = emptyList()
    private var thread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile private var pendingRemote: InetSocketAddress? = null

    fun start(context: Context) {
        if (running) return
        val opened = ArrayList<DatagramSocket>()
        try {
            for (port in 48000 until 48006) {
                opened += DatagramSocket(port).apply { receiveBufferSize = 8 * 1024 * 1024 }
            }
            control = DatagramSocket().apply { broadcast = true }
            beacon = DatagramSocket(47001).apply {
                broadcast = true
                soTimeout = 200
            }
        } catch (err: Exception) {
            opened.forEach { it.close() }
            control?.close()
            beacon?.close()
            status.value = "Could not listen for the computer: ${err.message}"
            return
        }
        videos = opened
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("floatdesk")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        running = true
        status.value = "Looking for your computer…"
        thread = Thread({ controlLoop() }, "floatdesk-control").also { it.start() }
    }

    fun disconnect() {
        running = false
        control?.close()
        beacon?.close()
        decoders.values.forEach { it.stop() }
        decoders.clear()
        videos.forEach { runCatching { it.close() } }
        videos = emptyList()
        thread?.join(500)
        thread = null
        control = null
        beacon = null
        pendingRemote = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        main.post {
            monitors.value = emptyList()
            cursors.value = emptyMap()
            status.value = "Disconnected"
        }
    }

    fun attachSurface(index: Int, surface: Surface) {
        decoders[index]?.attach(surface)
        send(Protocol.keyframeRequest(index))
    }

    fun detachSurface(index: Int) {
        decoders[index]?.detach()
    }

    private fun controlLoop() {
        val socket = control ?: return
        socket.soTimeout = 200
        val buffer = ByteArray(65535)
        val incoming = DatagramPacket(buffer, buffer.size)
        val broadcast = InetSocketAddress("255.255.255.255", 47000)
        var lastHello = 0L
        while (running) {
            pollBeacon()
            val now = System.currentTimeMillis()
            if (pendingRemote == null && now - lastHello > 1000) {
                val hello = Protocol.clientHello("floatdesk", 48000)
                runCatching { socket.send(DatagramPacket(hello, hello.size, broadcast)) }
                lastHello = now
            } else if (pendingRemote != null && now - lastHello > 1000) {
                send(Protocol.clientHello("floatdesk", 48000))
                lastHello = now
            }
            try {
                incoming.setLength(buffer.size)
                socket.receive(incoming)
                val decoded = Protocol.decode(buffer.copyOf(incoming.length)) ?: continue
                when (decoded) {
                    is Packet.HostHello -> {
                        if (pendingRemote == null) {
                            pendingRemote = InetSocketAddress(incoming.address, 47000)
                        }
                        onHello(decoded, pendingRemote ?: InetSocketAddress(incoming.address, 47000))
                    }
                    is Packet.Cursor -> onCursor(decoded.fragment)
                    is Packet.Beacon -> Unit
                    is Packet.Ignored -> Unit
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (running) main.post { status.value = "Control socket closed" }
                break
            }
        }
    }

    private fun pollBeacon() {
        val socket = beacon ?: return
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            packet.setLength(buffer.size)
            socket.receive(packet)
        } catch (_: Exception) {
            return
        }
        val decoded = Protocol.decode(buffer.copyOf(packet.length)) ?: return
        if (decoded is Packet.Beacon && pendingRemote == null) {
            val remote = InetSocketAddress(packet.address, decoded.listenPort)
            pendingRemote = remote
            val label = decoded.name.ifEmpty { packet.address.hostAddress ?: "computer" }
            main.post { status.value = "Found $label" }
        }
    }

    @Synchronized
    private fun onHello(hello: Packet.HostHello, remote: InetSocketAddress) {
        if (decoders.isNotEmpty()) return
        for (monitor in hello.monitors) {
            val video = videos.getOrNull(monitor.index) ?: continue
            val decoder = MonitorDecoder(video, monitor.width, monitor.height, dumpFile, ::askKeyframe)
            decoder.punch(remote.address)
            decoder.start()
            decoders[monitor.index] = decoder
        }
        send(Protocol.keyframeRequest(0xff))
        main.post {
            monitors.value = hello.monitors
            status.value = "Connected to ${remote.hostString}:${remote.port}, ${hello.monitors.size} monitor(s)"
        }
    }

    private fun onCursor(fragment: CursorFragment) {
        val view = assembler.push(fragment)
        val previous = pendingCursors[fragment.monitor] ?: cursors.value[fragment.monitor]
        val merged = if (view.pixels == null && previous?.pixels != null) {
            view.copy(pixels = previous.pixels)
        } else {
            view
        }
        pendingCursors[fragment.monitor] = merged
        cursorSeq.incrementAndGet()
        if (cursorFlushPosted.compareAndSet(false, true)) {
            main.post { flushCursors() }
        }
    }

    private fun flushCursors() {
        val seen = cursorSeq.get()
        cursors.value = HashMap(pendingCursors)
        if (cursorSeq.get() != seen) {
            main.post { flushCursors() }
        } else {
            cursorFlushPosted.set(false)
            if (cursorSeq.get() != seen && cursorFlushPosted.compareAndSet(false, true)) {
                main.post { flushCursors() }
            }
        }
    }

    private fun askKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastGapAsk < 1000) return
        lastGapAsk = now
        send(Protocol.keyframeRequest(0xff))
    }

    private fun send(bytes: ByteArray) {
        val socket = control ?: return
        val address = pendingRemote ?: return
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
    }
}
