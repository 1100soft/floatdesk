package dev.floatdesk.client

import dev.floatdesk.client.net.DesktopWindow
import dev.floatdesk.client.net.WorkspaceInfo
import kotlin.math.abs

internal data class SnapCell(val columns: Int, val rows: Int, val col: Int, val row: Int)

/** The menu grid cell this window currently fills on its screen, or null when it is freely placed. */
internal fun currentSnapCell(window: DesktopWindow, info: WorkspaceInfo, grids: List<Pair<Int, Int>> = WindowMenuLayout.grids, toleranceFraction: Float = .03f): SnapCell? {
    val cx = window.x + window.width / 2; val cy = window.y + window.height / 2
    val screen = info.screens.firstOrNull { cx >= it.x && cx < it.x + it.width && cy >= it.y && cy < it.y + it.height } ?: return null
    val tolerance = minOf(screen.width, screen.height) * toleranceFraction
    var best: SnapCell? = null; var bestError = Float.POSITIVE_INFINITY
    for ((columns, rows) in grids) for (row in 0 until rows) for (col in 0 until columns) {
        val cell = windowGridCell(window.id, screen.x, screen.y, screen.width, screen.height, columns, rows, col, row)
        val error = maxOf(abs(cell.x - window.x), abs(cell.y - window.y), abs(cell.width!! - window.width), abs(cell.height!! - window.height)).toFloat()
        if (error < bestError) { bestError = error; best = SnapCell(columns, rows, col, row) }
    }
    return best.takeIf { bestError <= tolerance }
}

internal sealed interface ToolbarTarget {
    data class Window(val id: Long) : ToolbarTarget
    data class Magnifier(val key: String) : ToolbarTarget
}

/**
 * Hover-opened toolbar: a target must be hovered briefly before it opens, and the toolbar stays while
 * the pointer is on the target or on the toolbar itself, with a grace period to travel between them.
 */
internal class HoverToolbarState(private val openMs: Long = 250, private val closeMs: Long = 700) {
    var target: ToolbarTarget? = null
        private set
    private var candidate: ToolbarTarget? = null
    private var candidateSince = 0L
    private var lastSeen = 0L

    fun clear() { target = null; candidate = null }

    fun update(now: Long, hovered: ToolbarTarget?, toolbarHovered: Boolean, blocked: Boolean): ToolbarTarget? {
        if (blocked && !toolbarHovered) { clear(); return null }
        if (toolbarHovered && target != null) { lastSeen = now; candidate = null; return target }
        if (hovered != null && hovered == target) { lastSeen = now; candidate = null }
        else if (hovered != null) {
            if (hovered != candidate) { candidate = hovered; candidateSince = now }
            if (now - candidateSince >= openMs) { target = hovered; lastSeen = now; candidate = null }
        } else candidate = null
        if (target != null && now - lastSeen > closeMs) target = null
        return target
    }
}
