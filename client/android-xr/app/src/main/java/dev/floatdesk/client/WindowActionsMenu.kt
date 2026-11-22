package dev.floatdesk.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

internal object WindowMenuLayout {
    val grids = listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2, 3 to 1, 1 to 3, 3 to 2, 2 to 3, 3 to 3)
    const val columns = 3
    const val cellM = .055f
    const val gapM = .006f
    val gridRows get() = (grids.size + columns - 1) / columns
    fun toolbarButtons(magnifier: Boolean) = if (magnifier) 6 else 3
    /** Sizes in units of one button cell, for a gap of [gap] cells. */
    fun toolbarWidth(magnifier: Boolean, gap: Float) = toolbarButtons(magnifier) + (toolbarButtons(magnifier) + 1) * gap + if (magnifier) 2 * gap else 0f
    fun toolbarHeight(gap: Float) = 1 + 2 * gap
    fun gridWidth(gap: Float) = columns + (columns + 1) * gap
    fun gridHeight(gap: Float) = gridRows + (gridRows + 1) * gap
}

private val ToolbarSurface = Color(0xFF1B2229)
private val SelectedCell = Color(0xFF2F7FA6)

/** One row above a window or magnifier; snap expands into [SnapGridMenu] below this row. */
@Composable
internal fun WindowToolbar(window: Long?, magnifier: Boolean, snap: SnapCell?, snapExpanded: Boolean, cell: Dp, gap: Dp,
                           onHover: (Boolean) -> Unit, onTooltip: (String?) -> Unit, onSnapHover: (Boolean) -> Unit,
                           onSnapToggle: () -> Unit, onAction: (String) -> Unit, onAdd: () -> Unit, onRemove: () -> Unit, pinned:Boolean, onPin: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp(0f)) {
            Row(Modifier.background(ToolbarSurface).trackMenuHover { onHover(it != null) }.padding(gap),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                if (magnifier) {
                    MenuIcon("add", "Add a magnifier", cell, true, onTooltip, onClick = onAdd)
                    MenuIcon("pin", if(pinned) "Unpin this window" else "Pin this window",cell,window!=null,onTooltip,active=pinned,onClick=onPin)
                    MenuIcon("remove", "Remove this magnifier", cell, true, onTooltip, onClick = onRemove)
                    Box(Modifier.width(gap).height(cell * .6f).background(Color(0xFF3A4752)))
                }
                MenuIcon("minimize", "Minimize window", cell, window != null, onTooltip) { onAction("minimize") }
                MenuIcon("snap", "Snap to grid", cell, window != null,
                    { onTooltip(it); onSnapHover(it != null) }, snap = snap, active = snapExpanded, onClick = onSnapToggle)
                MenuIcon("close", "Close window", cell, window != null, onTooltip) { onAction("close") }
            }
        }
    }
}

/** Every destination cell is selectable directly; the current placement is shown filled. */
@Composable
internal fun SnapGridMenu(window: Long?, current: SnapCell?, cell: Dp, gap: Dp, onHover: (Boolean) -> Unit, onSnap: (Int, Int, Int, Int) -> Unit) {
    Column(Modifier.background(ToolbarSurface).trackMenuHover { onHover(it != null) }.padding(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in WindowMenuLayout.grids.chunked(WindowMenuLayout.columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for ((columns, rows) in row) {
                    val selected = current?.takeIf { it.columns == columns && it.rows == rows }?.let { it.col to it.row }
                    GridChoice(columns, rows, cell, window != null, selected) { col, r -> onSnap(columns, rows, col, r) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuIcon(action: String, description: String, cell: Dp, enabled: Boolean, onTooltip: (String?) -> Unit,
                     snap: SnapCell? = null, active: Boolean = false, onClick: () -> Unit) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled,
            colors = if (active) IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF34556B)) else IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(cell).trackMenuHover { onTooltip(description.takeIf { _ -> it != null }) }.semantics { contentDescription = description }) {
            Canvas(Modifier.fillMaxSize().padding(cell * .22f)) {
                val ink = Color(0xFFC8E7F5)
                val stroke = size.minDimension * .09f
                fun line(x: Float, y: Float, r: Float, b: Float) = drawLine(ink, Offset(x * size.width, y * size.height), Offset(r * size.width, b * size.height), stroke)
                when (action) {
                    "minimize" -> line(.05f, .85f, .95f, .85f)
                    "close" -> { line(.1f,.1f,.9f,.9f); line(.9f,.1f,.1f,.9f) }
                    "snap" -> {
                        val outline = Stroke(stroke * .8f)
                        if (snap != null) {
                            drawRect(SelectedCell, Offset(size.width * snap.col / snap.columns, size.height * snap.row / snap.rows), Size(size.width / snap.columns, size.height / snap.rows))
                            for (c in 1 until snap.columns) line(c.toFloat() / snap.columns, 0f, c.toFloat() / snap.columns, 1f)
                            for (r in 1 until snap.rows) line(0f, r.toFloat() / snap.rows, 1f, r.toFloat() / snap.rows)
                        }
                        drawRect(ink, Offset.Zero, size, style = outline)
                    }
                    "pin" -> {line(.25f,.19f,.75f,.19f);line(.38f,.19f,.33f,.56f);line(.62f,.19f,.67f,.56f);line(.25f,.56f,.75f,.56f);line(.5f,.56f,.5f,.94f)}
                    "add" -> { line(.05f,.5f,.95f,.5f); line(.5f,.05f,.5f,.95f) }
                    "remove" -> {
                        line(.1f,.2f,.9f,.2f); line(.35f,.05f,.65f,.05f)
                        line(.22f,.3f,.27f,.95f); line(.27f,.95f,.73f,.95f); line(.73f,.95f,.78f,.3f)
                        line(.42f,.4f,.42f,.8f); line(.58f,.4f,.58f,.8f)
                    }
                }
            }
        }
}

internal fun Modifier.trackMenuHover(onHover: (Offset?) -> Unit) = composed {
    val latest by rememberUpdatedState(onHover)
    trackMenuHoverLatest { latest(it) }
}

private fun Modifier.trackMenuHoverLatest(onHover: (Offset?) -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
                PointerEventType.Enter, PointerEventType.Move -> {
                    val position = event.changes.firstOrNull()?.position
                    onHover(position?.let { Offset(it.x / size.width, it.y / size.height) })
                }
                PointerEventType.Exit -> onHover(null)
            }
        }
    }
}

@Composable
private fun GridChoice(columns: Int, rows: Int, cell: Dp, enabled: Boolean, selected: Pair<Int, Int>?, onChoose: (Int, Int) -> Unit) {
    var hovered by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun target(x: Float, y: Float): Pair<Int, Int> {
        fun axis(value: Float, count: Int) = ((value - .12f) / .76f * count).toInt().coerceIn(0, count - 1)
        return axis(x, columns) to axis(y, rows)
    }
    Canvas(Modifier.size(cell).trackMenuHover { point ->
        hovered = point?.takeIf { enabled }?.let { target(it.x, it.y) }
    }.background(Color(0xFF243544)).border(cell * .015f, Color(0xFF708EA4))
        .semantics { contentDescription = "$columns by $rows snap grid" }
        .pointerInput(columns, rows, enabled) {
            if (enabled) detectTapGestures { point ->
                val (col, row) = target(point.x / size.width, point.y / size.height)
                onChoose(col, row)
            }
        }) {
        val pad = size.minDimension * .12f
        val width = size.width - 2 * pad; val height = size.height - 2 * pad
        val ink = Color(0xFFB6D6E8)
        selected?.let { (col, row) ->
            drawRect(SelectedCell.copy(alpha = .55f), Offset(pad + width * col / columns, pad + height * row / rows), Size(width / columns, height / rows))
        }
        hovered?.let { (col, row) ->
            drawRect(Color(0xFF3988AA), Offset(pad + width * col / columns, pad + height * row / rows), Size(width / columns, height / rows))
        }
        drawRect(ink, Offset(pad, pad), Size(width, height), style = Stroke(size.minDimension * .025f))
        for (col in 1 until columns) drawLine(ink, Offset(pad + width * col / columns, pad), Offset(pad + width * col / columns, pad + height), size.minDimension * .02f)
        for (row in 1 until rows) drawLine(ink, Offset(pad, pad + height * row / rows), Offset(pad + width, pad + height * row / rows), size.minDimension * .02f)
    }
}
