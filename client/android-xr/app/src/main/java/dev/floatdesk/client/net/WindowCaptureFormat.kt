package dev.floatdesk.client.net

/** Shared bounded window-stream contract, used by allocation and decoder validation. */
internal object WindowCaptureFormat {
    const val MaxDimension=2048
    const val MaxPixels=2_097_152L
    const val MinDimension=128
    fun size(width:Int,height:Int):Pair<Int,Int>{
        require(width>0 && height>0)
        val scale=minOf(1.0,dev.floatdesk.client.RenderPolicy.WindowDimension.toDouble()/width,dev.floatdesk.client.RenderPolicy.WindowDimension.toDouble()/height,kotlin.math.sqrt(dev.floatdesk.client.RenderPolicy.WindowPixels.toDouble()/(width.toDouble()*height)))
        return ((width*scale).toInt()/2*2).coerceAtLeast(MinDimension) to ((height*scale).toInt()/2*2).coerceAtLeast(MinDimension)
    }
    /** Immutable native canvas: bounded headroom avoids replacing borrowed textures on menu changes. */
    fun canvasSize(width:Int,height:Int):Pair<Int,Int> {
        val coded=size(width,height)
        val minimum=kotlin.math.sqrt(dev.floatdesk.client.RenderPolicy.WindowPixels/2.0).toInt()
        return maxOf(minimum,coded.first) to maxOf(minimum,coded.second)
    }
    fun valid(width:Int,height:Int)=width in MinDimension..MaxDimension && height in MinDimension..MaxDimension && width.toLong()*height<=MaxPixels
}
