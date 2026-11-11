package dev.floatdesk.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    Column(Modifier.fillMaxSize().background(Color(0xF01C2430)).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Screens",color=Color.White,fontSize=22.sp)
            Button(onClick=onClose) {Text("Close")}
        }
        Text(if(busy) "Updating screens…" else "${state.screens.size} desktop screens · ${state.available} available",color=Color.White)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(enabled=!busy && state.available>0 && state.screens.size<6,onClick={connection.requestDisplays(1)}) {Text("Add screen")}
            Button(enabled=!busy,onClick={connection.requestDisplays()}) {Text("Refresh")}
        }
        monitors.firstOrNull()?.let { Text("Combined desktop — ${it.width} × ${it.height} streamed pixels",color=Color.White) }
        state.screens.forEachIndexed { index,(width,height) ->
            val managed=(state.owned and (1 shl index))!=0
            Text("${if(index==0) "Computer screen" else "Screen ${index+1}"} — $width × $height",color=Color.White,fontSize=16.sp)
            if(managed) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                for((w,h) in listOf(1280 to 720,1920 to 1080,2560 to 1600))
                    Button(enabled=!busy && (w!=width || h!=height),onClick={connection.requestDisplays(3,index,w,h)}) {Text("$w × $h",fontSize=12.sp)}
                Button(enabled=!busy,onClick={connection.requestDisplays(2,index)}) {Text("Remove")}
            }
        }
        Text(state.message,color=if(state.ok) Color(0xFFB8D9EA) else Color(0xFFFFB4AB),fontSize=13.sp)
        Text("Computer and pre-existing screens are preserved. Added screens are released when the host closes.",color=Color(0xFFB8D9EA),fontSize=12.sp)
    }
}
