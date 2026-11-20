package dev.floatdesk.client.net

/** Per-view bounded reconnection; a healthy frame resets the backoff. */
internal class WindowCaptureRetry {
    private var failures=0
    fun nextDelayMs():Long {
        val delay=minOf(5_000L,500L shl failures.coerceAtMost(4))
        failures++
        return delay
    }
    fun reset(){failures=0}
}
