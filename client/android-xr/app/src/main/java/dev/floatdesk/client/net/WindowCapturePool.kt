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
    private var active=connection.isVideoActive()
    fun setActive(value:Boolean){active=value;sources.values.forEach{it.stream?.setActive(value)}}
    private val capturedBounds=ConcurrentHashMap<Long,DesktopWindow>()
    private val popupBounds=ConcurrentHashMap<Long,List<DesktopWindow>>()
    fun owner(id:Long):Long=popupBounds.entries.firstOrNull{(_,rows)->rows.any{it.id==id}}?.key ?: id
    fun popups(owner:Long):List<DesktopWindow>{boundsRevision.intValue;return popupBounds[owner].orEmpty()}
    fun region(id:Long):DesktopWindow? {
        boundsRevision.intValue
        return popupBounds.values.firstNotNullOfOrNull{rows->rows.firstOrNull{it.id==id}}
    }
    private val boundsRevision=androidx.compose.runtime.mutableIntStateOf(0)
    fun source(id:Long?):DesktopWindow? {
        boundsRevision.intValue
        if(id==null)return null
        val info=connection.workspaceInfo.value
        val live=info?.windows?.firstOrNull{it.id==id} ?: info?.systemWindows?.firstOrNull{it.id==id} ?: popupBounds.values.firstNotNullOfOrNull{rows->rows.firstOrNull{it.id==id}} ?: return null
        return capturedBounds[id] ?: live
    }
    private class Member(var mirror:VideoMirror,val ready:()->Unit,val error:(String)->Unit,val output:WindowDestination?=null,val regionId:Long?=null,val previewBounds:DesktopWindow?=null){var crop:VideoCrop?=if(regionId==null && previewBounds==null)null else VideoCrop(0f,0f,0f,0f,1f);@Volatile var ink=VideoAim();val shown=AtomicBoolean()}
    private data class CaptureLayout(val meta:JSONObject,val owner:DesktopWindow,val regions:List<DesktopWindow>)
    private inner class Source(val id:Long){
        val members=ConcurrentHashMap<Any,Member>()
        val frames=java.util.concurrent.ConcurrentSkipListMap<Long,CaptureLayout>()
        val info=source(id)
        val width=info?.width ?: 1280;val height=info?.height ?: 800
        val size=WindowCaptureFormat.size(width,height)
        val w=size.first;val h=size.second
        val video=VideoCompositor(w,h)
        @Volatile private var encodedSize=w to h
        var stream:WindowVideo?=null
        @Volatile var retired=false;private set
        fun retire(){
            if(retired)return
            retired=true
            if(sources[id]===this){sources.remove(id);capturedBounds.remove(id);popupBounds.remove(id)}
            stream?.stop();video.mirrors(emptyList());video.flushOutputs()
            BackgroundRelease.close(AutoCloseable{close()})
        }
        init{
            if(id==0L){val c=video.input.lockCanvas(null);c.drawColor(Color.rgb(30,38,48));val paint=android.graphics.Paint().also{it.color=Color.rgb(90,110,130);it.strokeWidth=3f};c.drawLine(w*.4f,h*.5f,w*.6f,h*.5f,paint);c.drawLine(w*.5f,h*.4f,w*.5f,h*.6f,paint);video.input.unlockCanvasAndPost(c)}
            else stream=capture(id,video,{message->main.post{if(!retired){retire();members.values.forEach{it.output?.clear?.invoke();it.error(message)}}}}){pts,meta->
                val x=meta.optInt("sourceX",info?.x ?: 0);val y=meta.optInt("sourceY",info?.y ?: 0)
                val sw=meta.optInt("sourceWidth",width);val sh=meta.optInt("sourceHeight",height)
                val owner=DesktopWindow(id,meta.optInt("ownerX",x),meta.optInt("ownerY",y),meta.optInt("ownerWidth",sw),meta.optInt("ownerHeight",sh))
                val rows=meta.optJSONArray("popups")
                val regions=if(rows==null)emptyList()else (0 until rows.length()).map{i->
                    val p=rows.getJSONObject(i)
                    DesktopWindow(p.getLong("id"),p.getInt("x"),p.getInt("y"),p.getInt("width"),p.getInt("height"),
                        transientFor=p.optLong("transient_for").takeIf{it>0})
                }
                frames[pts]=CaptureLayout(meta,owner,regions)
                while(frames.size>64)frames.pollFirstEntry()
                if(!retired && (capturedBounds[id]!=owner || popupBounds[id]!=regions))main.post{
                    if(!retired){
                        val prior=capturedBounds.put(id,owner)
                        val priorRegions=popupBounds.put(id,regions)
                        if(prior!=owner || priorRegions!=regions)boundsRevision.intValue++
                    }
                }
                val next=meta.getInt("width") to meta.getInt("height")
                if(next!=encodedSize){encodedSize=next;members.values.forEach{it.output?.size?.invoke(next.first,next.second)}}
            }
            stream?.setActive(active)
        }
        fun outputSize()=encodedSize
        fun update(){if(retired)return;video.mirrors(members.values.map{member->member.mirror.copy(before={pts->
            val output=member.output
            val layout=frames[pts]
            val meta=layout?.meta
            if(layout!=null && meta!=null){
                val x=meta.optInt("sourceX",info?.x ?: 0);val y=meta.optInt("sourceY",info?.y ?: 0)
                val sw=meta.optInt("sourceWidth",width);val sh=meta.optInt("sourceHeight",height)
                val region=if(member.regionId==null)layout.owner.takeIf{member.previewBounds==null || member.previewBounds==it}
                    else layout.regions.firstOrNull{it.id==member.regionId}
                member.crop=if(output!=null)null else (region?.let{VideoCrop((it.x-x).toFloat()/sw,(it.y-y).toFloat()/sh,(it.x+it.width-x).toFloat()/sw,(it.y+it.height-y).toFloat()/sh,it.width.toFloat()/it.height)} ?: VideoCrop(0f,0f,0f,0f,1f))
            }
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
        },ink={member.ink},crop={member.crop},presented={if((member.regionId==null || member.crop?.let{it.right>it.left && it.bottom>it.top}==true) && member.shown.compareAndSet(false,true))main.post{if(!retired && member in members.values)member.ready()}})})}
        fun close(){stream?.close();video.close()}
    }
    internal class Lease(private val shapeAction:((()->Unit)->Boolean),private val updateAction:(Float,Float,Boolean,Float)->Unit,private val closeAction:()->Unit,private val overlayAction:(VideoAim)->Unit):AutoCloseable{
        private var closed=false
        fun tryShapeUpdate(update:()->Unit)=!closed && shapeAction(update)
        fun overlay(value:VideoAim){if(!closed)overlayAction(value)}
        fun update(padX:Float,padY:Float,pinned:Boolean,aspect:Float){if(!closed)updateAction(padX,padY,pinned,aspect)}
        override fun close(){if(!closed){closed=true;closeAction()}}
    }
    @JvmOverloads fun attach(id:Long,surface:Surface,onReady:()->Unit,onError:(String)->Unit,output:WindowDestination?=null,regionId:Long?=null,previewBounds:DesktopWindow?=null,transparentFit:Boolean=false,aperture:Pair<Float,Float>?=null):Lease{
        val source=sources.getOrPut(id){Source(id)};val key=Any()
        source.outputSize().let{output?.size?.invoke(it.first,it.second)}
        val member=Member(VideoMirror(surface,aspect=if(aperture==null)0f else 1f,transparentFit=transparentFit,aperture=aperture),onReady,onError,output,regionId,previewBounds);source.members[key]=member;source.update()
        return Lease(source.video::tryShapeUpdate,{x,y,pin,aspect->
            val prior=member.mirror
            if(kotlin.math.abs(prior.padX-x)>.0001f || kotlin.math.abs(prior.padY-y)>.0001f ||
                prior.pinned!=pin || kotlin.math.abs(prior.aspect-aspect)>.0001f){
                member.mirror=prior.copy(padX=x,padY=y,pinned=pin,aspect=aspect)
                source.update()
            }
        },{
            source.members.remove(key);output?.clear?.invoke()
            if(source.members.isEmpty())source.retire() else if(!source.retired){source.update();source.video.flushOutputs()}
        },{value->member.ink=value})
    }
}
