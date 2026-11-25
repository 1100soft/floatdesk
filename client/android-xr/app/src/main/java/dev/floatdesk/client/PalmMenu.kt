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
import dev.floatdesk.client.workspace.WorkspaceFile
import kotlin.math.cos
import kotlin.math.sin

internal enum class PalmAction { FOLLOW, RESET, SEARCH, SETTINGS, WARP, MOVE, HAND_MOUSE, FREE, REFERENCE, TAP_TO_FOCUS, DRAG_TO_MOVE, HANDS_OFF, DIAGNOSTICS, GAZE_FOCUS, MAGNIFIER, MOUSE_SPACES, LAYOUT, ALIGN_AXES, SNAP_OBJECTS, FACE_USER, AUTO_ADJUST, RESET_ORIGIN, UNDO, REDO, LOAD_1, LOAD_2, LOAD_3, SAVE_1, SAVE_2, SAVE_3, DONE }
internal enum class PalmPage { MAIN, LAYOUT; val isLayout get()=this==LAYOUT }
internal data class PalmButton(val action: PalmAction, val x: Float, val y: Float, val w: Float, val h: Float)

/** Same cell metrics as [WindowMenuLayout]; button rectangles are normalized to the panel. */
internal object PalmMenuLayout {
    const val cellM = .041f
    const val circleM=.298f
    const val modesHeight=.057f
    const val widthM = circleM
    const val tooltipHeightM = .038f
    private val center = listOf(PalmAction.RESET,PalmAction.LAYOUT,PalmAction.SETTINGS)
    private val modes = listOf(PalmAction.HAND_MOUSE,PalmAction.TAP_TO_FOCUS,PalmAction.DRAG_TO_MOVE,PalmAction.HANDS_OFF)
    private data class Cluster(val y:Float,val actions:List<PalmAction>,val pitch:Float=.047f)
    private val view=Cluster(.075f,listOf(PalmAction.FOLLOW,PalmAction.WARP,PalmAction.DIAGNOSTICS))
    private val content=Cluster(.222f,listOf(PalmAction.MAGNIFIER,PalmAction.REFERENCE,PalmAction.GAZE_FOCUS))
    private val layout = listOf(
        Cluster(.048f,listOf(PalmAction.ALIGN_AXES,PalmAction.FACE_USER,PalmAction.SNAP_OBJECTS)),
        Cluster(.096f,listOf(PalmAction.MOVE,PalmAction.FREE,PalmAction.RESET_ORIGIN)),
        Cluster(.147f,listOf(PalmAction.UNDO,PalmAction.RESET,PalmAction.AUTO_ADJUST,PalmAction.DONE,PalmAction.REDO)),
        Cluster(.204f,listOf(PalmAction.LOAD_1,PalmAction.LOAD_2,PalmAction.LOAD_3),.052f),
        Cluster(.252f,listOf(PalmAction.SAVE_1,PalmAction.SAVE_2,PalmAction.SAVE_3),.052f),
    )
    val layoutActions=layout.flatMap{it.actions}

    fun heightM(page:PalmPage)=circleM+(if(page.isLayout)0f else modesHeight)
    fun circleFraction(page:PalmPage)=circleM/heightM(page)
    fun buttons(page:PalmPage):List<PalmButton> = buildList {
        val height=heightM(page)
        fun button(action:PalmAction,x:Float,y:Float){
            add(PalmButton(action,(x-cellM*.5f)/widthM,(y-cellM*.5f)/height,cellM/widthM,cellM/height))
        }
        fun cluster(group:Cluster){
            group.actions.forEachIndexed{i,action->
                button(action,circleM*.5f+(i-(group.actions.size-1)*.5f)*group.pitch,group.y)
            }
        }
        if(page.isLayout)layout.forEach(::cluster)
        else{
            cluster(view)
            cluster(Cluster(.148f,center))
            cluster(content)
            button(PalmAction.MOUSE_SPACES,.052f,.148f)
            button(PalmAction.SEARCH,.246f,.148f)
        }
        if(!page.isLayout)modes.forEachIndexed{i,action->button(action,circleM*.5f+(i-1.5f)*.047f,circleM+modesHeight*.5f)}
    }
}

internal fun palmActionEnabled(action:PalmAction,workspace:WorkspaceFile,info:WorkspaceInfo?):Boolean {
    if(workspace.layoutModeActive)return action in PalmMenuLayout.layoutActions
    if(action != PalmAction.RESET && action in PalmMenuLayout.layoutActions)return false
    return when(action){
        PalmAction.WARP->RenderPolicy.SphericalDesktop
        PalmAction.GAZE_FOCUS->info?.gazeFocus==true || workspace.gazeToFocus
        PalmAction.MAGNIFIER->info?.windowMagnify==true || workspace.magnifyWindow
        PalmAction.MOUSE_SPACES->RenderPolicy.CursorTopology
        else->true
    }
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
        PalmAction.HAND_MOUSE -> toggle(workspace.handMouse)
        PalmAction.TAP_TO_FOCUS -> toggle(workspace.tapToFocus)
        PalmAction.DRAG_TO_MOVE -> toggle(workspace.dragToMove)
        PalmAction.HANDS_OFF -> toggle(workspace.handsOff)
        PalmAction.FREE -> toggle(workspace.freeManipulation)
        PalmAction.REFERENCE -> toggle(workspace.desktopReference)
        PalmAction.DIAGNOSTICS -> toggle(workspace.viewDetailDebug)
        PalmAction.GAZE_FOCUS -> toggle(workspace.gazeToFocus)
        PalmAction.MOUSE_SPACES -> toggle(workspace.cursorRemap)
        PalmAction.WARP -> PalmState(2, if (workspace.sphericalWarp) 1 else 0)
        PalmAction.MAGNIFIER -> PalmState(3, listOf("fixed", "floating", "off").indexOf(magnifierLabel(workspace)))
        else -> null
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
    PalmAction.SETTINGS -> "Settings"
    PalmAction.RESET -> "Reset desktop"
    PalmAction.FOLLOW -> if (latched) "Following · tap to stop" else "Follow"
    PalmAction.WARP -> when {
        !RenderPolicy.SphericalDesktop -> "Spherical view unavailable"
        workspace.sphericalWarp -> "Spherical · tap for flat"
        else -> "Flat · tap for spherical"
    }
    PalmAction.DIAGNOSTICS -> if (workspace.viewDetailDebug) "Hide diagnostics" else "Show diagnostics"
    PalmAction.MOVE -> "One-hand auto layout"
    PalmAction.HAND_MOUSE -> "Hand mouse"
    PalmAction.TAP_TO_FOCUS -> "Tap to focus"
    PalmAction.DRAG_TO_MOVE -> "Drag to move"
    PalmAction.HANDS_OFF -> "Hands off"
    PalmAction.FREE -> "Two-hand manual layout"
    PalmAction.REFERENCE -> "Desktop reference"
    PalmAction.SEARCH -> "Reconnect"
    PalmAction.GAZE_FOCUS -> if (workspace.gazeToFocus) "Gaze to focus on" else "Gaze to focus off"
    PalmAction.MAGNIFIER -> "Magnifier ${magnifierLabel(workspace)} · tap for ${magnifierLabel(nextMagnifier(workspace))}"
    PalmAction.MOUSE_SPACES -> if (workspace.cursorRemap) "Mouse spaces connected" else "Mouse spaces separate"
    PalmAction.LAYOUT -> "Layout"
    PalmAction.ALIGN_AXES -> "Align with axes"
    PalmAction.SNAP_OBJECTS -> "Snap to objects"
    PalmAction.FACE_USER -> "Face the user"
    PalmAction.AUTO_ADJUST -> "Auto adjust"
    PalmAction.RESET_ORIGIN -> "Reset origin"
    PalmAction.LOAD_1,PalmAction.LOAD_2,PalmAction.LOAD_3 -> "Load layout ${action.ordinal-PalmAction.LOAD_1.ordinal+1}"
    PalmAction.SAVE_1,PalmAction.SAVE_2,PalmAction.SAVE_3 -> "Save layout ${action.ordinal-PalmAction.SAVE_1.ordinal+1}"
    PalmAction.UNDO -> "Undo layout change"
    PalmAction.REDO -> "Redo layout change"
    PalmAction.DONE -> "Done"
}

@Composable
internal fun PalmControls(page: PalmPage, workspace: WorkspaceFile, info: WorkspaceInfo?, latched: Boolean, focused: PalmAction?, pressed: PalmAction?,
                          onHover: (PalmAction?) -> Unit, onAction: (PalmAction) -> Unit, previews:List<FloatArray?> = emptyList(), modePanel:Boolean=false) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp(0f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fraction=PalmMenuLayout.circleFraction(page)
                Canvas(Modifier.fillMaxSize()){
                    if(modePanel)drawRoundRect(Color(0xFF293F52),Offset(size.width*.16f,0f),
                        Size(size.width*.68f,size.height),CornerRadius(size.height*.22f))
                    else{
                        drawCircle(if(page.isLayout)Color(0xFF28344F) else Color(0xFF202D3B),size.height/2)
                        val rows=if(page.isLayout)listOf(.048f,.096f,.147f,.204f,.252f) else listOf(.075f,.148f,.222f)
                        for(y in rows){
                            val width=size.width*(if(page.isLayout && y==.147f).83f else .49f)
                            val height=size.height*.143f
                            drawRoundRect(Color(0xFF34465D),Offset((size.width-width)/2,size.height*y/PalmMenuLayout.circleM-height/2),
                                Size(width,height),CornerRadius(height*.48f))
                        }
                    }
                }
                for (button in PalmMenuLayout.buttons(page)) {
                    if((button.y>=fraction)!=modePanel)continue
                    val buttonY=if(modePanel)(button.y-fraction)/(1f-fraction) else button.y/fraction
                    val action = button.action
                    val slot=action.ordinal-PalmAction.LOAD_1.ordinal
                    val loadSlot=slot in 0..2
                    val preview=if(loadSlot)previews.getOrNull(slot) else null
                    PalmIcon(action, workspace, palmTooltip(action, workspace, latched), maxWidth * button.w,
                        palmActionEnabled(action, workspace, info) && (!loadSlot || preview!=null), palmState(action, workspace, latched),
                        action == focused, action == pressed, { hovering -> onHover(action.takeIf { hovering }) },
                        Modifier.offset(maxWidth * button.x, maxHeight * buttonY),preview) { onAction(action) }
                }
            }
        }
    }
}

/** Immediate actions keep the round window-menu button; state buttons are rounded squares with state pips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PalmIcon(action: PalmAction, workspace: WorkspaceFile, description: String, cell: Dp, enabled: Boolean, state: PalmState?,
                     focused: Boolean, pressed: Boolean, onHover: (Boolean) -> Unit, modifier: Modifier, preview:FloatArray?, onClick: () -> Unit) {
    val done=action==PalmAction.DONE
    val shape = if(done)RoundedCornerShape(28) else if(state==null)CircleShape else RoundedCornerShape(22)
    val container = when {
        pressed -> Color(0xFF237E59)
        done -> Color(0xFF2E8052)
        state?.toggleOn == true -> Color(0xFF145D77)
        state != null -> Color(0xFF405369)
        else -> null
    }
    FilledTonalIconButton(onClick = onClick, enabled = enabled, shape = shape,
        colors = container?.let { IconButtonDefaults.filledTonalIconButtonColors(containerColor = it) } ?: IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = modifier.size(cell).trackMenuHover { onHover(it != null) }.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = size.minDimension * .25f
            inset(inset,inset){
                if(preview!=null && preview.isNotEmpty())drawLayoutPreview(preview)
                else drawPalmIcon(action,workspace,if(enabled)Color(0xFFF1FAFF) else Color(0xFF77838C))
            }
            if(state?.slots==1)drawToggleTrack(state.toggleOn,enabled)
            else if(state!=null)drawStatePips(state,if(enabled)1f else .38f)
        }
    }
    if (focused) Canvas(modifier.size(cell)) {
        val width = size.minDimension * .035f
        if (state == null && !done) drawCircle(Color(0xFF8ADFFF), (size.minDimension - width) / 2, style = Stroke(width))
        else drawRoundRect(Color(0xFF8ADFFF), Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
            CornerRadius(size.minDimension * .22f), style = Stroke(width))
    }
}

private fun DrawScope.drawLayoutPreview(lines:FloatArray){
    val xs=lines.indices.filter{it%5==0 || it%5==2}.map{lines[it]}
    val ys=lines.indices.filter{it%5==1 || it%5==3}.map{lines[it]}
    val l=xs.min();val r=xs.max();val t=ys.min();val b=ys.max()
    val scale=minOf(size.width/(r-l).coerceAtLeast(.001f),size.height/(b-t).coerceAtLeast(.001f))
    fun point(x:Float,y:Float)=Offset(size.width*.5f+(x-(l+r)*.5f)*scale,size.height*.5f+(y-(t+b)*.5f)*scale)
    for(i in lines.indices step 5)drawLine(if(lines[i+4]>0f)Color(0xFFBE87FF) else Color(0xFF64DCF0),
        point(lines[i],lines[i+1]),point(lines[i+2],lines[i+3]),strokeWidth=size.minDimension*.075f)
}

private fun DrawScope.drawToggleTrack(on:Boolean,enabled:Boolean) {
    val h=size.minDimension*.12f
    val w=h*2.15f
    val left=(size.width-w)*.5f
    val top=size.height*.79f
    val color=if(on)Color(0xFF38D9F3) else Color(0xFF8198AD)
    val alpha=if(enabled)1f else .27f
    drawRoundRect(color.copy(alpha=alpha),Offset(left,top),Size(w,h),CornerRadius(h*.5f))
    val x=left+if(on)w-h*.5f else h*.5f
    drawCircle(Color.White.copy(alpha=alpha),h*.34f,Offset(x,top+h*.5f))
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

private fun DrawScope.drawPalmIcon(action: PalmAction, workspace: WorkspaceFile,ink:Color) {
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
        PalmAction.SETTINGS -> {
            ring(.5f,.5f,.28f);ring(.5f,.5f,.105f)
            for(tooth in 0..7){
                val angle=tooth*Math.PI/4
                val x=kotlin.math.cos(angle).toFloat();val y=kotlin.math.sin(angle).toFloat()
                line(.5f+x*.29f,.5f+y*.29f,.5f+x*.40f,.5f+y*.40f)
            }
        }
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
        PalmAction.TAP_TO_FOCUS -> { box(.13f,.2f,.87f,.8f);ring(.5f,.5f,.16f);dot(.5f,.5f,.035f) }
        PalmAction.DRAG_TO_MOVE -> { box(.13f,.2f,.87f,.8f);line(.25f,.5f,.75f,.5f);line(.5f,.3f,.5f,.7f) }
        PalmAction.HANDS_OFF -> { ring(.5f,.5f,.36f);line(.26f,.75f,.74f,.25f) }
        PalmAction.DIAGNOSTICS -> { line(.05f, .95f, .95f, .95f); line(.22f, .95f, .22f, .55f); line(.5f, .95f, .5f, .1f); line(.78f, .95f, .78f, .38f) }
        PalmAction.HAND_MOUSE -> { line(.25f, .05f, .25f, .8f); line(.25f, .8f, .45f, .62f); line(.45f, .62f, .7f, .95f); line(.25f, .05f, .8f, .58f); line(.8f, .58f, .52f, .6f) }
        PalmAction.LAYOUT -> { box(.12f,.12f,.88f,.88f);line(.5f,.12f,.5f,.88f);line(.12f,.5f,.88f,.5f) }
        PalmAction.ALIGN_AXES -> { line(.1f,.5f,.9f,.5f);line(.5f,.1f,.5f,.9f);ring(.5f,.5f,.17f) }
        PalmAction.SNAP_OBJECTS -> { box(.06f,.27f,.49f,.73f);box(.51f,.27f,.94f,.73f) }
        PalmAction.FACE_USER -> eye(.5f,.5f,.39f)
        PalmAction.AUTO_ADJUST -> { ring(.5f,.5f,.24f);line(.5f,.1f,.5f,.9f);line(.1f,.5f,.9f,.5f) }
        PalmAction.RESET_ORIGIN -> { box(.13f,.22f,.87f,.78f);ring(.5f,.5f,.15f);dot(.5f,.5f,.045f) }
        PalmAction.UNDO,PalmAction.REDO -> {
            val left=action==PalmAction.UNDO
            val tip=if(left).18f else .82f
            val shoulder=if(left).36f else .64f
            line(tip,.38f,shoulder,.20f);line(tip,.38f,shoulder,.57f)
            arc(.18f,.38f,.82f,.99f,180f,180f)
        }
        PalmAction.LOAD_1,PalmAction.LOAD_2,PalmAction.LOAD_3,PalmAction.SAVE_1,PalmAction.SAVE_2,PalmAction.SAVE_3 -> {
            val loading=action.ordinal in PalmAction.LOAD_1.ordinal..PalmAction.LOAD_3.ordinal
            val slot=action.ordinal-(if(loading)PalmAction.LOAD_1 else PalmAction.SAVE_1).ordinal
            box(.14f,.14f,.86f,.86f)
            for(i in 0..slot) dot(.32f+i*.18f,.34f,.047f)
            val tip=if(loading).49f else .75f
            val tail=if(loading).75f else .49f
            line(.5f,tail,.5f,tip);line(.5f,tip,.35f,(tip+tail)*.5f);line(.5f,tip,.65f,(tip+tail)*.5f)
        }
        PalmAction.DONE -> { line(.12f,.53f,.39f,.77f);line(.39f,.77f,.88f,.24f) }
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
