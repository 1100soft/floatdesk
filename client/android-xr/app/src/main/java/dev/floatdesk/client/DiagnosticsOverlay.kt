package dev.floatdesk.client

import androidx.compose.runtime.getValue
import kotlinx.coroutines.isActive
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import dev.floatdesk.client.net.DiagnosticSnapshot
import dev.floatdesk.client.workspace.ViewLayers

/** One head-relative spherical surface, without panel corner clipping or independent poses. */
@androidx.compose.runtime.Composable
internal fun DiagnosticsPanel(connection:dev.floatdesk.client.net.DeskConnection,
    viewer:java.util.concurrent.atomic.AtomicReference<dev.floatdesk.client.workspace.Pose?>,
    units:()->Float,angles:()->FloatArray,
    resources:Boolean,quality:Boolean,qualityDebug:Boolean) {
    if(!resources && !qualityDebug)return
    val session=androidx.xr.compose.platform.LocalSession.current ?: return
    val hud=androidx.compose.runtime.remember(session){SphericalDiagnosticsHud(session)}
    val options by androidx.compose.runtime.rememberUpdatedState(Triple(resources,quality,qualityDebug))
    androidx.compose.runtime.DisposableEffect(hud){onDispose{hud.close()}}
    androidx.compose.runtime.LaunchedEffect(connection,viewer,hud){
        var sampled=0L
        while(kotlinx.coroutines.currentCoroutineContext().isActive){
            androidx.compose.runtime.withFrameNanos {
                val head=viewer.get()
                val parent=findDeskSpace(session)
                val resumed=session.lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                if(head==null || parent==null || !resumed){hud.hide();return@withFrameNanos}
                val fov=angles();val selected=options
                hud.place(parent,head,units(),fov[0],fov[1],selected.first,selected.third)
                val now=SystemClock.uptimeMillis()
                if(now-sampled>=250 || hud.needsRedraw){
                    val snapshot=connection.diagnosticSnapshot()
                    hud.draw(snapshot.copy(state=snapshot.state.copy(enabled=selected.second,showOverlay=selected.third,showResources=selected.first)),connection.presentation().rects,now)
                    sampled=now
                }
            }
        }
    }
}

/** Shared desktop sphere geometry and video UV convention; only the four glyph regions have geometry. */
@android.annotation.SuppressLint("RestrictedApi")
@OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class SphericalDiagnosticsHud(private val session:androidx.xr.runtime.Session):AutoCloseable {
    private var entity:androidx.xr.scenecore.SurfaceEntity?=null
    private var layout:HudSurfaceLayout?=null
    private var visibility=0
    private var shown=false
    private var placedScale=Float.NaN
    private var placedParent:androidx.xr.scenecore.Entity?=null
    var needsRedraw=true;private set
    fun place(parent:androidx.xr.scenecore.Entity,head:dev.floatdesk.client.workspace.Pose,units:Float,horizontal:Float,vertical:Float,resources:Boolean,quality:Boolean){
        val scale=units.takeIf{it.isFinite() && it>0f} ?: 1f
        val next=hudSurfaceLayout(horizontal,vertical,ViewLayers.DiagnosticsDistanceM)
        val enabled=(if(resources)1 else 0) or (if(quality)2 else 0)
        if(entity==null || layout?.width!=next.width || layout?.height!=next.height || visibility!=enabled){
            val mesh=MonitorMesh(next.width,next.height,floatArrayOf(0f,0f,ViewLayers.DiagnosticsDistanceM),focus=floatArrayOf(0f,0f))
            val regions=next.regions.filterIndexed{index,_->if(index<2)resources else quality}.flatMap{listOf(it.left,it.top,it.right,it.bottom)}.toFloatArray()
            val geometry=clippedVideoGeometry(next.width,next.height,mesh,regions,0f)
            val shape=androidx.xr.scenecore.SurfaceEntity.Shape.CustomMesh(androidx.xr.scenecore.SurfaceEntity.Shape.TriangleMesh(
                java.nio.FloatBuffer.wrap(geometry.positions),java.nio.FloatBuffer.wrap(geometry.texCoords),java.nio.IntBuffer.wrap(geometry.indices)))
            val target=entity ?: androidx.xr.scenecore.SurfaceEntity.create(session,shape=shape,parent=parent,
                superSampling=androidx.xr.scenecore.SurfaceEntity.SuperSampling.NONE).also{
                it.mediaBlendingMode=androidx.xr.scenecore.SurfaceEntity.MediaBlendingMode.TRANSPARENT
                entity=it
            }
            target.shape=shape
            if(layout?.pixelWidth!=next.pixelWidth || layout?.pixelHeight!=next.pixelHeight)
                target.setSurfacePixelDimensions(androidx.xr.runtime.math.IntSize2d(next.pixelWidth,next.pixelHeight))
            layout=next;visibility=enabled;needsRedraw=true
            android.util.Log.i("Floatdesk","HUD spherical canvas ${next.pixelWidth}x${next.pixelHeight} glyphs=60px mask=$enabled")
        }
        val camera=Pose(Vector3(head.position[0],head.position[1],head.position[2]),androidx.xr.runtime.math.Quaternion(head.rotation[0],head.rotation[1],head.rotation[2],head.rotation[3]))
        entity?.let{
            if(placedParent!==parent){it.parent=parent;placedParent=parent}
            if(placedScale!=scale){it.setScale(scale);placedScale=scale}
            it.setPose(camera.compose(Pose(Vector3(0f,0f,-ViewLayers.DiagnosticsDistanceM*scale))))
            if(!shown){it.setEnabled(true);shown=true}
        }
    }
    fun draw(snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long){
        val target=entity ?: return;val surface=layout ?: return
        val canvas=target.getSurface().lockCanvas(null)
        try{DiagnosticsCanvas.drawSurface(canvas,snapshot,screens,now,surface);needsRedraw=false}
        finally{target.getSurface().unlockCanvasAndPost(canvas)}
    }
    fun hide(){if(shown)entity?.setEnabled(false);shown=false}
    override fun close(){entity?.dispose();entity=null;layout=null}
}

/** Canvas-only diagnostics, separate from scene placement and desktop video rendering. */
internal object DiagnosticsCanvas {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE;textSize=60f }
    /** Offscreen overview used by regression checks. */
    fun draw(canvas:Canvas,snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        val width=canvas.width*.40f;val height=canvas.height*.23f
        for(i in 0..3){
            val saved=canvas.save()
            val x=if(i%2==0)0f else canvas.width-width
            val y=if(i<2)0f else canvas.height-height
            canvas.clipRect(x,y,x+width,y+height);canvas.translate(x,y);canvas.scale(width/800f,height/400f)
            drawCluster(canvas,snapshot,screens,now,i);canvas.restoreToCount(saved)
        }
    }
    fun drawSurface(canvas:Canvas,snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long,layout:HudSurfaceLayout){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        for((index,r) in layout.regions.withIndex()){
            val left=r.left*canvas.width;val top=r.top*canvas.height
            val width=(r.right-r.left)*canvas.width;val height=(r.bottom-r.top)*canvas.height
            val saved=canvas.save();canvas.clipRect(left,top,left+width,top+height)
            canvas.translate(left,top);canvas.scale(width/800f,height/400f)
            drawCluster(canvas,snapshot,screens,now,index);canvas.restoreToCount(saved)
        }
    }
    fun drawCluster(canvas:Canvas,snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long,index:Int){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        val state=snapshot.state
        if(index<2 && !state.showResources || index>=2 && !state.showOverlay)return
        val title=listOf("HOST","HEADSET","STREAM","QUALITY")[index]
        fun metric(text:String,label:String)=Regex(Regex.escape(label)+"\\s+([^\\s]+)").find(text)?.groupValues?.get(1) ?: "—"
        fun value(text:String,suffix:String)=Regex("[0-9.]+"+suffix).find(text)?.value ?: "—"
        val host=state.hostResources;val local=state.localResources
        val transport=state.transport.split(" · ")
        val text=when(index){
            0->"CPU ${metric(host,"CPU(core)")} GPU ${metric(host,"GPU(proc)")}\n"+
                "RAM ${metric(host,"RSS").replace("MiB","M")} free ${metric(host,"free").replace("MiB","M")}\n"+
                "${value(host,"fps")} ${value(host,"Mbps")}\nCap ${metric(host,"cap")} Enc ${metric(host,"enc")}"
            1->"CPU ${metric(local,"CPU(core)")} GPU ${metric(local,"GPU")}\n"+
                "RAM ${metric(local,"PSS").replace("MiB","M")} Gfx ${metric(local,"gfx").replace("MiB","M")}\n"+
                "Free ${metric(local,"free").replace("MiB","M")}\nImage ${metric(snapshot.presentation,"Image")}\n"+
                "Draw ${metric(snapshot.presentation,"redraw").replace(".0fps","fps")} GL${metric(snapshot.presentation,"submit")}"
            2->if(state.transport.contains("Normal density:"))"Normal stream\n${if(state.transport.contains("source already native"))"Source-native stream" else "Zoom in for detail"}\n${state.reason.substringBefore(" · ")}\nRX ${metric(state.transport,"RX")} err ${metric(state.transport,"errors")}" else "${if(snapshot.frame!=null)"VIEW" else "NORMAL"} · ${snapshot.frame?.let{"${now-it.capturedAt}ms"} ?: "n/a"}\n${state.reason}\n"+
                (transport.firstOrNull{it.contains("HEVC") || it.contains("H264")}?.replace("native tiles ","") ?: transport.last())+
                "\nRX ${metric(state.transport,"RX")} err ${metric(state.transport,"errors")}\n"+transport.last()
            else->snapshot.sampling.replace(" · ","\n").replace("effective ","Gain ").ifEmpty{"Waiting for video"}
        }
        paint.style=Paint.Style.FILL;paint.color=when(index){0->Color.CYAN;1->Color.rgb(210,170,255);2->Color.rgb(150,255,190);else->Color.rgb(255,210,100)}
        paint.textSize=64f;paint.typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        val titleInk=android.graphics.Rect()
        paint.getTextBounds(title,0,title.length,titleInk)
        val titleBaseline=12f-titleInk.top
        canvas.drawText(title,18f,titleBaseline,paint)
        paint.typeface=Typeface.MONOSPACE;paint.textSize=60f
        val bodyInk=android.graphics.Rect();val sample="Agjp0123456789"
        paint.getTextBounds(sample,0,sample.length,bodyInk)
        val headerBottom=maxOf(titleBaseline+titleInk.bottom,if(index==3)78f else 0f)
        val bodyBaseline=headerBottom+16f-bodyInk.top
        val lineStep=bodyInk.height()+6f
        val left=18f
        val maxWidth=780f-left
        var row=0
        for(paragraph in text.lines()){
            var remaining=paragraph
            while(remaining.isNotEmpty() && row<5 && bodyBaseline+row*lineStep+bodyInk.bottom<=396f){
                val fit=paint.breakText(remaining,true,maxWidth,null).coerceAtLeast(1)
                val end=if(fit<remaining.length)remaining.lastIndexOf(' ',fit).takeIf{it>0} ?: fit else fit
                canvas.drawText(remaining.substring(0,end),left,bodyBaseline+row*lineStep,paint)
                remaining=remaining.substring(end).trimStart();row++
            }
        }
        if(index==3){
            val map=RectF(610f,12f,780f,78f)
            fun rect(r:FocusRect)=RectF(map.left+r.left*map.width(),map.top+r.top*map.height(),map.left+r.right*map.width(),map.top+r.bottom*map.height())
            paint.strokeWidth=3f
            for(i in 0 until screens.size/4){
                val n=i*4;val r=rect(FocusRect(screens[n],screens[n+1],screens[n+2],screens[n+3]))
                paint.style=Paint.Style.STROKE;paint.color=Color.LTGRAY;canvas.drawRect(r,paint)
                paint.style=Paint.Style.FILL;paint.textSize=24f;canvas.drawText("${i+1}",r.centerX()-8f,r.centerY()+10f,paint)
            }
            paint.style=Paint.Style.STROKE
            state.candidate?.let{paint.color=Color.rgb(255,190,30);canvas.drawRect(rect(it),paint)}
            snapshot.frame?.inner?.let{paint.color=Color.GREEN;canvas.drawRect(rect(it),paint)}
        }
    }
}
