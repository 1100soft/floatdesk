package dev.floatdesk.client

internal data class PanelButtonArea(val id: Int,val x: Float,val y: Float,val width: Float,val height: Float)

/** Discrete, sticky gaze focus is more stable than a continuously moving gaze cursor. */
internal class PanelGazeInput(private val buttons: List<PanelButtonArea>) {
    var focused: Int?=null
        private set
    private var hand: String?=null
    private var candidate: Int?=null
    private var candidateSince=0L
    private var lastGaze=0L
    private var pinchArmed=false
    private var foldedSince: Long?=null

    fun clear() { focused=null; hand=null; candidate=null; pinchArmed=false; foldedSince=null }

    fun update(now: Long,side: String?,gaze: FloatArray?,pinchMetres: Float?): Int? {
        if(side==null) { clear(); return null }
        if(side!=hand) { clear(); hand=side }
        fun contains(button: PanelButtonArea,padding: Float)=gaze!=null &&
            gaze[0] in button.x-padding..button.x+button.width+padding &&
            gaze[1] in button.y-padding..button.y+button.height+padding
        val sticky=buttons.firstOrNull { it.id==focused && contains(it,.035f) }
        val hit=sticky ?: buttons.firstOrNull { contains(it,.012f) }
        if(hit!=null) {
            lastGaze=now
            if(hit.id!=candidate) { candidate=hit.id; candidateSince=now }
            if(now-candidateSince>=120) focused=hit.id
        } else {
            candidate=null
            if(now-lastGaze>160) focused=null
        }
        if(pinchMetres==null || !pinchMetres.isFinite()) { pinchArmed=false; foldedSince=null; return null }
        if(pinchMetres>.035f) { pinchArmed=true; foldedSince=null }
        if(pinchMetres<.023f && pinchArmed) {
            val began=foldedSince
            if(began==null) foldedSince=now
            else if(now-began>=60) {
                pinchArmed=false; foldedSince=null
                if(now-lastGaze<=80 && focused!=null && hit?.id==focused) return focused
            }
        } else if(pinchMetres>.03f) foldedSince=null
        return null
    }
}
