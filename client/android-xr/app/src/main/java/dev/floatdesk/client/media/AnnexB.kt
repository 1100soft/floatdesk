package dev.floatdesk.client.media

/** Inspect headers without allocating/copying full NAL payloads on every decoder frame. */
internal fun isKeyframe(accessUnit: ByteArray): Boolean {
    var offset=0
    while(offset+3<=accessUnit.size){
        val length=startCodeLen(accessUnit,offset)
        if(length>0){
            val header=offset+length
            if(header<accessUnit.size && (accessUnit[header].toInt() and 0x1F)==5)return true
            offset+=length
        }else offset++
    }
    return false
}

internal fun annexBParameterSets(sps: ByteArray, pps: ByteArray): ByteArray {
    val out = ByteArray(8 + sps.size + pps.size)
    out[3] = 1
    sps.copyInto(out, 4)
    val ppsAt = 4 + sps.size
    out[ppsAt + 3] = 1
    pps.copyInto(out, ppsAt + 4)
    return out
}

internal fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) 0 else nal[0].toInt() and 0x1F

/** Visit payload ranges; consumers copy only headers they actually retain. */
internal fun visitAnnexB(data: ByteArray, visit: (Int, Int) -> Unit) {
    var payload = -1
    var offset = 0
    while (offset + 3 <= data.size) {
        val length = startCodeLen(data, offset)
        if (length == 0) {
            offset++
            continue
        }
        if (payload >= 0) visit(payload, offset)
        payload = offset + length
        offset += length
    }
    if (payload >= 0) visit(payload, data.size)
}

internal fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val result = ArrayList<ByteArray>()
    visitAnnexB(data) { start, end -> result.add(data.copyOfRange(start, end)) }
    return result
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
