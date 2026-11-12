package dev.floatdesk.client

import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.floatdesk.client.net.DeskConnection

@Composable
internal fun MonitorManagerPanel(connection: DeskConnection,spacing:Float,onSpacing:(Float)->Unit,onSpacingFinished:()->Unit,onClose:()->Unit) {
    val monitors by connection.monitors
    val state by connection.displayState
    val busy by connection.displayBusy
    val info by connection.workspaceInfo
    val screens=info?.screens
    val sizes=screens?.map {it.width to it.height} ?: state.screens
    val positions=screens?.map {it.x to it.y} ?: state.positions
    val available=info?.available ?: state.available
    var addSize by remember {mutableStateOf(1920 to 1080)}
    var setup by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color(0xF01C2430)).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Screens",color=Color.White,fontSize=22.sp)
            Button(onClick=onClose) {Text("Close")}
        }
        Text(if(busy) "Updating screens…" else "${sizes.size} desktop screens · $available available",color=Color.White)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(enabled=!busy && available>0,onClick={connection.requestDisplays(1,0,addSize.first,addSize.second)}) {Text("Add screen")}
            Button(enabled=!busy,onClick={connection.requestDisplays()}) {Text("Refresh")}
            Button(onClick={setup=!setup}) {Text("Screen setup")}
        }
        ResolutionChoice(addSize,info?.modes.orEmpty(),!busy) {addSize=it}
        info?.let {host->
            val pixels=sizes.sumOf {it.first.toLong()*it.second}
            val capturePixels=if(sizes.isEmpty()) 0L else (sizes.indices.maxOf {positions[it].first+sizes[it].first}-positions.minOf {it.first}).toLong()*(sizes.indices.maxOf {positions[it].second+sizes[it].second}-positions.minOf {it.second})
            Text(String.format(Locale.ROOT,"Content: %.1f MP · capture area: %.1f MP · estimate: %.1f MP at %d FPS",pixels/1e6,capturePixels/1e6,host.recommendedPixels/1e6,host.fps),color=Color.White,fontSize=13.sp)
            Text(String.format(Locale.ROOT,"%d CPU threads · %.1f GiB memory available. %s",host.cpuThreads,host.memoryBytes/1073741824.0,host.estimate),color=Color(0xFFB8D9EA),fontSize=12.sp)
            if(capturePixels>host.recommendedPixels && host.recommendedPixels>0) Text("Above the estimate; playback may be slower. You can still add or enlarge screens.",color=Color(0xFFFFD69A),fontSize=12.sp)
            if(host.setupRequired) Text("No virtual output backend is ready. Open the computer's Floatdesk tray → Virtual screen setup.",color=Color(0xFFFFD69A),fontSize=13.sp)
        }
        if(setup) {
            Text("Virtual screen setup",color=Color.White,fontSize=18.sp)
            Text("On the computer, run: python3 scripts/virtual-display-setup.py --check\nIf no pool is available, use --write-bundle to prepare installation files. A compatible VKMS configfs kernel, administrator setup and a graphical-session restart are required. Existing pools are reused; Floatdesk never rebuilds kernel topology during use.",color=Color(0xFFB8D9EA),fontSize=13.sp)
        }
        ScreenArrangementEditor(sizes,positions,busy,screens?.indexOfFirst {it.primary}?.coerceAtLeast(0) ?: if(state.primary==0) 0 else Integer.numberOfTrailingZeros(state.primary),info?.limits ?: (32767 to 32767)) {index,x,y->connection.requestDisplays(4,index,x,y)}
        if(sizes.isNotEmpty()) {
            Text(String.format(Locale.ROOT,"Visual spacing: %.1f%%",spacing*100),color=Color.White)
            androidx.compose.material3.Slider(value=spacing,onValueChange={onSpacing((it*1000).roundToInt()/1000f)},onValueChangeFinished=onSpacingFinished,valueRange=0f..0.15f,steps=149,enabled=!busy)
            Text("Reduces each screen’s width and height by this percentage, half on each edge. 10.0% leaves 90% of each dimension. Logical desktop edges stay connected; empty space is transparent.",color=Color(0xFFB8D9EA),fontSize=12.sp)
        }
        monitors.firstOrNull()?.let { Text("Combined desktop — ${it.width} × ${it.height} streamed pixels",color=Color.White) }
        sizes.forEachIndexed { index,(width,height) ->
            val managed=screens?.getOrNull(index)?.managed ?: ((state.owned and (1 shl index))!=0)
            val role=when(screens?.getOrNull(index)?.role ?: state.roles.getOrNull(index)) {3->"Built-in display";2->"Physical display";1->"Virtual display";else->"Display"}
            val name=screens?.getOrNull(index)?.name ?: state.names.getOrNull(index) ?: "Screen ${index+1}"
            Text("${index+1}. $name · $role${if(screens?.getOrNull(index)?.primary ?: (state.primary and (1 shl index)!=0)) " · Primary" else ""} — $width × $height",color=Color.White,fontSize=16.sp)
            screens?.getOrNull(index)?.refreshHz?.let {hz->Text(String.format(Locale.ROOT,"Refresh: %.3f Hz",hz),color=Color(0xFFB8D9EA),fontSize=12.sp)}
            if(managed) {
                ResolutionChoice(width to height,screens?.getOrNull(index)?.modes.orEmpty(),!busy) {(w,h)->connection.requestDisplays(3,index,w,h)}
                Button(enabled=!busy,onClick={connection.requestDisplays(2,index)}) {Text("Remove")}
            }
        }
        Text(state.message,color=if(state.ok) Color(0xFFB8D9EA) else Color(0xFFFFB4AB),fontSize=13.sp)
        Text("Resolution/removal is limited to app-added screens. Position edits affect the selected screen. Added screens are released when the host closes.",color=Color(0xFFB8D9EA),fontSize=12.sp)
    }
}

@Composable
private fun ResolutionChoice(current:Pair<Int,Int>,modes:List<Pair<Int,Int>>,enabled:Boolean,onChoose:(Pair<Int,Int>)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    var width by remember(current) {mutableStateOf(current.first.toString())}
    var height by remember(current) {mutableStateOf(current.second.toString())}
    Box {
        Button(enabled=enabled,onClick={expanded=true}) {Text("${current.first} × ${current.second} ▾")}
        androidx.compose.material3.DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            (modes+current).distinct().sortedBy {it.first.toLong()*it.second}.forEach {mode->
                androidx.compose.material3.DropdownMenuItem(text={Text("${mode.first} × ${mode.second}")},onClick={expanded=false;onChoose(mode)})
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(width,{width=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("Width")},singleLine=true,enabled=enabled)
        androidx.compose.material3.OutlinedTextField(height,{height=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("Height")},singleLine=true,enabled=enabled)
        val w=width.toIntOrNull();val h=height.toIntOrNull()
        Button(enabled=enabled && w!=null && h!=null && w in 1..65535 && h in 1..65535,onClick={onChoose(w!! to h!!)}) {Text("Set")}
    }
}
