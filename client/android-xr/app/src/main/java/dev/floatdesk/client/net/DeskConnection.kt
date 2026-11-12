package dev.floatdesk.client.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.mutableStateOf
import dev.floatdesk.client.media.MonitorDecoder
import dev.floatdesk.client.media.VideoAim
import dev.floatdesk.client.media.ScreenPresentation
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

    val displayState=mutableStateOf(Packet.MonitorStatus(0,true,0,0,"Checking available displays…"))
    val displayBusy=mutableStateOf(false)
    private val screenPresentation=AtomicReference(ScreenPresentation())
    private var screenSpacing=0f
    val reloading=mutableStateOf(false)
    private val videoPending=AtomicBoolean(false)
    private val decoderGeneration=AtomicInteger()
    private val monitorSequence=AtomicInteger()
    private val monitorPending=AtomicInteger()
    private var currentMonitors=emptyList<MonitorHello>()
    private val activeSurfaces=ConcurrentHashMap<Int,Surface>()
    private val main = Handler(Looper.getMainLooper())
    private val assembler = CursorAssembler()
    @Volatile private var renderingActive=true
    private val decoders = HashMap<Int, MonitorDecoder>()
    private val pendingCursors = ConcurrentHashMap<Int, CursorView>()
    private val cursorSeq = AtomicInteger()
    private val pointerSeq = AtomicInteger()
    private val clickSeq = AtomicInteger()
    private var pointerDragging = false
    private val padSeq=AtomicInteger()
    private val pendingPad=AtomicReference<ByteArray?>(null)
    private val padFlushPosted=AtomicBoolean(false)
    private var padFingers=0
    private val pendingPointer = AtomicReference<ByteArray?>(null)
    private val pointerFlushPosted = AtomicBoolean(false)
    @Volatile private var outgoing: Handler? = null
    private var outgoingThread: HandlerThread? = null
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
        outgoingThread = HandlerThread("floatdesk-input").also { it.start(); outgoing = Handler(it.looper) }
        status.value = "Looking for your computer…"
        thread = Thread({ controlLoop() }, "floatdesk-control").also { it.start() }
    }

    fun disconnect() {
        running = false
        control?.close()
        beacon?.close()
        pendingPointer.set(null)
        pointerDragging = false
        pendingPad.set(null); padFingers=0; padFlushPosted.set(false)
        outgoing = null
        outgoingThread?.quitSafely()
        outgoingThread?.join(500)
        outgoingThread = null
        pointerFlushPosted.set(false)
        decoders.values.forEach { it.stop() }
        decoders.clear()
        currentMonitors=emptyList()
        monitorPending.set(0)
        videoPending.set(false);decoderGeneration.incrementAndGet()
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
            displayBusy.value = false
            reloading.value=false
            status.value = "Disconnected"
        }
    }

    @Synchronized
    fun setRenderingActive(active: Boolean) {
        renderingActive=active
        decoders.values.forEach { it.setRenderingActive(active) }
    }

    fun requestDisplays(action:Int=0,index:Int=0,width:Int=1920,height:Int=1080) {
        val handler=outgoing ?: return
        if(!monitorPending.compareAndSet(0,-1)) return
        val id=monitorSequence.incrementAndGet()
        monitorPending.set(id);displayBusy.value=true
        val packet=Protocol.monitorCommand(id,action,index,width,height)
        handler.post { send(packet) }
        for(delay in listOf(300L,900L,1800L,3000L,5000L,8000L,12000L,18000L)) handler.postDelayed({ if(monitorPending.get()==id) send(packet) },delay)
        main.postDelayed({ if(monitorPending.get()==id) {
            displayState.value=displayState.value.copy(ok=true,message=if(action==0) "Checking screen state…" else "Applying screen changes…")
        } },3000)
        main.postDelayed({ if(monitorPending.compareAndSet(id,0)) {
            // An uncertain mutation is never reissued under a new ID. Reconcile
            // with a read-only query automatically when the host responds again.
            displayState.value=displayState.value.copy(ok=true,message="Waiting for the host; checking screen state…")
            requestDisplays()
        } },25000)

    }

    @Synchronized
    fun attachSurface(index: Int, surface: Surface) {
        activeSurfaces[index]=surface
        decoders[index]?.attach(surface)
        send(Protocol.keyframeRequest(index))
    }

    @Synchronized
    fun configurePresentation(spacing:Float) {
        screenSpacing=spacing
        val state=displayState.value
        val value=if(currentMonitors.size>1) ScreenPresentation() else ScreenPresentation.from(state.screens,state.positions,spacing)
        screenPresentation.set(value);decoders.values.forEach {it.presentation(value)}
    }
    fun desktopPoint(monitor:Int,u:Float,v:Float):Pair<Float,Float>? =
        if(monitors.value.size==1 && monitor==0) screenPresentation.get().desktopPoint(u,v) else u to v

    fun aimOverlay(index: Int,value: VideoAim) { decoders[index]?.overlay(value) }

    @Synchronized
    fun detachSurface(index: Int,expected:Surface?=null) {
        if(expected==null) activeSurfaces.remove(index) else if(!activeSurfaces.remove(index,expected)) return
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
                        if(pendingRemote!=null && incoming.address!=pendingRemote?.address) continue
                        if (pendingRemote == null) {
                            pendingRemote = InetSocketAddress(incoming.address, 47000)
                        }
                        onHello(decoded, pendingRemote ?: InetSocketAddress(incoming.address, 47000))
                    }
                    is Packet.StreamState -> {if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port) main.post {reloading.value=decoded.reloading || videoPending.get()}}
                    is Packet.MonitorStatus -> {
                        if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port) main.post {
                            if((decoded.requestId==0 && monitorPending.get()==0) || (decoded.requestId!=0 && monitorPending.compareAndSet(decoded.requestId,0))) {displayBusy.value=false;displayState.value=decoded;configurePresentation(screenSpacing)}
                        }
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
        if (hello.monitors==currentMonitors && decoders.isNotEmpty()) {
            // A dropped one-shot progress notification must not leave an overlay
            // behind after the host has returned to its normal heartbeat.
            if(!videoPending.get() && monitorPending.get()==0) main.post {reloading.value=false}
            return
        }
        main.post {reloading.value=true}
        val sameFormat=hello.monitors.map {it.copy(ssrc=0)}==currentMonitors.map {it.copy(ssrc=0)} && decoders.isNotEmpty()
        if(sameFormat) {
            currentMonitors=hello.monitors;videoPending.set(true)
            main.post {monitors.value=hello.monitors}
            send(Protocol.keyframeRequest(0xff))
            return
        }
        val generation=decoderGeneration.incrementAndGet()
        decoders.values.forEach { it.stop() };decoders.clear()
        videoPending.set(true)
        videos=videos.mapIndexed { index,socket -> if(socket.isClosed) DatagramSocket(48000+index).apply {receiveBufferSize=8*1024*1024} else socket }
        currentMonitors=hello.monitors
        for (monitor in hello.monitors) {
            val video = videos.getOrNull(monitor.index) ?: continue
            val decoder = MonitorDecoder(video, monitor.width, monitor.height, dumpFile, ::askKeyframe,onFrame={
                if(decoderGeneration.get()==generation && videoPending.compareAndSet(true,false)) main.post {reloading.value=false}
            },onNack={source,sequence->send(Protocol.videoNack(monitor.index,source,sequence))})
            decoder.presentation(if(hello.monitors.size>1) ScreenPresentation() else screenPresentation.get())
            activeSurfaces[monitor.index]?.let { decoder.attach(it) }
            decoder.punch(remote.address)
            decoder.setRenderingActive(renderingActive)
            decoder.start()
            decoders[monitor.index] = decoder
        }
        send(Protocol.keyframeRequest(0xff))
        main.post {
            monitors.value = hello.monitors
            if(monitorPending.get()==0) requestDisplays()
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

    fun movePointer(monitor: Int,x: Float,y: Float,dragging: Boolean = false) {
        if (monitors.value.none { it.index == monitor }) return
        val packet=Protocol.pointerMove(monitor,x,y,pointerSeq.incrementAndGet(),dragging)
        if(dragging != pointerDragging) {
            pointerDragging=dragging
            pendingPointer.set(null)
            // Button edges must not disappear into a newer coalesced motion frame.
            outgoing?.post { send(packet) }
        } else {
            pendingPointer.set(packet)
            schedulePointerFlush()
        }
    }

    fun clickPointer(monitor: Int,x: Float,y: Float,button: Int) {
        if (monitors.value.none { it.index == monitor }) return
        val sequence=clickSeq.incrementAndGet()
        val requestedAt=System.nanoTime()/1_000_000
        val packet=Protocol.pointerClick(monitor,x,y,button,sequence)
        val handler=outgoing ?: return
        // Atomic click commands are deduplicated by the host. Retries cannot become extra clicks.
        handler.post {
            if(button==3) Log.i("Floatdesk","right click sent sequence=$sequence queueMs=${System.nanoTime()/1_000_000-requestedAt}")
            send(packet)
        }
        handler.postDelayed({ send(packet) },20)
        handler.postDelayed({ send(packet) },60)
    }

    fun touchpad(fingers: Int,x: Float,y: Float) {
        val packet=Protocol.touchpad(fingers,x,y,padSeq.incrementAndGet())
        val handler=outgoing ?: return
        if(fingers!=padFingers) {
            padFingers=fingers; pendingPad.set(null)
            handler.post { send(packet) }
            if(fingers==0) { handler.postDelayed({ send(packet) },20); handler.postDelayed({ send(packet) },60) }
        } else {
            pendingPad.set(packet)
            schedulePadFlush()
        }
    }

    private fun schedulePadFlush() {
        val handler=outgoing ?: return
        if(padFlushPosted.compareAndSet(false,true)) handler.post {
            if(running) pendingPad.getAndSet(null)?.let { send(it) }
            padFlushPosted.set(false)
            if(pendingPad.get()!=null) schedulePadFlush()
        }
    }

    private fun schedulePointerFlush() {
        val handler = outgoing ?: return
        if (pointerFlushPosted.compareAndSet(false,true)) handler.post { flushPointer() }
    }

    private fun flushPointer() {
        if(running) pendingPointer.getAndSet(null)?.let { send(it) }
        pointerFlushPosted.set(false)
        if (pendingPointer.get() != null) schedulePointerFlush()
    }

    private fun send(bytes: ByteArray) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            outgoing?.post { send(bytes) }
            return
        }
        val socket = control ?: return
        val address = pendingRemote ?: return
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
            .onFailure { if (running) Log.w("Floatdesk","Control datagram send failed",it) }
    }
}
