package dev.floatdesk.client

/** User settings shared by mouse, focus selection and two-hand acquisition. Never adapt while using them. */
internal data class PinchThresholds(val leftIndexMm:Float=15.5f,val rightIndexMm:Float=12.5f,
                                  val leftMiddleMm:Float=15f,val rightMiddleMm:Float=14f) {
    fun indexMetres(side:String)=(if(side=="LEFT")leftIndexMm else rightIndexMm)/1000f
    fun middleMetres(side:String)=(if(side=="LEFT")leftMiddleMm else rightMiddleMm)/1000f
    fun indexClosed(side:String,separation:Float)=separation.isFinite() && separation>=0f && separation<=indexMetres(side)
    fun middleClosed(side:String,separation:Float)=separation.isFinite() && separation>=0f && separation<=middleMetres(side)
    fun valid()=listOf(leftIndexMm,rightIndexMm,leftMiddleMm,rightMiddleMm).all{it.isFinite() && it>0f}
    companion object {val Default=PinchThresholds()}
}
