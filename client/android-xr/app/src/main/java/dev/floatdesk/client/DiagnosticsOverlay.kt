package dev.floatdesk.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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

/** The same Compose spatial-panel path used by the working touch controls. */
@androidx.compose.runtime.Composable
internal fun DiagnosticsPanel(connection:dev.floatdesk.client.net.DeskConnection,
    viewer:java.util.concurrent.atomic.AtomicReference<dev.floatdesk.client.workspace.Pose?>,
    units:()->Float,angles:()->FloatArray,density:androidx.compose.ui.unit.Density,pixelDensity:androidx.xr.scenecore.PixelDensity,
    resources:Boolean,quality:Boolean,qualityDebug:Boolean) {
    if(!resources && !qualityDebug)return
    var head by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(viewer.get()) }
    var snapshot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(connection.diagnosticSnapshot()) }
    androidx.compose.runtime.LaunchedEffect(connection,viewer){
        var sampled=0L
        while(kotlinx.coroutines.currentCoroutineContext().isActive){
            head=viewer.get()
            val now=SystemClock.uptimeMillis()
            if(now-sampled>=250){snapshot=connection.diagnosticSnapshot();sampled=now}
            kotlinx.coroutines.delay(20)
        }
    }
    val h=head ?: return
    val unitsNow=units().takeIf{it.isFinite() && it>0f} ?: 1f
    val fov=angles()
    val boxes=peripheralHudPanels(fov[0],fov[1],ViewLayers.DiagnosticsDistanceM)
    val state=snapshot.copy(state=snapshot.state.copy(enabled=quality,showOverlay=qualityDebug,showResources=resources))
    val camera=Pose(Vector3(h.position[0],h.position[1],h.position[2]),androidx.xr.runtime.math.Quaternion(h.rotation[0],h.rotation[1],h.rotation[2],h.rotation[3]))
    for((index,box) in boxes.withIndex()){
        if(index<2 && !resources || index>=2 && !qualityDebug)continue
        val pose=camera.compose(Pose(Vector3(box.x*unitsNow,box.y*unitsNow,-ViewLayers.DiagnosticsDistanceM*unitsNow))).let{
            dev.floatdesk.client.workspace.Pose(floatArrayOf(it.translation.x,it.translation.y,it.translation.z),floatArrayOf(it.rotation.x,it.rotation.y,it.rotation.z,it.rotation.w))}
        androidx.compose.runtime.key(index){
            androidx.xr.compose.subspace.SpatialPanel(modifier=placedPanel(pose,box.width*unitsNow,box.height*unitsNow,density,pixelDensity)){
                androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()){
                    drawIntoCanvas { target ->
                        val canvas=target.nativeCanvas;val saved=canvas.save()
                        canvas.scale(size.width/800f,size.height/400f)
                        DiagnosticsCanvas.drawCluster(canvas,state,connection.presentation().rects,SystemClock.uptimeMillis(),index)
                        canvas.restoreToCount(saved)
                    }
                }
            }
        }
    }
}

/** Canvas-only diagnostics, separate from scene placement and desktop video rendering. */
internal object DiagnosticsCanvas {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE;textSize=40f }
    /** Offscreen overview used by regression checks; the application uses independent panels. */
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
    fun drawCluster(canvas:Canvas,snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long,index:Int){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        val state=snapshot.state
        if(index<2 && !state.showResources || index>=2 && !state.showOverlay)return
        val title=listOf("HOST","HEADSET","STREAM","QUALITY")[index]
        val text=when(index){
            0->state.hostResources
            1->state.localResources+"\n"+snapshot.presentation+"\nCPU 100% = one core"
            2->"${if(snapshot.frame!=null)"VIEW ACTIVE" else "NORMAL STREAM"} · frame age ${snapshot.frame?.let{"${now-it.capturedAt}ms"} ?: "n/a"}\n${state.reason}\n${state.transport}"
            else->snapshot.sampling.replace(" · ","\n").ifEmpty{"Waiting for video"}
        }
        paint.style=Paint.Style.FILL;paint.color=when(index){0->Color.CYAN;1->Color.rgb(210,170,255);2->Color.rgb(150,255,190);else->Color.rgb(255,210,100)}
        paint.textSize=44f;paint.typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        canvas.drawText(title,18f,44f,paint)
        paint.typeface=Typeface.MONOSPACE;paint.textSize=40f
        val left=if(index==3)280f else 18f
        val maxWidth=780f-left
        var row=0
        for(paragraph in text.lines()){
            var remaining=paragraph
            while(remaining.isNotEmpty() && row<6){
                val fit=paint.breakText(remaining,true,maxWidth,null).coerceAtLeast(1)
                val end=if(fit<remaining.length)remaining.lastIndexOf(' ',fit).takeIf{it>0} ?: fit else fit
                canvas.drawText(remaining.substring(0,end),left,100f+row*54f,paint)
                remaining=remaining.substring(end).trimStart();row++
            }
        }
        if(index==3){
            val map=RectF(18f,100f,248f,330f)
            fun rect(r:FocusRect)=RectF(map.left+r.left*map.width(),map.top+r.top*map.height(),map.left+r.right*map.width(),map.top+r.bottom*map.height())
            paint.strokeWidth=3f
            for(i in 0 until screens.size/4){
                val n=i*4;val r=rect(FocusRect(screens[n],screens[n+1],screens[n+2],screens[n+3]))
                paint.style=Paint.Style.STROKE;paint.color=Color.LTGRAY;canvas.drawRect(r,paint)
                paint.style=Paint.Style.FILL;paint.textSize=28f;canvas.drawText("${i+1}",r.centerX()-8f,r.centerY()+10f,paint)
            }
            paint.style=Paint.Style.STROKE
            state.candidate?.let{paint.color=Color.rgb(255,190,30);canvas.drawRect(rect(it),paint)}
            snapshot.frame?.inner?.let{paint.color=Color.GREEN;canvas.drawRect(rect(it),paint)}
        }
    }
}
