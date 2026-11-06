package dev.floatdesk.client.net

import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.floatdesk.client.media.MonitorDecoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import androidx.compose.runtime.mutableStateOf

internal class DeskConnection {
    val status = mutableStateOf("Disconnected")
    val monitors = mutableStateOf<List<MonitorHello>>(emptyList())
    val cursors = mutableStateOf<Map<Int, CursorView>>(emptyMap())

    private val main = Handler(Looper.getMainLooper())
    private val assembler = CursorAssembler()
    private val decoders = HashMap<Int, MonitorDecoder>()
    private var control: DatagramSocket? = null
    private var videos: List<DatagramSocket> = emptyList()
    private var thread: Thread? = null
    @Volatile private var running = false

    fun connect(hostText: String) {
        disconnect()
        val remote = parse(hostText)
        if (remote == null) {
            status.value = "Enter the host as host or host:47000"
            return
        }
        val opened = ArrayList<DatagramSocket>()
        try {
            for (port in 48000 until 48006) opened += DatagramSocket(port)
            control = DatagramSocket()
        } catch (err: Exception) {
            opened.forEach { it.close() }
            status.value = "Could not bind UDP ports: ${err.message}"
            return
        }
        videos = opened
        pendingRemote = remote
        running = true
        status.value = "Waiting for $remote"
        thread = Thread({ controlLoop(remote) }, "floatdesk-control").also { it.start() }
    }

    fun disconnect() {
        running = false
        control?.close()
        decoders.values.forEach { it.stop() }
        decoders.clear()
        videos.forEach { runCatching { it.close() } }
        videos = emptyList()
        thread?.join(500)
        thread = null
        control = null
        main.post {
            monitors.value = emptyList()
            cursors.value = emptyMap()
            if (status.value.startsWith("Connected")) status.value = "Disconnected"
        }
    }

    fun attachSurface(index: Int, surface: Surface) {
        decoders[index]?.attach(surface)
        send(Protocol.keyframeRequest(index))
    }

    fun detachSurface(index: Int) {
        decoders[index]?.detach()
    }

    private fun controlLoop(remote: InetSocketAddress) {
        val socket = control ?: return
        socket.soTimeout = 200
        val buffer = ByteArray(65535)
        val incoming = DatagramPacket(buffer, buffer.size)
        var lastHello = 0L
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastHello > 1000) {
                send(Protocol.clientHello("floatdesk", 48000))
                lastHello = now
            }
            try {
                socket.receive(incoming)
                val decoded = Protocol.decode(buffer.copyOf(incoming.length)) ?: continue
                when (decoded) {
                    is Packet.HostHello -> onHello(decoded, remote)
                    is Packet.Cursor -> onCursor(decoded.fragment)
                    is Packet.Ignored -> Unit
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (running) main.post { status.value = "Control socket closed" }
                break
            }
        }
    }

    @Synchronized
    private fun onHello(hello: Packet.HostHello, remote: InetSocketAddress) {
        if (decoders.isNotEmpty()) return
        for (monitor in hello.monitors) {
            val video = videos.getOrNull(monitor.index) ?: continue
            val decoder = MonitorDecoder(video, monitor.width, monitor.height)
            decoder.start()
            decoders[monitor.index] = decoder
        }
        send(Protocol.keyframeRequest(0xff))
        main.post {
            monitors.value = hello.monitors
            status.value = "Connected to $remote, ${hello.monitors.size} monitor(s)"
        }
    }

    private fun onCursor(fragment: CursorFragment) {
        val view = assembler.push(fragment)
        main.post {
            val next = cursors.value.toMutableMap()
            val previous = next[fragment.monitor]
            next[fragment.monitor] = if (view.pixels == null && previous?.pixels != null) {
                view.copy(pixels = previous.pixels)
            } else {
                view
            }
            cursors.value = next
        }
    }

    private fun send(bytes: ByteArray) {
        val socket = control ?: return
        val address = pendingRemote ?: return
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
    }

    @Volatile private var pendingRemote: InetSocketAddress? = null

    private fun parse(text: String): InetSocketAddress? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 47000
        return runCatching { InetSocketAddress(host, port) }.getOrNull()?.takeIf { !it.isUnresolved }
    }
}
