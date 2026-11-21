package dev.floatdesk.client.net

internal enum class HeadsetNetwork { NONE, OTHER, WIFI }

/** User-facing description of why the host is silent, or null while it answers normally. */
internal fun connectionProblem(nowMs: Long, startedAtMs: Long, lastReplyAtMs: Long, lastBeaconAtMs: Long, network: HeadsetNetwork, graceMs: Long = 3000): String? {
    val silentMs = nowMs - maxOf(startedAtMs, lastReplyAtMs)
    if (silentMs < graceMs) return null
    val seconds = silentMs / 1000
    val what = if (lastReplyAtMs == 0L) "Can't reach the computer ($seconds s)." else "No reply from the computer for $seconds s. Reconnecting…"
    val why = when {
        network == HeadsetNetwork.NONE -> "The headset has no network connection."
        network == HeadsetNetwork.OTHER -> "The headset is not on Wi-Fi."
        lastBeaconAtMs > 0 && nowMs - lastBeaconAtMs < 5000 -> "The computer is visible on the network but is not answering."
        else -> "Wi-Fi is connected, but no packets are arriving. Check the computer and the Wi-Fi network."
    }
    return "$what $why"
}
