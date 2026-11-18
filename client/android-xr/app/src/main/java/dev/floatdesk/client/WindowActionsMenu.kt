package dev.floatdesk.client

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.floatdesk.client.net.DeskConnection

/** The same action sheet serves desktop windows and both kinds of magnifier. */
@Composable
internal fun WindowActionsMenu(connection:DeskConnection,window:Long?,magnifier:Boolean,onAdd:()->Unit,onRemove:()->Unit,onClose:()->Unit){
    var grid by remember{mutableStateOf(2 to 2)}
    fun action(name:String){window?.let{connection.windowAction(it,name)};onClose()}
    MaterialTheme(colorScheme=darkColorScheme()){
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                for((label,name) in listOf("−" to "minimize","□" to "maximize","×" to "close")){
                    Button(enabled=window!=null,onClick={action(name)},modifier=Modifier.weight(1f)){Text(label)}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){
                for(size in listOf(1 to 1,2 to 1,3 to 1,2 to 2)){
                    OutlinedButton(onClick={grid=size},modifier=Modifier.weight(1f),contentPadding=PaddingValues(2.dp)){
                        Text(if(grid==size)"●" else "${size.first}×${size.second}")
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                repeat(grid.second){row->Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    repeat(grid.first){col->Button(enabled=window!=null,onClick={
                        val info=connection.workspaceInfo.value
                        val source=info?.windows?.firstOrNull{it.id==window}
                        val screen=info?.screens?.firstOrNull{s->source!=null && source.x+source.width/2 in s.x until s.x+s.width && source.y+source.height/2 in s.y until s.y+s.height} ?: info?.screens?.firstOrNull{it.primary}
                        if(window!=null && screen!=null){
                            val x=screen.x+screen.width*col/grid.first;val y=screen.y+screen.height*row/grid.second
                            val right=screen.x+screen.width*(col+1)/grid.first;val bottom=screen.y+screen.height*(row+1)/grid.second
                            connection.windowAction(window,"geometry",WindowMove(window,x,y,right-x,bottom-y))
                        };onClose()
                    },modifier=Modifier.weight(1f).fillMaxHeight(),contentPadding=PaddingValues(0.dp)){Text("▣")}}
                }}
            }
            if(magnifier)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Button(onClick=onAdd,modifier=Modifier.weight(1f)){Text("Add")}
                Button(onClick=onRemove,modifier=Modifier.weight(1f)){Text("Remove")}
            }
            OutlinedButton(onClick=onClose,modifier=Modifier.fillMaxWidth()){Text("Dismiss")}
        }
    }
}
