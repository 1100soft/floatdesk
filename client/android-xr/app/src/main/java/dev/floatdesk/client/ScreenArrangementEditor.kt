package dev.floatdesk.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
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
internal fun ScreenArrangementEditor(sizes:List<Pair<Int,Int>>,positions:List<Pair<Int,Int>>,busy:Boolean,primary:Int=0,limits:Pair<Int,Int> = 32767 to 32767,onMove:(Int,Int,Int)->Unit) {
    if(sizes.isEmpty() || sizes.size!=positions.size) return
    val screens=remember(sizes,positions) {sizes.mapIndexed {i,(w,h)->DesktopScreen(positions[i].first,positions[i].second,w,h)}}
    var dragging by remember(screens) {mutableIntStateOf(-1)}
    var raw by remember(screens) {mutableStateOf(0f to 0f)}
    var preview by remember(screens) {mutableStateOf<DesktopScreen?>(null)}
    var waitingForApply by remember(screens) {mutableStateOf(false)}
    LaunchedEffect(busy,screens) {if(!busy && waitingForApply) {dragging=-1;preview=null;waitingForApply=false}}
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
            val shown=if(dragging==index) preview ?: screen else screen
            Box(Modifier.offset {IntOffset(((viewport.offsetX+shown.x*scale)*density).roundToInt(),((viewport.offsetY+shown.y*scale)*density).roundToInt())}
                .size((shown.width*scale).dp,(shown.height*scale).dp)
                .zIndex(if(dragging==index) 1f else 0f)
                .background(if(dragging==index) Color(0xFF205976) else Color(0xFF293A50))
                .border(2.dp,if(dragging==index && !validPreview || dragging!=index && (!connected[index] || overlaps[index])) Color(0xFFFF6B6B) else if(dragging==index) Color.Cyan else Color(0xFF8DAAC6))
                .pointerInput(screens,busy,scale) {
                    if(!busy) detectDragGestures(
                        onDragStart={dragging=index;raw=screen.x.toFloat() to screen.y.toFloat();preview=screen},
                        onDragCancel={dragging=-1;preview=null},
                        onDragEnd={
                            val moved=preview
                            if(moved!=null && canMoveScreen(screens,index,moved,primary,limits) && (moved.x!=screen.x || moved.y!=screen.y)) {
                                waitingForApply=true;onMove(index,moved.x,moved.y)
                            } else {dragging=-1;preview=null}
                        },
                        onDrag={change,delta->
                            change.consume()
                            raw=(raw.first+delta.x/density/scale) to (raw.second+delta.y/density/scale)
                            preview=snapScreen(screen.copy(x=raw.first.roundToInt(),y=raw.second.roundToInt()),screens.filterIndexed {i,_->i!=index},(14f/scale).roundToInt().coerceIn(24,160))
                        },
                    )
                },contentAlignment=Alignment.Center) {
                Text("${index+1}",color=Color.White,fontSize=14.sp,maxLines=1)
            }
        }
    }
    preview?.let {Text(if(validPreview) "Screen ${dragging+1}: ${it.x}, ${it.y}" else "Screens must share an edge without overlapping.",color=if(validPreview) Color.Cyan else Color(0xFFFF6B6B),fontSize=12.sp)}
}
