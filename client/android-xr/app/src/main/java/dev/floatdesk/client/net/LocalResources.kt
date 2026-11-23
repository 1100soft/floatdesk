package dev.floatdesk.client.net

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.Locale

/** Cheap counters update each second; expensive PSS accounting has a separate budget. */
internal class LocalResources(
    private val thermalStatus: () -> Int = { -1 },
    private val memoryIntervalMs: Long = 5000,
    private val onThermal: (Int,Long) -> Unit = {_,_->},
    private val diagnosticsVisible: () -> Boolean = {true},
    private val publish: (String) -> Unit,
) : AutoCloseable {
    @Volatile private var running = true
    private val worker = Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        var at = SystemClock.elapsedRealtime()
        var cpu = Process.getElapsedCpuTime()
        val memory = Debug.MemoryInfo()
        var memoryDue = 0L
        var hasMemory = false
        while (running) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            if (!running) break
            val now = SystemClock.elapsedRealtime()
            val thermal = runCatching { thermalStatus() }.getOrDefault(-1)
            onThermal(thermal,now)
            val next = Process.getElapsedCpuTime()
            val percent = (next - cpu) * 100.0 / (now - at).coerceAtLeast(1)
            if(!diagnosticsVisible()){at=now;cpu=next;continue}
            if (now >= memoryDue) {
                hasMemory = runCatching { Debug.getMemoryInfo(memory) }.isSuccess
                memoryDue = now + memoryIntervalMs
            }
            val thermalText = when (thermal) {
                0 -> "normal"
                1 -> "light"
                2 -> "moderate"
                3 -> "severe"
                4 -> "critical"
                5 -> "emergency"
                6 -> "shutdown"
                else -> "unavailable"
            }
            val graphics = if (hasMemory) runCatching {
                memory.getMemoryStat("summary.graphics")?.toLongOrNull()?.div(1024.0)
            }.getOrNull() else null
            val gpu = runCatching {
                val values = File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim()
                    .split(Regex("\\s+")).map { it.toDouble() }
                if (values.size >= 2 && values[1] > 0) 100 * values[0] / values[1] else null
            }.getOrNull()
            val available = runCatching {
                File("/proc/meminfo").readLines().firstOrNull { it.startsWith("MemAvailable:") }
                    ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
                    ?.firstOrNull()?.toLongOrNull()?.div(1024.0)
            }.getOrNull()
            val gpuText = gpu?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "n/a"
            if (!running) break
            publish(if (hasMemory) String.format(Locale.ROOT,
                "XR CPU(core) %.0f%% PSS %.0fMiB free %.0fMiB gfx %.0fMiB GPU %s · thermal %s",
                percent, memory.totalPss / 1024.0, available ?: 0.0, graphics ?: 0.0, gpuText, thermalText)
            else String.format(Locale.ROOT, "XR CPU(core) %.0f%% · memory unavailable · thermal %s", percent, thermalText))
            at = now
            cpu = next
        }
    }, "floatdesk-resources").also { it.isDaemon = true; it.start() }

    override fun close() {
        running = false
        worker.interrupt()
    }
}
