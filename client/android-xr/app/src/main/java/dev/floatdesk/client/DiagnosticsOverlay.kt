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
import dev.floatdesk.client.net.DiagnosticSnapshot
import dev.floatdesk.client.workspace.ViewLayers

/** View-following Android panel, ordered above video by the scene panel clipping policy. */
internal class DiagnosticsOverlay(private val session:Session):AutoCloseable {
    private var entity:androidx.xr.scenecore.PanelEntity?=null
    private var drawnAt=0L
    private var scale=Float.NaN
    private var data:DiagnosticSnapshot?=null
    private var screens=floatArrayOf(0f,0f,1f,1f)
    private val view=object:android.view.View(session.context){
        init {setBackgroundColor(Color.TRANSPARENT);isClickable=false;isFocusable=false;isEnabled=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onTouchEvent(event:android.view.MotionEvent)=false
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val value=data ?: return
            val checkpoint=canvas.save()
            canvas.scale(width/1280f,height/480f)
            DiagnosticsCanvas.draw(canvas,value,screens,SystemClock.uptimeMillis())
            canvas.restoreToCount(checkpoint)
        }
    }
    fun update(root:Entity,head:Pose?,units:Float,snapshot:DiagnosticSnapshot,screens:FloatArray,verticalHalfAngle:Float) {
        val state=snapshot.state
        if(head==null || !(state.enabled && state.showOverlay) && !state.showResources){entity?.parent=null;return}
        val panel=entity ?: androidx.xr.scenecore.PanelEntity.create(session,view,IntSize2d(1280,480),"Floatdesk diagnostics",parent=null).also {
            it.size=FloatSize2d(.72f,.27f);it.cornerRadius=0f;entity=it
            android.util.Log.i("Floatdesk","Diagnostics Android panel created; depth testing disabled for UI panels")
        }
        // Explicit layer distances: desktop 1.2m, controls .65m, diagnostics .55m.
        val distance=ViewLayers.DiagnosticsDistanceM
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
        data=snapshot;this.screens=screens;view.invalidate();drawnAt=now
    }
    @Suppress("DEPRECATION")
    @SuppressLint("RestrictedApi")
    override fun close(){entity?.let{it.parent=null;it.dispose()};entity=null;data=null}
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
