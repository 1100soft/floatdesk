package dev.floatdesk.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** XR may leave the Activity resumed while the headset is asleep. */
@Composable
internal fun rememberDeviceInteractive(context: Context): State<Boolean> {
    val app = context.applicationContext
    val power = remember(app) { app.getSystemService(PowerManager::class.java) }
    val interactive = remember(power) { mutableStateOf(power?.isInteractive ?: true) }
    DisposableEffect(app, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                interactive.value = power?.isInteractive ?: true
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Cover a transition between the initial read and receiver registration.
        interactive.value = power?.isInteractive ?: true
        onDispose { app.unregisterReceiver(receiver) }
    }
    // SCREEN_ON can precede PowerManager becoming interactive. Reconcile even
    // when XR leaves the Activity resumed across sleep.
    LaunchedEffect(power) {
        while (true) {
            interactive.value = power?.isInteractive ?: true
            delay(250)
        }
    }
    return interactive
}
