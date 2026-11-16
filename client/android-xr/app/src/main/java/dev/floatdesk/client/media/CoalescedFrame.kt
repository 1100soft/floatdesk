package dev.floatdesk.client.media

import java.util.concurrent.atomic.AtomicBoolean

/** Multiple producers share one pending display frame. No timed input-update drops. */
internal class CoalescedFrame(
    private val schedule: (() -> Unit) -> Unit,
    private val render: () -> Unit,
) {
    private val pending = AtomicBoolean(false)

    fun request() {
        if (!pending.compareAndSet(false, true)) return
        schedule {
            // Updates arriving during rendering belong to the next display frame.
            pending.set(false)
            render()
        }
    }
}
