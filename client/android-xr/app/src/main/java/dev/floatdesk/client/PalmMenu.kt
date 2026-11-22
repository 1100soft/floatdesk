package dev.floatdesk.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.floatdesk.client.net.WorkspaceInfo
import dev.floatdesk.client.workspace.InteractionMode
import dev.floatdesk.client.workspace.WorkspaceFile
import kotlin.math.cos
import kotlin.math.sin

internal enum class PalmAction { FOLLOW, RESET, SEARCH, SPACE, WARP, MOVE, HAND_MOUSE, FREE, REFERENCE, MODE, MORE, BACK, DIAGNOSTICS, GAZE_FOCUS, MAGNIFIER, MOUSE_SPACES }
internal enum class PalmPage { MAIN, MORE }
internal data class PalmButton(val action: PalmAction, val x: Float, val y: Float, val w: Float, val h: Float)

/** Same cell metrics as [WindowMenuLayout]; button rectangles are normalized to the panel. */
internal object PalmMenuLayout {
    const val columns = 3
    const val cellM = WindowMenuLayout.cellM
    const val gapM = WindowMenuLayout.gapM
    const val widthM = columns * cellM + (columns + 1) * gapM
    const val tooltipHeightM = .038f
    private val main = listOf(PalmAction.SPACE, PalmAction.RESET, PalmAction.FOLLOW, PalmAction.WARP, PalmAction.MOVE, PalmAction.HAND_MOUSE, PalmAction.MODE, PalmAction.FREE, PalmAction.MORE)
    private val more = listOf(PalmAction.DIAGNOSTICS, PalmAction.REFERENCE, PalmAction.SEARCH,
        PalmAction.GAZE_FOCUS, PalmAction.MAGNIFIER, PalmAction.MOUSE_SPACES, PalmAction.BACK)
    fun actions(page: PalmPage) = if (page == PalmPage.MAIN) main else more
    fun rows(page: PalmPage) = (actions(page).size + columns - 1) / columns
    fun heightM(page: PalmPage) = rows(page) * cellM + (rows(page) + 1) * gapM
    fun buttons(page: PalmPage): List<PalmButton> {
        val height = heightM(page)
        return actions(page).mapIndexed { index, action ->
            val column = index % columns; val row = index / columns
            PalmButton(action, (gapM + column * (cellM + gapM)) / widthM, (gapM + row * (cellM + gapM)) / height, cellM / widthM, cellM / height)
        }
    }
}

internal fun palmActionEnabled(action: PalmAction, workspace: WorkspaceFile, info: WorkspaceInfo?): Boolean = when (action) {
    PalmAction.WARP -> RenderPolicy.CustomMeshes
    PalmAction.HAND_MOUSE, PalmAction.MODE -> !workspace.grabToMove
    PalmAction.GAZE_FOCUS -> info?.gazeFocus == true || workspace.gazeToFocus
    PalmAction.MAGNIFIER -> info?.windowMagnify == true || workspace.magnifyWindow
    PalmAction.MOUSE_SPACES -> RenderPolicy.CursorTopology
    else -> true
}

/** Toggles are a single state slot, multi-mode buttons have one slot per mode; immediate actions have none. */
private data class PalmState(val slots: Int, val current: Int) {
    val toggleOn get() = slots == 1 && current == 0
}

private fun palmState(action: PalmAction, workspace: WorkspaceFile, latched: Boolean): PalmState? {
    fun toggle(on: Boolean) = PalmState(1, if (on) 0 else -1)
    return when (action) {
        PalmAction.FOLLOW -> toggle(latched)
        PalmAction.MOVE -> toggle(workspace.grabToMove)
        PalmAction.HAND_MOUSE -> toggle(workspace.handMouseActive)
        PalmAction.FREE -> toggle(workspace.freeManipulation)
        PalmAction.REFERENCE -> toggle(workspace.desktopReference)
        PalmAction.DIAGNOSTICS -> toggle(workspace.viewDetailDebug)
        PalmAction.GAZE_FOCUS -> toggle(workspace.gazeToFocus)
        PalmAction.MOUSE_SPACES -> toggle(workspace.cursorRemap)
        PalmAction.WARP -> PalmState(2, if (workspace.sphericalWarp) 1 else 0)
        PalmAction.MODE -> PalmState(InteractionMode.entries.size, workspace.interactionMode.ordinal)
        PalmAction.MAGNIFIER -> PalmState(3, listOf("fixed", "floating", "off").indexOf(magnifierLabel(workspace)))
        PalmAction.SPACE, PalmAction.RESET, PalmAction.SEARCH, PalmAction.MORE, PalmAction.BACK -> null
    }
}

internal fun magnifierLabel(workspace: WorkspaceFile) = when {
    !workspace.magnifyWindow -> "off"
    workspace.magnifierMode == "fixed" -> "fixed"
    else -> "floating"
}

/** Cycles fixed → floating → off → fixed, using the same fields as Settings. */
internal fun nextMagnifier(workspace: WorkspaceFile): WorkspaceFile = when (magnifierLabel(workspace)) {
    "off" -> workspace.copy(magnifyWindow = true, magnifierMode = "fixed")
    "fixed" -> workspace.copy(magnifyWindow = true, magnifierMode = "floating")
    else -> workspace.copy(magnifyWindow = false)
}

internal fun palmTooltip(action: PalmAction, workspace: WorkspaceFile, latched: Boolean): String = when (action) {
    PalmAction.SPACE -> "Workspace"
    PalmAction.RESET -> "Reset desktop"
    PalmAction.FOLLOW -> if (latched) "Following · tap to stop" else "Follow"
    PalmAction.WARP -> when {
        !RenderPolicy.CustomMeshes -> "Spherical view is paused in this build (custom meshes off)"
        workspace.sphericalWarp -> "Spherical · tap for flat"
        else -> "Flat · tap for spherical"
    }
    PalmAction.MODE -> "${workspace.interactionMode.label} · tap for ${workspace.interactionMode.next().label}"
    PalmAction.MORE -> "More"
    PalmAction.BACK -> "Back"
    PalmAction.DIAGNOSTICS -> if (workspace.viewDetailDebug) "Hide diagnostics" else "Show diagnostics"
    PalmAction.MOVE -> "Grab to move"
    PalmAction.HAND_MOUSE -> "Hand mouse"
    PalmAction.FREE -> "Two-hand free manipulation"
    PalmAction.REFERENCE -> "Desktop reference"
    PalmAction.SEARCH -> "Reconnect"
    PalmAction.GAZE_FOCUS -> if (workspace.gazeToFocus) "Gaze to focus on" else "Gaze to focus off"
    PalmAction.MAGNIFIER -> "Magnifier ${magnifierLabel(workspace)} · tap for ${magnifierLabel(nextMagnifier(workspace))}"
    PalmAction.MOUSE_SPACES -> if (workspace.cursorRemap) "Mouse spaces connected" else "Mouse spaces separate"
}

@Composable
internal fun PalmControls(page: PalmPage, workspace: WorkspaceFile, info: WorkspaceInfo?, latched: Boolean, focused: PalmAction?, pressed: PalmAction?,
                          onHover: (PalmAction?) -> Unit, onAction: (PalmAction) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp(0f)) {
            BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                for (button in PalmMenuLayout.buttons(page)) {
                    val action = button.action
                    PalmIcon(action, workspace, palmTooltip(action, workspace, latched), maxWidth * button.w,
                        palmActionEnabled(action, workspace, info), palmState(action, workspace, latched),
                        action == focused, action == pressed, { hovering -> onHover(action.takeIf { hovering }) },
                        Modifier.offset(maxWidth * button.x, maxHeight * button.y)) { onAction(action) }
                }
            }
        }
    }
}

/** Immediate actions keep the round window-menu button; state buttons are rounded squares with state pips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PalmIcon(action: PalmAction, workspace: WorkspaceFile, description: String, cell: Dp, enabled: Boolean, state: PalmState?,
                     focused: Boolean, pressed: Boolean, onHover: (Boolean) -> Unit, modifier: Modifier, onClick: () -> Unit) {
    val shape = if (state == null) CircleShape else RoundedCornerShape(22)
    val container = when {
        pressed -> Color(0xFF237E59)
        state?.toggleOn == true -> Color(0xFF2F7FA6)
        state != null -> Color(0xFF26323C)
        else -> null
    }
    FilledTonalIconButton(onClick = onClick, enabled = enabled, shape = shape,
        colors = container?.let { IconButtonDefaults.filledTonalIconButtonColors(containerColor = it) } ?: IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = modifier.size(cell).trackMenuHover { onHover(it != null) }.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = size.minDimension * .25f
            inset(inset, inset) { drawPalmIcon(action, workspace) }
            if (state != null) drawStatePips(state, if (enabled) 1f else .38f)
        }
    }
    if (focused) Canvas(modifier.size(cell)) {
        val width = size.minDimension * .035f
        if (state == null) drawCircle(Color(0xFF8ADFFF), (size.minDimension - width) / 2, style = Stroke(width))
        else drawRoundRect(Color(0xFF8ADFFF), Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
            CornerRadius(size.minDimension * .22f), style = Stroke(width))
    }
}

private fun DrawScope.drawStatePips(state: PalmState, alpha: Float) {
    val radius = size.minDimension * .035f
    val pitch = size.minDimension * .12f
    val y = size.height * .87f
    val first = size.width / 2 - pitch * (state.slots - 1) / 2
    for (slot in 0 until state.slots) {
        val center = Offset(first + slot * pitch, y)
        if (slot == state.current) drawCircle(Color(0xFFE8F6FF).copy(alpha = alpha), radius * 1.15f, center)
        else drawCircle(Color(0xFF8FA9BA).copy(alpha = alpha), radius, center, style = Stroke(radius * .55f))
    }
}

private fun DrawScope.drawPalmIcon(action: PalmAction, workspace: WorkspaceFile) {
    val ink = Color(0xFFC8E7F5)
    val stroke = size.minDimension * .09f
    val w = size.width; val h = size.height
    fun line(x: Float, y: Float, r: Float, b: Float) = drawLine(ink, Offset(x * w, y * h), Offset(r * w, b * h), stroke)
    fun box(x: Float, y: Float, r: Float, b: Float) = drawRect(ink, Offset(x * w, y * h), Size((r - x) * w, (b - y) * h), style = Stroke(stroke))
    fun ring(x: Float, y: Float, radius: Float) = drawCircle(ink, radius * w, Offset(x * w, y * h), style = Stroke(stroke))
    fun dot(x: Float, y: Float, radius: Float) = drawCircle(ink, radius * w, Offset(x * w, y * h))
    fun arc(x: Float, y: Float, r: Float, b: Float, start: Float, sweep: Float) =
        drawArc(ink, start, sweep, false, Offset(x * w, y * h), Size((r - x) * w, (b - y) * h), style = Stroke(stroke))
    fun eye(cx: Float, cy: Float, half: Float) {
        arc(cx - half, cy - half * .6f, cx + half, cy + half * 1.4f, 200f, 140f)
        arc(cx - half, cy - half * 1.4f, cx + half, cy + half * .6f, 20f, 140f)
        dot(cx, cy, half * .28f)
    }
    when (action) {
        PalmAction.SPACE -> { box(.05f, .05f, .45f, .45f); box(.55f, .05f, .95f, .45f); box(.05f, .55f, .45f, .95f); box(.55f, .55f, .95f, .95f) }
        PalmAction.RESET -> {
            // Counter-clockwise "back to start" arrow: the head sits at the top and points left, into the gap.
            val cx = .5f; val cy = .54f; val r = .38f; val start = 265f
            arc(cx - r, cy - r, cx + r, cy + r, start, 300f)
            val angle = Math.toRadians(start.toDouble())
            val tip = Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
            val back = Offset(-sin(angle).toFloat(), cos(angle).toFloat())
            for (turn in listOf(40.0, -40.0)) {
                val t = Math.toRadians(turn)
                val wing = Offset((back.x * cos(t) - back.y * sin(t)).toFloat(), (back.x * sin(t) + back.y * cos(t)).toFloat())
                line(tip.x, tip.y, tip.x + wing.x * .24f, tip.y + wing.y * .24f)
            }
        }
        PalmAction.FOLLOW -> { line(.08f, .2f, .38f, .5f); line(.38f, .5f, .08f, .8f); line(.48f, .2f, .78f, .5f); line(.78f, .5f, .48f, .8f) }
        PalmAction.WARP -> if (workspace.sphericalWarp) {
            arc(.05f, .05f, .95f, .45f, 200f, 140f); arc(.05f, .7f, .95f, 1.1f, 200f, 140f)
            line(.1f, .17f, .1f, .82f); line(.9f, .17f, .9f, .82f)
        } else box(.05f, .2f, .95f, .8f)
        PalmAction.MODE -> when (workspace.interactionMode) {

            InteractionMode.FOCUS -> {
                line(.05f, .05f, .32f, .05f); line(.05f, .05f, .05f, .32f); line(.95f, .05f, .68f, .05f); line(.95f, .05f, .95f, .32f)
                line(.05f, .95f, .32f, .95f); line(.05f, .95f, .05f, .68f); line(.95f, .95f, .68f, .95f); line(.95f, .95f, .95f, .68f)
                dot(.5f, .5f, .1f)
            }
            InteractionMode.WINDOW_GRAB -> { box(.05f, .15f, .95f, .85f); line(.05f, .33f, .95f, .33f); line(.5f, .45f, .5f, .75f); line(.35f, .6f, .65f, .6f) }
        }
        PalmAction.MORE -> { dot(.15f, .5f, .09f); dot(.5f, .5f, .09f); dot(.85f, .5f, .09f) }
        PalmAction.BACK -> { line(.65f, .1f, .25f, .5f); line(.25f, .5f, .65f, .9f) }
        PalmAction.DIAGNOSTICS -> { line(.05f, .95f, .95f, .95f); line(.22f, .95f, .22f, .55f); line(.5f, .95f, .5f, .1f); line(.78f, .95f, .78f, .38f) }
        PalmAction.HAND_MOUSE -> { line(.25f, .05f, .25f, .8f); line(.25f, .8f, .45f, .62f); line(.45f, .62f, .7f, .95f); line(.25f, .05f, .8f, .58f); line(.8f, .58f, .52f, .6f) }
        PalmAction.MOVE -> { line(.1f,.5f,.9f,.5f); line(.5f,.1f,.5f,.9f); line(.1f,.5f,.27f,.32f); line(.9f,.5f,.73f,.68f) }
        PalmAction.FREE -> { line(.06f,.72f,.42f,.27f); line(.58f,.72f,.94f,.27f); line(.06f,.72f,.3f,.72f); line(.70f,.27f,.94f,.27f) }
        PalmAction.REFERENCE -> box(.08f,.16f,.92f,.84f)
        PalmAction.SEARCH -> { arc(.05f, .25f, .95f, 1.15f, 225f, 90f); arc(.22f, .42f, .78f, .98f, 225f, 90f); dot(.5f, .78f, .08f) }
        PalmAction.GAZE_FOCUS -> {
            line(.05f, .05f, .28f, .05f); line(.05f, .05f, .05f, .28f); line(.95f, .05f, .72f, .05f); line(.95f, .05f, .95f, .28f)
            line(.05f, .95f, .28f, .95f); line(.05f, .95f, .05f, .72f); line(.95f, .95f, .72f, .95f); line(.95f, .95f, .95f, .72f)
            eye(.5f, .5f, .3f)
        }
        PalmAction.MAGNIFIER -> {
            ring(.42f, .42f, .3f); line(.64f, .64f, .95f, .95f)
            when (magnifierLabel(workspace)) {
                "off" -> line(.05f, .95f, .95f, .05f)
                "fixed" -> { line(.42f, .26f, .42f, .5f); dot(.42f, .26f, .08f) }
            }
        }
        PalmAction.MOUSE_SPACES -> {
            box(.02f, .25f, .36f, .65f); box(.64f, .25f, .98f, .65f)
            line(.36f, .45f, .64f, .45f)
            if (!workspace.cursorRemap) line(.42f, .3f, .58f, .6f)
            line(.12f, .8f, .26f, .8f); line(.74f, .8f, .88f, .8f)
        }
    }
}
