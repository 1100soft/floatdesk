package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SurfaceEntity
import dev.floatdesk.client.net.DiagnosticSnapshot

/** Independent view-following canvas. No input, move or resize components are attached. */
@OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class DiagnosticsOverlay(private val session:Session):AutoCloseable {
    private var entity:SurfaceEntity?=null
    private var drawnAt=0L
    private var scale=Float.NaN
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE;textSize=22f }
    fun update(root:Entity,head:Pose?,units:Float,snapshot:DiagnosticSnapshot,screens:FloatArray) {
        val state=snapshot.state
        val debug=state.enabled && state.showOverlay
        if(head==null || !debug && !state.showResources){entity?.parent=null;return}
        val panel=entity ?: SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(.72f,.27f)),parent=null,superSampling=SurfaceEntity.SuperSampling.NONE).also {
            it.setSurfacePixelDimensions(IntSize2d(1280,480));entity=it
        }
        if(scale!=units){panel.setScale(units);scale=units}
        panel.setPose(head.compose(Pose(Vector3(0f,-.30f*units,-.85f*units))))
        panel.parent=root
        val now=SystemClock.uptimeMillis()
        if(now-drawnAt<250)return
        val canvas=panel.getSurface().lockCanvas(null)
        try { draw(canvas,snapshot,screens,now) } finally {panel.getSurface().unlockCanvasAndPost(canvas)}
        drawnAt=now
    }
    private fun draw(canvas:Canvas,snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long) {
        canvas.drawColor(Color.rgb(15,21,30))
        val state=snapshot.state;val debug=state.enabled && state.showOverlay
        val lines=mutableListOf<String>()
        if(state.showResources){lines.addAll(state.hostResources.lines());lines.add(state.localResources);lines.add(snapshot.presentation);lines.add("CPU: 100% = one core")}
        if(debug){
            lines.add("VIEW ${if(snapshot.frame!=null)"ACTIVE" else "BASELINE"} · age ${snapshot.frame?.let{"${now-it.capturedAt}ms"} ?: "n/a"}")
            lines.add(state.reason);lines.add(state.transport)
            lines.add(java.lang.String.format(java.util.Locale.ROOT,"Dense sampling gain X%.2fx Y%.2fx",state.gainX,state.gainY))
            lines.add("Map: amber visible crop · green decoded dense area")
        }
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        lines.forEachIndexed { i,line->canvas.drawText(line,if(debug)278f else 16f,32f+i*43f,paint) }
        if(debug){
            val map=RectF(14f,80f,254f,320f)
            fun rect(r:FocusRect)=RectF(map.left+r.left*map.width(),map.top+r.top*map.height(),map.left+r.right*map.width(),map.top+r.bottom*map.height())
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=Color.GRAY
            for(i in 0 until screens.size/4){val n=i*4;canvas.drawRect(rect(FocusRect(screens[n],screens[n+1],screens[n+2],screens[n+3])),paint)}
            state.candidate?.let {paint.color=Color.rgb(255,190,30);canvas.drawRect(rect(it),paint)}
            snapshot.frame?.inner?.let {paint.style=Paint.Style.FILL;paint.color=Color.argb(100,20,255,50);canvas.drawRect(rect(it),paint);paint.style=Paint.Style.STROKE;paint.color=Color.GREEN;canvas.drawRect(rect(it),paint)}
        }
    }
    @Suppress("DEPRECATION")
    @SuppressLint("RestrictedApi")
    override fun close(){entity?.let{it.parent=null;it.dispose()};entity=null}
}
