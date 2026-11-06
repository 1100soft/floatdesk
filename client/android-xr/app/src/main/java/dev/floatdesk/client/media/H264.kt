package dev.floatdesk.client.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class H264Depacketizer {
    private var expectedSeq: Int? = null
    private val nals = ArrayList<ByteArray>()
    private val fu = ArrayList<Byte>(256)
    private var fuOpen = false

    fun push(packet: ByteArray, length: Int): ByteArray? {
        if (length < 12 || packet[0].toInt() ushr 6 != 2) return null
        if (packet[0].toInt() and 0x3F != 0) return null
        val marker = packet[1].toInt() and 0x80 != 0
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val expected = expectedSeq
        expectedSeq = (seq + 1) and 0xFFFF
        if (expected != null && seq != expected) {
            reset()
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
            fu.clear()
            fu += ((indicator and 0xE0) or (header and 0x1F)).toByte()
            fuOpen = true
        } else if (!fuOpen) {
            reset()
            return null
        }
        for (i in 14 until length) fu += packet[i]
        if (!end) return null
        val nal = ByteArray(fu.size)
        for (i in fu.indices) nal[i] = fu[i]
        fu.clear()
        fuOpen = false
        nals += nal
        return finish(marker)
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
        fu.clear()
        fuOpen = false
    }
}

internal class MonitorDecoder(
    private val socket: java.net.DatagramSocket,
    private val width: Int,
    private val height: Int,
) {
    private val running = AtomicBoolean(true)
    private val surface = AtomicReference<Surface?>(null)
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "floatdesk-decode").also { it.start() }
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
        socket.soTimeout = 50
        while (running.get()) {
            val accessUnit = try {
                socket.receive(packet)
                depacketizer.push(buffer, packet.length)
            } catch (_: java.net.SocketTimeoutException) {
                null
            } catch (_: java.net.SocketException) {
                break
            }
            val target = surface.get()
            if (codec == null && target != null && accessUnit != null && hasParameterSets(accessUnit)) {
                codec = startCodec(target, accessUnit)
            }
            val active = codec
            if (active != null && accessUnit != null) {
                queue(active, accessUnit)
                drain(active)
            } else if (active != null) {
                drain(active)
            }
        }
        codec?.stop()
        codec?.release()
    }

    private fun startCodec(target: Surface, accessUnit: ByteArray): MediaCodec? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets(accessUnit)))
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        val lowLatency = decoderSupportsLowLatency()
        if (lowLatency) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            codec.configure(format, target, null, 0)
            codec.start()
            codec
        } catch (_: Exception) {
            codec.release()
            if (!lowLatency) return null
            val fallback = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            fallback.setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets(accessUnit)))
            val retry = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            retry.configure(fallback, target, null, 0)
            retry.start()
            retry
        }
    }

    private fun queue(codec: MediaCodec, accessUnit: ByteArray) {
        val index = codec.dequeueInputBuffer(5_000)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        input.put(accessUnit)
        codec.queueInputBuffer(index, 0, accessUnit.size, System.nanoTime() / 1000, 0)
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> codec.releaseOutputBuffer(index, true)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return
            }
        }
    }
}

private fun decoderSupportsLowLatency(): Boolean {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return list.codecInfos.any { info ->
        !info.isEncoder &&
            info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }
}

private fun hasParameterSets(accessUnit: ByteArray): Boolean {
    val types = nalTypes(accessUnit)
    return types.contains(7) && types.contains(8)
}

private fun parameterSets(accessUnit: ByteArray): ByteArray {
    val nals = splitAnnexB(accessUnit).filter { nalType(it) == 7 || nalType(it) == 8 }
    var size = 0
    for (nal in nals) size += 4 + nal.size
    val out = ByteArray(size)
    var offset = 0
    for (nal in nals) {
        out[offset + 3] = 1
        nal.copyInto(out, offset + 4)
        offset += 4 + nal.size
    }
    return out
}

private fun nalTypes(accessUnit: ByteArray): Set<Int> = splitAnnexB(accessUnit).map { nalType(it) }.toSet()

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
