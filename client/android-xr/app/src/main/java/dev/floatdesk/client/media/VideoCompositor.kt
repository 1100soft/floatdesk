package dev.floatdesk.client.media

import dev.floatdesk.client.ViewProjection
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Normalized top-left video coordinates. At most 256 colored border segments and two contacts. */
internal data class VideoAim @JvmOverloads constructor(
    val segments: FloatArray = floatArrayOf(), val contacts: FloatArray = floatArrayOf(),
    val thickness: Float = 2f, val opacity: Float = .18f, val colors: FloatArray = floatArrayOf(),
)

/** One GPU pass keeps aiming decorations and live video in the same compositor layer. */
internal class VideoCompositor(width: Int, height: Int) : AutoCloseable {
    private val thread=HandlerThread("floatdesk-video-gl").also { it.start() }
    private val handler=Handler(thread.looper)
    private val ready=CountDownLatch(1)
    private val redrawPosted=AtomicBoolean(false)
    private val presentationPosted=AtomicBoolean(false)
    @Volatile private var closed=false
    @Volatile private var failure: Throwable?=null
    @Volatile private var aim=VideoAim()
    @Volatile private var presentation=ScreenPresentation()
    private var display=EGL14.EGL_NO_DISPLAY
    private var context=EGL14.EGL_NO_CONTEXT
    private var pbuffer=EGL14.EGL_NO_SURFACE
    private var window=EGL14.EGL_NO_SURFACE
    private var config: android.opengl.EGLConfig?=null
    private var target: Surface?=null
    private lateinit var texture: SurfaceTexture
    lateinit var input: Surface
        private set
    private val detailPending=java.util.concurrent.atomic.AtomicReference<dev.floatdesk.client.net.DetailPatch?>(null)
    private val detailPosted=AtomicBoolean(false)
    @Volatile private var diagnostics=dev.floatdesk.client.net.DetailDiagnostics()
    private val diagnosticsPosted=AtomicBoolean(false)
    private var detailUploads=0L
    private var hudTexture=0
    private var hudProgram=0
    private var hudAt=0L
    private var hudActive=false
    private val debugTick=object:Runnable {override fun run(){if(closed || !diagnostics.enabled)return;guard{draw()};handler.postDelayed(this,250)}}
    private var hudBitmap:android.graphics.Bitmap?=null
    private var detailTexture=0
    private var detailRect:dev.floatdesk.client.FocusRect?=null
    private var detailAt=0L
    private var detailSourceWidth=4096
    private var detailSourceHeight=2160
    @Volatile private var detailCursor=floatArrayOf(-1f,-1f)
    private var textureId=0
    private var lookupTexture=0
    private var uploadedProjection:ViewProjection?=null
    private var lookupSize=0 to 0
    @Volatile private var viewProjection:ViewProjection?=null
    private val projectionPosted=AtomicBoolean(false)
    private var program=0
    private var inkProgram=0
    private var hasFrame=false
    private var lastDraw=0L
    private val transform=FloatArray(16)
    private val vertices=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)).also { it.position(0) }

    init {
        handler.post {
            try {
                display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
                val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
                check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
                config=checkNotNull(configs[0])
                context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
                check(context!=EGL14.EGL_NO_CONTEXT)
                pbuffer=EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
                check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
                val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); textureId=ids[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                texture=SurfaceTexture(textureId).also { it.setDefaultBufferSize(width,height) }
                input=Surface(texture)
                texture.setOnFrameAvailableListener({
                    if(!closed) guard {
                        texture.updateTexImage(); texture.getTransformMatrix(transform); hasFrame=true; draw()
                    }
                },handler)
                val detailIds=IntArray(1);GLES20.glGenTextures(1,detailIds,0);detailTexture=detailIds[0]
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,detailTexture)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,1,1,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4))
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                program=link(VERTEX,FRAGMENT)
                inkProgram=link(INK_VERTEX,INK_FRAGMENT)
                hudProgram=link(VERTEX,HUD_FRAGMENT)
                Log.i("Floatdesk","live video compositor ready ${width}x$height")
            } catch(error: Throwable) { failure=error }
            finally { ready.countDown() }
        }
        check(ready.await(5,TimeUnit.SECONDS)) { "video compositor initialization timed out" }
        failure?.let { close(); throw IllegalStateException("video compositor initialization failed",it) }
    }

    fun output(surface: Surface?) {
        if(closed) return
        handler.post {
            if(closed || target===surface) return@post
            guard {
                check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context))
                if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
                window=EGL14.EGL_NO_SURFACE; target=surface
                if(surface!=null && surface.isValid) {
                    window=EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
                    check(window!=EGL14.EGL_NO_SURFACE) { "could not bind video output" }
                    check(EGL14.eglMakeCurrent(display,window,window,context))
                    draw()
                }
            }
        }
    }

    fun detailDebug(value:dev.floatdesk.client.net.DetailDiagnostics) {
        val before=diagnostics;diagnostics=value
        if(!before.enabled && value.enabled && !closed)handler.postDelayed(debugTick,250)
        if(!value.enabled)handler.removeCallbacks(debugTick)
        if(before==value || closed || !diagnosticsPosted.compareAndSet(false,true))return
        handler.post {diagnosticsPosted.set(false);if(!closed)guard{draw()}}
    }

    fun detailCursor(u:Float,v:Float){detailCursor=floatArrayOf(u,v)}

    fun detail(patch:dev.floatdesk.client.net.DetailPatch?) {
        if(closed){patch?.bitmap?.recycle();return}
        detailPending.getAndSet(patch)?.bitmap?.recycle()
        if(!detailPosted.compareAndSet(false,true))return
        handler.post {
            val patch=detailPending.getAndSet(null)
            detailPosted.set(false)
            if(closed){patch?.bitmap?.recycle();return@post}
            try {
                if(patch==null || android.os.SystemClock.uptimeMillis()-patch.capturedAt>=300){detailRect=null;draw();return@post}
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                if(detailTexture==0){val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);detailTexture=ids[0]}
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,detailTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,patch.bitmap,0)
                detailUploads++
                detailRect=patch.rect;detailAt=patch.capturedAt
                detailSourceWidth=patch.sourceWidth;detailSourceHeight=patch.sourceHeight
                draw()
                val stamp=detailAt
                handler.postDelayed({if(!closed && stamp==detailAt){detailRect=null;guard{draw()}}},(300-(android.os.SystemClock.uptimeMillis()-stamp)).coerceAtLeast(0))
            } catch(error:Exception){detailRect=null;Log.w("Floatdesk","Viewed detail upload failed",error)}
            finally {patch?.bitmap?.recycle();GLES20.glActiveTexture(GLES20.GL_TEXTURE0)}
        }
    }

    fun overlay(value: VideoAim) {
        val before=aim
        if(before.thickness==value.thickness && before.opacity==value.opacity &&
            before.segments.contentEquals(value.segments) && before.contacts.contentEquals(value.contacts) &&
            before.colors.contentEquals(value.colors)) return
        aim=value
        if(!closed && redrawPosted.compareAndSet(false,true)) handler.post {
            redrawPosted.set(false)
            if(!closed && System.nanoTime()-lastDraw>12_000_000L) guard { draw() }
        }
    }

    fun presentation(value:ScreenPresentation) {
        val before=presentation
        if(before.spacing==value.spacing && before.rects.contentEquals(value.rects)) return
        presentation=value
        if(!closed && presentationPosted.compareAndSet(false,true)) handler.post {presentationPosted.set(false);if(!closed) guard {draw()}}
    }

    fun projection(value:ViewProjection?) {
        if(viewProjection===value)return
        viewProjection=value
        if(!closed && projectionPosted.compareAndSet(false,true)) handler.post {
            projectionPosted.set(false);if(!closed) guard {draw()}
        }
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch(error: Exception) { Log.e("Floatdesk","live video compositor failed",error) }
    }

    private fun draw() {
        if(!hasFrame || window==EGL14.EGL_NO_SURFACE || target?.isValid!=true) return
        val w=IntArray(1); val h=IntArray(1)
        EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,w,0); EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,h,0)
        GLES20.glViewport(0,0,w[0],h[0]); GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"video"),0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"textureMatrix"),1,false,transform,0)
        // Active sampler types must use distinct texture units even when the branch is off.
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"projectionMap"),1)
        if(lookupTexture==0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            val id=IntArray(1);GLES20.glGenTextures(1,id,0);lookupTexture=id[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,lookupTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,1,1,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"detailImage"),2)
        val region=detailRect?.takeIf {android.os.SystemClock.uptimeMillis()-detailAt<300}
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"detailEnabled"),if(region!=null)1 else 0)
        if(region!=null){
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,detailTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"detailImage"),2)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"detailRect"),region.left,region.top,region.right,region.bottom)
            val cursor=detailCursor
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"detailCursor"),cursor[0],cursor[1],64f/detailSourceWidth,64f/detailSourceHeight)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        val debug=diagnostics
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"detailDebug"),if(debug.enabled)1 else 0)
        val candidate=debug.candidate
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"candidateRect"),candidate?.left ?: -1f,candidate?.top ?: -1f,candidate?.right ?: -1f,candidate?.bottom ?: -1f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"debugPixel"),2f/w[0],2f/h[0])
        val projection=viewProjection
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"projectionEnabled"),if(projection==null) 0 else 1)
        if(projection!=null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            if(lookupTexture==0) {val id=IntArray(1);GLES20.glGenTextures(1,id,0);lookupTexture=id[0]}
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,lookupTexture)
            if(uploadedProjection!==projection) {
                val bytes=ByteBuffer.allocateDirect(projection.encodedRgba.size).put(projection.encodedRgba);bytes.position(0)
                val size=projection.columns+1 to projection.rows+1
                if(size!=lookupSize) {
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,size.first,size.second,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
                    lookupSize=size
                } else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,size.first,size.second,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
                uploadedProjection=projection
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"projectionMap"),1)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"projectionGrid"),projection.columns.toFloat(),projection.rows.toFloat())
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        val mapping=presentation
        GLES20.glClearColor(0f,0f,0f,0f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"contentScale"),1f-mapping.spacing.coerceIn(0f,.15f))
        val position=GLES20.glGetAttribLocation(program,"position")
        GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
        // Submit bounded uniform batches, rather than imposing an output-count limit.
        for(start in 0 until mapping.rects.size/4 step 6) {
            val count=minOf(6,mapping.rects.size/4-start)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"screenCount"),count)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program,"screenRects[0]"),count,mapping.rects,start*4)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        }
        drawInk(w[0],h[0])
        drawDiagnostics(w[0],h[0],region!=null)
        check(EGL14.eglSwapBuffers(display,window)) { "video swap failed" }
        lastDraw=System.nanoTime()
    }

    /** Video-layer HUD: no new native input target or independent overlay entity. */
    private fun drawDiagnostics(width:Int,height:Int,active:Boolean) {
        val debug=diagnostics
        if(!debug.enabled)return
        val now=android.os.SystemClock.uptimeMillis()
        val candidate=debug.candidate ?: dev.floatdesk.client.FocusRect(.15f,.2f,.85f,.8f)
        val pieces=mutableListOf<dev.floatdesk.client.FocusRect>()
        val mapping=presentation;val scale=1f-mapping.spacing.coerceIn(0f,.15f)
        for(i in 0 until mapping.rects.size/4){
            val n=i*4;val r=dev.floatdesk.client.FocusRect(mapping.rects[n],mapping.rects[n+1],mapping.rects[n+2],mapping.rects[n+3])
            val hit=r.intersect(candidate) ?: continue
            pieces.add(dev.floatdesk.client.FocusRect(r.centerU+(hit.left-r.centerU)*scale,r.centerV+(hit.top-r.centerV)*scale,r.centerU+(hit.right-r.centerU)*scale,r.centerV+(hit.bottom-r.centerV)*scale))
        }
        val piece=pieces.maxByOrNull{(it.right-it.left)*(it.bottom-it.top)} ?: return
        if(hudTexture==0 || now-hudAt>=200 || hudActive!=active){
            val bitmap=hudBitmap ?: android.graphics.Bitmap.createBitmap(1280,256,android.graphics.Bitmap.Config.ARGB_8888).also{hudBitmap=it}
            val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(15,21,30))
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{textSize=26f;typeface=android.graphics.Typeface.MONOSPACE;color=android.graphics.Color.WHITE}
            val age=if(detailAt>0)"${now-detailAt}ms" else "none"
            val lines=listOf(
                "VIEW DETAIL  ${if(active)"GPU ACTIVE" else "BASELINE ONLY"}  uploads=$detailUploads age=$age",
                debug.reason,
                debug.transport,
                java.lang.String.format(java.util.Locale.ROOT,"density gain X %.2fx / Y %.2fx (both must exceed 1)",debug.gainX,debug.gainY),
                java.lang.String.format(java.util.Locale.ROOT,"crop L%.0f%% T%.0f%% W%.0f%% H%.0f%%",candidate.left*100,candidate.top*100,(candidate.right-candidate.left)*100,(candidate.bottom-candidate.top)*100),
                "AMBER: candidate   GREEN + tint: live GPU detail")
            lines.forEachIndexed{i,line->
                paint.color=if(i==4)android.graphics.Color.rgb(255,192,60) else android.graphics.Color.WHITE
                canvas.drawText(line.take(65),260f,29f+i*42f,paint)
            }
            val mapWidth=minOf(220f,170f*width/height);val mapHeight=mapWidth*height/width
            fun mapRect(r:dev.floatdesk.client.FocusRect)=android.graphics.RectF(12+r.left*mapWidth,40+r.top*mapHeight,12+r.right*mapWidth,40+r.bottom*mapHeight)
            paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=android.graphics.Color.GRAY
            for(i in 0 until mapping.rects.size/4){val n=i*4;canvas.drawRect(mapRect(dev.floatdesk.client.FocusRect(mapping.rects[n],mapping.rects[n+1],mapping.rects[n+2],mapping.rects[n+3])),paint)}
            if(active)detailRect?.let{r->
                paint.style=android.graphics.Paint.Style.FILL;paint.color=android.graphics.Color.argb(75,20,255,50);canvas.drawRect(mapRect(r),paint)
                paint.style=android.graphics.Paint.Style.STROKE;paint.color=android.graphics.Color.GREEN;paint.strokeWidth=4f;canvas.drawRect(mapRect(r),paint)
            }
            paint.color=android.graphics.Color.rgb(255,190,30);paint.strokeWidth=2f;canvas.drawRect(mapRect(candidate),paint)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            if(hudTexture==0){val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);hudTexture=ids[0]}
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,hudTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);hudAt=now;hudActive=active
        }
        var boxWidth=(piece.right-piece.left)*.85f
        var boxHeight=boxWidth*width/height*(256f/1280f)
        val maxHeight=(piece.bottom-piece.top)*.3f
        if(boxHeight>maxHeight){boxWidth*=maxHeight/boxHeight;boxHeight=maxHeight}
        val x=((piece.centerU-boxWidth*.5f)*width).toInt().coerceAtLeast(0)
        val y=((1f-(piece.centerV+boxHeight))*height).toInt().coerceAtLeast(0)
        GLES20.glViewport(x,y,(boxWidth*width).toInt().coerceAtLeast(1),(boxHeight*height).toInt().coerceAtLeast(1))
        GLES20.glUseProgram(hudProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,hudTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hudProgram,"image"),3)
        val position=GLES20.glGetAttribLocation(hudProgram,"position")
        vertices.position(0);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(position)
        GLES20.glViewport(0,0,width,height);GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawInk(width: Int,height: Int) {
        val overlay=aim
        if(overlay.segments.isEmpty() && overlay.contacts.isEmpty()) return
        val values=ArrayList<Float>()
        fun vertex(x: Float,y: Float,alpha: Float,red: Float,green: Float,blue: Float) {
            values.add(x/width*2f-1f); values.add(1f-y/height*2f); values.add(alpha)
            values.add(red); values.add(green); values.add(blue)
        }
        fun line(ax: Float,ay: Float,bx: Float,by: Float,thickness: Float,alpha: Float,red: Float,green: Float,blue: Float) {
            val dx=bx-ax; val dy=by-ay; val length=kotlin.math.hypot(dx,dy)
            if(length<.001f) return
            val ox=-dy/length*thickness*.5f; val oy=dx/length*thickness*.5f
            vertex(ax+ox,ay+oy,alpha,red,green,blue); vertex(ax-ox,ay-oy,alpha,red,green,blue); vertex(bx+ox,by+oy,alpha,red,green,blue)
            vertex(bx+ox,by+oy,alpha,red,green,blue); vertex(ax-ox,ay-oy,alpha,red,green,blue); vertex(bx-ox,by-oy,alpha,red,green,blue)
        }
        for(i in 0 until minOf(256,overlay.segments.size/4)) {
            val n=i*4
            val red=if(overlay.colors.size>=i*3+3) overlay.colors[i*3] else .4236f
            val green=if(overlay.colors.size>=i*3+3) overlay.colors[i*3+1] else .9777f
            val blue=if(overlay.colors.size>=i*3+3) overlay.colors[i*3+2] else 1f
            line(overlay.segments[n]*width,overlay.segments[n+1]*height,
                overlay.segments[n+2]*width,overlay.segments[n+3]*height,overlay.thickness,overlay.opacity,red,green,blue)
        }
        for(i in 0 until minOf(2,overlay.contacts.size/2)) {
            val x=overlay.contacts[i*2]*width; val y=overlay.contacts[i*2+1]*height
            for(j in 0 until 24) {
                val a=j*2.0*Math.PI/24; val b=(j+1)*2.0*Math.PI/24
                line(x+kotlin.math.cos(a).toFloat()*5f,y+kotlin.math.sin(a).toFloat()*5f,
                    x+kotlin.math.cos(b).toFloat()*5f,y+kotlin.math.sin(b).toFloat()*5f,2f,.4f,.4236f,.9777f,1f)
            }
        }
        if(values.isEmpty()) return
        val buffer=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(values.toFloatArray()).also { it.position(0) }
        GLES20.glUseProgram(inkProgram)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA,GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val p=GLES20.glGetAttribLocation(inkProgram,"position")
        val a=GLES20.glGetAttribLocation(inkProgram,"alpha")
        val c=GLES20.glGetAttribLocation(inkProgram,"color")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(a); GLES20.glEnableVertexAttribArray(c)
        buffer.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,24,buffer)
        buffer.position(2); GLES20.glVertexAttribPointer(a,1,GLES20.GL_FLOAT,false,24,buffer)
        buffer.position(3); GLES20.glVertexAttribPointer(c,3,GLES20.GL_FLOAT,false,24,buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,values.size/6)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(a); GLES20.glDisableVertexAttribArray(c); GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun link(vertex: String,fragment: String): Int {
        val linked=GLES20.glCreateProgram()
        for((kind,source) in listOf(GLES20.GL_VERTEX_SHADER to vertex,GLES20.GL_FRAGMENT_SHADER to fragment)) {
            val shader=GLES20.glCreateShader(kind); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
            val status=IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
            check(status[0]!=0) { GLES20.glGetShaderInfoLog(shader) }
            GLES20.glAttachShader(linked,shader); GLES20.glDeleteShader(shader)
        }
        GLES20.glLinkProgram(linked)
        val status=IntArray(1); GLES20.glGetProgramiv(linked,GLES20.GL_LINK_STATUS,status,0)
        check(status[0]!=0) { GLES20.glGetProgramInfoLog(linked) }
        return linked
    }

    override fun close() {
        closed=true
        handler.post {
            hudBitmap?.recycle();hudBitmap=null
            if(::input.isInitialized) input.release()
            if(::texture.isInitialized) texture.release()
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
                if(pbuffer!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,pbuffer)
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
        if(Thread.currentThread()!==thread) thread.join(1000)
    }

    companion object {
        private const val VERTEX="""
            attribute vec2 position;
            uniform mat4 textureMatrix;
            varying vec2 uv;
            varying vec2 sampleUV;
            void main() {
                gl_Position=vec4(position,0.0,1.0);
                uv=position*0.5+0.5;
                sampleUV=(textureMatrix*vec4(uv,0.0,1.0)).xy;
            }
        """
        private const val FRAGMENT="""
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES video;
            uniform mat4 textureMatrix;
            uniform int screenCount;
            uniform vec4 screenRects[6];
            uniform float contentScale;
            uniform sampler2D projectionMap;
            uniform vec2 projectionGrid;
            uniform int projectionEnabled;
            uniform sampler2D detailImage;
            uniform vec4 detailRect;
            uniform vec4 detailCursor;
            uniform int detailEnabled;
            uniform int detailDebug;
            uniform vec4 candidateRect;
            uniform vec2 debugPixel;
            float border(vec2 point,vec4 rect) {
                if(point.x<rect.x || point.y<rect.y || point.x>rect.z || point.y>rect.w)return 0.0;
                vec2 edge=min(point-rect.xy,rect.zw-point)/debugPixel;
                return 1.0-step(1.0,min(edge.x,edge.y));
            }
            varying vec2 uv;
            void main() {
                vec2 point=vec2(uv.x,1.0-uv.y);
                bool found=false;
                gl_FragColor=vec4(0.0);
                for(int i=0;i<6;i++) {
                    if(i<screenCount) {
                        vec4 rect=screenRects[i];
                        vec2 center=(rect.xy+rect.zw)*0.5;
                        vec2 source=center+(point-center)/contentScale;
                        if(source.x>=rect.x && source.x<=rect.z && source.y>=rect.y && source.y<=rect.w) {
                            if(projectionEnabled==1) {
                                vec4 encodedLookup=texture2D(projectionMap,(point*projectionGrid+0.5)/(projectionGrid+1.0));
                                vec2 mapped=vec2(dot(encodedLookup.rg,vec2(65280.0,255.0)),dot(encodedLookup.ba,vec2(65280.0,255.0)))/65535.0;
                                vec2 local=(source-rect.xy)/(rect.zw-rect.xy);
                                vec2 edge=sin(clamp(local,0.0,1.0)*3.14159265359);
                                float weight=edge.x*edge.x*edge.y*edge.y;
                                vec2 limit=0.04*(rect.zw-rect.xy)*contentScale;
                                source=center+(point+clamp(mapped-point,-limit,limit)*weight-center)/contentScale;
                            }
                            vec2 sample=(textureMatrix*vec4(source.x,1.0-source.y,0.0,1.0)).xy;
                            gl_FragColor=vec4(texture2D(video,sample).rgb,1.0);
                            if(detailEnabled==1 && source.x>detailRect.x && source.y>detailRect.y && source.x<detailRect.z && source.y<detailRect.w) {
                                vec2 detailUV=(source-detailRect.xy)/(detailRect.zw-detailRect.xy);
                                vec2 edge=min(detailUV,1.0-detailUV);
                                float feather=smoothstep(0.0,0.035,min(edge.x,edge.y));
                                vec2 cursorDistance=abs(source-detailCursor.xy)/detailCursor.zw;
                                float cursorMask=smoothstep(0.8,1.2,max(cursorDistance.x,cursorDistance.y));
                                gl_FragColor.rgb=mix(gl_FragColor.rgb,texture2D(detailImage,detailUV).rgb,feather*cursorMask);
                            }
                            if(detailDebug==1) {
                                float amber=border(source,candidateRect);
                                gl_FragColor.rgb=mix(gl_FragColor.rgb,vec3(1.0,0.72,0.12),amber);
                                if(detailEnabled==1) {
                                    if(source.x>detailRect.x && source.x<detailRect.z && source.y>detailRect.y && source.y<detailRect.w)
                                        gl_FragColor.rgb=mix(gl_FragColor.rgb,vec3(0.05,1.0,0.2),0.12);
                                    gl_FragColor.rgb=mix(gl_FragColor.rgb,vec3(0.05,1.0,0.2),border(source,detailRect));
                                }
                            }
                            found=true;
                            break;
                        }
                    }
                }
                if(!found) discard;
            }
        """
        private const val HUD_FRAGMENT="""
            precision mediump float;
            uniform sampler2D image;
            varying vec2 uv;
            void main(){gl_FragColor=texture2D(image,vec2(uv.x,1.0-uv.y));}
        """
        private const val INK_VERTEX="""
            attribute vec2 position;
            attribute float alpha;
            attribute vec3 color;
            varying float opacity;
            varying vec3 ink;
            void main() { gl_Position=vec4(position,0.0,1.0); opacity=alpha; ink=color; }
        """
        private const val INK_FRAGMENT="""
            precision mediump float;
            varying float opacity;
            varying vec3 ink;
            void main() { gl_FragColor=vec4(ink,opacity); }
        """
    }
}
