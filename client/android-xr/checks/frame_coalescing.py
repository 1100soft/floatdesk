#!/usr/bin/env python3
"""Exercise the actual multi-producer frame gate without Android display dependencies."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client'
source = (root / 'media/CoalescedFrame.kt').read_text().split('\n', 1)[1]
code = source + r'''
fun main() {
    val queue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    var latest = 0
    val rendered = mutableListOf<Int>()
    lateinit var gate: CoalescedFrame
    gate = CoalescedFrame({ queue.add(it); Unit }, {
        rendered.add(latest)
        if (latest == 100) { latest = 101; gate.request() }
    })
    repeat(100) { latest = it; gate.request() }
    check(queue.size == 1)
    queue.remove().invoke()
    check(rendered == listOf(99) && queue.isEmpty())
    latest = 100; gate.request(); queue.remove().invoke()
    check(rendered == listOf(99, 100) && queue.size == 1)
    queue.remove().invoke()
    check(rendered == listOf(99, 100, 101) && queue.isEmpty())
    val start = java.util.concurrent.CountDownLatch(1)
    val workers = List(16) { Thread { start.await(); repeat(1000) { gate.request() } }.also { it.start() } }
    start.countDown(); workers.forEach { it.join() }
    check(queue.size == 1)
    queue.remove().invoke()
    check(rendered.size == 4 && queue.isEmpty())
    println("PASS concurrent redraw coalescing, latest-state rendering and updates during rendering")
}
'''
lib = Path(sys.argv[1])
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-frame-') as tmp:
    file = Path(tmp) / 'FrameCheck.kt'
    file.write_text(code)
    subprocess.run(['java', '-Xmx512m', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', str(stdlib), '-d', tmp, str(file)], check=True)
    subprocess.run(['java', '-cp', tmp + ':' + str(stdlib), 'FrameCheckKt'], check=True)
