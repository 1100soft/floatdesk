package dev.floatdesk.client.net

import java.net.InetSocketAddress

/** Retry fresh interfaces advertised under the selected host's name; never accept an unsolicited reply. */
internal class HostDiscovery {
    private data class Candidate(val name:String,val seenAt:Long)
    private val candidates=linkedMapOf<InetSocketAddress,Candidate>()
    private var selected:InetSocketAddress?=null
    private var selectedName:String?=null
    private var selectedAt=0L

    fun observe(address:InetSocketAddress,name:String,now:Long) {
        candidates[address]=Candidate(name,now)
        candidates.entries.removeAll {now-it.value.seenAt>10_000}
        while(candidates.size>32) candidates.remove(candidates.keys.first())
    }
    fun select(current:InetSocketAddress?,healthy:Boolean,now:Long):InetSocketAddress? {
        if(healthy)return current
        if(current==null) {
            val first=candidates.entries.firstOrNull {now-it.value.seenAt<=10_000} ?: return null
            selected=first.key;selectedName=first.value.name;selectedAt=now
            return first.key
        }
        if(selected!=current) {
            selected=current;selectedName=candidates[current]?.name;selectedAt=now
            return current
        }
        if(now-selectedAt<2500)return current
        val name=selectedName ?: return current
        val fresh=candidates.filter {it.value.name==name && now-it.value.seenAt<=10_000}.keys.toList()
        if(fresh.isEmpty())return current
        val index=fresh.indexOf(current)
        val next=fresh[(index+1)%fresh.size]
        if(next!=current) {selected=next;selectedAt=now}
        return next
    }
    fun clear() {candidates.clear();selected=null;selectedName=null;selectedAt=0}
}
