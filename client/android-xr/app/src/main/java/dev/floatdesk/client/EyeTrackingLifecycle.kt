package dev.floatdesk.client

internal data class EyeTrackingAction(val configureFine: Boolean?=null,val clearResources: Boolean=false,val refreshResources: Boolean=false)

/** Keep the sensor ready for palm controls; desktop mouse routing does not own its lifecycle. */
internal class EyeTrackingLifecycle {
    private var wasResumed=false
    private var previousPermission: Boolean?=null
    private var lastConfigure=-10_000L
    private var lastRefresh=-10_000L

    fun update(now: Long,resumed: Boolean,permission: Boolean,actualFine: Boolean,
               hasResources: Boolean,anyTracking: Boolean): EyeTrackingAction {
        if(!resumed) {
            val clear=wasResumed
            wasResumed=false
            return EyeTrackingAction(clearResources=clear)
        }
        val restarted=!wasResumed
        val changed=previousPermission!=permission
        wasResumed=true;previousPermission=permission
        val configure=if(actualFine!=permission && (restarted || changed || now-lastConfigure>=1000)) permission else null
        if(configure!=null) lastConfigure=now
        val clear=restarted || changed || configure!=null
        val available=hasResources && !clear
        val retryAfter=if(available) 1000 else 100
        val refresh=permission && actualFine && now-lastConfigure>=40 &&
            (clear || !available || !anyTracking) && (restarted || now-lastRefresh>=retryAfter)
        if(refresh) lastRefresh=now
        return EyeTrackingAction(configure,clear,refresh)
    }
}
