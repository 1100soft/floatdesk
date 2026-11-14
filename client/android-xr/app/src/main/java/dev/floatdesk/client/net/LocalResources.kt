package dev.floatdesk.client.net

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.Locale

/** Background sampling avoids /proc and memory-stat reads on the input/GL threads. */
internal class LocalResources(private val publish:(String)->Unit):AutoCloseable {
    @Volatile private var running=true
    private val worker=Thread({
        runCatching{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)}
        var at=SystemClock.elapsedRealtime();var cpu=Process.getElapsedCpuTime()
        while(running){
            try{Thread.sleep(1000)}catch(_:InterruptedException){break}
            if(!running)break
            val now=SystemClock.elapsedRealtime();val next=Process.getElapsedCpuTime()
            val percent=(next-cpu)*100.0/(now-at).coerceAtLeast(1)
            val info=Debug.MemoryInfo()
            if(runCatching{Debug.getMemoryInfo(info)}.isFailure){publish("XR CPU %.0f%% · memory unavailable".format(Locale.ROOT,percent));at=now;cpu=next;continue}
            val graphics=runCatching{info.getMemoryStat("summary.graphics")?.toLongOrNull()?.let{it/1024.0}}.getOrNull()
            val gpu=runCatching{val values=File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+")).map{it.toDouble()};if(values.size>=2 && values[1]>0)100*values[0]/values[1] else null}.getOrNull()
            val available=runCatching{File("/proc/meminfo").readLines().firstOrNull{it.startsWith("MemAvailable:")}?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.div(1024.0)}.getOrNull()
            val gpuText=gpu?.let{String.format(Locale.ROOT,"%.0f%%",it)} ?: "n/a"
            publish(String.format(Locale.ROOT,"XR CPU %.0f%% PSS %.0fMiB free %.0fMiB gfx %.0fMiB GPU %s",percent,info.totalPss/1024.0,available ?: 0.0,graphics ?: 0.0,gpuText))
            at=now;cpu=next
        }
    },"floatdesk-resources").also{it.isDaemon=true;it.start()}
    override fun close(){running=false;worker.interrupt()}
}
