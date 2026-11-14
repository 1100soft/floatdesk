package dev.floatdesk.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.xr.compose.subspace.draw.scale
import androidx.xr.compose.subspace.layout.pointerHoverIcon
import androidx.xr.scenecore.scene
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

/** One foreground UI group. Child surfaces stay outside the central aiming area. */
@androidx.compose.runtime.Composable
internal fun DiagnosticsPanel(connection:dev.floatdesk.client.net.DeskConnection,
    viewer:java.util.concurrent.atomic.AtomicReference<dev.floatdesk.client.workspace.Pose?>,
    units:()->Float,angles:()->FloatArray,resources:Boolean,quality:Boolean,qualityDebug:Boolean) {
    if(!qualityDebug)return
    val session=androidx.xr.compose.platform.LocalSession.current ?: return
    val density=androidx.compose.ui.platform.LocalDensity.current
    val pixelDensity=session.scene.virtualPixelDensity
    var head by androidx.compose.runtime.remember{androidx.compose.runtime.mutableStateOf(viewer.get())}
    var snapshot by androidx.compose.runtime.remember{androidx.compose.runtime.mutableStateOf(connection.diagnosticSnapshot())}
    var stamp by androidx.compose.runtime.remember{androidx.compose.runtime.mutableStateOf(0L)}
    androidx.compose.runtime.LaunchedEffect(connection,viewer){
        while(kotlinx.coroutines.currentCoroutineContext().isActive){
            androidx.compose.runtime.withFrameNanos{
                head=viewer.get()
                val now=SystemClock.uptimeMillis()
                if(now-stamp>=250){snapshot=connection.diagnosticSnapshot();stamp=now}
            }
        }
    }
    val pose=head ?: return
    val fov=angles();val h=kotlin.math.round(fov[0]*100f)/100f;val v=kotlin.math.round(fov[1]*100f)/100f
    val patches=androidx.compose.runtime.remember(h,v){hudForegroundPatches(h,v,ViewLayers.DiagnosticsDistanceM)}
    val state=androidx.compose.runtime.remember(snapshot,resources,quality,qualityDebug){snapshot.copy(state=snapshot.state.copy(enabled=quality,showOverlay=qualityDebug,showResources=resources))}
    val unit=units().takeIf{it.isFinite() && it>0f} ?: 1f
    // Only this parent follows the head. Text refreshes never update individual block poses.
    androidx.xr.compose.subspace.SpatialBox(modifier=placedPanel(pose,unit,unit,density,pixelDensity)){
        for((index,patch) in patches.withIndex()){
            if(index<2 && !resources || index>=2 && !qualityDebug)continue
            androidx.compose.runtime.key(index){
                val texture=androidx.compose.runtime.remember{HudGlyphTexture()}
                androidx.compose.runtime.DisposableEffect(texture){onDispose{texture.close()}}
                val q=androidx.xr.runtime.math.Quaternion.fromLookTowards(Vector3(patch.normal[0],patch.normal[1],patch.normal[2]),Vector3(patch.up[0],patch.up[1],patch.up[2]))
                val local=dev.floatdesk.client.workspace.Pose(FloatArray(3){patch.position[it]*unit},floatArrayOf(q.x,q.y,q.z,q.w))
                val supersample=6f
                androidx.xr.compose.subspace.SpatialPanel(
                    modifier=placedPanel(local,patch.width*unit*supersample,patch.height*unit*supersample,density,pixelDensity).scale(1f/supersample).pointerHoverIcon(androidx.xr.scenecore.SpatialPointerIcon.NONE),
                    shape=androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape(androidx.compose.foundation.shape.CornerSize(androidx.compose.ui.unit.Dp(0f)))){
                    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()){
                        drawIntoCanvas{target->
                            val c=target.nativeCanvas;val saved=c.save();c.scale(size.width/800f,size.height/400f)
                            texture.draw(c,state,connection.presentation().rects,stamp,index,patch.columns,patch.rows,patch.vertices)
                            c.restoreToCount(saved)
                        }
                    }
                }
            }
        }
    }
}

internal class HudGlyphTexture:AutoCloseable{
    val bitmap=android.graphics.Bitmap.createBitmap(800,400,android.graphics.Bitmap.Config.ARGB_8888)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var stamp=Long.MIN_VALUE;private var state:DiagnosticSnapshot?=null
    private var closed=false
    private fun update(value:DiagnosticSnapshot,screens:FloatArray,now:Long,index:Int){
        if(stamp==now && state===value)return
        DiagnosticsCanvas.drawCluster(Canvas(bitmap),value,screens,now,index);stamp=now;state=value
    }
    fun draw(canvas:Canvas,value:DiagnosticSnapshot,screens:FloatArray,now:Long,index:Int,columns:Int,rows:Int,vertices:FloatArray){
        if(closed)return
        update(value,screens,now,index)
        canvas.drawBitmapMesh(bitmap,columns,rows,vertices,0,null,0,paint)
    }
    // Compose/RenderThread can retain recorded draws after disposal. Let the bitmap be
    // collected with those references instead of recycling storage they may still use.
    override fun close(){closed=true}
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
