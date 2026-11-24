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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.floatdesk.client.net.DeskConnection

@Composable
internal fun MonitorManagerPanel(connection: DeskConnection,spacing:Float,onSpacing:(Float)->Unit,onSpacingFinished:()->Unit,onClose:()->Unit,pinches:PinchThresholds,onPinches:(PinchThresholds)->Unit,viewedDetail:Boolean,onViewedDetail:(Boolean)->Unit,viewDebug:Boolean,onViewDebug:(Boolean)->Unit,quality:dev.floatdesk.client.workspace.WorkspaceFile,onQuality:(dev.floatdesk.client.workspace.WorkspaceFile)->Unit,onPage:(String)->Unit={},onAddFixed:()->Unit={},onViewingDistances:(Float,Float,Boolean)->Unit={_,_,_->}) {
    val monitors by connection.monitors
    val state by connection.displayState
    val busy by connection.displayBusy
    val info by connection.workspaceInfo
    val screens=info?.screens
    val sizes=screens?.map {it.width to it.height} ?: state.screens
    val positions=screens?.map {it.x to it.y} ?: state.positions
    val available=info?.available ?: state.available
    var section by remember { mutableStateOf("Screens") }
    androidx.compose.runtime.SideEffect{onPage(section)}
    MaterialTheme(colorScheme=darkColorScheme()) {
    Column(Modifier.fillMaxSize().background(Color(0xF01C2430)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(section,color=Color.White,fontSize=22.sp)
            IconButton(onClick=onClose,modifier=Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest,CircleShape).semantics {contentDescription="Close workspace"}) {Text("×",fontSize=30.sp)}
        }
        if(section!="Screens")Button(onClick={section=if(section=="Settings")"Screens" else "Settings"}){Text("‹ Back")}
        Text(if(busy) "Updating screens…" else "${sizes.size} desktop screens · $available available",color=Color.White)
        if(section=="Focus & magnification") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            for(mode in dev.floatdesk.client.workspace.InteractionMode.entries){
                Button(onClick={onQuality(quality.copy(interactionMode=mode))},modifier=Modifier.weight(1f)){
                    Text((if(quality.interactionMode==mode)"● "else "")+mode.label)
                }
            }
        }
        if(section=="Focus & magnification") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("Connected mouse spaces",color=Color.White)
            androidx.compose.material3.Switch(enabled=RenderPolicy.CursorTopology,checked=quality.cursorRemap,onCheckedChange={onQuality(quality.copy(cursorRemap=it))})
        }
        if(section=="Screens") Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(enabled=!busy && available>0,onClick={connection.requestDisplays(1)}) {Text("Add screen")}
            Button(enabled=!busy,onClick={connection.requestDisplays()}) {Text("Refresh")}
            Button(onClick={section="Settings"}) {Text("Settings")}
        }
        if(section=="Screens") Button(enabled=!busy && info!=null,onClick={
            connection.requestDisplays(if(info?.virtualScreensDisabled==true)7 else 6)
        }) {Text(if(info?.virtualScreensDisabled==true)"Restore virtual screens" else "Disable virtual screens")}
        if(section=="Screens")info?.let {host->
            val pixels=sizes.sumOf {it.first.toLong()*it.second}
            val capturePixels=if(sizes.isEmpty()) 0L else (sizes.indices.maxOf {positions[it].first+sizes[it].first}-positions.minOf {it.first}).toLong()*(sizes.indices.maxOf {positions[it].second+sizes[it].second}-positions.minOf {it.second})
            Text(String.format(Locale.ROOT,"%,d pixels (%.2f MP) · capture %.2f MP",pixels,pixels/1e6,capturePixels/1e6),color=Color.White,fontSize=13.sp)
            if(host.setupRequired) Text("No virtual output backend is ready. Open the computer's Floatdesk tray → Virtual screen setup.",color=Color(0xFFFFD69A),fontSize=13.sp)
        }
        if(section=="Screens")monitors.firstOrNull()?.let {stream->
            val sourceWidth=if(sizes.isEmpty()) stream.width else sizes.indices.maxOf {positions[it].first+sizes[it].first}-positions.minOf {it.first}
            val percent=(100f*stream.width/sourceWidth.coerceAtLeast(1)).roundToInt()
            Text("${if(viewedDetail) "Normal fallback" else "Stream"} ${stream.width} × ${stream.height} · $percent% scale",color=Color.White,fontSize=13.sp)
        }
        if(section=="Screens" && quality.showResources)Text(connection.resourceStatus.value,color=Color(0xFFB8D9EA),fontSize=11.sp,maxLines=3)
        if(!state.ok) Text(state.message,color=Color(0xFFFFB4AB),fontSize=13.sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(section=="Settings") {
            listOf("Focus & magnification","Viewing distance","Streaming","Pinches","Virtual screen setup").forEach{label->Button(onClick={section=label},modifier=Modifier.fillMaxWidth()){Text(label)}}
        }
        if(section=="Focus & magnification") {
            Row(verticalAlignment=Alignment.CenterVertically){
                androidx.compose.material3.Switch(checked=quality.aimToHover,onCheckedChange={onQuality(quality.copy(aimToHover=it))})
                Text("Aim to hover",color=Color.White)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                androidx.compose.material3.Switch(checked=quality.gazeToFocus,enabled=info?.gazeFocus==true || quality.gazeToFocus,onCheckedChange={onQuality(quality.copy(gazeToFocus=it))})
                Text("Gaze to focus",color=Color.White)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                androidx.compose.material3.Switch(checked=quality.magnifyWindow,enabled=info?.windowMagnify==true || quality.magnifyWindow,onCheckedChange={onQuality(quality.copy(magnifyWindow=it))})
                Text("Magnify focused window",color=Color.White)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("floating" to "Floating","fixed" to "Fixed").forEach{(mode,label)->Button(onClick={onQuality(quality.copy(magnifierMode=mode))}){Text(if(quality.magnifierMode==mode)"✓ $label" else label)}}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Panels & system menu",color=Color.White)
                androidx.compose.material3.Switch(checked=quality.magnifySystemPanels,onCheckedChange={onQuality(quality.copy(magnifySystemPanels=it))})
            }
            if(quality.magnifierMode=="fixed")Button(onClick=onAddFixed){Text("Add fixed magnifier")}
            if(quality.gazeToFocus && connection.gazeFocusError.value.isNotEmpty())Text(connection.gazeFocusError.value,color=Color(0xFFFFB4AB),fontSize=12.sp)
            var quiet by remember(quality.typingQuietMs){mutableStateOf((quality.typingQuietMs/1000f).toString())}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                androidx.compose.material3.OutlinedTextField(quiet,{quiet=it},Modifier.weight(1f),label={Text("Typing focus lock (seconds)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                val seconds=quiet.toFloatOrNull()?.takeIf{it.isFinite() && it in 0f..10f}
                Button(enabled=seconds!=null,onClick={seconds?.let{onQuality(quality.copy(typingQuietMs=(it*1000).toLong()))}}){Text("Apply")}
            }
        }
        if(section=="Streaming") {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(false to "Normal",true to "Dynamic").forEach{(mode,label)->
                    Button(enabled=!mode || RenderPolicy.DynamicQuality,onClick={onViewedDetail(mode)},colors=androidx.compose.material3.ButtonDefaults.buttonColors(containerColor=if(viewedDetail==mode)Color(0xFF396A9B)else Color(0xFF263344))){Text(if(viewedDetail==mode)"✓ $label" else label)}
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Switch(checked=viewDebug,onCheckedChange=onViewDebug)
                Text("Show diagnostics",color=Color.White)
            }
            if(viewedDetail)Text(connection.detailStatus.value,color=Color(0xFFB8D9EA),fontSize=13.sp)
            StreamingSettings(quality,onQuality)
        }
        if(section=="Viewing distance")ViewingDistanceSettings(quality,onViewingDistances)
        if(section=="Pinches"){
            Row(verticalAlignment=Alignment.CenterVertically){
                androidx.compose.material3.Switch(checked=quality.reverseHandScroll,onCheckedChange={onQuality(quality.copy(reverseHandScroll=it))})
                Text("Reverse hand scrolling",color=Color.White)
            }
            PinchSettings(pinches,onPinches)
        }
        if(section=="Virtual screen setup") {
            Text("Virtual screen setup",color=Color.White,fontSize=18.sp)
            Text("On the computer, run: python3 scripts/virtual-display-setup.py --check\nIf no pool is available, use --write-bundle to prepare installation files. A compatible VKMS configfs kernel, administrator setup and a graphical-session restart are required. Existing pools are reused; Floatdesk never rebuilds kernel topology during use.",color=Color(0xFFB8D9EA),fontSize=13.sp)
        }
        if(section=="Screens") {
        val names=sizes.indices.map{screens?.getOrNull(it)?.name ?: state.names.getOrNull(it) ?: "Screen ${it+1}"}
        var selectedName by remember {mutableStateOf<String?>(null)}
        var choosing by remember {mutableStateOf(false)}
        val index=names.indexOf(selectedName).takeIf{it>=0} ?: sizes.indices.firstOrNull{
            (screens?.getOrNull(it)?.role ?: state.roles.getOrNull(it))==1
        } ?: 0
        val name=names.getOrNull(index)
        if(name!=null){
            val output=screens?.getOrNull(index)
            val role=output?.role ?: state.roles.getOrNull(index)
            val managed=role==1 || output?.managed==true || (state.owned and (1 shl index))!=0
            val current=sizes[index]
            Box{
                Button(onClick={choosing=true},modifier=Modifier.fillMaxWidth()){
                    Text("$name · ${current.first} × ${current.second} ▾")
                }
                androidx.compose.material3.DropdownMenu(expanded=choosing,onDismissRequest={choosing=false}){
                    names.forEachIndexed{i,label->
                        val kind=when(screens?.getOrNull(i)?.role ?: state.roles.getOrNull(i)){3->"Built-in";2->"Physical";1->"Virtual";else->"Display"}
                        androidx.compose.material3.DropdownMenuItem(text={Text("$label · $kind")},onClick={selectedName=label;choosing=false})
                    }
                }
            }
            output?.refreshHz?.let {hz->Text(String.format(Locale.ROOT,"Refresh: %.3f Hz",hz),color=Color(0xFFB8D9EA),fontSize=12.sp)}
            if(managed) androidx.compose.runtime.key(name){
                ResolutionChoice(current,output?.modes.orEmpty(),!busy) {(w,h)->connection.requestDisplays(3,index,w,h)}
                Button(enabled=!busy,onClick={connection.requestDisplays(2,index)}) {Text("Remove $name")}
            }
        }
        ScreenArrangementEditor(sizes,positions,busy,screens?.indexOfFirst {it.primary}?.coerceAtLeast(0) ?: if(state.primary==0) 0 else Integer.numberOfTrailingZeros(state.primary),info?.limits ?: (32767 to 32767)) {draft->connection.requestArrangement(draft)}
        if(sizes.isNotEmpty()) {
            Text(String.format(Locale.ROOT,"Visual spacing: %.1f%%",spacing*100),color=Color.White)
            androidx.compose.material3.Slider(value=spacing,onValueChange={onSpacing((it*1000).roundToInt()/1000f)},onValueChangeFinished=onSpacingFinished,valueRange=0f..0.15f,steps=149,enabled=!busy)
        }
        }
        }
    }
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
    Text(String.format(Locale.ROOT,"%,d pixels",(width.toIntOrNull()?.toLong() ?: 0)*(height.toIntOrNull()?.toLong() ?: 0)),color=MaterialTheme.colorScheme.onSurface,fontSize=12.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(width,{width=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("Width")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=enabled)
        androidx.compose.material3.OutlinedTextField(height,{height=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("Height")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=enabled)
        val w=width.toIntOrNull();val h=height.toIntOrNull()
        Button(enabled=enabled && w!=null && h!=null && w in 1..65535 && h in 1..65535,onClick={onChoose(w!! to h!!)}) {Text("Set")}
    }
}

@Composable
private fun ViewingDistanceSettings(value:dev.floatdesk.client.workspace.WorkspaceFile,onChange:(Float,Float,Boolean)->Unit) {
    val layers=dev.floatdesk.client.workspace.ViewLayers
    Text("Floating objects sit between these distances; the desktop is farthest. Changes keep each object's apparent size.",color=Color(0xFFB8D9EA),fontSize=13.sp)
    DistanceControl("Closest",value.nearestDistanceM,layers.MinNearestM,layers.MaxNearestM){nearest,persist->onChange(nearest,value.farthestDistanceM,persist)}
    DistanceControl("Farthest",value.farthestDistanceM,value.nearestDistanceM+layers.MinSpanM,layers.MaxFarthestM){farthest,persist->onChange(value.nearestDistanceM,farthest,persist)}
    Button(onClick={onChange(layers.DefaultNearestM,layers.DefaultFarthestM,true)}){Text("Reset to 0.90 – 1.20 m")}
}

@Composable
private fun DistanceControl(label:String,meters:Float,minimum:Float,maximum:Float,onChange:(Float,Boolean)->Unit) {
    var text by remember(meters){mutableStateOf(String.format(Locale.ROOT,"%.2f",meters))}
    Text(String.format(Locale.ROOT,"%s: %.2f m · %.2f–%.2f m",label,meters,minimum,maximum),color=Color.White)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(maximum-minimum>=.01f)androidx.compose.material3.Slider(value=meters.coerceIn(minimum,maximum),onValueChange={onChange((it*100).roundToInt()/100f,false)},
            onValueChangeFinished={onChange(meters,true)},valueRange=minimum..maximum,modifier=Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        androidx.compose.material3.OutlinedTextField(text,{text=it},Modifier.width(96.dp),label={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        val parsed=text.toFloatOrNull()?.takeIf{it.isFinite() && it>=minimum-.0001f && it<=maximum+.0001f}
        Button(enabled=parsed!=null,onClick={parsed?.let{onChange(it,true)}}){Text("Set")}
    }
}

@Composable
private fun PinchSettings(value:PinchThresholds,onApply:(PinchThresholds)->Unit) {
    Text("Pinch thresholds",color=Color.White,fontSize=18.sp)
    var leftIndex by remember(value){mutableStateOf(value.leftIndexMm.toString())}
    var rightIndex by remember(value){mutableStateOf(value.rightIndexMm.toString())}
    var leftMiddle by remember(value){mutableStateOf(value.leftMiddleMm.toString())}
    var rightMiddle by remember(value){mutableStateOf(value.rightMiddleMm.toString())}
    Text("Contact distance (mm) · larger is easier, smaller is firmer",color=Color.White,fontSize=13.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(leftIndex,{leftIndex=it},Modifier.weight(1f),label={Text("Left index")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        androidx.compose.material3.OutlinedTextField(rightIndex,{rightIndex=it},Modifier.weight(1f),label={Text("Right index")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
    }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(leftMiddle,{leftMiddle=it},Modifier.weight(1f),label={Text("Left middle")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        androidx.compose.material3.OutlinedTextField(rightMiddle,{rightMiddle=it},Modifier.weight(1f),label={Text("Right middle")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
    }
    val parsed=listOf(leftIndex,rightIndex,leftMiddle,rightMiddle).map{it.toFloatOrNull()}
    val next=if(parsed.all{it!=null})PinchThresholds(parsed[0]!!,parsed[1]!!,parsed[2]!!,parsed[3]!!).takeIf{it.valid()} else null
    Button(enabled=next!=null,onClick={next?.let(onApply)}){Text("Apply to mouse, focus and grab")}
}

@Composable
private fun StreamingSettings(value:dev.floatdesk.client.workspace.WorkspaceFile,onApply:(dev.floatdesk.client.workspace.WorkspaceFile)->Unit){
    Text("Encoder effort",color=Color.White,fontSize=18.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        listOf("Fast","Balanced","Quality").forEachIndexed{i,label->
            Button(onClick={onApply(value.copy(encoderQuality=i))}){Text(if(value.encoderQuality==i)"✓ $label" else label)}
        }
    }
    var rate by remember(value.encoderMbps){mutableStateOf(value.encoderMbps.toString())}
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
        androidx.compose.material3.OutlinedTextField(rate,{rate=it},Modifier.weight(1f),label={Text("Bitrate (Mbps) · 0 = host default")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        val parsed=rate.toFloatOrNull()?.takeIf{it.isFinite() && it in 0f..200f}
        Button(enabled=parsed!=null,onClick={parsed?.let{onApply(value.copy(encoderMbps=it))}}){Text("Apply")}
    }
    var sharp by remember(value.sharpening){mutableStateOf(value.sharpening)}
    Text(String.format(Locale.ROOT,"Sharpening %.2f",sharp),color=Color.White)
    androidx.compose.material3.Slider(value=sharp,onValueChange={sharp=(it*20).roundToInt()/20f},onValueChangeFinished={onApply(value.copy(sharpening=sharp))},valueRange=0f..1.5f,steps=29)
    if(value.experimentalViewedDetail) {
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Button(onClick={onApply(value.copy(detailAreaPixels=0))}){Text(if(value.detailAreaPixels==0L)"✓ Auto" else "Auto")}
        Button(onClick={onApply(value.copy(detailAreaPixels=value.detailAreaPixels.takeIf{it>0} ?: 1_048_576))}){Text(if(value.detailAreaPixels>0)"✓ Fixed area" else "Fixed area")}
    }
    run{
        var fps by remember(value.detailTargetFps){mutableStateOf(value.detailTargetFps.toString())}
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
            androidx.compose.material3.OutlinedTextField(fps,{fps=it},Modifier.weight(1f),label={Text("FPS target")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            val parsed=fps.toIntOrNull()?.takeIf{it in 5..120}
            Button(enabled=parsed!=null,onClick={parsed?.let{onApply(value.copy(detailTargetFps=it))}}){Text("Apply")}
        }
    }
    if(value.detailAreaPixels>0L){
        var side by remember(value.detailAreaPixels){mutableStateOf(kotlin.math.sqrt(value.detailAreaPixels.toDouble()).toInt().toString())}
        val parsed=side.toLongOrNull()?.takeIf{it in 64..4096}
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
            androidx.compose.material3.OutlinedTextField(side,{side=it},Modifier.weight(1f),label={Text("Native square side (px)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Button(enabled=parsed!=null,onClick={parsed?.let{onApply(value.copy(detailAreaPixels=it*it))}}){Text("Apply")}
        }
        Text(parsed?.let{String.format(Locale.ROOT,"%d × %d · %.2f MP patch",it,it,it*it/1e6)} ?: "64–4096 pixels per side",color=Color.White)
    }
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        androidx.compose.material3.Switch(checked=value.showResources,onCheckedChange={onApply(value.copy(showResources=it))});Text("Live resources",color=Color.White)
    }
}
