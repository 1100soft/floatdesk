package dev.floatdesk.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

internal object WindowMenuLayout {
    val grids = listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2, 3 to 1, 1 to 3, 3 to 2, 2 to 3, 3 to 3)
    const val columns = 3
    const val cellM = .055f
    const val gapM = .006f
    const val widthM = columns * cellM + (columns + 1) * gapM
    val gridRows get() = (grids.size + columns - 1) / columns
    val heightM get() = (1 + gridRows) * cellM + (gridRows + 3) * gapM + gapM / 6
    const val magnifierWidthM = 2 * cellM + 3 * gapM
    const val magnifierHeightM = cellM + 2 * gapM
}

/** Every destination cell is selectable directly; there are no submenu pages. */
@Composable
internal fun WindowActionsMenu(window: Long?, cell: Dp, gap: Dp, onAction: (String) -> Unit,
                               onSnap: (Int, Int, Int, Int) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp(0f)){
        Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.width(cell * WindowMenuLayout.columns + gap * (WindowMenuLayout.columns - 1)), horizontalArrangement = Arrangement.SpaceBetween) {
                MenuIcon("minimize", "Minimize window", cell, window != null) { onAction("minimize") }
                MenuIcon("close", "Close window", cell, window != null) { onAction("close") }
            }
            HorizontalDivider(thickness = gap / 6)
            for (row in WindowMenuLayout.grids.chunked(WindowMenuLayout.columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for ((columns, rows) in row) {
                        GridChoice(columns, rows, cell, window != null) { col, r -> onSnap(columns, rows, col, r) }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun MagnifierActionsMenu(cell: Dp, gap: Dp, onAdd: () -> Unit, onRemove: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp(0f)){
        Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
            MenuIcon("add", "Add a magnifier", cell, true, onAdd)
            MenuIcon("remove", "Remove this magnifier", cell, true, onRemove)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuIcon(action: String, description: String, cell: Dp, enabled: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box {

        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(cell).trackMenuHover { hovered = it != null }.semantics { contentDescription = description }) {
            Canvas(Modifier.fillMaxSize().padding(cell * .25f)) {
                val ink = Color(0xFFC8E7F5)
                val stroke = size.minDimension * .09f
                fun line(x: Float, y: Float, r: Float, b: Float) = drawLine(ink, Offset(x * size.width, y * size.height), Offset(r * size.width, b * size.height), stroke)
                when (action) {
                    "minimize" -> line(.05f, .85f, .95f, .85f)
                    "close" -> { line(.1f,.1f,.9f,.9f); line(.9f,.1f,.1f,.9f) }
                    "add" -> { line(.05f,.5f,.95f,.5f); line(.5f,.05f,.5f,.95f) }
                    "remove" -> {
                        line(.1f,.2f,.9f,.2f); line(.35f,.05f,.65f,.05f)
                        line(.22f,.3f,.27f,.95f); line(.27f,.95f,.73f,.95f); line(.73f,.95f,.78f,.3f)
                        line(.42f,.4f,.42f,.8f); line(.58f,.4f,.58f,.8f)
                    }
                }
            }
        }
        if (hovered) Text(description, Modifier.align(Alignment.BottomCenter)
            .background(Color(0xFF18232E)), style = MaterialTheme.typography.labelSmall,
            maxLines = 2)

    }
}

private fun Modifier.trackMenuHover(onHover: (Offset?) -> Unit) = pointerInput(Unit) {
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
private fun GridChoice(columns: Int, rows: Int, cell: Dp, enabled: Boolean, onChoose: (Int, Int) -> Unit) {
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
        hovered?.let { (col, row) ->
            drawRect(Color(0xFF3988AA), Offset(pad + width * col / columns, pad + height * row / rows), Size(width / columns, height / rows))
        }
        drawRect(ink, Offset(pad, pad), Size(width, height), style = Stroke(size.minDimension * .025f))
        for (col in 1 until columns) drawLine(ink, Offset(pad + width * col / columns, pad), Offset(pad + width * col / columns, pad + height), size.minDimension * .02f)
        for (row in 1 until rows) drawLine(ink, Offset(pad, pad + height * row / rows), Offset(pad + width, pad + height * row / rows), size.minDimension * .02f)
    }
}
