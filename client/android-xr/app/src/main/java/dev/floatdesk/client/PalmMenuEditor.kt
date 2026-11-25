package dev.floatdesk.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.floatdesk.client.net.WorkspaceInfo
import dev.floatdesk.client.workspace.WorkspaceFile
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val PalmGridM=PalmMenuLayout.cellM/2
internal const val PalmEditorControlsHeightM=.11f

internal data class PalmGridPoint(val column:Int,val row:Int){
    val x get()=column*PalmGridM
    val y get()=row*PalmGridM
}
internal data class PalmMenuLabel(val point:PalmGridPoint,val text:String)
internal data class PalmCustomLayout(
    val positions:Map<PalmAction,PalmGridPoint>,
    val labels:List<PalmMenuLabel>,
){
    fun buttons(page:PalmPage)=PalmMenuLayout.buttons(page).map{button->
        val point=positions[button.action]
        if(point==null)button else button.copy(
            x=point.x/PalmMenuLayout.widthM-button.w/2,y=point.y/PalmMenuLayout.heightM(page)-button.h/2)
    }

    fun move(page:PalmPage,action:PalmAction,point:PalmGridPoint):PalmCustomLayout{
        val occupied=buttons(page).any{other->
            other.action!=action &&
                abs((other.x+other.w/2)*PalmMenuLayout.widthM-point.x)<PalmMenuLayout.cellM-.00001f &&
                abs((other.y+other.h/2)*PalmMenuLayout.heightM(page)-point.y)<PalmMenuLayout.cellM-.00001f
        }
        return if(occupied)this else copy(positions=positions+(action to point))
    }

    fun label(point:PalmGridPoint,text:String):PalmCustomLayout{
        val remaining=labels.filterNot{it.point==point}
        return copy(labels=if(text.isBlank())remaining else remaining+PalmMenuLabel(point,text.trim().take(32)))
    }

    fun encode():String{
        val cells=JSONObject()
        positions.forEach{(action,point)->cells.put(action.name,JSONArray(listOf(point.column,point.row)))}
        return JSONObject().put("positions",cells).put("labels",JSONArray(labels.map{
            JSONArray(listOf(it.point.column,it.point.row,it.text))
        })).toString()
    }

    companion object{
        fun defaults(page:PalmPage)=PalmCustomLayout(emptyMap(),PalmMenuLayout.clusters(page).mapNotNull{
            it.label.takeIf(String::isNotEmpty)?.let{text->PalmMenuLabel(
                PalmGridPoint((it.x*PalmMenuLayout.circleM/PalmGridM).roundToInt(),
                    (it.y*PalmMenuLayout.circleM/PalmGridM).roundToInt()),text)}
        })
        fun read(value:String?):PalmCustomLayout?=value?.let{raw->runCatching{
            val objectValue=JSONObject(raw)
            val cells=objectValue.getJSONObject("positions")
            val labels=objectValue.getJSONArray("labels")
            PalmCustomLayout(PalmAction.entries.mapNotNull{action->cells.optJSONArray(action.name)?.let{
                action to PalmGridPoint(it.getInt(0),it.getInt(1))
            }}.toMap(),List(labels.length()){index->
                val label=labels.getJSONArray(index)
                PalmMenuLabel(PalmGridPoint(label.getInt(0),label.getInt(1)),label.getString(2))
            })
        }.getOrNull()}
    }
}

@Composable
internal fun EditablePalmControls(
    page:PalmPage,saved:PalmCustomLayout?,workspace:WorkspaceFile,info:WorkspaceInfo?,latched:Boolean,
    editing:Boolean,focused:PalmAction?,pressed:PalmAction?,previews:List<FloatArray?>,bodyFraction:Float,
    onHover:(PalmAction?)->Unit,onAction:(PalmAction)->Unit,onFinish:(PalmCustomLayout?)->Unit,
){
    var draft by remember(page,saved,editing){mutableStateOf(saved ?: PalmCustomLayout.defaults(page))}
    var toolLabels by remember(page,editing){mutableStateOf(false)}
    var selectedLabel by remember(page,editing){mutableStateOf<PalmGridPoint?>(null)}
    var labelText by remember(page,editing){mutableStateOf("")}
    var dragged by remember(page,editing){mutableStateOf<PalmAction?>(null)}
    var dragCenter by remember(page,editing){mutableStateOf(Offset.Zero)}
    val chosen=if(editing)draft else saved ?: draft
    val currentLayout=rememberUpdatedState(chosen)
    val heightM=PalmMenuLayout.heightM(page)
    MaterialTheme(colorScheme=darkColorScheme()){
        BoxWithConstraints(Modifier.fillMaxSize()){
            val bodyHeight=maxHeight*bodyFraction
            val width=maxWidth
            var plane=Modifier.fillMaxWidth().height(bodyHeight).background(Color(0xFF202D3B))
            if(editing)plane=plane.pointerInput(page,toolLabels){
                fun cell(point:Offset,button:Boolean):PalmGridPoint{
                    val min=if(button)1 else 0
                    return PalmGridPoint(
                        (point.x/size.width*PalmMenuLayout.widthM/PalmGridM).roundToInt()
                            .coerceIn(min,floor(PalmMenuLayout.widthM/PalmGridM).toInt()-maxOf(1,min)),
                        (point.y/size.height*heightM/PalmGridM).roundToInt()
                            .coerceIn(min,floor(heightM/PalmGridM).toInt()-maxOf(1,min)),
                    )
                }
                if(toolLabels)detectTapGestures{point->
                    val picked=cell(point,false)
                    selectedLabel=picked
                    labelText=currentLayout.value.labels.firstOrNull{it.point==picked}?.text.orEmpty()
                }else detectDragGestures(
                    onDragStart={position->
                        val u=position.x/size.width;val v=position.y/size.height
                        val button=currentLayout.value.buttons(page).firstOrNull{u in it.x..it.x+it.w && v in it.y..it.y+it.h}
                        if(button!=null){
                            dragged=button.action
                            dragCenter=Offset((button.x+button.w/2)*size.width,(button.y+button.h/2)*size.height)
                        }
                    },
                    onDragCancel={dragged=null},
                    onDragEnd={dragged?.let{draft=draft.move(page,it,cell(dragCenter,true))};dragged=null},
                ){change,delta->if(dragged!=null){change.consume();dragCenter+=delta}}
            }
            Box(plane){
                if(editing)Canvas(Modifier.fillMaxSize()){
                    val dx=size.width*PalmGridM/PalmMenuLayout.widthM
                    val dy=size.height*PalmGridM/heightM
                    for(i in 1..floor(PalmMenuLayout.widthM/PalmGridM).toInt())
                        drawLine(Color(0xFF43627C),Offset(i*dx,0f),Offset(i*dx,size.height))
                    for(i in 1..floor(heightM/PalmGridM).toInt())
                        drawLine(Color(0xFF43627C),Offset(0f,i*dy),Offset(size.width,i*dy))
                    selectedLabel?.takeIf{toolLabels}?.let{
                        drawCircle(Color(0xFF65E0FC),3.dp.toPx(),Offset(it.column*dx,it.row*dy))
                    }
                }
                chosen.labels.forEach{label->
                    Text(label.text,Modifier.offset(width*(label.point.x/PalmMenuLayout.widthM),bodyHeight*(label.point.y/heightM)),
                        color=Color(0xFFD3E3F4),fontSize=11.sp,maxLines=1)
                }
                val scale=androidx.compose.ui.platform.LocalDensity.current
                for(button in chosen.buttons(page)){
                    val action=button.action
                    val x=if(dragged==action)with(scale){dragCenter.x.toDp()}-width*button.w/2 else width*button.x
                    val y=if(dragged==action)with(scale){dragCenter.y.toDp()}-bodyHeight*button.h/2 else bodyHeight*button.y
                    val slot=action.ordinal-PalmAction.LOAD_1.ordinal
                    val preview=previews.getOrNull(slot.takeIf{it in 0..2} ?: -1)
                    PalmIcon(action,workspace,palmTooltip(action,workspace,latched),width*button.w,
                        editing || palmActionEnabled(action,workspace,info) && (slot !in 0..2 || preview!=null),
                        palmState(action,workspace,latched),!editing && action==focused,!editing && action==pressed,
                        {if(!editing)onHover(action.takeIf{_->it})},Modifier.offset(x,y),preview){if(!editing)onAction(action)}
                }
            }
            if(editing)Column(Modifier.offset(y=bodyHeight).fillMaxWidth().background(Color(0xFF16232F)).padding(horizontal=6.dp)){
                Row{
                    TextButton(onClick={toolLabels=!toolLabels;selectedLabel=null}){Text(if(toolLabels)"Move buttons" else "Labels")}
                    TextButton(onClick={onFinish(null)}){Text("Cancel")}
                    TextButton(onClick={onFinish(draft)}){Text("Save")}
                }
                if(toolLabels && selectedLabel==null)Text("Tap a grid point to place or edit a label")
                selectedLabel?.takeIf{toolLabels}?.let{point->
                    Row{
                        OutlinedTextField(labelText,{labelText=it.take(32)},Modifier.weight(1f),singleLine=true,label={Text("Label")})
                        TextButton(onClick={draft=draft.label(point,labelText);selectedLabel=null}){Text(if(labelText.isBlank())"Remove" else "Place")}
                    }
                }
            }
        }
    }
}
