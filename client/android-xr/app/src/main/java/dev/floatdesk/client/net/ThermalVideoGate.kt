package dev.floatdesk.client.net

/** Android thermal levels: pause at critical, recover after sustained moderate or cooler. */
internal class ThermalVideoGate(private val recoveryMs:Long=10_000){
    private var paused=false
    private var coolSince:Long?=null

    fun update(level:Int,now:Long):Boolean{
        if(level>=4){paused=true;coolSince=null}
        else if(paused && level in 0..2){
            val since=coolSince ?: now.also{coolSince=it}
            if(now-since>=recoveryMs){paused=false;coolSince=null}
        }else coolSince=null
        return paused
    }
}
