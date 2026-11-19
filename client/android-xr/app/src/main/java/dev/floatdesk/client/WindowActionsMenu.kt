package dev.floatdesk.client

import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.floatdesk.client.net.DeskConnection

/** Independent pages keep pane management separate from host window actions. */
@Composable
internal fun WindowActionsMenu(connection:DeskConnection,window:Long?,magnifier:Boolean,onAdd:()->Unit,onRemove:()->Unit,onClose:()->Unit,onGrid:(Boolean)->Unit={}){
    var page by remember(window,magnifier){mutableIntStateOf(if(magnifier)0 else 1)}
    SideEffect{onGrid(page==2)}
    var grid by remember{mutableStateOf(2 to 2)}
    fun action(name:String){window?.let{connection.windowAction(it,name)};onClose()}
    MaterialTheme(colorScheme=darkColorScheme()){
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                if(page==2 || (magnifier && page==1))OutlinedButton(onClick={page--}){Text("‹")}
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick=onClose){Text("×")}
            }
            when(page){
                0->{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        IconButton(onClick=onAdd){ActionDiagram("add")}
                        IconButton(onClick=onRemove){ActionDiagram("remove")}
                        IconButton(enabled=window!=null,onClick={page=1}){ActionDiagram("window")}
                    }
                }
                1->{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        for(name in listOf("minimize","maximize","close"))IconButton(enabled=window!=null,onClick={action(name)}){ActionDiagram(name)}
                        IconButton(onClick={page=2}){GridDiagram(2,2,null,Modifier.size(28.dp))}
                    }
                }
                2->{
                    Row(Modifier.fillMaxWidth().height(48.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(shape in listOf(1 to 1,2 to 1,3 to 1,2 to 2)){
                            OutlinedButton(onClick={grid=shape},modifier=Modifier.weight(1f).fillMaxHeight(),contentPadding=PaddingValues(4.dp)){
                                GridDiagram(shape.first,shape.second,null,Modifier.fillMaxSize(),grid==shape)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)){
                        // Every hit cell draws the entire monitor outline, emphasizing its destination.
                        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            repeat(grid.second){row->Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                repeat(grid.first){col->OutlinedButton(enabled=window!=null,onClick={
                                    val info=connection.workspaceInfo.value
                                    val source=info?.windows?.firstOrNull{it.id==window}
                                    val screen=info?.screens?.firstOrNull{s->source!=null && source.x+source.width/2 in s.x until s.x+s.width && source.y+source.height/2 in s.y until s.y+s.height} ?: info?.screens?.firstOrNull{it.primary}
                                    if(window!=null && screen!=null)connection.windowAction(window,"geometry",windowGridCell(window,screen.x,screen.y,screen.width,screen.height,grid.first,grid.second,col,row))
                                    onClose()
                                },modifier=Modifier.weight(1f).fillMaxHeight(),contentPadding=PaddingValues(6.dp)){
                                    GridDiagram(grid.first,grid.second,col to row,Modifier.fillMaxSize())
                                }}
                            }}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridDiagram(columns:Int,rows:Int,selected:Pair<Int,Int>?,modifier:Modifier,active:Boolean=false){
    Canvas(modifier){
        val pad=3.dp.toPx();val w=size.width-2*pad;val h=size.height-2*pad
        val ink=if(active)Color(0xFF70E5FA)else Color(0xFFB9CBDA)
        selected?.let{(col,row)->drawRect(Color(0xFF487BA5),Offset(pad+w*col/columns,pad+h*row/rows),Size(w/columns,h/rows))}
        drawRect(ink,Offset(pad,pad),Size(w,h),style=Stroke(2.dp.toPx()))
        for(col in 1 until columns)drawLine(ink,Offset(pad+w*col/columns,pad),Offset(pad+w*col/columns,pad+h),1.dp.toPx())
        for(row in 1 until rows)drawLine(ink,Offset(pad,pad+h*row/rows),Offset(pad+w,pad+h*row/rows),1.dp.toPx())
    }
}

@Composable
private fun ActionDiagram(action:String){
    Canvas(Modifier.size(26.dp)){
        val ink=Color(0xFFBCD7E8);val stroke=2.dp.toPx();val w=size.width;val h=size.height
        fun line(x:Float,y:Float,r:Float,b:Float)=drawLine(ink,Offset(x*w,y*h),Offset(r*w,b*h),stroke)
        when(action){
            "minimize"->line(.15f,.8f,.85f,.8f)
            "close"->{line(.2f,.2f,.8f,.8f);line(.8f,.2f,.2f,.8f)}
            else->{
                drawRect(ink,Offset(w*.1f,h*.15f),Size(w*.8f,h*.65f),style=Stroke(stroke))
                line(.1f,.3f,.9f,.3f)
                if(action=="add" || action=="remove"){line(.32f,.55f,.68f,.55f);if(action=="add")line(.5f,.4f,.5f,.7f)}
                if(action=="window"){line(.65f,.45f,.8f,.55f);line(.8f,.55f,.65f,.65f)}
            }
        }
    }
}
