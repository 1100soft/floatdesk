package dev.floatdesk.client

/** Identity of the native object an operation touches. Slot keys, X11 IDs and surface generations stay separate. */
internal data class NativeTag(val pane:String,val owner:Long=0L,val child:Long=0L,val generation:Int=0,val curved:Boolean=false)

/**
 * Records every native SceneCore create, shape, size, visibility, producer, collider and release operation
 * before it runs. A SplitEngine abort kills the process on a render worker, so only lines already handed to
 * logcat survive it. Platform-free: the activity installs the logcat sink, process ID and uptime clock.
 */
internal object NativeJournal {
    const val Tag="FloatdeskNative"
    const val Capacity=512
    const val SummaryIntervalMs=1000L
    @Volatile private var sink:(String)->Unit={}
    @Volatile private var clock:()->Long={System.nanoTime()/1_000_000L}
    @Volatile private var pid=0
    private val ring=ArrayDeque<String>(Capacity)
    // Keyed by pane slot, not generation, so the maps stay bounded by the number of slots.
    private val shapes=HashMap<String,String>()
    private val visibility=HashMap<String,String>()
    private val repeats=LinkedHashMap<String,Int>()
    private var summaryAt=Long.MIN_VALUE

    fun install(pid:Int,clock:()->Long,sink:(String)->Unit){this.pid=pid;this.clock=clock;this.sink=sink}

    fun record(tag:NativeTag,operation:String,detail:String=""){
        append("${prefix()} pane=${tag.pane} owner=${tag.owner} child=${tag.child} gen=${tag.generation} mode=${if(tag.curved)"curved" else "flat"} op=$operation"+
            if(detail.isEmpty())"" else " $detail")
    }

    inline fun <T> native(tag:NativeTag,operation:String,detail:String="",block:()->T):T{record(tag,operation,detail);return block()}

    /** The call always runs. Only a change from the last recorded state of [target] is logged, because callers repeat it per frame. */
    inline fun <T> enabled(tag:NativeTag,target:String,value:Boolean,block:()->T):T{noteEnabled(tag,target,value);return block()}

    fun noteEnabled(tag:NativeTag,target:String,value:Boolean){
        val state="${tag.generation}:$value"
        if(synchronized(this){visibility.put("${tag.pane}#$target",state)!=state})record(tag,"enabled","target=$target value=$value")
    }

    /** Full lines only when a surface's generation, shape kind or vertex count changes; otherwise a count summary at most once per interval. */
    fun shape(tag:NativeTag,kind:String,vertices:Int){
        val signature="${tag.generation}:$kind:$vertices"
        val summary=synchronized(this){
            if(shapes.put(tag.pane,signature)!=signature)null else {
                repeats[tag.pane]=(repeats[tag.pane] ?: 0)+1
                val now=clock()
                if(summaryAt!=Long.MIN_VALUE && now-summaryAt<SummaryIntervalMs)return
                summaryAt=now
                repeats.entries.joinToString(","){"${it.key}=${it.value}"}.also{repeats.clear()}
            }
        }
        if(summary==null)record(tag,"shape","kind=$kind vertices=$vertices") else append("${prefix()} op=shape-summary counts=$summary")
    }

    fun recent():List<String> = synchronized(this){ring.toList()}

    private fun prefix()="t=${clock()} pid=$pid thread=${Thread.currentThread().name}"

    private fun append(line:String){
        synchronized(this){if(ring.size==Capacity)ring.removeFirst();ring.addLast(line)}
        sink(line)
    }
}
