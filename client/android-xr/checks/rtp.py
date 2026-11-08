#!/usr/bin/env python3
"""Run the production RTP depacketizer/reorder checks with a cached Kotlin compiler."""
from pathlib import Path
import subprocess
import sys
import tempfile

source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/media/H264.kt').read_text()
code = source[source.index('internal class H264Depacketizer'):source.index('internal class MonitorDecoder')]
code += '''
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
'''
lib = Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='floatdesk-rtp-') as temp:
    test = Path(temp) / 'RtpChecks.kt'; test.write_text(code)
    stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'RtpChecksKt'],check=True)
