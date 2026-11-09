package dev.floatdesk.client

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.InteractableComponent
import java.util.concurrent.Executors

/** Measure runtime delivery before the UI queue, while keeping scene mutations on main. */
internal object NativeMonitorInput {
    private val worker=Executors.newSingleThreadExecutor { task ->
        Thread(task,"floatdesk-native-input").also { it.isDaemon=true }
    }
    private val main=Handler(Looper.getMainLooper())
    private var began=0L
    private var received=0
    private var maxQueueMs=0L

    fun create(session: Session,listener: (InputEvent)->Unit): InteractableComponent =
        InteractableComponent.create(session,worker) { event ->
            val at=SystemClock.uptimeMillis()
            val hover=event.source==InputEvent.Source.HANDS && event.action==InputEvent.Action.HOVER_MOVE
            // These counters are accessed only on main, after recording the worker receipt time.
            main.post {
                if(hover) {
                    val now=SystemClock.uptimeMillis()
                    if(began==0L) began=now
                    received++
                    maxQueueMs=maxOf(maxQueueMs,now-at)
                    if(now-began>=5000) {
                        Log.i("Floatdesk","native input delivery moves=$received windowMs=${now-began} maxUiQueueMs=$maxQueueMs eventTimestamp=${event.timestamp} receivedAt=$at")
                        began=now;received=0;maxQueueMs=0
                    }
                }
                listener(event)
            }
        }
}
