package dev.floatdesk.client

/** Retain asynchronous consumers' inputs with explicit entry and memory budgets. */
internal class BoundedHistory<T:Any>(private val maxEntries:Int,private val maxBytes:Long,private val weight:(T)->Long){
    private val entries=java.util.ArrayDeque<Pair<T,Long>>()
    var bytes=0L;private set
    val size get()=entries.size
    init{require(maxEntries>0 && maxBytes>0)}
    fun retain(value:T){
        if(entries.peekLast()?.first===value)return
        val cost=weight(value).coerceAtLeast(0);entries.addLast(value to cost);bytes+=cost
        // The current input remains alive even if it alone exceeds the configured budget.
        while(entries.size>1 && (entries.size>maxEntries || bytes>maxBytes)){bytes-=entries.removeFirst().second}
    }
}
