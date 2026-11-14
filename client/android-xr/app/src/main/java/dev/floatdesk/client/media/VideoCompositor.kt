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
    private val baseWidth=width
    private val baseHeight=height
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
    @Volatile private var diagnostics=dev.floatdesk.client.net.DetailDiagnostics()
    private var detailUploads=0L
    private val debugTick=object:Runnable {override fun run(){if(closed || (!diagnostics.showOverlay && !diagnostics.showResources))return;samplePresentation();handler.postDelayed(this,250)}}
    private var viewTextureId=0
    private lateinit var viewTexture:SurfaceTexture
    lateinit var viewInput:Surface
        private set
    private val viewTransform=FloatArray(16)
    private val viewFrames=java.util.concurrent.ConcurrentSkipListMap<Long,dev.floatdesk.client.net.ViewFrame>()
    private var viewTimestamp=Long.MIN_VALUE
    private var viewLatched=false
    private var retainedTexture=0
    private var retainedFramebuffer=0
    private var copyProgram=0
    @Volatile private var handoff=false
    @Volatile private var outputPixels=0 to 0
    private var rejectedViews=0L
    @Volatile private var viewCurrent:dev.floatdesk.client.net.ViewFrame?=null
    fun displayedQualityFocus():Pair<Float,Float>?=viewCurrent?.takeIf{diagnostics.enabled && viewLatched}?.let{it.focusU to it.focusV}
    @Volatile private var viewPresented:(()->Unit)?=null
    fun onViewPresented(callback:()->Unit){viewPresented=callback}
    private var textureId=0
    private var lookupTexture=0
    private var uploadedProjection:ViewProjection?=null
    private var lookupSize=0 to 0
    @Volatile private var viewProjection:ViewProjection?=null
    private val projectionPosted=AtomicBoolean(false)
    private var program=0
    private var inkProgram=0
    @Volatile var outputLimit:Int=4096
        private set
    private var hasFrame=false
    @Volatile private var sharpening=0f
    fun sharpen(value:Float){val next=value.takeIf{it.isFinite()}?.coerceIn(0f,1.5f) ?: 0f;if(sharpening==next)return;sharpening=next;if(!closed)handler.post{if(!closed)guard{draw()}}}
    private var baseFrames=0L
    private var swaps=0L
    private var submitNs=0L
    private var statsAt=0L;private var statsBase=0L;private var statsView=0L;private var statsSwaps=0L;private var statsSubmit=0L
    @Volatile private var presentationStats="XR image 0fps · submit 0ms"
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
                val maxTexture=IntArray(1);val maxViewport=IntArray(2)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,maxTexture,0);GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS,maxViewport,0)
                outputLimit=minOf(8192,maxTexture[0],maxViewport[0],maxViewport[1]).coerceAtLeast(2)
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
                        texture.updateTexImage(); texture.getTransformMatrix(transform); hasFrame=true;baseFrames++
                        if(handoff){handoff=false;viewCurrent=null;viewLatched=false}
                        draw()
                    }
                },handler)
                val viewIds=IntArray(1);GLES20.glGenTextures(1,viewIds,0);viewTextureId=viewIds[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,viewTextureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                viewTexture=SurfaceTexture(viewTextureId).also{it.setDefaultBufferSize(2048,1536)}
                viewInput=Surface(viewTexture)
                viewTexture.setOnFrameAvailableListener({if(!closed)guard{latchView()}},handler)
                copyProgram=link(VERTEX,COPY_FRAGMENT)
                program=link(VERTEX,FRAGMENT)
                inkProgram=link(INK_VERTEX,INK_FRAGMENT)
                if(diagnostics.showOverlay || diagnostics.showResources)handler.postDelayed(debugTick,250)
                Log.i("Floatdesk","live video compositor ready ${width}x$height")
            } catch(error: Throwable) { failure=error }
            finally { ready.countDown() }
        }
        check(ready.await(5,TimeUnit.SECONDS)) { "video compositor initialization timed out" }
        failure?.let { close(); throw IllegalStateException("video compositor initialization failed",it) }
    }

    private fun latchView() {
        viewTexture.updateTexImage()
        val pts=viewTexture.timestamp/1000
        // Queued callbacks can latch the same newest image. Keep its consumed metadata.
        if(pts==viewTimestamp)return
        val previous=viewTimestamp
        viewTimestamp=maxOf(viewTimestamp,pts)
        val next=if(pts>previous)viewFrames.remove(pts) else null
        viewFrames.headMap(pts,true).clear()
        if(next!=null){
            viewTexture.getTransformMatrix(viewTransform)
            retainAtlas()
            viewCurrent=next;viewLatched=true;handoff=false
            detailUploads++;viewPresented?.invoke()
        } else rejectedViews++ // Keep the last independently cached pixels AND their mapping.
        draw()
    }

    /** One fixed 12 MiB GPU atlas keeps valid pixels independent of decoder/SurfaceTexture lifetime. */
    private fun retainAtlas(){
        if(retainedTexture==0){
            val id=IntArray(1);GLES20.glGenTextures(1,id,0);retainedTexture=id[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,retainedTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,2048,1536,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
            GLES20.glGenFramebuffers(1,id,0);retainedFramebuffer=id[0]
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,retainedFramebuffer)
        try {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,retainedTexture,0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)==GLES20.GL_FRAMEBUFFER_COMPLETE)
            GLES20.glViewport(0,0,2048,1536);GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(copyProgram);GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,viewTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram,"video"),0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram,"textureMatrix"),1,false,viewTransform,0)
            val position=GLES20.glGetAttribLocation(copyProgram,"position")
            vertices.position(0);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(position)
        } finally {GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)}
    }

    @JvmOverloads
    fun output(surface: Surface?,force:Boolean=false) {
        if(closed) return
        handler.post {
            if(closed || target===surface && !force) return@post
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

    fun viewFrame(ptsUs:Long,value:dev.floatdesk.client.net.ViewFrame){
        viewFrames[ptsUs]=value
        while(viewFrames.size>64)viewFrames.pollFirstEntry()
    }
    fun clearView(){viewFrames.clear();if(!closed)handler.post{
        // Hold the cached view through handoffs, until a fresh baseline callback arrives.
        handoff=viewCurrent!=null
    }}

    fun detailDebug(value:dev.floatdesk.client.net.DetailDiagnostics) {
        val before=diagnostics;diagnostics=value
        if(!(before.showOverlay || before.showResources) && (value.showOverlay || value.showResources) && !closed)handler.postDelayed(debugTick,250)
        if(!(value.showOverlay || value.showResources))handler.removeCallbacks(debugTick)
    }

    fun diagnosticSnapshot():dev.floatdesk.client.net.DiagnosticSnapshot = dev.floatdesk.client.net.DiagnosticSnapshot(
        diagnostics,viewCurrent?.takeIf{viewLatched},presentationStats,
        "Output ${outputPixels.first}x${outputPixels.second} · base ${baseWidth}x${baseHeight} · rejected $rejectedViews" +
        (viewCurrent?.let { f -> java.lang.String.format(java.util.Locale.ROOT," · effective X%.2f Y%.2f",minOf(2048f/((f.inner.right-f.inner.left)*baseWidth),outputPixels.first.toFloat()/baseWidth),minOf(1024f/((f.inner.bottom-f.inner.top)*baseHeight),outputPixels.second.toFloat()/baseHeight)) } ?: ""))


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
        if(window==EGL14.EGL_NO_SURFACE || target?.isValid!=true) return
        val drawStarted=System.nanoTime()
        val w=IntArray(1); val h=IntArray(1)
        EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,w,0); EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,h,0)
        outputPixels=w[0] to h[0]
        val view=viewCurrent?.takeIf{viewLatched && (diagnostics.enabled || handoff)}
        if(view==null && !hasFrame)return
        GLES20.glViewport(0,0,w[0],h[0]); GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"viewMode"),if(view!=null)1 else 0)
        if(view!=null){
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"viewOuter"),view.outer.left,view.outer.top,view.outer.right,view.outer.bottom)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"viewInner"),view.inner.left,view.inner.top,view.inner.right,view.inner.bottom)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,if(view!=null)viewTextureId else textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"video"),0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"retainedVideo"),2)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"useRetained"),if(view!=null)1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,retainedTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"textureMatrix"),1,false,if(view!=null)viewTransform else transform,0)
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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"sharpenStrength"),sharpening)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"videoTexel"),if(view!=null)1f/2048 else 1f/baseWidth,if(view!=null)1f/1536 else 1f/baseHeight)
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
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f,0f,0f,0f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if(view!=null)mapping.displayBounds(view.outer)?.let{bounds->
            val left=kotlin.math.floor(bounds.left*w[0]).toInt().coerceIn(0,w[0]);val right=kotlin.math.ceil(bounds.right*w[0]).toInt().coerceIn(left,w[0])
            val bottom=kotlin.math.floor((1f-bounds.bottom)*h[0]).toInt().coerceIn(0,h[0]);val top=kotlin.math.ceil((1f-bounds.top)*h[0]).toInt().coerceIn(bottom,h[0])
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);GLES20.glScissor(left,bottom,right-left,top-bottom)
        }
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
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        drawInk(w[0],h[0])
        check(EGL14.eglSwapBuffers(display,window)) { "video swap failed" }
        lastDraw=System.nanoTime();submitNs+=lastDraw-drawStarted;swaps++
    }

    private fun samplePresentation() {
        val now=android.os.SystemClock.uptimeMillis()
        if(now-statsAt<1000)return
        val seconds=(now-statsAt).coerceAtLeast(1)/1000f
        val active=displayedQualityFocus()!=null
        val images=if(active)detailUploads-statsView else baseFrames-statsBase
        presentationStats=java.lang.String.format(java.util.Locale.ROOT,"Image %.1ffps redraw %.1ffps · GL submit %.2fms",images/seconds,(swaps-statsSwaps)/seconds,if(swaps>statsSwaps)(submitNs-statsSubmit).toDouble()/(swaps-statsSwaps)/1e6 else 0.0)
        statsAt=now;statsBase=baseFrames;statsView=detailUploads;statsSwaps=swaps;statsSubmit=submitNs
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
            if(::viewInput.isInitialized)viewInput.release()
            if(::viewTexture.isInitialized)viewTexture.release()
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
            uniform float sharpenStrength;
            uniform vec2 videoTexel;
            uniform sampler2D retainedVideo;
            uniform int useRetained;
            vec3 videoAt(vec2 point){
                if(useRetained==1)return texture2D(retainedVideo,vec2(point.x,1.0-point.y)).rgb;
                return texture2D(video,(textureMatrix*vec4(point.x,1.0-point.y,0.0,1.0)).xy).rgb;
            }
            vec3 filteredVideo(vec2 point,vec4 bounds){
                vec2 lo=bounds.xy+videoTexel*0.5;vec2 hi=bounds.zw-videoTexel*0.5;
                vec3 base=videoAt(clamp(point,lo,hi));
                if(sharpenStrength<0.001)return base;
                vec3 a=videoAt(clamp(point+vec2(videoTexel.x,0.0),lo,hi));
                vec3 b=videoAt(clamp(point-vec2(videoTexel.x,0.0),lo,hi));
                vec3 c=videoAt(clamp(point+vec2(0.0,videoTexel.y),lo,hi));
                vec3 d=videoAt(clamp(point-vec2(0.0,videoTexel.y),lo,hi));
                vec3 weights=vec3(0.2126,0.7152,0.0722);
                float y=dot(base,weights);float blur=dot((a+b+c+d)*0.25,weights);
                float minimum=min(y,min(min(dot(a,weights),dot(b,weights)),min(dot(c,weights),dot(d,weights))));
                float maximum=max(y,max(max(dot(a,weights),dot(b,weights)),max(dot(c,weights),dot(d,weights))));
                float adjusted=clamp(y+sharpenStrength*(y-blur),minimum,maximum);
                return clamp(base+vec3(adjusted-y),0.0,1.0);
            }
            uniform int viewMode;
            uniform vec4 viewOuter;
            uniform vec4 viewInner;
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
                            vec2 sampleSource=source;
                            if(viewMode==1){
                                if(source.x<viewOuter.x || source.x>viewOuter.z || source.y<viewOuter.y || source.y>viewOuter.w)discard;
                                vec2 low=(source-viewOuter.xy)/(viewOuter.zw-viewOuter.xy);
                                sampleSource=vec2(low.x,2.0/3.0+low.y/3.0);
                            }
                            vec2 sample=(textureMatrix*vec4(sampleSource.x,1.0-sampleSource.y,0.0,1.0)).xy;
                            gl_FragColor=vec4(filteredVideo(sampleSource,viewMode==1?vec4(0.0,2.0/3.0,1.0,1.0):vec4(0.0,0.0,1.0,1.0)),1.0);
                            bool wholeHigh=viewMode==1 && all(lessThan(abs(viewInner-viewOuter),vec4(0.000001)));
                            if(viewMode==1 && (wholeHigh || (source.x>viewInner.x && source.x<viewInner.z && source.y>viewInner.y && source.y<viewInner.w))){
                                vec2 high=(source-viewInner.xy)/(viewInner.zw-viewInner.xy);
                                vec2 highSample=(textureMatrix*vec4(high.x,1.0-high.y*2.0/3.0,0.0,1.0)).xy;
                                float feather=wholeHigh?1.0:smoothstep(0.0,0.025,min(min(high.x,1.0-high.x),min(high.y,1.0-high.y)));
                                gl_FragColor.rgb=mix(gl_FragColor.rgb,filteredVideo(vec2(high.x,high.y*2.0/3.0),vec4(0.0,0.0,1.0,2.0/3.0)),feather);
                            }
                            found=true;
                            break;
                        }
                    }
                }
                if(!found) discard;
            }
        """
        private const val COPY_FRAGMENT="""
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES video;
            uniform mat4 textureMatrix;
            varying vec2 uv;
            void main(){gl_FragColor=texture2D(video,(textureMatrix*vec4(uv,0.0,1.0)).xy);}
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
