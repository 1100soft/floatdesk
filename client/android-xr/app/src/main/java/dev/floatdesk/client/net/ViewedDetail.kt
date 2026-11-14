package dev.floatdesk.client.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import dev.floatdesk.client.FocusRect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

internal data class DetailRequest(val host:InetSocketAddress,val sessionId:Long,val rect:FocusRect,val desktop:List<Int>)
internal data class DetailPatch @JvmOverloads constructor(val rect:FocusRect,val bitmap:Bitmap,val sourceWidth:Int=4096,val sourceHeight:Int=2160,val capturedAt:Long=SystemClock.uptimeMillis())

/** One request in flight, no queued screenshots. Response geometry travels with its own image. */
internal class ViewedDetail(private val publish:(DetailPatch?)->Unit,private val status:(String)->Unit):AutoCloseable {
    private val request=AtomicReference<DetailRequest?>(null)
    @Volatile private var running=true
    @Volatile private var socket:Socket?=null
    private var attempts=0L
    private var received=0L
    private var late=0L
    private var errors=0L
    private fun report(message:String){status("REQ $attempts / RX $received / late $late / errors $errors · $message")}
    private val worker=Thread({
        while(running) {
            val next=request.get()
            if(next==null){Thread.sleep(100);continue}
            val start=SystemClock.elapsedRealtime()
            attempts++;report("request in flight")
            try {
                val connection=Socket();socket=connection
                connection.use {
                    it.connect(next.host,500);it.soTimeout=700
                    val json=JSONObject().put("sessionId",next.sessionId).put("rect",org.json.JSONArray(listOf(next.rect.left,next.rect.top,next.rect.right,next.rect.bottom))).toString().toByteArray(Charsets.UTF_8)
                    DataOutputStream(it.getOutputStream()).apply {writeInt(json.size);write(json);flush()}
                    val input=DataInputStream(it.getInputStream());val size=input.readInt();require(size in 1..1024)
                    val header=ByteArray(size);input.readFully(header);val meta=JSONObject(String(header,Charsets.UTF_8))
                    val count=meta.getInt("bytes");require(count in 1..4*1024*1024)
                    require(meta.getInt("width") in 1..1536 && meta.getInt("height") in 1..1024)
                    val desktop=meta.getJSONArray("desktop");require((0..3).map{desktop.getInt(it)}==next.desktop)
                    val a=meta.getJSONArray("rect");val rect=FocusRect(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat())
                    require(rect.left>=0 && rect.top>=0 && rect.right<=1 && rect.bottom<=1 && rect.right>rect.left && rect.bottom>rect.top)
                    val bytes=ByteArray(count);input.readFully(bytes)
                    val options=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,count,options)
                    require(options.outWidth==meta.getInt("width") && options.outHeight==meta.getInt("height"))
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,count) ?: error("Invalid detail image")
                    val current=request.get()
                    if(running && current!=null && current.host==next.host && current.sessionId==next.sessionId && current.desktop==next.desktop && SystemClock.elapsedRealtime()-start<250) {
                        received++;publish(DetailPatch(rect,bitmap,next.desktop[2],next.desktop[3],SystemClock.uptimeMillis()-(SystemClock.elapsedRealtime()-start)));report("${SystemClock.elapsedRealtime()-start} ms · ${count/1024} KiB")
                    } else {late++;bitmap.recycle();report("discarded: stale or changed view/session (${SystemClock.elapsedRealtime()-start} ms)")}
                }
            } catch(error:Exception) {if(running && request.get()!=null){errors++;report("${error.javaClass.simpleName}: ${error.message.orEmpty().take(55)}");Thread.sleep(750)}}
            finally {socket=null}
            if(running)Thread.sleep((125-(SystemClock.elapsedRealtime()-start)).coerceAtLeast(10))
        }
    },"floatdesk-view-detail").also{it.isDaemon=true;it.start()}
    fun update(value:DetailRequest?) {val before=request.getAndSet(value);if(value==null && before!=null){socket?.runCatching{close()};publish(null)}}
    override fun close(){running=false;request.set(null);socket?.runCatching{close()};publish(null)}
}
