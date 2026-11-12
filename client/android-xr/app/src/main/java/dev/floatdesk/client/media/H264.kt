package dev.floatdesk.client.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Floatdesk"

internal class H264Depacketizer {
    private var expectedSeq: Int? = null
    private val nals = ArrayList<ByteArray>()
    private var fu = ByteArray(0)
    private var fuSize = 0
    private var fuOpen = false
    private var lost = false
    private var damaged = false

    fun incomplete():Boolean = fuOpen || nals.isNotEmpty()

    fun consumeLoss(): Boolean = lost.also { lost = false }

    fun restart() {
        reset()
        expectedSeq = null
        damaged = false
        lost = false
    }

    fun push(packet: ByteArray, length: Int): ByteArray? {
        if (length < 12) return null
        // Byte.toInt() sign-extends. 0x80 is a normal RTP version-2 header, and an
        // arithmetic shift of that sign-extended value is not 2, so every packet was dropped.
        val first = packet[0].toInt() and 0xFF
        if (first ushr 6 != 2) return null
        if (first and 0x3F != 0) return null
        val marker = packet[1].toInt() and 0x80 != 0
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val expected = expectedSeq
        expectedSeq = (seq + 1) and 0xFFFF
        if (expected != null && seq != expected) {
            reset()
            lost = true
            damaged = true
        }
        if (damaged) {
            if (marker) damaged = false
            return null
        }
        if (length <= 12) return null
        val nalType = packet[12].toInt() and 0x1F
        if (nalType == 28) {
            return pushFu(packet, length, marker)
        }
        if (nalType !in 1..23) return null
        if (fuOpen) {
            reset()
            return null
        }
        nals += packet.copyOfRange(12, length)
        return finish(marker)
    }

    private fun pushFu(packet: ByteArray, length: Int, marker: Boolean): ByteArray? {
        if (length < 15) return null
        val indicator = packet[12].toInt() and 0xFF
        val header = packet[13].toInt() and 0xFF
        val start = header and 0x80 != 0
        val end = header and 0x40 != 0
        if (start) {
            fuSize = 0
            appendFuByte((indicator and 0xE0) or (header and 0x1F))
            fuOpen = true
        } else if (!fuOpen) {
            reset()
            return null
        }
        appendFu(packet, 14, length)
        if (!end) return null
        val nal = fu.copyOf(fuSize)
        fuSize = 0
        fuOpen = false
        nals += nal
        return finish(marker)
    }

    private fun appendFuByte(value: Int) {
        ensureFu(1)
        fu[fuSize++] = value.toByte()
    }

    private fun appendFu(packet: ByteArray, from: Int, until: Int) {
        val count = until - from
        if (count <= 0) return
        ensureFu(count)
        packet.copyInto(fu, fuSize, from, until)
        fuSize += count
    }

    private fun ensureFu(extra: Int) {
        if (fuSize + extra <= fu.size) return
        var cap = if (fu.isEmpty()) 4096 else fu.size
        while (cap < fuSize + extra) cap *= 2
        fu = fu.copyOf(cap)
    }

    private fun finish(marker: Boolean): ByteArray? {
        if (!marker) return null
        var size = 0
        for (nal in nals) size += 4 + nal.size
        val out = ByteArray(size)
        var offset = 0
        for (nal in nals) {
            out[offset] = 0
            out[offset + 1] = 0
            out[offset + 2] = 0
            out[offset + 3] = 1
            nal.copyInto(out, offset + 4)
            offset += 4 + nal.size
        }
        nals.clear()
        return out
    }

    private fun reset() {
        nals.clear()
        fuSize = 0
        fuOpen = false
    }
}

/**
 * Wi-Fi delivers RTP a packet or two out of order. Treating that as a lost
 * frame makes the decoder drop everything until the next keyframe, which
 * freezes the picture and the pointer with it.
 */
private class RtpReorder {
    private val held = HashMap<Int, ByteArray>()
    private var nextSeq = -1
    private var holeSince = 0L
    private var source: Int? = null
    private val retiredSources=linkedSetOf<Int>()
    private var lastPacketMs = 0L
    private var restarted = false
    private var lastRepairMs=0L

    fun consumeRestart(): Boolean = restarted.also { restarted = false }

    fun push(packet: ByteArray, length: Int, nowMs: Long): List<ByteArray> {
        if (length < 12) return emptyList()
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val ssrc = (8..11).fold(0) { value, index -> (value shl 8) or (packet[index].toInt() and 0xFF) }
        if(ssrc in retiredSources) return emptyList()
        val behindAfterIdle = nextSeq >= 0 && ((seq - nextSeq) and 0xFFFF) > 0x8000 && nowMs - lastPacketMs >= 1000
        if (source != ssrc || behindAfterIdle) {
            if (source != null) {
                restarted = true
                if(source!=ssrc) {retiredSources.add(source!!);if(retiredSources.size>4) retiredSources.remove(retiredSources.first())}
            }
            source = ssrc
            held.clear()
            nextSeq = seq
            holeSince = 0L
        }
        lastPacketMs = nowMs
        if (nextSeq < 0) nextSeq = seq
        val ahead = (seq - nextSeq) and 0xFFFF
        if (ahead > 0x8000) return flush(nowMs)
        if (ahead > 256) {
            held.clear()
            nextSeq = seq
            holeSince = 0L
        }
        held[seq] = packet.copyOf(length)
        return flush(nowMs)
    }

    fun expiredTail(nowMs:Long)=held.isEmpty() && nextSeq>=0 && nowMs-lastPacketMs>200

    fun repair(nowMs:Long,incomplete:Boolean):Pair<Int,Int>? {
        val id=source ?: return null
        if(nextSeq<0 || nowMs-lastRepairMs<8) return null
        val gap=held.isNotEmpty() && !held.containsKey(nextSeq)
        val tail=incomplete && held.isEmpty() && nowMs-lastPacketMs in 20..180
        if(!gap && !tail) return null
        lastRepairMs=nowMs
        return id to nextSeq
    }

    fun flush(nowMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            val ready = held.remove(nextSeq)
            if (ready != null) {
                out.add(ready)
                nextSeq = (nextSeq + 1) and 0xFFFF
                holeSince = 0L
                continue
            }
            if (held.isEmpty() || nextSeq < 0) break
            if (holeSince == 0L) holeSince = nowMs
            if (nowMs - holeSince < 60 && held.size < 256) break
            nextSeq = (nextSeq + 1) and 0xFFFF
            holeSince = nowMs
        }
        return out
    }
}

internal class MonitorDecoder(
    private val socket: java.net.DatagramSocket,
    private val width: Int,
    private val height: Int,
    private val dumpFile: File? = null,
    private val onGap: () -> Unit = {},
    private val onFrame: () -> Unit = {},
    private val onNack:(Int,Int)->Unit={_,_->},
) {
    private val renderingActive=AtomicBoolean(true)
    private val running = AtomicBoolean(true)
    private val surface = AtomicReference<Surface?>(null)
    private val pending = ArrayBlockingQueue<ByteArray>(4)
    private val sps = AtomicReference<ByteArray?>(null)
    private val pps = AtomicReference<ByteArray?>(null)
    private val awaitingKey = AtomicBoolean(true)
    @Volatile private var compositor: VideoCompositor? = null
    private val aim = AtomicReference(VideoAim())
    private val presentation=AtomicReference(ScreenPresentation())
    private var receiveThread: Thread? = null
    private var decodeThread: Thread? = null

    fun start() {
        receiveThread = Thread({ receiveLoop() }, "floatdesk-net").also { it.start() }
        decodeThread = Thread({ decodeLoop() }, "floatdesk-decode").also { it.start() }
    }

    fun punch(host: InetAddress) {
        runCatching {
            socket.send(DatagramPacket(byteArrayOf(0), 1, host, 47000))
        }
    }

    fun setRenderingActive(active: Boolean) {
        if(renderingActive.getAndSet(active)==active) return
        pending.clear()
        if(active) recover("visible stream resumed") else awaitingKey.set(true)
    }

    fun presentation(value:ScreenPresentation) {presentation.set(value);compositor?.presentation(value)}

    fun overlay(value: VideoAim) { aim.set(value); compositor?.overlay(value) }

    fun attach(next: Surface) {
        surface.set(next)
    }

    fun detach() {
        surface.set(null)
    }

    fun stop() {
        running.set(false)
        socket.close()
        receiveThread?.join(500)
        decodeThread?.join(500)
    }

    private fun receiveLoop() {
        val depacketizer = H264Depacketizer()
        val reorder = RtpReorder()
        val buffer = ByteArray(2048)
        val packet = java.net.DatagramPacket(buffer, buffer.size)
        var packets = 0
        runCatching { socket.receiveBufferSize = 8 * 1024 * 1024 }
        socket.soTimeout = 20
        while (running.get()) {
            val ready = try {
                packet.setLength(buffer.size)
                socket.receive(packet)
                packets += 1
                if (packets == 1 || packets % 600 == 0) {
                    Log.i(TAG, "video packets=$packets last=${packet.length} surface=${surface.get() != null}")
                }
                reorder.push(buffer, packet.length, System.currentTimeMillis())
            } catch (_: java.net.SocketTimeoutException) {
                reorder.flush(System.currentTimeMillis())
            } catch (_: java.net.SocketException) {
                break
            }
            if(!renderingActive.get()) { depacketizer.restart();reorder.consumeRestart();continue }
            if (reorder.consumeRestart()) {
                depacketizer.restart()
                recover("RTP source restarted")
            }
            for (copy in ready) {
                val accessUnit = depacketizer.push(copy, copy.size)
                if (depacketizer.consumeLoss()) recover("RTP packet loss")
                if (accessUnit == null) continue
                for (nal in splitAnnexB(accessUnit)) {
                    when (nalType(nal)) {
                        7 -> sps.set(nal)
                        8 -> pps.set(nal)
                    }
                }
                val keyframe = isKeyframe(accessUnit)
                if (awaitingKey.get() && !keyframe) {
                    onGap()
                    continue
                }
                if (keyframe) awaitingKey.set(false)
                if (!pending.offer(accessUnit)) {
                    recover("compressed queue overflow")
                    if (keyframe) {
                        awaitingKey.set(false)
                        pending.offer(accessUnit)
                    }
                }
            }
            val now=System.currentTimeMillis()
            reorder.repair(now,depacketizer.incomplete())?.let {(source,sequence)->onNack(source,sequence)}
            if(depacketizer.incomplete() && reorder.expiredTail(now)) {
                depacketizer.restart();recover("Video repair timed out")
            }
        }
    }

    private fun recover(reason: String) {
        if (!awaitingKey.getAndSet(true)) Log.w(TAG, "requesting IDR: $reason")
        pending.clear()
        onGap()
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var codecSurface: Surface? = null
        var rendered = 0
        var sawKeyframe = false
        val byteDump = dumpFile != null
        val renderer=if(byteDump) null else runCatching { VideoCompositor(width,height) }
            .onFailure { Log.e(TAG,"Live video compositor unavailable; using direct decode",it) }.getOrNull()
        compositor=renderer
        renderer?.overlay(aim.get())
        renderer?.presentation(presentation.get())
        var displayedSurface: Surface?=null
        while (running.get()) {
            if(!renderingActive.get()) {
                codec?.stop();codec?.release();codec=null;codecSurface=null;sawKeyframe=false
                if(displayedSurface!=null) { renderer?.output(null);displayedSurface=null }
                // Transition methods clear the queue. Clearing it here could race
                // with a newly queued resume keyframe after the active flag changes.
                Thread.sleep(20)
                continue
            }
            val accessUnit = try {
                pending.poll(2, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if(accessUnit==null) {
                // Completed output must not wait for the next compressed frame.
                codec?.let { rendered += drain(it,rendered,byteDump) }
                continue
            }
            val display=surface.get()
            if(display !== displayedSurface) { renderer?.output(display); displayedSurface=display }
            val target = if (byteDump || display==null) null else renderer?.input ?: display
            val parameters = sps.get() to pps.get()
            val surfaceReady = byteDump || target != null
            if (surfaceReady && parameters.first != null && parameters.second != null &&
                (codec == null || (!byteDump && target != codecSurface))
            ) {
                codec?.stop()
                codec?.release()
                sawKeyframe = false
                codec = startCodec(target, parameters.first!!, parameters.second!!)
                codecSurface = target
                Log.i(TAG, "decoder ${if (codec == null) "failed" else "started"} ${width}x$height")
            }
            val active=codec
            if(active==null) {if(surfaceReady) onGap();continue}
            val keyframe = isKeyframe(accessUnit)
            if (keyframe) sawKeyframe = true
            if (!sawKeyframe) {
                onGap()
                continue
            }
            if (!queue(active, accessUnit, System.nanoTime() / 1000, keyframe, byteDump)) {
                sawKeyframe = false
                recover("decoder rejected access unit")
            }
            rendered += drain(active, rendered, byteDump)
        }
        codec?.stop()
        codec?.release()
        compositor=null
        renderer?.close()
    }

    private fun startCodec(target: Surface?, sps: ByteArray, pps: ByteArray): MediaCodec? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        // This decoder ignores csd-1, so SPS and PPS both have to live in csd-0.
        // The access units stay Annex-B, matching the stream ffmpeg already decodes.
        format.setByteBuffer("csd-0", ByteBuffer.wrap(annexBParameterSets(sps, pps)))
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        return try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, target, null, 0)
            codec.start()
            codec
        } catch (err: Exception) {
            Log.e(TAG, "decoder configure failed", err)
            null
        }
    }

    private fun queue(
        codec: MediaCodec, accessUnit: ByteArray, ptsUs: Long, keyframe: Boolean, byteDump: Boolean,
    ): Boolean {
        try {
            // Never silently discard a reference picture while the codec is busy.
            var index = -1
            while (running.get() && index < 0) {
                index = codec.dequeueInputBuffer(10_000)
                if (index < 0) drain(codec, 0, byteDump)
            }
            if (index < 0) return false
            val input = codec.getInputBuffer(index)
            if (input == null || accessUnit.size > input.capacity()) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                Log.w(TAG, "access unit ${accessUnit.size} exceeds decoder input capacity")
                return false
            }
            input.clear()
            input.put(accessUnit)
            val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(index, 0, accessUnit.size, ptsUs, flags)
            return true
        } catch (err: Exception) {
            Log.e(TAG, "queue failed", err)
            return false
        }
    }

    private fun drain(codec: MediaCodec, already: Int, byteDump: Boolean): Int {
        val info = MediaCodec.BufferInfo()
        var rendered = 0
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index >= 0 -> {
                        // A Surface buffer reports a dummy size. A byte-buffer frame is the
                        // whole YUV picture, which is what the dump path checks.
                        val realFrame = if (byteDump) info.size > width * height / 2 else info.size > 0
                        if (byteDump && realFrame) saveLuma(codec, index, info)
                        codec.releaseOutputBuffer(index, !byteDump && realFrame)
                        if (realFrame) {
                            onFrame()
                            rendered += 1
                            val total = already + rendered
                            if (total == 1 || total % 30 == 0) {
                                Log.i(TAG, "rendered=$total bytes=${info.size}")
                            }
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Log.i(TAG, "decoder output ${codec.outputFormat}")
                    else -> return rendered
                }
            }
        } catch (err: Exception) {
            Log.e(TAG, "drain failed", err)
            return rendered
        }
    }

    private fun saveLuma(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val file = dumpFile ?: return
        if (file.length() > 0L) return
        val buffer = codec.getOutputBuffer(index) ?: return
        val stride = try {
            codec.outputFormat.getInteger(MediaFormat.KEY_STRIDE)
        } catch (_: Exception) {
            width
        }.coerceAtLeast(width)
        buffer.position(info.offset)
        val luma = ByteArray(width * height)
        var sum = 0L
        var sumSq = 0L
        for (row in 0 until height) {
            if (buffer.remaining() < stride) break
            for (col in 0 until width) {
                val sample = buffer.get().toInt() and 0xFF
                luma[row * width + col] = sample.toByte()
                sum += sample
                sumSq += sample * sample
            }
            val skip = stride - width
            if (skip > 0 && buffer.remaining() >= skip) buffer.position(buffer.position() + skip)
        }
        val count = (width * height).toDouble()
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { stream ->
            stream.write("P5\n$width $height\n255\n".toByteArray())
            stream.write(luma)
        }
        Log.i(TAG, "saved ${file.absolutePath} bytes=${file.length()} lumaVariance=${"%.1f".format(variance)}")
    }
}

private fun isKeyframe(accessUnit: ByteArray): Boolean =
    splitAnnexB(accessUnit).any { nalType(it) == 5 }

private fun annexBParameterSets(sps: ByteArray, pps: ByteArray): ByteArray {
    val out = ByteArray(8 + sps.size + pps.size)
    out[3] = 1
    sps.copyInto(out, 4)
    val ppsAt = 4 + sps.size
    out[ppsAt + 3] = 1
    pps.copyInto(out, ppsAt + 4)
    return out
}

private fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) 0 else nal[0].toInt() and 0x1F

private fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val marks = ArrayList<Pair<Int, Int>>()
    var i = 0
    while (i + 3 <= data.size) {
        val len = startCodeLen(data, i)
        if (len > 0) {
            marks += i to (i + len)
            i += len
        } else {
            i += 1
        }
    }
    return marks.mapIndexed { index, mark ->
        val end = if (index + 1 < marks.size) marks[index + 1].first else data.size
        data.copyOfRange(mark.second, end)
    }
}

private fun startCodeLen(data: ByteArray, i: Int): Int {
    if (i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
        data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
    ) {
        return 4
    }
    if (i + 3 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
        return 3
    }
    return 0
}
