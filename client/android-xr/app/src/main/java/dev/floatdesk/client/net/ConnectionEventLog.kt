package dev.floatdesk.client.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun headsetNetwork(context: Context?): HeadsetNetwork {
    val manager = context?.getSystemService(ConnectivityManager::class.java) ?: return HeadsetNetwork.WIFI
    val caps = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return HeadsetNetwork.NONE
    return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) HeadsetNetwork.WIFI else HeadsetNetwork.OTHER
}

/** Small persistent record of connection trouble; logcat on the headset rotates within minutes. */
internal class ConnectionEventLog(private val file: File?, private val limitBytes: Long = 256 * 1024) {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    @Synchronized fun append(message: String) {
        val target = file ?: return
        runCatching {
            if (target.length() > limitBytes) target.renameTo(File(target.parentFile, target.name + ".1"))
            target.appendText("${format.format(Date())} $message\n")
        }
    }
}
