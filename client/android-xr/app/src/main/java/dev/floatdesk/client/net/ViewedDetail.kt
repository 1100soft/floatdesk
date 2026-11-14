package dev.floatdesk.client.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import dev.floatdesk.client.FocusRect
import dev.floatdesk.client.media.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

internal data class DetailRequest(val host:InetSocketAddress,val sessionId:Long,val rect:FocusRect,val desktop:List<Int>,val center:Pair<Float,Float> = rect.centerU to rect.centerV,val quality:Int=0,val bitrateMbps:Float=0f,val fraction:Float=.35f)
internal data class ViewFrame @JvmOverloads constructor(val outer:FocusRect,val inner:FocusRect,val capturedAt:Long,val focusU:Float=inner.centerU,val focusV:Float=inner.centerV)
internal data class ViewDestination(val surface:Surface,val frame:(Long,ViewFrame)->Unit,val clear:()->Unit)

/** One H.264 access unit in flight. Every decoded image is paired with its own crop by PTS. */
internal class ViewedDetail(private val destination:()->ViewDestination?,private val status:(String)->Unit,private val fallback:()->Unit):AutoCloseable {
    private val request=AtomicReference<DetailRequest?>(null)
    @Volatile private var running=true
    @Volatile private var socket:Socket?=null
    private var attempts=0L;private var received=0L;private var late=0L;private var errors=0L
    private fun report(message:String){status("REQ $attempts / RX $received / late $late / errors $errors · $message")}
    private val worker=Thread({
        while(running){
            val first=request.get();val output=destination()
            if(first==null || output==null){Thread.sleep(30);continue}
            var codec:MediaCodec?=null
            try {
                val connection=Socket();socket=connection
                connection.use {stream->
                    stream.connect(first.host,600);stream.soTimeout=900;stream.tcpNoDelay=true
                    val writer=DataOutputStream(stream.getOutputStream());val input=DataInputStream(stream.getInputStream())
                    var previous=0L
                    while(running){
                        val next=request.get() ?: break
                        if(next.host!=first.host || next.sessionId!=first.sessionId || destination()?.surface!==output.surface || next.quality!=first.quality || next.bitrateMbps!=first.bitrateMbps)break
                        val start=SystemClock.elapsedRealtime();attempts++
                        val json=JSONObject().put("mode","view-h264-v1").put("sessionId",next.sessionId)
                            .put("rect",org.json.JSONArray(listOf(next.rect.left,next.rect.top,next.rect.right,next.rect.bottom)))
                            .put("quality",next.quality).put("bitrateMbps",next.bitrateMbps).put("fraction",next.fraction)
                            .put("center",org.json.JSONArray(listOf(next.center.first,next.center.second))).toString().toByteArray(Charsets.UTF_8)
                        writer.writeInt(json.size);writer.write(json);writer.flush()
                        val size=input.readInt();require(size in 1..1024);val header=ByteArray(size);input.readFully(header)
                        val meta=JSONObject(String(header,Charsets.UTF_8));require(meta.getString("type")=="view-h264-v1")
                        val sequence=meta.getLong("frame");require(sequence>previous);previous=sequence
                        val count=meta.getInt("bytes");require(count in 1..8*1024*1024)
                        require(meta.getInt("width")==2048 && meta.getInt("height")==1536)
                        val desktop=meta.getJSONArray("desktop");require((0..3).map{desktop.getInt(it)}==next.desktop)
                        fun rect(name:String):FocusRect {val a=meta.getJSONArray(name);val r=FocusRect(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat());require(r.left>=0 && r.top>=0 && r.right<=1 && r.bottom<=1 && r.right>r.left && r.bottom>r.top);return r}
                        val outer=rect("rect");val inner=rect("inner");require(inner.left>=outer.left && inner.right<=outer.right && inner.top>=outer.top && inner.bottom<=outer.bottom)
                        val bytes=ByteArray(count);input.readFully(bytes)
                        if(codec==null){
                            val nals=splitAnnexB(bytes);val sps=nals.firstOrNull{nalType(it)==7} ?: error("View stream has no SPS")
                            val pps=nals.firstOrNull{nalType(it)==8} ?: error("View stream has no PPS")
                            require(isKeyframe(bytes))
                            val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,2048,1536)
                            format.setByteBuffer("csd-0",ByteBuffer.wrap(annexBParameterSets(sps,pps)));format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8*1024*1024)
                            codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also{it.configure(format,output.surface,null,0);it.start()}
                        }
                        val active=codec!!;var index=-1
                        val deadline=SystemClock.elapsedRealtime()+250
                        while(index<0 && running && SystemClock.elapsedRealtime()<deadline){drain(active);index=active.dequeueInputBuffer(5_000)}
                        check(index>=0){"View decoder backpressure"}
                        val buffer=active.getInputBuffer(index) ?: error("No view input buffer");require(count<=buffer.capacity());buffer.clear();buffer.put(bytes)
                        val pts=System.nanoTime()/1000
                        val age=SystemClock.elapsedRealtime()-start
                        if(age>250)late++
                        output.frame(pts,ViewFrame(outer,inner,SystemClock.uptimeMillis()-age,next.center.first,next.center.second))
                        active.queueInputBuffer(index,0,count,pts,if(isKeyframe(bytes))MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        drain(active);received++
                        if(received%10L==1L)report("${age}ms · H.264 2048x1536 · ${count/1024}KiB")
                    }
                }
            } catch(error:Exception){if(running && request.get()!=null){errors++;report("${error.javaClass.simpleName}: ${error.message.orEmpty().take(45)}");fallback();Thread.sleep(400)}}
            finally {socket=null;runCatching{codec?.stop()};runCatching{codec?.release()};output.clear();fallback()}
        }
    },"floatdesk-view-h264").also{it.isDaemon=true;it.start()}
    private fun drain(codec:MediaCodec){
        val info=MediaCodec.BufferInfo();var newest=-1
        while(true){
            val index=codec.dequeueOutputBuffer(info,0)
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)continue
            if(index<0)break
            if(info.size<=0){codec.releaseOutputBuffer(index,false);continue}
            if(newest>=0)codec.releaseOutputBuffer(newest,false)
            newest=index
        }
        if(newest>=0)codec.releaseOutputBuffer(newest,true)
    }
    fun update(value:DetailRequest?){val before=request.getAndSet(value);if(value==null && before!=null){socket?.runCatching{close()};destination()?.clear();fallback()}}
    override fun close(){running=false;request.set(null);socket?.runCatching{close()};destination()?.clear();fallback()}
}
