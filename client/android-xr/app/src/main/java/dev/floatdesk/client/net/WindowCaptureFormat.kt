package dev.floatdesk.client.net

/** Shared bounded window-stream contract, used by allocation and decoder validation. */
internal object WindowCaptureFormat {
    const val MaxDimension=2048
    const val MaxPixels=2_097_152L
    const val MinDimension=32
    fun size(width:Int,height:Int):Pair<Int,Int>{
        require(width>0 && height>0)
        val scale=minOf(1.0,MaxDimension.toDouble()/width,MaxDimension.toDouble()/height,kotlin.math.sqrt(MaxPixels.toDouble()/(width.toDouble()*height)))
        return ((width*scale).toInt()/2*2).coerceAtLeast(MinDimension) to ((height*scale).toInt()/2*2).coerceAtLeast(MinDimension)
    }
    fun valid(width:Int,height:Int)=width in MinDimension..MaxDimension && height in MinDimension..MaxDimension && width.toLong()*height<=MaxPixels
}
