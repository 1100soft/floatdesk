package dev.floatdesk.client

import android.util.Log
import kotlinx.coroutines.delay
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

internal fun normalizeWakeMac(value: String): String? {
    val hex = value.trim().replace(":", "").replace("-", "")
    if (!hex.matches(Regex("[0-9a-fA-F]{12}"))) return null
    if (hex.take(2).toInt(16) and 1 != 0 || hex == "000000000000") return null
    return hex.uppercase(Locale.ROOT).chunked(2).joinToString(":")
}

internal suspend fun sendWakeOnLan(address: String) {
    val mac = normalizeWakeMac(address)?.split(":")?.map { it.toInt(16).toByte() } ?: return
    val payload = ByteArray(102) { index -> if (index < 6) 0xff.toByte() else mac[(index - 6) % mac.size] }
    try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 9)
            repeat(3) { attempt ->
                if (attempt > 0) delay(400)
                socket.send(packet)
            }
        }
    } catch (error: java.io.IOException) {
        Log.w("Floatdesk", "Could not send Wake-on-LAN packet", error)
    }
}
