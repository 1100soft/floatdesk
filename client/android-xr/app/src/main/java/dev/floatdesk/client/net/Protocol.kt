package dev.floatdesk.client.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Little-endian control datagrams. The layout matches `crates/protocol`.
 *
 * ```
 * 0  magic "FDC1"
 * 4  version
 * 5  kind
 * 6  flags u16
 * 8  payload length u32
 * 12 reserved u32
 * 16 payload
 * ```
 */
internal object Protocol {
    const val VERSION: Int = 1
    const val HEADER = 16
    const val KIND_CLIENT_HELLO = 1
    const val KIND_HOST_HELLO = 2
    const val KIND_CURSOR = 3
    const val KIND_KEYFRAME = 4
    const val KIND_GOODBYE = 6
    const val KIND_BEACON = 7
    const val KIND_POINTER_MOVE = 8
    const val KIND_POINTER_CLICK = 9
    const val KIND_TOUCHPAD_STATE = 10
    private const val CURSOR_FIXED = 38

    fun clientHello(name: String, videoPort: Int): ByteArray {
        val trimmed = name.encodeToByteArray().let { if (it.size > 64) it.copyOf(64) else it }
        val payload = ByteBuffer.allocate(1 + trimmed.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(trimmed.size.toByte())
        payload.put(trimmed)
        payload.putShort(videoPort.toShort())
        return frame(KIND_CLIENT_HELLO, payload.array())
    }

    fun keyframeRequest(monitor: Int): ByteArray = frame(KIND_KEYFRAME, byteArrayOf(monitor.toByte()))

    fun pointerMove(monitor: Int, x: Float, y: Float, sequence: Int, leftDown: Boolean = false): ByteArray {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        val payload = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(monitor.toByte()).putFloat(x).putFloat(y).putInt(sequence).put(if(leftDown) 1.toByte() else 0.toByte())
        return frame(KIND_POINTER_MOVE,payload.array())
    }

    fun pointerClick(monitor: Int,x: Float,y: Float,button: Int,sequence: Int): ByteArray {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && button in listOf(1,2,3))
        val payload=ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(monitor.toByte()).putFloat(x).putFloat(y).put(button.toByte()).putInt(sequence)
        return frame(KIND_POINTER_CLICK,payload.array())
    }

    fun touchpad(fingers: Int,x: Float,y: Float,sequence: Int): ByteArray {
        require(fingers in listOf(0,2) && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        val payload=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(fingers.toByte()).putFloat(x).putFloat(y).putInt(sequence)
        return frame(KIND_TOUCHPAD_STATE,payload.array())
    }

    fun goodbye(): ByteArray = frame(KIND_GOODBYE, ByteArray(0))

    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER) return null
        if (bytes[0] != 'F'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
            bytes[2] != 'C'.code.toByte() || bytes[3] != '1'.code.toByte()
        ) {
            return null
        }
        if (bytes[4].toInt() != VERSION) return null
        val kind = bytes[5].toInt() and 0xFF
        val payloadLen = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (payloadLen < 0 || bytes.size != HEADER + payloadLen) return null
        val payload = bytes.copyOfRange(HEADER, bytes.size)
        return when (kind) {
            KIND_HOST_HELLO -> decodeHostHello(payload)
            KIND_CURSOR -> decodeCursor(payload)
            KIND_BEACON -> decodeBeacon(payload)
            else -> Packet.Ignored(kind)
        }
    }

    private fun frame(kind: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put('F'.code.toByte())
        out.put('D'.code.toByte())
        out.put('C'.code.toByte())
        out.put('1'.code.toByte())
        out.put(VERSION.toByte())
        out.put(kind.toByte())
        out.putShort(0)
        out.putInt(payload.size)
        out.putInt(0)
        out.put(payload)
        return out.array()
    }

    private fun decodeBeacon(payload: ByteArray): Packet? {
        if (payload.size < 3) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val port = buf.short.toInt() and 0xFFFF
        val nameLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < nameLen) return null
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        return Packet.Beacon(port, String(nameBytes, Charsets.UTF_8))
    }

    private fun decodeHostHello(payload: ByteArray): Packet? {
        if (payload.size < 9) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sessionId = buf.long
        val count = buf.get().toInt() and 0xFF
        val monitors = ArrayList<MonitorHello>(count)
        repeat(count) {
            if (buf.remaining() < 11) return null
            val index = buf.get().toInt() and 0xFF
            val width = buf.short.toInt() and 0xFFFF
            val height = buf.short.toInt() and 0xFFFF
            val port = buf.short.toInt() and 0xFFFF
            val ssrc = buf.int
            val nameLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nameLen) return null
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            monitors += MonitorHello(index, width, height, port, ssrc, String(nameBytes, Charsets.UTF_8))
        }
        return Packet.HostHello(sessionId, monitors)
    }

    private fun decodeCursor(payload: ByteArray): Packet? {
        if (payload.size < CURSOR_FIXED) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val monitor = buf.get().toInt() and 0xFF
        val visible = buf.get().toInt() != 0
        val hotspotX = buf.short.toInt()
        val hotspotY = buf.short.toInt()
        val normX = buf.float
        val normY = buf.float
        val shapeId = buf.long
        val timestampNs = buf.long
        val bitmapW = buf.short.toInt() and 0xFFFF
        val bitmapH = buf.short.toInt() and 0xFFFF
        val fragIndex = buf.short.toInt() and 0xFFFF
        val fragCount = buf.short.toInt() and 0xFFFF
        val pixels = ByteArray(buf.remaining())
        buf.get(pixels)
        return Packet.Cursor(
            CursorFragment(
                monitor, visible, hotspotX, hotspotY, normX, normY, shapeId, timestampNs,
                bitmapW, bitmapH, fragIndex, fragCount, pixels,
            ),
        )
    }
}

internal data class MonitorHello(
    val index: Int,
    val width: Int,
    val height: Int,
    val port: Int,
    val ssrc: Int,
    val name: String,
)

internal data class CursorFragment(
    val monitor: Int,
    val visible: Boolean,
    val hotspotX: Int,
    val hotspotY: Int,
    val normX: Float,
    val normY: Float,
    val shapeId: Long,
    val timestampNs: Long,
    val bitmapW: Int,
    val bitmapH: Int,
    val fragIndex: Int,
    val fragCount: Int,
    val pixels: ByteArray,
)

internal sealed interface Packet {
    data class HostHello(val sessionId: Long, val monitors: List<MonitorHello>) : Packet
    data class Cursor(val fragment: CursorFragment) : Packet
    data class Beacon(val listenPort: Int, val name: String) : Packet
    data class Ignored(val kind: Int) : Packet
}

internal class CursorAssembler {
    private var shapeId = 0L
    private var parts = arrayOfNulls<ByteArray>(0)

    fun push(fragment: CursorFragment): CursorView {
        if (fragment.fragCount == 0) {
            return CursorView(fragment, pixels = null)
        }
        if (shapeId != fragment.shapeId || parts.size != fragment.fragCount) {
            shapeId = fragment.shapeId
            parts = arrayOfNulls(fragment.fragCount)
        }
        if (fragment.fragIndex in parts.indices) {
            parts[fragment.fragIndex] = fragment.pixels
        }
        val complete = parts.all { it != null }
        val pixels = if (complete) {
            val out = ByteArray(parts.sumOf { it?.size ?: 0 })
            var offset = 0
            for (part in parts) {
                val bytes = part ?: continue
                bytes.copyInto(out, offset)
                offset += bytes.size
            }
            out
        } else {
            null
        }
        return CursorView(fragment, pixels)
    }
}

internal data class CursorView(
    val fragment: CursorFragment,
    /** Full BGRA bitmap once every fragment has arrived. Null while incomplete or position-only. */
    val pixels: ByteArray?,
)
