internal class H264Depacketizer {
    private var expectedSeq: Int? = null
    private val nals = ArrayList<ByteArray>()
    private var fu = ByteArray(0)
    private var fuSize = 0
    private var fuOpen = false
    private var lost = false
    private var damaged = false

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

    fun push(packet: ByteArray, length: Int, nowMs: Long): List<ByteArray> {
        if (length < 4) return emptyList()
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (nextSeq < 0) nextSeq = seq
        val ahead = (seq - nextSeq) and 0xFFFF
        if (ahead > 0x8000) return flush(nowMs)
        if (ahead > 48) {
            held.clear()
            nextSeq = seq
            holeSince = 0L
        }
        held[seq] = packet.copyOf(length)
        return flush(nowMs)
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
            if (nowMs - holeSince < 40 && held.size < 24) break
            nextSeq = (nextSeq + 1) and 0xFFFF
            holeSince = nowMs
        }
        return out
    }
}


fun packet(seq: Int, marker: Boolean, vararg payload: Int): ByteArray {
    val b = ByteArray(12 + payload.size)
    b[0] = 0x80.toByte(); b[1] = (if (marker) 0xe0 else 0x60).toByte()
    b[2] = (seq shr 8).toByte(); b[3] = seq.toByte()
    payload.forEachIndexed { i, x -> b[12+i] = x.toByte() }
    return b
}
private fun H264Depacketizer.accept(b: ByteArray) = push(b, b.size)
fun main() {
    val d = H264Depacketizer()
    check(d.accept(packet(65535, true, 0x65, 1))!!.contentEquals(byteArrayOf(0,0,0,1,0x65,1)))
    check(d.accept(packet(0, true, 0x41, 2)) != null)
    check(!d.consumeLoss())
    check(d.accept(packet(1, false, 0x7c, 0x81, 3)) == null)
    check(d.accept(packet(3, true, 0x7c, 0x41, 5)) == null)
    check(d.consumeLoss()); check(!d.consumeLoss())
    check(d.accept(packet(4, true, 0x65, 6)) != null)
    // A single-NAL slice after a hole must not escape as a partial access unit.
    check(d.accept(packet(6, false, 0x41, 7)) == null)
    check(d.accept(packet(7, true, 0x41, 8)) == null)
    check(d.accept(packet(8, true, 0x65, 9)) != null)
    val r = RtpReorder()
    check(r.push(packet(100, true, 0x41), 13, 1000).size == 1)
    check(r.push(packet(102, true, 0x41), 13, 1001).isEmpty())
    val ready = r.push(packet(101, true, 0x41), 13, 1002)
    check(ready.size == 2)
    val ordered = H264Depacketizer()
    ready.forEach { ordered.accept(it) }; check(!ordered.consumeLoss())
    println("RTP checks passed: AU assembly, sequence wrap, fragment loss, partial-AU rejection, reorder")
}
