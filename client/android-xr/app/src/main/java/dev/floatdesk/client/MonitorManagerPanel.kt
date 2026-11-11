package dev.floatdesk.client

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
internal fun MonitorManagerPanel(connection: DeskConnection,onClose:()->Unit) {
    val monitors by connection.monitors
    val state by connection.displayState
    val busy by connection.displayBusy
    var setup by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color(0xF01C2430)).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Screens",color=Color.White,fontSize=22.sp)
            Button(onClick=onClose) {Text("Close")}
        }
        Text(if(busy) "Updating screens…" else "${state.screens.size} desktop screens · ${state.available} available",color=Color.White)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(enabled=!busy && state.available>0 && state.screens.size<6,onClick={connection.requestDisplays(1)}) {Text("Add screen")}
            Button(enabled=!busy,onClick={connection.requestDisplays()}) {Text("Refresh")}
            Button(onClick={setup=!setup}) {Text("Screen setup")}
        }
        if(setup) {
            Text("Virtual screen setup",color=Color.White,fontSize=18.sp)
            Text("On the computer, run: python3 scripts/virtual-display-setup.py --check\nIf no pool is available, use --write-bundle to prepare installation files. A compatible VKMS configfs kernel, administrator setup and a graphical-session restart are required. Existing pools are reused; Floatdesk never rebuilds kernel topology during use.",color=Color(0xFFB8D9EA),fontSize=13.sp)
        }
        ScreenArrangementEditor(state.screens,state.positions,busy) {index,x,y->connection.requestDisplays(4,index,x,y)}
        monitors.firstOrNull()?.let { Text("Combined desktop — ${it.width} × ${it.height} streamed pixels",color=Color.White) }
        state.screens.forEachIndexed { index,(width,height) ->
            val managed=(state.owned and (1 shl index))!=0
            val role=when(state.roles.getOrNull(index)) {3->"Built-in display";2->"Physical display";1->"Virtual display";else->"Display"}
            val name=state.names.getOrNull(index) ?: "Screen ${index+1}"
            Text("${index+1}. $name · $role${if(state.primary and (1 shl index)!=0) " · Primary" else ""} — $width × $height",color=Color.White,fontSize=16.sp)
            if(managed) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                for((w,h) in listOf(1280 to 720,1920 to 1080,2560 to 1600))
                    Button(enabled=!busy && (w!=width || h!=height),onClick={connection.requestDisplays(3,index,w,h)}) {Text("$w × $h",fontSize=12.sp)}
                Button(enabled=!busy,onClick={connection.requestDisplays(2,index)}) {Text("Remove")}
            }
        }
        Text(state.message,color=if(state.ok) Color(0xFFB8D9EA) else Color(0xFFFFB4AB),fontSize=13.sp)
        Text("Resolution/removal is limited to app-added screens. Position edits affect the selected screen. Added screens are released when the host closes.",color=Color(0xFFB8D9EA),fontSize=12.sp)
    }
}
