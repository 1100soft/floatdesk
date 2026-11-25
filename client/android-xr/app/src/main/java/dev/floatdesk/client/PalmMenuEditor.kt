package dev.floatdesk.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
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

    fun itemAt(page:PalmPage,u:Float,v:Float):PalmDragItem?{
        buttons(page).firstOrNull{u in it.x..it.x+it.w && v in it.y..it.y+it.h}
            ?.let{return PalmDragItem.Button(it.action)}
        return labels.asReversed().firstOrNull{
            val dx=(u*PalmMenuLayout.widthM-it.point.x)/PalmGridM
            val dy=(v*PalmMenuLayout.heightM(page)-it.point.y)/PalmGridM
            dx>=-.35f && dx<=maxOf(1f,it.text.length*.40f) && dy in -.25f..1f
        }?.let{PalmDragItem.Label(it.point)}
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

internal data class PalmEditSession(val panel:PalmPanelState,val draft:PalmCustomLayout)

internal sealed interface PalmDragItem{
    data class Button(val action:PalmAction):PalmDragItem
    data class Label(val point:PalmGridPoint):PalmDragItem
}

@Composable
internal fun EditablePalmControls(
    page:PalmPage,saved:PalmCustomLayout?,workspace:WorkspaceFile,info:WorkspaceInfo?,latched:Boolean,
    editing:Boolean,focused:PalmAction?,pressed:PalmAction?,previews:List<FloatArray?>,bodyFraction:Float,
    onHover:(PalmAction?)->Unit,onAction:(PalmAction)->Unit,onDraft:(PalmCustomLayout)->Unit,onFinish:(PalmCustomLayout?)->Unit,
){
    var selectedLabel by remember(page,editing){mutableStateOf<PalmGridPoint?>(null)}
    var keyboardRequest by remember(page,editing){mutableStateOf(0)}
    var labelText by remember(page,editing){mutableStateOf("")}
    var dragged by remember(page,editing){mutableStateOf<PalmDragItem?>(null)}
    var displacement by remember(page,editing){mutableStateOf(Offset.Zero)}
    val chosen=saved ?: remember(page){PalmCustomLayout.defaults(page)}
    val currentLayout=rememberUpdatedState(chosen)
    val changeDraft=rememberUpdatedState(onDraft)
    val heightM=PalmMenuLayout.heightM(page)
    val focus=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    fun editLabel(point:PalmGridPoint){
        labelText=currentLayout.value.labels.firstOrNull{it.point==point}?.text.orEmpty()
        selectedLabel=point
        keyboardRequest++
    }
    MaterialTheme(colorScheme=darkColorScheme()){
        BoxWithConstraints(Modifier.fillMaxSize()){
            val bodyHeight=maxHeight*bodyFraction
            val width=maxWidth
            val density=androidx.compose.ui.platform.LocalDensity.current
            var plane=Modifier.fillMaxWidth().height(bodyHeight).background(Color(0xFF202D3B))
            if(editing)plane=plane.pointerInput(page){
                fun cell(x:Float,y:Float,button:Boolean):PalmGridPoint{
                    val min=if(button)1 else 0
                    return PalmGridPoint((x/PalmGridM).roundToInt().coerceIn(min,floor(PalmMenuLayout.widthM/PalmGridM).toInt()-1),
                        (y/PalmGridM).roundToInt().coerceIn(min,floor(heightM/PalmGridM).toInt()-1))
                }
                awaitEachGesture{
                    val down=awaitFirstDown(requireUnconsumed=false)
                    val u=down.position.x/size.width
                    val v=down.position.y/size.height
                    val layout=currentLayout.value
                    val item=layout.itemAt(page,u,v) ?: return@awaitEachGesture
                    val button=(item as? PalmDragItem.Button)?.let{picked->layout.buttons(page).first{it.action==picked.action}}
                    down.consume()
                    displacement=Offset.Zero
                    try{
                        while(true){
                            val change=awaitPointerEvent().changes.firstOrNull{it.id==down.id} ?: break
                            if(change.isConsumed)break
                            val delta=change.position-down.position
                            if(delta.getDistance()>viewConfiguration.touchSlop && dragged==null)dragged=item
                            if(!change.pressed){
                                change.consume()
                                val latest=currentLayout.value
                                if(dragged!=null){
                                    val dx=delta.x/size.width*PalmMenuLayout.widthM
                                    val dy=delta.y/size.height*heightM
                                    when(item){
                                        is PalmDragItem.Button->if(button!=null)changeDraft.value(latest.move(page,item.action,cell(
                                            (button.x+button.w/2)*PalmMenuLayout.widthM+dx,(button.y+button.h/2)*heightM+dy,true)))
                                        is PalmDragItem.Label->{
                                            val destination=cell(item.point.x+dx,item.point.y+dy,false)
                                            val text=latest.labels.firstOrNull{it.point==item.point}?.text
                                            if(text!=null && latest.labels.none{it.point==destination})
                                                changeDraft.value(latest.label(item.point,"").label(destination,text))
                                        }
                                    }
                                }else if(item is PalmDragItem.Label)editLabel(item.point)
                                break
                            }
                            if(dragged!=null)displacement=delta
                            change.consume()
                        }
                    }finally{dragged=null;displacement=Offset.Zero}
                }
            }
            Box(plane){
                if(editing)Canvas(Modifier.fillMaxSize()){
                    val dx=size.width*PalmGridM/PalmMenuLayout.widthM
                    val dy=size.height*PalmGridM/heightM
                    for(i in 1..floor(PalmMenuLayout.widthM/PalmGridM).toInt())
                        drawLine(Color(0xFF43627C),Offset(i*dx,0f),Offset(i*dx,size.height))
                    for(i in 1..floor(heightM/PalmGridM).toInt())
                        drawLine(Color(0xFF43627C),Offset(0f,i*dy),Offset(size.width,i*dy))
                }
                chosen.labels.forEach{label->
                    val delta=if(dragged==PalmDragItem.Label(label.point))displacement else Offset.Zero
                    Text(label.text,Modifier.offset(width*(label.point.x/PalmMenuLayout.widthM)+with(density){delta.x.toDp()},
                        bodyHeight*(label.point.y/heightM)+with(density){delta.y.toDp()}),
                        color=Color(0xFFD3E3F4),fontSize=11.sp,maxLines=1)
                }
                for(button in chosen.buttons(page)){
                    val action=button.action
                    val delta=if(dragged==PalmDragItem.Button(action))displacement else Offset.Zero
                    val slot=action.ordinal-PalmAction.LOAD_1.ordinal
                    val preview=previews.getOrNull(slot.takeIf{it in 0..2} ?: -1)
                    PalmIcon(action,workspace,palmTooltip(action,workspace,latched),width*button.w,
                        editing || palmActionEnabled(action,workspace,info) && (slot !in 0..2 || preview!=null),
                        palmState(action,workspace,latched),!editing && action==focused,!editing && action==pressed,
                        {if(!editing)onHover(action.takeIf{_->it})},Modifier.offset(width*button.x+with(density){delta.x.toDp()},
                            bodyHeight*button.y+with(density){delta.y.toDp()}),preview,interactive=!editing){onAction(action)}
                }
            }
            if(editing)Column(Modifier.offset(y=bodyHeight).fillMaxWidth().background(Color(0xFF16232F)).padding(horizontal=6.dp)){
                Row{
                    TextButton(onClick={
                        val columns=1 until floor(PalmMenuLayout.widthM/PalmGridM).toInt()
                        val rows=1 until floor(heightM/PalmGridM).toInt()
                        val point=rows.asSequence().flatMap{row->columns.asSequence().map{PalmGridPoint(it,row)}}
                            .firstOrNull{cell->chosen.labels.none{it.point==cell} && chosen.buttons(page).none{
                                cell.x/PalmMenuLayout.widthM in it.x..it.x+it.w && cell.y/heightM in it.y..it.y+it.h
                            }}
                        point?.let(::editLabel)
                    }){Text("Add label")}
                    TextButton(onClick={onFinish(null)}){Text("Cancel")}
                    TextButton(onClick={onFinish(selectedLabel?.let{chosen.label(it,labelText)} ?: chosen)}){Text("Save")}
                }
                selectedLabel?.let{point->
                    LaunchedEffect(keyboardRequest){focus.requestFocus();keyboard?.show()}
                    Row{
                        OutlinedTextField(labelText,{labelText=it.take(32)},Modifier.weight(1f).focusRequester(focus),singleLine=true,label={Text("Label")})
                        TextButton(onClick={onDraft(chosen.label(point,labelText));selectedLabel=null;keyboard?.hide()}){
                            Text(if(labelText.isBlank())"Remove" else "Done")
                        }
                    }
                }
            }
        }
    }
}
