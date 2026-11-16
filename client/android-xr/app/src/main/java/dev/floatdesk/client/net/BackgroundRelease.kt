package dev.floatdesk.client.net

/** Stop producers synchronously, then join decoder/GL workers away from the input/UI loop. */
internal object BackgroundRelease {
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor{r->Thread(r,"floatdesk-resource-release").also{it.isDaemon=true}}
    fun close(resource:AutoCloseable){executor.execute{runCatching{resource.close()}.onFailure{android.util.Log.w("Floatdesk","Resource release failed",it)}}}
}
