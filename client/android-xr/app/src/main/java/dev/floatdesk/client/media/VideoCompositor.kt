package dev.floatdesk.client.media

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

/** Normalized top-left video coordinates. At most sixteen projected ray segments and two contacts. */
internal data class VideoAim(val segments: FloatArray = floatArrayOf(), val contacts: FloatArray = floatArrayOf(),
                             val thickness: Float = 2f, val opacity: Float = .18f)

/** One GPU pass keeps aiming decorations and live video in the same compositor layer. */
internal class VideoCompositor(width: Int, height: Int) : AutoCloseable {
    private val thread=HandlerThread("floatdesk-video-gl").also { it.start() }
    private val handler=Handler(thread.looper)
    private val ready=CountDownLatch(1)
    private val redrawPosted=AtomicBoolean(false)
    @Volatile private var closed=false
    @Volatile private var failure: Throwable?=null
    @Volatile private var aim=VideoAim()
    private var display=EGL14.EGL_NO_DISPLAY
    private var context=EGL14.EGL_NO_CONTEXT
    private var pbuffer=EGL14.EGL_NO_SURFACE
    private var window=EGL14.EGL_NO_SURFACE
    private var config: android.opengl.EGLConfig?=null
    private var target: Surface?=null
    private lateinit var texture: SurfaceTexture
    lateinit var input: Surface
        private set
    private var textureId=0
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
                program=link(VERTEX,FRAGMENT)
                inkProgram=link(INK_VERTEX,INK_FRAGMENT)
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

    fun overlay(value: VideoAim) {
        aim=value
        if(!closed && redrawPosted.compareAndSet(false,true)) handler.post {
            redrawPosted.set(false)
            if(!closed && System.nanoTime()-lastDraw>12_000_000L) guard { draw() }
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
        val position=GLES20.glGetAttribLocation(program,"position")
        GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        drawInk(w[0],h[0])
        check(EGL14.eglSwapBuffers(display,window)) { "video swap failed" }
        lastDraw=System.nanoTime()
    }

    private fun drawInk(width: Int,height: Int) {
        val overlay=aim
        if(overlay.segments.isEmpty() && overlay.contacts.isEmpty()) return
        val values=ArrayList<Float>()
        fun vertex(x: Float,y: Float,alpha: Float) { values.add(x/width*2f-1f); values.add(1f-y/height*2f); values.add(alpha) }
        fun line(ax: Float,ay: Float,bx: Float,by: Float,thickness: Float,alpha: Float) {
            val dx=bx-ax; val dy=by-ay; val length=kotlin.math.hypot(dx,dy)
            if(length<.001f) return
            val ox=-dy/length*thickness*.5f; val oy=dx/length*thickness*.5f
            vertex(ax+ox,ay+oy,alpha); vertex(ax-ox,ay-oy,alpha); vertex(bx+ox,by+oy,alpha)
            vertex(bx+ox,by+oy,alpha); vertex(ax-ox,ay-oy,alpha); vertex(bx-ox,by-oy,alpha)
        }
        for(i in 0 until minOf(16,overlay.segments.size/4)) {
            val n=i*4; line(overlay.segments[n]*width,overlay.segments[n+1]*height,
                overlay.segments[n+2]*width,overlay.segments[n+3]*height,overlay.thickness,overlay.opacity)
        }
        for(i in 0 until minOf(2,overlay.contacts.size/2)) {
            val x=overlay.contacts[i*2]*width; val y=overlay.contacts[i*2+1]*height
            for(j in 0 until 24) {
                val a=j*2.0*Math.PI/24; val b=(j+1)*2.0*Math.PI/24
                line(x+kotlin.math.cos(a).toFloat()*5f,y+kotlin.math.sin(a).toFloat()*5f,
                    x+kotlin.math.cos(b).toFloat()*5f,y+kotlin.math.sin(b).toFloat()*5f,2f,.4f)
            }
        }
        val buffer=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(values.toFloatArray()).also { it.position(0) }
        GLES20.glUseProgram(inkProgram)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val p=GLES20.glGetAttribLocation(inkProgram,"position")
        val a=GLES20.glGetAttribLocation(inkProgram,"alpha")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(a)
        buffer.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,12,buffer)
        buffer.position(2); GLES20.glVertexAttribPointer(a,1,GLES20.GL_FLOAT,false,12,buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,values.size/3)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(a); GLES20.glDisable(GLES20.GL_BLEND)
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
            precision mediump float;
            uniform samplerExternalOES video;
            varying vec2 sampleUV;
            void main() { gl_FragColor=vec4(texture2D(video,sampleUV).rgb,1.0); }
        """
        private const val INK_VERTEX="""
            attribute vec2 position;
            attribute float alpha;
            varying float opacity;
            void main() { gl_Position=vec4(position,0.0,1.0); opacity=alpha; }
        """
        private const val INK_FRAGMENT="""
            precision mediump float;
            varying float opacity;
            void main() { gl_FragColor=vec4(0.4236,0.9777,1.0,opacity); }
        """
    }
}
