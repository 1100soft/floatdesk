package dev.floatdesk.client

import androidx.compose.runtime.*
import dev.floatdesk.client.net.DeskConnection
import kotlinx.coroutines.*

/** Reuse the desktop's compositor, geometry and input. No additional scene surface. */
@Composable
internal fun InPlaceWindowDetail(connection:DeskConnection,id:Long?,onReady:()->Unit,onError:(String)->Unit){
    val ready=rememberUpdatedState(onReady);val failed=rememberUpdatedState(onError)
    LaunchedEffect(connection,id){
        if(id==null)return@LaunchedEffect
        while(isActive){
            val video=connection.windowRenderer()
            if(video==null){delay(50);continue}
            val main=android.os.Handler(android.os.Looper.getMainLooper())
            var live=true
            val output=try{video.windowDestination{main.post{if(live)ready.value()}}}catch(e:Exception){failed.value(e.message ?: "Window detail unavailable");return@LaunchedEffect}
            val stream=connection.windowCaptures.attach(id,output.surface,{if(live)ready.value()},{message->if(live)failed.value(message)},output)
            try{
                while(isActive && connection.windowRenderer()===video)delay(100)
            }finally{live=false;stream?.close();output.clear()}
        }
    }
}
