package dev.floatdesk.client.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.mutableStateOf
import dev.floatdesk.client.ViewProjection
import dev.floatdesk.client.FocusTarget
import dev.floatdesk.client.FocusResult
import dev.floatdesk.client.qualityOutputSize
import dev.floatdesk.client.qualityDenseFraction
import dev.floatdesk.client.debugSegments
import dev.floatdesk.client.coarsened
import dev.floatdesk.client.wires
import dev.floatdesk.client.FocusRect
import dev.floatdesk.client.media.MonitorDecoder
import dev.floatdesk.client.media.VideoAim
import dev.floatdesk.client.media.ScreenPresentation
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class DeskConnection(
    private val dumpFile: java.io.File? = null,
) {
    val status = mutableStateOf("Looking for your computer…")
    val problem = mutableStateOf<String?>(null)
    @Volatile private var appContext: Context? = null
    private var events = ConnectionEventLog(null)
    @Volatile private var startedAt = 0L
    @Volatile private var lastHostReplyAt = 0L
    @Volatile private var lastBeaconAt = 0L
    private var lastProblemCheck = 0L
    private var lastProblem: String? = null
    val monitors = mutableStateOf<List<MonitorHello>>(emptyList())
    val cursors = mutableStateOf<Map<Int, CursorView>>(emptyMap())

    val displayState=mutableStateOf(Packet.MonitorStatus(0,true,0,0,"Checking available displays…"))
    val displayBusy=mutableStateOf(false)
    val workspaceInfo=mutableStateOf<WorkspaceInfo?>(null)
    val resourceStatus=mutableStateOf("Waiting for resource telemetry…")
    @Volatile private var videoQuality=0
    @Volatile private var videoMbps=0f
    @Volatile private var videoSharpen=0f
    @Volatile private var denseFraction=.35f
    @Volatile private var nativeAreaPixels=0L
    @Volatile private var detailTargetFps=30
    private var localResources:LocalResources?=null
    private val cooling=ThermalVideoGate()
    val thermalPaused=mutableStateOf(false)
    private val optionalRenderingLimited=mutableStateOf(false)
    val limitOptionalRendering get()=optionalRenderingLimited.value
    @Volatile private var heatPaused=false
    fun isVideoActive()=renderingActive && !heatPaused

    private fun updateThermal(level:Int,now:Long){
        optionalRenderingLimited.value=level>=android.os.PowerManager.THERMAL_STATUS_SEVERE
        val paused=cooling.update(level,now)
        if(paused==heatPaused)return
        heatPaused=paused
        thermalPaused.value=paused
        if(paused)viewedDetail(null,"Paused: headset cooling")
        Log.i("Floatdesk",if(paused)"Video paused at thermal level $level" else "Video resumed after cooling")
        applyVideoActivity()
        if(!paused && renderingActive)askKeyframe()
    }
    fun configureVideo(quality:Int,mbps:Float,sharpen:Float,fraction:Float,areaPixels:Long=0,targetFps:Int=30){
        val encodingChanged=videoQuality!=quality || videoMbps!=mbps
        nativeAreaPixels=areaPixels;detailTargetFps=targetFps
        videoQuality=quality;videoMbps=mbps;videoSharpen=sharpen;denseFraction=fraction
        decoders.values.forEach{it.sharpening(sharpen)}
        if(encodingChanged)sendEncoderSettings()
    }
    private fun sendEncoderSettings(){send(Protocol.workspace(org.json.JSONObject().put("type","encoderSettings").put("quality",videoQuality).put("bitrateMbps",videoMbps).toString()))}
    private fun publishResources(){val state=detailDiagnostics.get();main.post{resourceStatus.value=state.hostResources+"\n"+state.localResources}}
    val detailStatus=mutableStateOf("Experimental viewed detail off")
    private var detailWorker:ViewedDetail?=null
    val windowCaptureEpoch=androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile private var detailSession=0L
    private val detailDiagnostics=AtomicReference(DetailDiagnostics())
    fun refreshOutput(index:Int){decoders[index]?.refreshOutput()}
    fun diagnosticSnapshot()=decoders[0]?.diagnosticSnapshot() ?: DiagnosticSnapshot(detailDiagnostics.get(),null,"Waiting for video")
    fun displayedQualityFocus():Pair<Float,Float>?=decoders[0]?.displayedQualityFocus()
    fun hasViewedDetail():Boolean=decoders[0]?.hasViewedDetail()==true
    fun qualityOutputLimit():Int=decoders[0]?.qualityOutputLimit() ?: 4096
    fun detailDebug(enabled:Boolean,showOverlay:Boolean=true,showResources:Boolean=false) {
        if(detailDiagnostics.get().let{it.enabled==enabled && it.showOverlay==showOverlay && it.showResources==showResources})return
        val state=detailDiagnostics.updateAndGet{it.copy(enabled=enabled,showOverlay=showOverlay,showResources=showResources)};decoders[0]?.detailDebug(state)
    }
    fun viewedDetail(rect:FocusRect?,blockedReason:String="No desktop in view",center:Pair<Float,Float>?=null,targeting:String="GAZE") {
        val remote=pendingRemote
        val bounds=workspaceInfo.value?.bounds()?.toList()
        val format=currentMonitors.singleOrNull()
        val target=bounds?.let{qualityOutputSize(it[2],it[3],qualityOutputLimit())}
        val fraction=if(rect!=null && target!=null)qualityDenseFraction(denseFraction,rect,target.first,target.second) else denseFraction
        val request=if(detailDiagnostics.get().enabled && rect!=null && isVideoActive() && desktopVisible && remote!=null && bounds!=null && format!=null)DetailRequest(remote,detailSession,rect,bounds,center ?: (rect.centerU to rect.centerV),videoQuality,videoMbps,fraction,nativeAreaPixels,detailTargetFps) else null
        val reason=when {
            !detailDiagnostics.get().enabled->"Normal full-desktop streaming"
            rect==null->blockedReason
            format==null->"Waiting for one combined desktop stream"
            !renderingActive->"Paused: app hidden"
            remote==null || bounds==null->"Waiting for host/layout"
            else->if(nativeAreaPixels==0L)"$targeting · Auto $detailTargetFps fps" else "$targeting · %.2f MP native".format(java.util.Locale.ROOT,nativeAreaPixels/1e6)
        }
        val state=detailDiagnostics.updateAndGet{it.copy(candidate=rect,reason=reason,
            gainX=if(rect!=null && format!=null)2048f/((rect.right-rect.left)*fraction*format.width) else 0f,
            gainY=if(rect!=null && format!=null)1024f/((rect.bottom-rect.top)*fraction*format.height) else 0f)}
        decoders[0]?.detailDebug(state)
        if(request!=null && detailWorker==null)detailWorker=ViewedDetail({decoders[0]?.viewDestination()},{message->
            val updated=detailDiagnostics.updateAndGet{it.copy(transport=message)};decoders[0]?.detailDebug(updated)
            main.post{detailStatus.value=message}
        },{send(Protocol.workspace("{\"type\":\"viewStop\"}"));askKeyframe()})
        detailWorker?.update(request)
        if(rect==null && format!=null && blockedReason=="No desktop in view" && detailDiagnostics.get().enabled && renderingActive)
            send(Protocol.workspace("{\"type\":\"viewIdle\"}"))
    }
    val focusResult=mutableStateOf<FocusResult?>(null)
    val focusBusy=mutableStateOf(false)
    private val focusPending=AtomicInteger()
    private val gazeFocusPending=AtomicInteger()
    val gazeFocusError=mutableStateOf("")
    private val screenPresentation=AtomicReference(ScreenPresentation())
    private var screenSpacing=0f
    val reloading=mutableStateOf(false)
    private val videoPending=AtomicBoolean(false)
    private val decoderGeneration=AtomicInteger()
    private val monitorSequence=AtomicInteger()
    private val monitorPending=AtomicInteger()
    private var currentMonitors=emptyList<MonitorHello>()
    private val hostDiscovery=HostDiscovery()
    @Volatile private var lastAcceptedHello=0L
    private val activeSurfaces=ConcurrentHashMap<Int,Surface>()
    private val main = Handler(Looper.getMainLooper())
    private var assembler = CursorAssembler()
    private val inputEpoch = AtomicInteger()
    @Volatile private var renderingActive=true
    @Volatile private var desktopVisible=true
    private val decoders = ConcurrentHashMap<Int, MonitorDecoder>()
    private val viewProjections=HashMap<Int,ViewProjection?>()
    private val pendingCursors = ConcurrentHashMap<Int, CursorView>()
    private val cursorSeq = AtomicInteger()
    private val pointerSeq = AtomicInteger()
    private val clickSeq = AtomicInteger()
    private var pointerDragging = false
    private val padSeq=AtomicInteger()
    private val pendingPad=AtomicReference<ByteArray?>(null)
    private val padFlushPosted=AtomicBoolean(false)
    private var padFingers=0
    private val pendingPointer = AtomicReference<ByteArray?>(null)
    private val pointerFlushPosted = AtomicBoolean(false)
    @Volatile private var outgoing: Handler? = null
    private var outgoingThread: HandlerThread? = null
    private val cursorFlushPosted = AtomicBoolean(false)
    private var lastGapAsk = 0L
    @Volatile private var control: DatagramSocket? = null
    private var beacon: DatagramSocket? = null
    private var videos: List<DatagramSocket> = emptyList()
    private var thread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile private var pendingRemote: InetSocketAddress? = null

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        events = ConnectionEventLog(java.io.File(context.applicationContext.filesDir, "connection-events.log"))
        startedAt = android.os.SystemClock.elapsedRealtime(); lastHostReplyAt = 0L; lastBeaconAt = 0L; lastProblem = null
        val opened = ArrayList<DatagramSocket>()
        try {
            for (port in 48000 until 48006) {
                opened += DatagramSocket(port).apply { receiveBufferSize = 8 * 1024 * 1024 }
            }
            control = DatagramSocket().apply { broadcast = true }
            beacon = DatagramSocket(47001).apply {
                broadcast = true
                soTimeout = 1 // Beacon polling must not stall each control packet for 200 ms.
            }
        } catch (err: Exception) {
            opened.forEach { it.close() }
            control?.close()
            beacon?.close()
            status.value = "Could not listen for the computer: ${err.message}"
            problem.value = status.value
            Log.e("Floatdesk", status.value, err); events.append(status.value)
            return
        }
        videos = opened
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("floatdesk")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        running = true
        val power=context.applicationContext.getSystemService(android.os.PowerManager::class.java)
        localResources=LocalResources(thermalStatus={power?.currentThermalStatus ?: -1},
            onThermal={level,now->main.post{if(running)updateThermal(level,now)}},
            diagnosticsVisible={detailDiagnostics.get().let{it.showResources && it.showOverlay}}) {message->
            val value=detailDiagnostics.updateAndGet{it.copy(localResources=message)};decoders[0]?.detailDebug(value);publishResources()
        }
        outgoingThread = HandlerThread("floatdesk-input").also { it.start(); outgoing = Handler(it.looper) }
        status.value = "Looking for your computer…"
        thread = Thread({ controlLoop() }, "floatdesk-control").also { it.start() }
    }

    fun disconnect() {
        publishCursorMap(dev.floatdesk.client.CursorTopology())
        invalidateHostState()
        localResources?.close();localResources=null
        detailWorker?.close();detailWorker=null
        running = false
        control?.close()
        beacon?.close()
        pendingPointer.set(null)
        pointerDragging = false
        pendingPad.set(null); padFingers=0; padFlushPosted.set(false)
        outgoing = null
        outgoingThread?.quitSafely()
        outgoingThread?.join(500)
        outgoingThread = null
        pointerFlushPosted.set(false)
        decoders.values.forEach { it.stop() }
        decoders.clear()
        currentMonitors=emptyList()
        screenPresentation.set(ScreenPresentation())
        monitorPending.set(0);focusPending.set(0);gazeFocusPending.set(0)
        videoPending.set(false);decoderGeneration.incrementAndGet()
        videos.forEach { runCatching { it.close() } }
        videos = emptyList()
        thread?.join(500)
        thread = null
        control = null
        beacon = null
        pendingRemote = null
        hostDiscovery.clear();lastAcceptedHello=0L
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        main.post {
            monitors.value = emptyList()
            cursors.value = emptyMap()
            displayBusy.value = false
            workspaceInfo.value=null;focusBusy.value=false;focusResult.value=null
            reloading.value=false
            status.value = "Disconnected"
            problem.value = null
        }
    }

    fun isRenderingActive(): Boolean = renderingActive

    @Synchronized
    fun setDesktopVisible(visible:Boolean) {
        if(desktopVisible==visible)return
        desktopVisible=visible
        sendRenderingState()
        if(!visible)viewedDetail(null)
        decoders.values.forEach { it.setRenderingActive(isVideoActive() && visible) }
    }

    private fun sendRenderingState(){send(Protocol.workspace(org.json.JSONObject().put("type","renderState").put("active",isVideoActive() && desktopVisible).toString()))}
    @Synchronized
    fun setRenderingActive(active: Boolean) {
        renderingActive=active
        if(!active){viewedDetail(null);cancelGazeFocus();publishCursorMap(dev.floatdesk.client.CursorTopology())}
        applyVideoActivity()
    }

    @Synchronized
    private fun applyVideoActivity(){
        sendRenderingState()
        val active=isVideoActive()
        if(windowCapturesLazy.isInitialized())windowCaptures.setActive(active)
        decoders.values.forEach { it.setRenderingActive(active && desktopVisible) }
    }

    private val rebuildStalledDecoders=java.util.concurrent.atomic.AtomicBoolean()
    private val outputRecoveryEpoch=java.util.concurrent.atomic.AtomicInteger()
    fun recoverRenderingOutputs(){
        val epoch=outputRecoveryEpoch.incrementAndGet()
        val started=android.os.SystemClock.elapsedRealtime()
        fun recover(attempt:Int){
            if(!running || !isVideoActive() || !desktopVisible || outputRecoveryEpoch.get()!=epoch)return
            val awaitingSurface=monitors.value.any { activeSurfaces[it.index]?.isValid!=true }
            val stalled=decoders.filter{(index,decoder)->outputNeedsRecovery(activeSurfaces[index]?.isValid==true,decoder.lastPresentedAt(),started)}
            if(attempt==0 || stalled.isNotEmpty()){
                stalled.values.forEach{it.recoverOutput()}
                pendingRemote?.let{remote->stalled.values.forEach{it.punch(remote)}}
                send(Protocol.keyframeRequest(0xff))
            }
            if(attempt<3 && (attempt==0 || awaitingSurface || stalled.isNotEmpty()))main.postDelayed({recover(attempt+1)},1500L)
            else if(attempt==3 && stalled.isNotEmpty() && dumpFile==null){
                Log.w("Floatdesk","Rebuilding desktop decoders after wake without a presented frame")
                rebuildStalledDecoders.set(true)
            }
        }
        main.post{windowCaptureEpoch.intValue++;recover(0)}
    }

    fun requestDisplays(action:Int=0,index:Int=0,width:Int=1920,height:Int=1080) {
        if (lastAcceptedHello == 0L || outgoing == null) return
        if(!monitorPending.compareAndSet(0,-1)) return
        val id=monitorSequence.incrementAndGet()
        monitorPending.set(id);displayBusy.value=true
        submitDisplayRequest(id,monitorCommand(id,action,index,width,height),action==0)
    }

    fun requestArrangement(positions:List<Pair<Int,Int>>) {
        if (lastAcceptedHello == 0L) return
        val info=workspaceInfo.value ?: return
        if(positions.size!=info.screens.size || outgoing==null || !monitorPending.compareAndSet(0,-1))return
        val id=monitorSequence.incrementAndGet();monitorPending.set(id);displayBusy.value=true
        val rows=org.json.JSONArray()
        info.screens.forEachIndexed {i,s->rows.put(org.json.JSONObject().put("name",s.name).put("x",positions[i].first).put("y",positions[i].second).put("width",s.width).put("height",s.height))}
        val packet=Protocol.workspace(org.json.JSONObject().put("type","command").put("action",5).put("requestId",id).put("positions",rows).toString())
        submitDisplayRequest(id,packet,false)
    }

    private fun submitDisplayRequest(id:Int,packet:ByteArray,readOnly:Boolean) {
        val handler=outgoing ?: return
        handler.post { if(monitorPending.get()==id) send(packet) }
        for(delay in listOf(300L,900L,1800L,3000L,5000L,8000L,12000L,18000L)) handler.postDelayed({ if(monitorPending.get()==id) send(packet) },delay)
        main.postDelayed({ if(monitorPending.get()==id) {
            displayState.value=displayState.value.copy(ok=true,message=if(readOnly) "Checking screen state…" else "Applying screen changes…")
        } },3000)
        main.postDelayed({ if(monitorPending.compareAndSet(id,0)) {
            // An uncertain mutation is never reissued under a new ID. Reconcile
            // with a read-only query automatically when the host responds again.
            displayState.value=displayState.value.copy(ok=true,message="Waiting for the host; checking screen state…")
            requestDisplays()
        } },25000)

    }

    private val windowCapturesLazy=lazy{WindowCapturePool(this)}
    val windowCaptures by windowCapturesLazy
    fun captureWindow(id:Long,video:dev.floatdesk.client.media.VideoCompositor,failed:(String)->Unit,frame:(Long,org.json.JSONObject)->Unit):WindowVideo?{
        val remote=pendingRemote ?: return null
        return WindowVideo(java.net.InetSocketAddress(remote.address,remote.port+2),detailSession,id,detailTargetFps,video,failed,video.input,frame,false)
    }
    fun windowRenderer()=decoders[0]?.windowRenderer()
    fun hasWindowDetail()=decoders[0]?.windowRenderer()?.hasWindowDetail()==true
    fun openWindowVideo(id:Long,video:dev.floatdesk.client.media.VideoCompositor,failed:(String)->Unit,output:WindowDestination?=null):WindowVideo?{
        val remote=pendingRemote ?: return null
        return WindowVideo(java.net.InetSocketAddress(remote.address,remote.port+2),detailSession,id,detailTargetFps,video,failed,output?.surface ?: video.input,if(output==null)null else {pts,meta->
            val bounds=workspaceInfo.value?.bounds()
            val win=workspaceInfo.value?.windows?.firstOrNull{it.id==id}
            if(bounds!=null && win!=null){
                val x=meta.optInt("sourceX",win.x);val y=meta.optInt("sourceY",win.y)
                val w=meta.optInt("sourceWidth",win.width);val h=meta.optInt("sourceHeight",win.height)
                output.frame(pts,FocusRect((x-bounds[0]).toFloat()/bounds[2],(y-bounds[1]).toFloat()/bounds[3],(x+w-bounds[0]).toFloat()/bounds[2],(y+h-bounds[1]).toFloat()/bounds[3]))
            }
        },output!=null)
    }
    @Volatile private var lastTypingAt=0L
    private var lastTypingPoll=0L
    @Volatile private var lastTypingKey:String?=null
    @Volatile var typingWindow:Long?=null
    var typingQuietMs=1000L
    fun typingLocked(now:Long=android.os.SystemClock.uptimeMillis()):Boolean=lastTypingAt>0 && now-lastTypingAt<typingQuietMs
    fun typingLabel(now:Long):String?=lastTypingKey?.takeIf{lastTypingAt>0 && now-lastTypingAt<1200}
    fun pollTyping(now:Long){if(now-lastTypingPoll>=150){lastTypingPoll=now;send(Protocol.workspace(org.json.JSONObject().put("type","typingState").toString()))}}
    fun cancelGazeFocus(){gazeFocusPending.set(0)}
    fun requestGazeFocus(window:Long){
        if (lastAcceptedHello == 0L) return
        val handler=outgoing ?: return
        val id=monitorSequence.incrementAndGet();gazeFocusPending.set(id);gazeFocusError.value=""
        val packet=Protocol.workspace(org.json.JSONObject().put("type","gazeFocus").put("quietMs",typingQuietMs).put("requestId",id).put("windowId",window).toString())
        for(delay in listOf(0L,300L,900L))handler.postDelayed({if(gazeFocusPending.get()==id)send(packet)},delay)
        main.postDelayed({if(gazeFocusPending.compareAndSet(id,0))gazeFocusError.value="Gaze focus request timed out"},2000)
    }
    @Volatile var cursorLocation:dev.floatdesk.client.CursorLocation?=null
    @Volatile var layoutMapDebug=floatArrayOf()
    @Volatile var cursorTopology=dev.floatdesk.client.CursorTopology()
    private val cursorMapSequence=java.util.concurrent.atomic.AtomicLong()
    @Volatile var cursorMapDebug=floatArrayOf()
    @Volatile var cursorWires=emptyMap<String,FloatArray>()
    private var lastCursorMap:dev.floatdesk.client.CursorTopology?=null
    private var lastCursorMapJson=""
    private var cursorMapSentAt=0L
    private var cursorMapSentEpoch=-1
    fun publishCursorMap(map:dev.floatdesk.client.CursorTopology){
        val now=android.os.SystemClock.uptimeMillis()
        if(lastCursorMap==map){
            if(cursorMapSentEpoch!=inputEpoch.get() || now-cursorMapSentAt>=400){
                cursorMapSentEpoch=inputEpoch.get()
                cursorMapSentAt=now
                sendCursorMap(lastCursorMapJson)
            }
            return
        }
        var actual=map
        var json=actual.json()
        while(json.toByteArray(Charsets.UTF_8).size>60000){
            val next=actual.coarsened()
            actual=if(next.patches.size<actual.patches.size)next else dev.floatdesk.client.CursorTopology()
            json=actual.json()
        }
        if(actual.patches.isEmpty())cursorLocation=null
        cursorTopology=actual
        cursorMapDebug=actual.debugSegments()
        val bounds=workspaceInfo.value?.bounds()
        cursorWires=actual.wires().groupBy{it.back}.mapValues{(key,rows)->
            val pieces=actual.patches.filter{it.key==key}
            val l=pieces.minOf{it.source.left};val r=pieces.maxOf{it.source.right}
            val t=pieces.minOf{it.source.top};val b=pieces.maxOf{it.source.bottom}
            fun point(p:Pair<Float,Float>):Pair<Float,Float>{
                if(key=="desktop" && bounds!=null)return presentation().extendedDisplayPoint((p.first-bounds[0])/bounds[2],(p.second-bounds[1])/bounds[3]) ?: (0f to 0f)
                return (p.first-l)/(r-l) to (p.second-t)/(b-t)
            }
            rows.flatMap{val a=point(it.a);val b=point(it.b);listOf(a.first,a.second,b.first,b.second)}.toFloatArray()
        }
        lastCursorMap=map;lastCursorMapJson=json;cursorMapSentAt=now;cursorMapSentEpoch=inputEpoch.get()
        sendCursorMap(json)
    }
    private fun sendCursorMap(json:String)=send(Protocol.workspace(org.json.JSONObject(json)
        .put("sequence",cursorMapSequence.incrementAndGet()).toString()))
    private val windowRequests=java.util.concurrent.ConcurrentHashMap<Int,ByteArray>()
    fun windowAction(window:Long,action:String,move:dev.floatdesk.client.WindowMove?=null){
        if (lastAcceptedHello == 0L) return
        val json=org.json.JSONObject().put("type","windowAction").put("requestId",monitorSequence.incrementAndGet()).put("windowId",window).put("action",action)
        move?.let{json.put("x",it.x).put("y",it.y);it.width?.let{w->json.put("width",w)};it.height?.let{h->json.put("height",h)}}
        val id=json.getInt("requestId");val packet=Protocol.workspace(json.toString())
        send(packet)
        run {
            // Moves are release-only transactions now, so they need the same ACK/retry as other actions.
            windowRequests[id]=packet
            for(delay in listOf(300L,900L))outgoing?.postDelayed({if(windowRequests[id]===packet)send(packet)},delay)
            main.postDelayed({windowRequests.remove(id)},2000)
        }
    }
    fun requestFocus(target:FocusTarget):Boolean {
        if (lastAcceptedHello == 0L) return false
        val handler=outgoing ?: return false
        if(!focusPending.compareAndSet(0,-1))return false
        val id=monitorSequence.incrementAndGet();focusPending.set(id);focusBusy.value=true
        val json=org.json.JSONObject().put("type","focus").put("requestId",id)
        if(target.windowId!=null)json.put("windowId",target.windowId) else json.put("screen",target.screen)
        json.put("contactU",target.contactU).put("contactV",target.contactV)
        val packet=Protocol.workspace(json.toString())
        for(delay in listOf(0L,300L,900L,1800L))handler.postDelayed({if(focusPending.get()==id)send(packet)},delay)
        main.postDelayed({if(focusPending.compareAndSet(id,0)) {
            focusBusy.value=false;focusResult.value=FocusResult(id,false,null,"Focus request timed out")
        }},3500)
        return true
    }

    private fun monitorCommand(id:Int,action:Int,index:Int,width:Int,height:Int):ByteArray {
        val info=workspaceInfo.value ?: return Protocol.monitorCommand(id,action,index,width,height)
        val name=info.screens.getOrNull(index)?.name ?: ""
        return Protocol.workspace(org.json.JSONObject().put("type","command").put("requestId",id).put("action",action).put("name",name).put("width",width).put("height",height).toString())
    }

    @Synchronized
    fun attachSurface(index: Int, surface: Surface) {
        activeSurfaces[index]=surface
        decoders[index]?.attach(surface)
        send(Protocol.keyframeRequest(index))
    }

    @Synchronized
    fun configurePresentation(spacing:Float) {
        screenSpacing=spacing
        val state=displayState.value
        val value=if(currentMonitors.size>1) ScreenPresentation() else workspaceInfo.value?.let {info->ScreenPresentation.from(info.screens.map {it.width to it.height},info.screens.map {it.x to it.y},spacing)} ?: ScreenPresentation.from(state.screens,state.positions,spacing)
        screenPresentation.set(value);decoders.values.forEach {it.presentation(value)}
    }
    fun presentation():ScreenPresentation=screenPresentation.get()
    fun desktopPoint(monitor:Int,u:Float,v:Float):Pair<Float,Float>? =
        if(monitors.value.size==1 && monitor==0) screenPresentation.get().desktopPoint(u,v) else u to v

    @Synchronized
    fun projection(index:Int,value:ViewProjection?) {viewProjections[index]=value;decoders[index]?.projection(value)}
    private var nextNativeMeshUpdate=0L
    /** Mesh replacements share one SceneCore command queue, even across different video decoders. */
    fun tryNativeMeshUpdate(submit:()->Boolean):Boolean {
        val now=android.os.SystemClock.uptimeMillis()
        if(now<nextNativeMeshUpdate || thermalPaused.value)return false
        if(!submit())return false
        nextNativeMeshUpdate=now+if(limitOptionalRendering)64 else 32
        return true
    }

    fun tryDesktopShapeUpdate(index:Int,update:()->Unit):Boolean {
        val compositor=decoders[index]?.windowRenderer()
        return tryNativeMeshUpdate {
            if(compositor!=null)compositor.tryShapeUpdate(update) else {update();true}
        }
    }
    fun aimOverlay(index: Int,value: VideoAim) { decoders[index]?.overlay(value) }

    @Synchronized
    fun detachSurface(index: Int,expected:Surface?=null) {
        if(expected==null) activeSurfaces.remove(index) else if(!activeSurfaces.remove(index,expected)) return
        decoders[index]?.detach()
    }

    private fun invalidateHostState() {
        inputEpoch.incrementAndGet()
        windowRequests.clear()
        pendingPointer.set(null)
        pendingPad.set(null)
        cursorLocation = null
        pendingCursors.clear()
        assembler = CursorAssembler()
        lastTypingAt = 0L
        typingWindow = null
        lastTypingKey=null
        monitorPending.set(0)
        focusPending.set(0)
        gazeFocusPending.set(0)
        val epoch = inputEpoch.get()
        main.post {
            if (inputEpoch.get() != epoch) return@post
            pointerDragging = false
            padFingers = 0
            cursors.value = emptyMap()
            displayBusy.value = false
            focusBusy.value = false
            focusResult.value = null
        }
    }

    private fun controlLoop() {
        var socket = control ?: return
        socket.soTimeout = 200
        val buffer = ByteArray(65535)
        val incoming = DatagramPacket(buffer, buffer.size)
        val broadcast = InetSocketAddress("255.255.255.255", 47000)
        var lastHello = 0L
        var lastReplyAt=android.os.SystemClock.elapsedRealtime()
        val replyTimeoutMs=10_000L
        fun reopenControl(reason:String):Boolean {
            if(!running)return false
            val next=try{DatagramSocket().apply{this.broadcast=true;soTimeout=200}}catch(error:Exception){
                Log.e("Floatdesk","Control socket reopen failed",error);return false
            }
            val previous=socket
            socket=next;control=next;previous.close()
            // Host mutation deduplication includes the source endpoint. Never
            // resend an uncertain mutation after changing our ephemeral port.
            lastAcceptedHello = 0L
            invalidateHostState()
            lastHello=0L;lastReplyAt=android.os.SystemClock.elapsedRealtime()
            Log.w("Floatdesk","Control socket reopened: $reason");events.append("control socket reopened: $reason")
            return true
        }
        while (running) {
            try {
            pollBeacon()
            val discoveryNow=android.os.SystemClock.elapsedRealtime()
            if(discoveryNow-lastProblemCheck>=1000) {
                lastProblemCheck=discoveryNow
                reportProblem(connectionProblem(discoveryNow,startedAt,lastHostReplyAt,lastBeaconAt,headsetNetwork(appContext)))
            }
            if(pendingRemote!=null && discoveryNow-lastReplyAt>=replyTimeoutMs) {
                if(!reopenControl("host replies timed out"))Thread.sleep(250)
            }
            val selected=hostDiscovery.select(pendingRemote,lastAcceptedHello!=0L && discoveryNow-lastAcceptedHello<5000,discoveryNow)
            if(selected!=null && selected!=pendingRemote) {
                Log.i("Floatdesk","Retrying host interface $pendingRemote -> $selected")
                lastAcceptedHello = 0L
                invalidateHostState()
                pendingRemote=selected
                lastHello=0L
            }
            val now = discoveryNow
            if (pendingRemote == null && now - lastHello > 1000) {
                val hello = Protocol.clientHello("floatdesk", 48000)
                runCatching { socket.send(DatagramPacket(hello, hello.size, broadcast)) }
                lastHello = now
            } else if (pendingRemote != null && now - lastHello > 1000) {
                send(Protocol.clientHello("floatdesk", 48000))
                lastHello = now
            }
                incoming.setLength(buffer.size)
                socket.receive(incoming)
                val decoded = Protocol.decode(buffer.copyOf(incoming.length)) ?: continue
                if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port){lastReplyAt=android.os.SystemClock.elapsedRealtime();lastHostReplyAt=lastReplyAt}
                when (decoded) {
                    is Packet.HostHello -> {
                        if(pendingRemote!=null && (incoming.address!=pendingRemote?.address || incoming.port!=pendingRemote?.port)) continue
                        lastHostReplyAt=android.os.SystemClock.elapsedRealtime()
                        if (pendingRemote == null) {
                            pendingRemote = InetSocketAddress(incoming.address, incoming.port)
                        }
                        val recovered = lastAcceptedHello == 0L || detailSession != decoded.sessionId ||
                            android.os.SystemClock.elapsedRealtime()-lastAcceptedHello>3500L
                        lastAcceptedHello=android.os.SystemClock.elapsedRealtime()
                        onHello(decoded, InetSocketAddress(incoming.address, incoming.port), recovered)
                    }
                    is Packet.StreamState -> {if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port) main.post {reloading.value=decoded.reloading || videoPending.get()}}
                    is Packet.MonitorStatus -> {
                        if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port) main.post {
                            if((decoded.requestId==0 && monitorPending.get()==0) || (decoded.requestId!=0 && monitorPending.compareAndSet(decoded.requestId,0))) {displayBusy.value=false;displayState.value=decoded;configurePresentation(screenSpacing)}
                        }
                    }
                    is Packet.Workspace -> {
                        if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port) {
                            val reply = runCatching { org.json.JSONObject(decoded.json) }.getOrNull()
                            reply?.takeIf{it.optString("type")=="windowActionResult"}?.let{windowRequests.remove(it.optInt("requestId"))}
                            reply?.takeIf{it.optString("type")=="cursorOwner"}?.let{reply->
                                cursorLocation=reply.optString("key").takeIf{it.isNotEmpty()}?.let{dev.floatdesk.client.CursorLocation(it,reply.optDouble("u").toFloat(),reply.optDouble("v").toFloat(),reply.optDouble("x").toFloat(),reply.optDouble("y").toFloat(),reply.optDouble("sourceX",reply.optDouble("x")).toFloat(),reply.optDouble("sourceY",reply.optDouble("y")).toFloat())}
                            }
                            reply?.takeIf{it.optString("type")=="typingState"}?.let{reply->
                                typingWindow=reply.optLong("windowId").takeIf{it>0L}
                                lastTypingKey=reply.optString("keyLabel",reply.optString("shortcut","")).takeIf{it.isNotBlank() && it!="null"}
                                if(reply.optBoolean("available"))lastTypingAt=(android.os.SystemClock.uptimeMillis()-reply.optLong("ageMs",60000)).coerceAtLeast(1)
                            }
                            reply?.takeIf{it.optString("type")=="gazeFocusResult"}?.let{reply->
                                if(gazeFocusPending.compareAndSet(reply.optInt("requestId"),0))main.post{gazeFocusError.value=if(reply.optBoolean("ok"))"" else reply.optString("message","Could not focus window")}
                            }
                            reply?.takeIf{it.optString("type")=="focusResult"}?.let {reply->
                                val id=reply.optInt("requestId")
                                if(id>0 && focusPending.compareAndSet(id,0))main.post {
                                    val a=reply.optJSONArray("rect")
                                    val rect=if(a!=null && a.length()==4)FocusRect(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat()).takeIf{it.left>=0 && it.top>=0 && it.right<=1 && it.bottom<=1 && it.right>it.left && it.bottom>it.top} else null
                                    focusBusy.value=false;focusResult.value=FocusResult(id,reply.optBoolean("ok") && rect!=null,rect,reply.optString("message"))
                                }
                            }
                            reply?.takeIf{it.optString("type")=="resourceStats"}?.let{stats->
                                fun n(key:String)=stats.optDouble(key,Double.NaN).takeIf{it.isFinite()}
                                val gpu=n("gpuPct")?.let{String.format(java.util.Locale.ROOT,"%.0f%%",it)} ?: "n/a"
                                val stages=if(n("captureMs")!=null && n("encodeMs")!=null)String.format(java.util.Locale.ROOT,"cap %.1fms enc %.1fms",n("captureMs"),n("encodeMs")) else "view timing n/a"
                                val text=String.format(java.util.Locale.ROOT,"Host CPU(core) %.0f%% RSS %.0fMiB free %.0fMiB GPU(proc) %s\nHost video %.1ffps %.1fMbps · %s",n("cpuPct") ?: 0.0,n("rssMiB") ?: 0.0,n("availableMiB") ?: 0.0,gpu,n("fps") ?: 0.0,n("mbps") ?: 0.0,stages)
                                val value=detailDiagnostics.updateAndGet{it.copy(hostResources=text)};decoders[0]?.detailDebug(value);publishResources()
                            }
                            WorkspaceInfo.parse(decoded.json)?.let {info->main.post {workspaceInfo.value=info;configurePresentation(screenSpacing)}}
                        }
                    }
                    is Packet.Cursor -> if(incoming.address==pendingRemote?.address && incoming.port==pendingRemote?.port) onCursor(decoded.fragment)
                    is Packet.Beacon -> Unit
                    is Packet.Ignored -> Unit
                }
            } catch (_: SocketTimeoutException) {
            } catch (error:java.net.SocketException) {
                if(!running)break
                Log.w("Floatdesk","Control socket failed",error);events.append("control socket failed: $error")
                if(!reopenControl("socket failure"))Thread.sleep(250)
            } catch (error: Exception) {
                // A malformed/unexpected reply must not permanently stop heartbeats and telemetry.
                if(!running)break
                Log.w("Floatdesk","Control packet rejected; keeping connection alive",error)
            }
        }
    }

    private fun reportProblem(next: String?) {
        val previous = lastProblem
        lastProblem = next
        if (next == previous) return
        if ((next == null) != (previous == null)) {
            val message = next?.let { "connection problem: $it" } ?: "connection recovered"
            if (next == null) Log.i("Floatdesk", message) else Log.w("Floatdesk", message)
            events.append(message)
        }
        main.post { if (running) problem.value = next }
    }

    private fun pollBeacon() {
        val socket = beacon ?: return
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            packet.setLength(buffer.size)
            socket.receive(packet)
        } catch (_: Exception) {
            return
        }
        val decoded = Protocol.decode(buffer.copyOf(packet.length)) ?: return
        if (decoded is Packet.Beacon) {
            lastBeaconAt = android.os.SystemClock.elapsedRealtime()
            val remote = InetSocketAddress(packet.address, decoded.listenPort)
            hostDiscovery.observe(remote,decoded.name,android.os.SystemClock.elapsedRealtime())
            if(pendingRemote!=null)return
            pendingRemote = remote
            val label = decoded.name.ifEmpty { packet.address.hostAddress ?: "computer" }
            main.post { status.value = "Found $label" }
        }
    }

    @Synchronized
    private fun onHello(hello: Packet.HostHello, remote: InetSocketAddress, recovered: Boolean) {
        sendRenderingState()
        sendEncoderSettings()
        if (recovered) {
            invalidateHostState()
            viewedDetail(null)
            detailSession = hello.sessionId
            decoders.values.forEach { it.punch(remote) }
            recoverRenderingOutputs()
            main.post {
                status.value = "Connected to ${remote.hostString}:${remote.port}, ${hello.monitors.size} monitor(s)"
                requestDisplays()
            }
        }
        val healthy=!rebuildStalledDecoders.getAndSet(false) && decoders.size==hello.monitors.size && hello.monitors.all{decoders[it.index]?.isHealthy()==true}
        if (hello.monitors==currentMonitors && healthy) {
            // A dropped one-shot progress notification must not leave an overlay
            // behind after the host has returned to its normal heartbeat.
            if(!videoPending.get() && monitorPending.get()==0) main.post {reloading.value=false}
            return
        }
        Log.i("Floatdesk","Host handshake from $remote: ${hello.monitors.size} video stream(s)")
        main.post {reloading.value=hello.monitors.isNotEmpty()}
        val sameFormat=hello.monitors.map {it.copy(ssrc=0)}==currentMonitors.map {it.copy(ssrc=0)} && healthy
        if(sameFormat) {
            currentMonitors=hello.monitors;videoPending.set(true)
            main.post {monitors.value=hello.monitors}
            send(Protocol.keyframeRequest(0xff))
            return
        }
        val generation=decoderGeneration.incrementAndGet()
        decoders.values.forEach { it.stop() };decoders.clear()
        videoPending.set(hello.monitors.isNotEmpty())
        videos=videos.mapIndexed { index,socket -> if(socket.isClosed) DatagramSocket(48000+index).apply {receiveBufferSize=8*1024*1024} else socket }
        currentMonitors=hello.monitors
        for (monitor in hello.monitors) {
            val video = videos.getOrNull(monitor.index) ?: continue
            val decoder = MonitorDecoder(video, monitor.width, monitor.height, dumpFile, ::askKeyframe,onFrame={
                if(decoderGeneration.get()==generation && videoPending.compareAndSet(true,false)) main.post {reloading.value=false}
            },onNack={source,sequence->send(Protocol.videoNack(monitor.index,source,sequence))})
            decoder.sharpening(videoSharpen)
            decoder.presentation(if(hello.monitors.size>1) ScreenPresentation() else screenPresentation.get())
            decoder.projection(viewProjections[monitor.index])
            activeSurfaces[monitor.index]?.let { decoder.attach(it) }
            decoder.punch(remote)
            decoder.setRenderingActive(isVideoActive() && desktopVisible)
            decoder.start()
            decoders[monitor.index] = decoder
        }
        send(Protocol.keyframeRequest(0xff))
        main.post {
            monitors.value = hello.monitors
            if(monitorPending.get()==0) requestDisplays()
            status.value = "Connected to ${remote.hostString}:${remote.port}, ${hello.monitors.size} monitor(s)"
        }
    }

    private fun onCursor(fragment: CursorFragment) {
        val view = assembler.push(fragment)
        val previous = pendingCursors[fragment.monitor] ?: cursors.value[fragment.monitor]
        val merged = if (view.pixels == null && previous?.pixels != null) {
            view.copy(pixels = previous.pixels)
        } else {
            view
        }
        pendingCursors[fragment.monitor] = merged
        cursorSeq.incrementAndGet()
        if (cursorFlushPosted.compareAndSet(false, true)) {
            main.post { flushCursors() }
        }
    }

    private fun flushCursors() {
        val seen = cursorSeq.get()
        cursors.value = HashMap(pendingCursors)
        if (cursorSeq.get() != seen) {
            main.post { flushCursors() }
        } else {
            cursorFlushPosted.set(false)
            if (cursorSeq.get() != seen && cursorFlushPosted.compareAndSet(false, true)) {
                main.post { flushCursors() }
            }
        }
    }

    private fun askKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastGapAsk < 1000) return
        lastGapAsk = now
        send(Protocol.keyframeRequest(0xff))
    }

    fun movePointer(monitor: Int,x: Float,y: Float,dragging: Boolean = false,spaceKey:String?=null) {
        if (lastAcceptedHello == 0L || monitors.value.none { it.index == monitor }) return
        val sequence=pointerSeq.incrementAndGet()
        val packet=if(spaceKey==null)Protocol.pointerMove(monitor,x,y,sequence,dragging) else
            Protocol.workspace(org.json.JSONObject().put("type","handPointer").put("monitor",monitor)
                .put("u",x).put("v",y).put("sequence",sequence).put("dragging",dragging).put("key",spaceKey).toString())
        if(dragging != pointerDragging) {
            pointerDragging=dragging
            pendingPointer.set(null)
            // Button edges must not disappear into a newer coalesced motion frame.
            val epoch = inputEpoch.get()
            outgoing?.post { if (inputEpoch.get() == epoch) send(packet) }
        } else {
            pendingPointer.set(packet)
            schedulePointerFlush()
        }
    }

    fun clickPointer(monitor: Int,x: Float,y: Float,button: Int) {
        if (lastAcceptedHello == 0L || monitors.value.none { it.index == monitor }) return
        val sequence=clickSeq.incrementAndGet()
        val epoch = inputEpoch.get()
        val requestedAt=System.nanoTime()/1_000_000
        val packet=Protocol.pointerClick(monitor,x,y,button,sequence)
        val handler=outgoing ?: return
        // Atomic click commands are deduplicated by the host. Retries cannot become extra clicks.
        handler.post {
            if (inputEpoch.get() != epoch) return@post
            if(button==3) Log.i("Floatdesk","right click sent sequence=$sequence queueMs=${System.nanoTime()/1_000_000-requestedAt}")
            send(packet)
        }
        handler.postDelayed({ if (inputEpoch.get() == epoch) send(packet) },20)
        handler.postDelayed({ if (inputEpoch.get() == epoch) send(packet) },60)
    }

    fun touchpad(fingers: Int,x: Float,y: Float) {
        if (lastAcceptedHello == 0L) return
        val epoch = inputEpoch.get()
        val packet=Protocol.touchpad(fingers,x,y,padSeq.incrementAndGet())
        val handler=outgoing ?: return
        if(fingers!=padFingers) {
            padFingers=fingers; pendingPad.set(null)
            handler.post { if (inputEpoch.get() == epoch) send(packet) }
            if(fingers==0) {
                for (delay in listOf(20L, 60L)) handler.postDelayed({ if (inputEpoch.get() == epoch) send(packet) }, delay)
            }
        } else {
            pendingPad.set(packet)
            schedulePadFlush()
        }
    }

    private fun schedulePadFlush() {
        val handler=outgoing ?: return
        if(padFlushPosted.compareAndSet(false,true)) handler.post {
            if(running) pendingPad.getAndSet(null)?.let { send(it) }
            padFlushPosted.set(false)
            if(pendingPad.get()!=null) schedulePadFlush()
        }
    }

    private fun schedulePointerFlush() {
        val handler = outgoing ?: return
        if (pointerFlushPosted.compareAndSet(false,true)) handler.post { flushPointer() }
    }

    private fun flushPointer() {
        if(running) pendingPointer.getAndSet(null)?.let { send(it) }
        pointerFlushPosted.set(false)
        if (pendingPointer.get() != null) schedulePointerFlush()
    }

    private fun send(bytes: ByteArray) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val epoch = inputEpoch.get()
            outgoing?.post { if (inputEpoch.get() == epoch) send(bytes) }
            return
        }
        val socket = control ?: return
        val address = pendingRemote ?: return
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
            .onFailure { if (running) Log.w("Floatdesk","Control datagram send failed",it) }
    }
}
