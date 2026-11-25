package dev.floatdesk.client.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import dev.floatdesk.client.media.*
import java.net.InetSocketAddress
import java.net.Socket
import java.io.*
import java.nio.ByteBuffer
import org.json.JSONObject

/** One bounded window decoder; its opaque quad uses the same compositor as normal video. */
internal data class WindowDestination(val surface:android.view.Surface,val frame:(Long,dev.floatdesk.client.FocusRect)->Unit,val clear:()->Unit,val size:(Int,Int)->Unit={_,_->},val mask:(Long,FloatArray)->Unit={_,_->})

internal class WindowVideo @JvmOverloads constructor(private val host:InetSocketAddress,private val session:Long,private val id:Long,private val fps:Int,
    private val video:VideoCompositor,private val failed:(String)->Unit,private val destination:android.view.Surface=video.input,private val beforeFrame:((Long,JSONObject)->Unit)?=null,private val rootCapture:Boolean=false):AutoCloseable{
    @Volatile private var active=true
    fun setActive(value:Boolean){active=value}
    @Volatile private var running=true
    @Volatile private var socket:Socket?=null
    private val thread=Thread({
        var codec:MediaCodec?=null;var size=0 to 0
        try{Socket().use{connection->
            socket=connection;connection.connect(host,1000);connection.soTimeout=2000;connection.tcpNoDelay=true
            val input=DataInputStream(connection.getInputStream());val output=DataOutputStream(connection.getOutputStream())
            fun drain(c:MediaCodec){val info=MediaCodec.BufferInfo();while(true){val n=c.dequeueOutputBuffer(info,0);if(n<0)return;c.releaseOutputBuffer(n,active)}}
            while(running){
                if(!active)Thread.sleep(250)
                val bytes=JSONObject().put("sessionId",session).put("windowId",id).put("fps",minOf(fps,dev.floatdesk.client.RenderPolicy.WindowFps)).put("maxDimension",dev.floatdesk.client.RenderPolicy.WindowDimension).put("maxPixels",dev.floatdesk.client.RenderPolicy.WindowPixels).put("rootCapture",rootCapture).put("skipIdentical",true).put("paused",!active).toString().toByteArray()
                output.writeInt(bytes.size);output.write(bytes);output.flush()
                val n=input.readInt();require(n in 1..4096);val header=ByteArray(n);input.readFully(header);val meta=JSONObject(String(header))
                require(meta.getLong("windowId")==id)
                if(meta.optString("type") in setOf("window-paused-v1","window-unchanged-v1")){codec?.let{drain(it)};continue}
                val w=meta.getInt("width");val h=meta.getInt("height");require(WindowCaptureFormat.valid(w,h))
                val length=meta.getInt("bytes");require(length in 1..8*1024*1024);val data=ByteArray(length);input.readFully(data)
                if(codec==null || size!=(w to h)){
                    codec?.stop();codec?.release();codec=null;size=w to h
                    val csd=ByteArrayOutputStream();splitAnnexB(data).filter{nalType(it) in 7..8}.forEach{csd.write(byteArrayOf(0,0,0,1));csd.write(it)}
                    require(csd.size()>0)
                    val format=MediaFormat.createVideoFormat("video/avc",w,h);format.setByteBuffer("csd-0",ByteBuffer.wrap(csd.toByteArray()));format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8*1024*1024)
                    codec=MediaCodec.createDecoderByType("video/avc").also{it.configure(format,destination,null,0);it.start()}
                }
                val c=codec!!;drain(c);var slot=c.dequeueInputBuffer(10000);val until=SystemClock.uptimeMillis()+500
                while(slot<0 && running && SystemClock.uptimeMillis()<until){drain(c);slot=c.dequeueInputBuffer(10000)}
                check(slot>=0);val b=c.getInputBuffer(slot)!!;require(b.capacity()>=data.size);b.clear();b.put(data)
                val pts=System.nanoTime()/1000;beforeFrame?.invoke(pts,meta)
                c.queueInputBuffer(slot,0,data.size,pts,if(isKeyframe(data))MediaCodec.BUFFER_FLAG_KEY_FRAME else 0);drain(c)
            }
        }}catch(e:Exception){if(running){android.util.Log.w("Floatdesk","Window stream failed",e);failed(e.message?.takeIf{it.isNotBlank()} ?: "Window magnification stopped")}}
        finally{socket=null;runCatching{codec?.stop()};runCatching{codec?.release()}}
    },"floatdesk-window-video").also{it.start()}
    fun stop(){running=false;runCatching{socket?.close()}}
    override fun close(){stop();if(Thread.currentThread()!==thread)thread.join(1500)}
}
