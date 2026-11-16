package dev.floatdesk.client.net

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.floatdesk.client.FocusRect
import dev.floatdesk.client.media.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Capture/encode/decode once per window; GPU fan-out serves panels and the desktop patch. */
internal class WindowCapturePool @JvmOverloads constructor(private val connection:DeskConnection,
    private val capture:(Long,VideoCompositor,(String)->Unit,(Long,JSONObject)->Unit)->WindowVideo?={id,video,error,frame->connection.captureWindow(id,video,error,frame)}){
    private val main=Handler(Looper.getMainLooper())
    private val sources=mutableMapOf<Long,Source>()
    private class Member(var mirror:VideoMirror,val ready:()->Unit,val error:(String)->Unit,val output:WindowDestination?=null){val shown=AtomicBoolean()}
    private inner class Source(val id:Long){
        val members=ConcurrentHashMap<Any,Member>()
        val frames=java.util.concurrent.ConcurrentSkipListMap<Long,JSONObject>()
        val info=connection.workspaceInfo.value?.windows?.firstOrNull{it.id==id}
        val width=info?.width ?: 1280;val height=info?.height ?: 800
        val size=WindowCaptureFormat.size(width,height)
        val w=size.first;val h=size.second
        val video=VideoCompositor(w,h)
        @Volatile private var encodedSize=w to h
        var stream:WindowVideo?=null
        init{
            if(id==0L){val c=video.input.lockCanvas(null);c.drawColor(Color.rgb(30,38,48));val paint=android.graphics.Paint().also{it.color=Color.rgb(90,110,130);it.strokeWidth=3f};c.drawLine(w*.4f,h*.5f,w*.6f,h*.5f,paint);c.drawLine(w*.5f,h*.4f,w*.5f,h*.6f,paint);video.input.unlockCanvasAndPost(c)}
            else stream=capture(id,video,{message->main.post{members.values.forEach{it.error(message)}}}){pts,meta->
                frames[pts]=meta;while(frames.size>64)frames.pollFirstEntry()
                val next=meta.getInt("width") to meta.getInt("height")
                if(next!=encodedSize){encodedSize=next;members.values.forEach{it.output?.size?.invoke(next.first,next.second)}}
            }
        }
        fun outputSize()=encodedSize
        fun update(){video.mirrors(members.values.map{member->member.mirror.copy(before={pts->
            val output=member.output
            val meta=frames[pts]
            if(meta!=null)video.sourceAspect=meta.optInt("sourceWidth",width).toFloat()/meta.optInt("sourceHeight",height).coerceAtLeast(1)
            if(output!=null && meta!=null){
                val b=connection.workspaceInfo.value?.bounds()
                if(b!=null){
                    val x=meta.optInt("sourceX",info?.x ?: 0);val y=meta.optInt("sourceY",info?.y ?: 0);val sw=meta.optInt("sourceWidth",width);val sh=meta.optInt("sourceHeight",height)
                    output.frame(pts,FocusRect((x-b[0]).toFloat()/b[2],(y-b[1]).toFloat()/b[3],(x+sw-b[0]).toFloat()/b[2],(y+sh-b[1]).toFloat()/b[3]))
                    val masks=meta.optJSONArray("occluders")
                    output.mask(pts,if(masks==null)floatArrayOf() else FloatArray(minOf(16,masks.length())*4){i->val r=masks.getJSONArray(i/4);when(i%4){0->(r.getInt(0)-b[0]).toFloat()/b[2];1->(r.getInt(1)-b[1]).toFloat()/b[3];2->(r.getInt(0)+r.getInt(2)-b[0]).toFloat()/b[2];else->(r.getInt(1)+r.getInt(3)-b[1]).toFloat()/b[3]}})
                }
            }
        },presented={if(member.shown.compareAndSet(false,true))main.post{if(member in members.values)member.ready()}})})}
        fun close(){stream?.close();video.close()}
    }
    internal class Lease(private val updateAction:(Float,Float,Boolean,Float)->Unit,private val closeAction:()->Unit):AutoCloseable{
        private var closed=false
        fun update(padX:Float,padY:Float,pinned:Boolean,aspect:Float){if(!closed)updateAction(padX,padY,pinned,aspect)}
        override fun close(){if(!closed){closed=true;closeAction()}}
    }
    fun attach(id:Long,surface:Surface,onReady:()->Unit,onError:(String)->Unit,output:WindowDestination?=null):Lease{
        val source=sources.getOrPut(id){Source(id)};val key=Any()
        source.outputSize().let{output?.size?.invoke(it.first,it.second)}
        val member=Member(VideoMirror(surface),onReady,onError,output);source.members[key]=member;source.update()
        return Lease({x,y,pin,aspect->if(member.mirror.padX!=x || member.mirror.padY!=y || member.mirror.pinned!=pin || member.mirror.aspect!=aspect){member.mirror=member.mirror.copy(padX=x,padY=y,pinned=pin,aspect=aspect);source.update()}},{
            source.members.remove(key);output?.clear?.invoke()
            if(source.members.isEmpty()){sources.remove(id);source.stream?.stop();source.video.mirrors(emptyList());BackgroundRelease.close(AutoCloseable{source.close()})}else source.update()
        })
    }
}
