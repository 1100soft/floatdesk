package dev.floatdesk.client

import dev.floatdesk.client.net.DesktopWindow
import dev.floatdesk.client.net.WorkspaceInfo
import dev.floatdesk.client.workspace.Pose
import kotlin.math.abs

internal data class SnapCell(val columns: Int, val rows: Int, val col: Int, val row: Int, val endCol:Int=col,val endRow:Int=row)

/** The menu grid cell this window currently fills on its screen, or null when it is freely placed. */
internal fun currentSnapCell(window: DesktopWindow, info: WorkspaceInfo, grids: List<Pair<Int, Int>> = WindowMenuLayout.grids, toleranceFraction: Float = .03f): SnapCell? {
    val cx = window.x + window.width / 2; val cy = window.y + window.height / 2
    val screen = info.screens.firstOrNull { cx >= it.x && cx < it.x + it.width && cy >= it.y && cy < it.y + it.height } ?: return null
    val work=windowWorkArea(screen,info.systemWindows)
    val tolerance = minOf(work[2],work[3]) * toleranceFraction
    var best: SnapCell? = null; var bestError = Float.POSITIVE_INFINITY
    for ((columns, rows) in grids) for (row in 0 until rows) for (col in 0 until columns) for(endRow in row until rows)for(endCol in col until columns){
        val cell = windowGridCell(window.id, work[0],work[1],work[2],work[3], columns, rows, col, row,endCol,endRow)
        val error = maxOf(abs(cell.x - window.x), abs(cell.y - window.y), abs(cell.width!! - window.width), abs(cell.height!! - window.height)).toFloat()
        if (error < bestError) { bestError = error; best = SnapCell(columns, rows, col, row,endCol,endRow) }
    }
    return best.takeIf { bestError <= tolerance }
}

internal sealed interface ToolbarTarget {
    data class Window(val id: Long) : ToolbarTarget
    data class Magnifier(val key: String) : ToolbarTarget
}

internal class SnapGridPresence(private val graceMs:Long=700L){
    private var lastPresent:Long?=null
    fun update(now:Long,present:Boolean){if(present || lastPresent==null)lastPresent=now}
    fun expired(now:Long)=lastPresent?.let{now-it>=graceMs} ?: false
    fun clear(){lastPresent=null}
}

/** The space just above a target's top edge: a gaze target while highlighted, then the toolbar's place. */
internal data class ToolbarStrip(val pose: Pose, val width: Float, val height: Float, val cell: Float, val gap: Float, val magnifier: Boolean) {
    fun contains(origin: FloatArray, direction: FloatArray) = surfaceRayDistance(pose, width, height, null, origin, direction) != null
    /** The one-icon highlight at the strip centre, with a little margin for gaze jitter. */
    fun containsMarker(origin: FloatArray, direction: FloatArray) = surfaceRayDistance(pose, cell * 1.6f, cell * 1.6f, null, origin, direction) != null
}

/** Fit the full menu hit rectangle inside the foreground radius, preserving its view angles. */
internal fun ToolbarStrip.insideViewSphere(eye:FloatArray,radius:Float?,units:Float):ToolbarStrip {
    if(radius==null)return this
    val local=viewerInMonitor(eye,pose.position,pose.rotation)
    val x=kotlin.math.abs(local[0])+width*.5f
    val y=kotlin.math.abs(local[1])+height*.5f
    val extent=kotlin.math.sqrt(x*x+y*y+local[2]*local[2])
    val clearance=minOf(.065f*units,radius*.3f)
    val scale=((radius-clearance)/extent.coerceAtLeast(.001f)).coerceAtMost(1f)
    return copy(pose=pose.copy(position=FloatArray(3){eye[it]+(pose.position[it]-eye[it])*scale}),
        width=width*scale,height=height*scale,cell=cell*scale,gap=gap*scale)
}

/** Keep every corner ahead of both the desktop and the window that opened the grid. */
internal fun ToolbarStrip.gridInFrontOf(viewer:Pose,desktopRadius:Float?,owner:MagnifiedWindow?,units:Float):ToolbarStrip {
    val ownerRadius=owner?.let{paneFrontRadius(it,viewer)}?.takeIf{it.isFinite() && it>0f}
    val limit=when {
        ownerRadius==null -> desktopRadius
        desktopRadius==null -> ownerRadius
        else -> minOf(ownerRadius,desktopRadius)
    }
    val mesh=owner?.mesh?.takeUnless{it.planar}
    val aligned=if(mesh!=null && owner!=null){
        val center=transformPoint(owner.pose,mesh.eye)
        copy(pose=pose.copy(rotation=facingMagnifierRotation(pose.position,Pose(center),pose.rotation)))
    }else this
    return aligned.insideViewSphere(viewer.position,limit,units)
}

/** [anchor] is the centre of the target's top edge; the strip is exactly where the toolbar opens. */
internal fun toolbarStrip(anchor: Pose, cell: Float, magnifier: Boolean): ToolbarStrip {
    val ratio = WindowMenuLayout.gapM / WindowMenuLayout.cellM; val gap = cell * ratio
    val height = WindowMenuLayout.toolbarHeight(ratio) * cell
    val width = WindowMenuLayout.toolbarWidth(magnifier, ratio) * cell
    return ToolbarStrip(Pose(transformPoint(anchor, floatArrayOf(0f, gap + height * .5f, 0f)), anchor.rotation), width, height, cell, gap, magnifier)
}

/**
 * Hovering a target highlights the strip above it; the toolbar opens only once the eyes dwell on that
 * strip, and stays while they remain there or the pointer is on the toolbar.
 */
internal class HoverToolbarState(private val highlightMs: Long = 120, private val lingerMs: Long = 900, private val openMs: Long = 200, private val closeMs: Long = 600) {
    var highlight: ToolbarTarget? = null
        private set
    var open: ToolbarTarget? = null
        private set
    private var candidate: ToolbarTarget? = null
    private var candidateSince = 0L
    private var hoverSeen = 0L
    private var stripSince = -1L
    private var openSeen = 0L

    fun clear() { highlight = null; open = null; candidate = null; stripSince = -1 }

    /** [gazeStrip] is the highlighted or open target whose strip the eyes are on. */
    fun update(now: Long, hovered: ToolbarTarget?, gazeStrip: ToolbarTarget?, toolbarHovered: Boolean, blocked: Boolean) {
        if (blocked && !toolbarHovered) { clear(); return }
        open?.let { if (toolbarHovered || gazeStrip == it) openSeen = now else if (now - openSeen > closeMs) open = null }
        if (gazeStrip != null && gazeStrip == highlight) {
            hoverSeen = now; candidate = null
            if (stripSince < 0) stripSince = now
            if (open != gazeStrip && now - stripSince >= openMs) { open = gazeStrip; openSeen = now }
            return
        }
        stripSince = -1
        if (toolbarHovered && open != null) return
        if (hovered != null && hovered == highlight) { hoverSeen = now; candidate = null }
        else if (hovered != null) {
            if (hovered != candidate) { candidate = hovered; candidateSince = now }
            if (now - candidateSince >= highlightMs) { highlight = hovered; hoverSeen = now; candidate = null }
        } else candidate = null
        if (highlight != null && now - hoverSeen > lingerMs) highlight = null
    }
}
