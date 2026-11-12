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
    const val KIND_MONITOR_COMMAND=11
    const val KIND_MONITOR_STATUS=12
    const val KIND_STREAM_STATE=13
    const val KIND_VIDEO_NACK=14
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

    fun monitorCommand(id: Int,action: Int,index: Int,width: Int,height: Int): ByteArray {
        require(action in 0..4 && index in 0..5)
        val p=ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        p.putInt(id).put(action.toByte()).put(index.toByte()).putShort(width.toShort()).putShort(height.toShort())
        return frame(KIND_MONITOR_COMMAND,p.array())
    }

    fun videoNack(index:Int,ssrc:Int,sequence:Int):ByteArray {
        require(index in 0..5 && sequence in 0..65535)
        return frame(KIND_VIDEO_NACK,ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).put(index.toByte()).putInt(ssrc).putShort(sequence.toShort()).array())
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
            KIND_STREAM_STATE -> {if(payload.size!=1 || payload[0].toInt() !in 0..1) null else Packet.StreamState(payload[0].toInt()==1)}
            KIND_MONITOR_STATUS -> {
                if(payload.size<8 || payload[4].toInt() !in 0..1) return null
                val end=8+(payload[7].toInt() and 255)
                if(payload.size<end) return null
                val screens=mutableListOf<Pair<Int,Int>>()
                if(payload.size>end) {
                    val count=payload[end].toInt() and 255
                    if(count>6 || (payload.size!=end+1+count*4 && payload.size<end+1+count*8)) return null
                    val sizes=ByteBuffer.wrap(payload,end+1,count*4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(count) {
                        val w=sizes.short.toInt() and 65535;val h=sizes.short.toInt() and 65535
                        if(w<160 || h<160) return null
                        screens.add(w to h)
                    }
                }
                val positions=mutableListOf<Pair<Int,Int>>()
                if(screens.isNotEmpty() && payload.size>=end+1+screens.size*8) {
                    val points=ByteBuffer.wrap(payload,end+1+screens.size*4,screens.size*4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(screens.size) {positions.add(points.short.toInt() to points.short.toInt())}
                }
                val names=mutableListOf<String>();val roles=mutableListOf<Int>();var primary=0
                val extension=end+1+screens.size*8
                if(payload.size>extension) {
                    if(payload.size<extension+2 || payload[extension].toInt()!=1) return null
                    primary=payload[extension+1].toInt() and 255
                    if(primary and ((1 shl screens.size)-1).inv()!=0) return null
                    var cursor=extension+2
                    repeat(screens.size) {
                        if(cursor+2>payload.size) return null
                        val role=payload[cursor++].toInt() and 255;val length=payload[cursor++].toInt() and 255
                        if(role>3 || cursor+length>payload.size) return null
                        roles.add(role);names.add(String(payload,cursor,length,Charsets.UTF_8));cursor+=length
                    }
                    if(cursor!=payload.size) return null
                }
                Packet.MonitorStatus(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int,payload[4].toInt()!=0,payload[5].toInt() and 255,payload[6].toInt() and 255,String(payload,8,end-8,Charsets.UTF_8),screens,positions,names,roles,primary)
            }
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
        if(count !in 1..6) return null
        val monitors = ArrayList<MonitorHello>(count)
        repeat(count) {
            if (buf.remaining() < 12) return null
            val index = buf.get().toInt() and 0xFF
            val width = buf.short.toInt() and 0xFFFF
            val height = buf.short.toInt() and 0xFFFF
            val port = buf.short.toInt() and 0xFFFF
            val ssrc = buf.int
            val nameLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < nameLen) return null
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            if(index!=monitors.size || width<160 || height<160) return null
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
    data class MonitorStatus(val requestId:Int,val ok:Boolean,val available:Int,val owned:Int,val message:String,val screens:List<Pair<Int,Int>> = emptyList(),val positions:List<Pair<Int,Int>> = emptyList(),val names:List<String> = emptyList(),val roles:List<Int> = emptyList(),val primary:Int=0):Packet
    data class StreamState(val reloading:Boolean):Packet
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
