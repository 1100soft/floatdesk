package dev.floatdesk.client

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

/** Small, ephemeral key label, drawn in the owning video buffer's coordinates. */
internal class TypingKeyInk {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD)
        textSize=32f
    }
    private var cachedLabel=""
    private var glyphs=floatArrayOf()
    private var aspect=1f

    fun segments(label:String,rect:FocusRect,bufferAspect:Float):FloatArray {
        val text=label.take(36)
        if(text!=cachedLabel){
            cachedLabel=text
            val path=Path()
            paint.getTextPath(text,0,text.length,0f,0f,path)
            val bounds=RectF()
            path.computeBounds(bounds,true)
            if(bounds.isEmpty){
                glyphs=floatArrayOf()
            }else{
                aspect=bounds.width()/bounds.height()
                val measure=PathMeasure(path,false)
                val contours=mutableListOf<Float>()
                val a=FloatArray(2)
                val b=FloatArray(2)
                do {
                    val count=ceil(measure.length/6f).toInt().coerceAtLeast(1)
                    for(i in 0 until count){
                        if(!measure.getPosTan(measure.length*i/count,a,null) ||
                            !measure.getPosTan(measure.length*(i+1)/count,b,null))continue
                        contours.addAll(listOf((a[0]-bounds.left)/bounds.width(),(a[1]-bounds.top)/bounds.height(),
                            (b[0]-bounds.left)/bounds.width(),(b[1]-bounds.top)/bounds.height()))
                    }
                }while(measure.nextContour())
                glyphs=contours.toFloatArray()
            }
        }
        val contentWidth=rect.right-rect.left
        val contentHeight=rect.bottom-rect.top
        if(contentWidth<=0f || contentHeight<=0f || bufferAspect<=0f)return floatArrayOf()
        val height=minOf(contentHeight*.075f,contentWidth*.55f*bufferAspect/aspect)
        val width=height*aspect/bufferAspect
        val left=rect.right-contentWidth*.035f-width
        val top=rect.top+contentHeight*.04f
        return FloatArray(glyphs.size){i->if(i%2==0)left+glyphs[i]*width else top+glyphs[i]*height}
    }
}
