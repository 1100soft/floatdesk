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

    fun consumeLoss(): Boolean = lost.also { lost = false }

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

internal class MonitorDecoder(
    private val socket: java.net.DatagramSocket,
    private val width: Int,
    private val height: Int,
    private val dumpFile: File? = null,
    private val onGap: () -> Unit = {},
) {
    private val running = AtomicBoolean(true)
    private val surface = AtomicReference<Surface?>(null)
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "floatdesk-decode").also { it.start() }
    }

    fun punch(host: InetAddress) {
        runCatching {
            socket.send(DatagramPacket(byteArrayOf(0), 1, host, 47000))
        }
    }

    fun attach(next: Surface) {
        surface.set(next)
    }

    fun detach() {
        surface.set(null)
    }

    fun stop() {
        running.set(false)
        socket.close()
        thread?.join(500)
    }

    private fun loop() {
        val depacketizer = H264Depacketizer()
        val buffer = ByteArray(2048)
        val packet = java.net.DatagramPacket(buffer, buffer.size)
        var codec: MediaCodec? = null
        var codecSurface: Surface? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var packets = 0
        var accessUnits = 0
        var rendered = 0
        var sawKeyframe = false
        val byteDump = dumpFile != null
        runCatching { socket.receiveBufferSize = 8 * 1024 * 1024 }
        socket.soTimeout = 50
        while (running.get()) {
            val accessUnit = try {
                packet.setLength(buffer.size)
                socket.receive(packet)
                packets += 1
                if (packets == 1 || packets % 120 == 0) {
                    Log.i(TAG, "video packets=$packets last=${packet.length} surface=${surface.get() != null}")
                }
                val unit = depacketizer.push(buffer, packet.length)
                if (depacketizer.consumeLoss()) onGap()
                unit
            } catch (_: java.net.SocketTimeoutException) {
                null
            } catch (_: java.net.SocketException) {
                break
            }
            if (accessUnit != null) {
                accessUnits += 1
                for (nal in splitAnnexB(accessUnit)) {
                    when (nalType(nal)) {
                        7 -> sps = nal
                        8 -> pps = nal
                    }
                }
                if (accessUnits == 1 || accessUnits % 60 == 0) {
                    Log.i(TAG, "access units=$accessUnits sps=${sps != null} pps=${pps != null}")
                }
            }
            val target = if (byteDump) null else surface.get()
            val haveParameters = sps != null && pps != null
            val surfaceReady = byteDump || target != null
            if (surfaceReady && haveParameters && (codec == null || (!byteDump && target != codecSurface))) {
                codec?.stop()
                codec?.release()
                sawKeyframe = false
                codec = startCodec(target, sps!!, pps!!)
                codecSurface = target
                Log.i(TAG, "decoder ${if (codec == null) "failed" else "started"} ${width}x$height")
            }
            val active = codec
            val keyframe = accessUnit != null && isKeyframe(accessUnit)
            if (active != null && keyframe) sawKeyframe = true
            if (active != null && accessUnit != null && sawKeyframe) {
                // A live picture should show up as soon as it is decoded. Pacing the
                // timestamps holds frames back and makes the desktop feel late.
                queue(active, accessUnit, 0, keyframe)
                rendered += drain(active, rendered, byteDump)
            } else if (active != null) {
                rendered += drain(active, rendered, byteDump)
            }
        }
        codec?.stop()
        codec?.release()
    }

    private fun startCodec(target: Surface?, sps: ByteArray, pps: ByteArray): MediaCodec? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        // This decoder ignores csd-1, so SPS and PPS both have to live in csd-0.
        // The access units stay Annex-B, matching the stream ffmpeg already decodes.
        format.setByteBuffer("csd-0", ByteBuffer.wrap(annexBParameterSets(sps, pps)))
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

    private fun queue(codec: MediaCodec, accessUnit: ByteArray, ptsUs: Long, keyframe: Boolean) {
        try {
            // Dropping this frame would leave the headset showing an older picture
            // until the next keyframe. Wait for a free buffer instead.
            val index = codec.dequeueInputBuffer(50)
            if (index < 0) return
            val input = codec.getInputBuffer(index) ?: return
            input.clear()
            if (accessUnit.size > input.remaining()) {
                Log.w(TAG, "access unit ${accessUnit.size} exceeds input buffer ${input.remaining()}")
                return
            }
            input.put(accessUnit)
            val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(index, 0, accessUnit.size, ptsUs, flags)
        } catch (err: Exception) {
            Log.e(TAG, "queue failed", err)
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
