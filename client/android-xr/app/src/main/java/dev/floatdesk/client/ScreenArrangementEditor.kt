package dev.floatdesk.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable
internal fun ScreenArrangementEditor(sizes:List<Pair<Int,Int>>,positions:List<Pair<Int,Int>>,busy:Boolean,primary:Int=0,limits:Pair<Int,Int> = 32767 to 32767,onApply:(List<Pair<Int,Int>>)->Unit) {
    if(sizes.isEmpty() || sizes.size!=positions.size) return
    val original=remember(sizes,positions) {sizes.mapIndexed {i,(w,h)->DesktopScreen(positions[i].first,positions[i].second,w,h)}}
    var screens by remember(original) {mutableStateOf(original)}
    val dropHistory=remember(screens) {ArrangementDropHistory()}
    var dragging by remember(screens) {mutableIntStateOf(-1)}
    var raw by remember(screens) {mutableStateOf(0f to 0f)}
    var preview by remember(screens) {mutableStateOf<DesktopScreen?>(null)}
    val connected=connectedScreens(screens,primary)
    val overlaps=overlappingScreens(screens)
    val validPreview=preview?.let {dragging in screens.indices && canMoveScreen(screens,dragging,it,primary,limits)} ?: true
    val density=LocalDensity.current.density
    if(overlaps.any {it}) Text("Overlapping screens: move them apart.",color=Color(0xFFFFB4AB),fontSize=13.sp)
    if(connected.any {!it}) Text("Disconnected screens: reconnect them to the main group.",color=Color(0xFFFFB4AB),fontSize=13.sp)
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).clipToBounds().background(Color(0xFF101720))) {
        val viewport=arrangementViewport(screens,maxWidth.value,220f)
        val scale=viewport.scale
        screens.forEachIndexed {index,screen ->
            val interaction=remember(index){MutableInteractionSource()}
            val hovered by interaction.collectIsHoveredAsState()
            val shown=if(dragging==index) preview ?: screen else screen
            Box(Modifier.offset {IntOffset(((viewport.offsetX+shown.x*scale)*density).roundToInt(),((viewport.offsetY+shown.y*scale)*density).roundToInt())}
                .size((shown.width*scale).dp,(shown.height*scale).dp)
                .zIndex(if(dragging==index) 1f else 0f)
                .background(if(dragging==index || hovered) Color(0xFF205976) else Color(0xFF293A50))
                .border(2.dp,if(dragging==index && !validPreview || dragging!=index && (!connected[index] || overlaps[index])) Color(0xFFFF6B6B) else if(dragging==index || hovered) Color.Cyan else Color(0xFF8DAAC6))
                .hoverable(interaction)
                .pointerInput(screens,busy,scale) {
                    if(!busy) detectDragGestures(
                        orientationLock=null,
                        onDragStart={_,start,_->dragging=index;raw=screen.x.toFloat() to screen.y.toFloat();preview=screen;dropHistory.begin(screen,start.uptimeMillis)},
                        onDragCancel={dragging=-1;preview=null;dropHistory.clear()},
                        onDragEnd={release->
                            val moved=dropHistory.finish(release.uptimeMillis)
                            dropHistory.clear()
                            if(moved!=null) screens=screens.mapIndexed {i,value->if(i==index)moved else value}
                            dragging=-1;preview=null
                        },
                        onDrag={change,delta->
                            change.consume()
                            raw=(raw.first+delta.x/density/scale) to (raw.second+delta.y/density/scale)
                            preview=snapScreen(screen.copy(x=raw.first.roundToInt(),y=raw.second.roundToInt()),screens.filterIndexed {i,_->i!=index},(14f/scale).roundToInt().coerceIn(24,160))
                            preview?.let {dropHistory.record(it,change.uptimeMillis)}
                        },
                    )
                },contentAlignment=Alignment.Center) {
                Text("${index+1}",color=Color.White,fontSize=14.sp,maxLines=1)
            }
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(enabled=!busy && screens!=original && canApplyArrangement(screens,primary,limits) && dragging<0,onClick={onApply(screens.map{it.x to it.y})}){Text("Apply")}
        TextButton(enabled=!busy && screens!=original,onClick={screens=original;preview=null;dragging=-1}){Text("Revert")}
    }
    preview?.let {Text(if(validPreview) "Screen ${dragging+1}: ${it.x}, ${it.y}" else "Screens must share an edge without overlapping.",color=if(validPreview) Color.Cyan else Color(0xFFFF6B6B),fontSize=12.sp)}
}
