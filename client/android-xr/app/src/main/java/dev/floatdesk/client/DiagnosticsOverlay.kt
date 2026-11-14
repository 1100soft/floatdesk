package dev.floatdesk.client

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
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
@SuppressLint("RestrictedApi")
@OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
internal class DiagnosticsOverlay(private val session:Session):AutoCloseable {
    private var entity:SurfaceEntity?=null
    private var drawnAt=0L
    private var scale=Float.NaN
    fun update(root:Entity,head:Pose?,units:Float,snapshot:DiagnosticSnapshot,screens:FloatArray,clearance:Float,verticalHalfAngle:Float) {
        val state=snapshot.state
        val debug=state.enabled && state.showOverlay
        if(head==null || !debug && !state.showResources){entity?.parent=null;return}
        val panel=entity ?: SurfaceEntity.create(session,shape=SurfaceEntity.Shape.Quad(FloatSize2d(.72f,.27f)),parent=null,superSampling=SurfaceEntity.SuperSampling.NONE).also {
            it.setSurfacePixelDimensions(IntSize2d(1280,480));it.mediaBlendingMode=SurfaceEntity.MediaBlendingMode.TRANSPARENT;entity=it
        }
        // Keep angular size constant as the HUD moves in front of nearby desktop geometry.
        val distance=(clearance*.6f).coerceIn(.35f,.5f)
        val halfAngle=verticalHalfAngle.takeIf{it.isFinite() && it>0f} ?: .6f
        val upper=kotlin.math.tan(halfAngle*.9f)
        val lower=kotlin.math.tan(halfAngle*.25f)
        val angularScale=((upper-lower)/(.27f/.85f)).coerceIn(.4f,1f)
        val vertical=upper-(.27f/(2f*.85f))*angularScale
        val nextScale=units*distance/.85f*angularScale
        if(scale!=nextScale){panel.setScale(nextScale);scale=nextScale}
        panel.setPose(head.compose(Pose(Vector3(0f,vertical*distance*units,-distance*units))))
        panel.parent=root
        val now=SystemClock.uptimeMillis()
        if(now-drawnAt<250)return
        val canvas=panel.getSurface().lockCanvas(null)
        try { DiagnosticsCanvas.draw(canvas,snapshot,screens,now) } finally {panel.getSurface().unlockCanvasAndPost(canvas)}
        drawnAt=now
    }
    @Suppress("DEPRECATION")
    @SuppressLint("RestrictedApi")
    override fun close(){entity?.let{it.parent=null;it.dispose()};entity=null}
}

/** Canvas-only diagnostics, separate from scene placement and desktop video rendering. */
internal object DiagnosticsCanvas {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE;textSize=22f }
    fun draw(canvas:Canvas,snapshot:DiagnosticSnapshot,screens:FloatArray,now:Long) {
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        val state=snapshot.state;val debug=state.enabled && state.showOverlay
        val lines=mutableListOf<String>()
        if(state.showResources){lines.addAll(state.hostResources.lines());lines.add(state.localResources);lines.add(snapshot.presentation);lines.add("CPU: 100% = one core")}
        if(debug){
            lines.add("VIEW ${if(snapshot.frame!=null)"ACTIVE" else "BASELINE"} · age ${snapshot.frame?.let{"${now-it.capturedAt}ms"} ?: "n/a"}")
            lines.add(state.reason);lines.add(state.transport)
            lines.add(java.lang.String.format(java.util.Locale.ROOT,"Dense sampling gain X%.2fx Y%.2fx",state.gainX,state.gainY))
            lines.add(snapshot.sampling.substringBefore(" · effective "))
            if(snapshot.sampling.contains(" · effective "))lines.add("Effective sampling gain " + snapshot.sampling.substringAfter(" · effective "))
        }
        paint.style=Paint.Style.FILL;paint.color=Color.CYAN
        lines.forEachIndexed { i,line->
            val x=if(debug)278f else 16f
            paint.textSize=22f
            val measured=paint.measureText(line)
            if(measured>1264-x)paint.textSize*=((1264-x)/measured)
            canvas.drawText(line,x,32f+i*43f,paint)
        }
        if(debug){
            val map=RectF(14f,80f,254f,320f)
            fun rect(r:FocusRect)=RectF(map.left+r.left*map.width(),map.top+r.top*map.height(),map.left+r.right*map.width(),map.top+r.bottom*map.height())
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=Color.LTGRAY
            for(i in 0 until screens.size/4){val n=i*4;canvas.drawRect(rect(FocusRect(screens[n],screens[n+1],screens[n+2],screens[n+3])),paint)}
            state.candidate?.let {paint.color=Color.rgb(255,190,30);canvas.drawRect(rect(it),paint)}
            snapshot.frame?.inner?.let {paint.style=Paint.Style.STROKE;paint.color=Color.GREEN;canvas.drawRect(rect(it),paint)}
        }
    }
}
