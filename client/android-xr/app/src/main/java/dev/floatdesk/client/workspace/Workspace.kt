package dev.floatdesk.client.workspace

import dev.floatdesk.client.PinchThresholds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * One persisted room anchor, with every monitor stored relative to it.
 * Moving the workspace changes only [WorkspaceFile.anchorUuid]'s pose in the
 * runtime. Moving a monitor changes that monitor's child pose.
 */
internal data class Pose(
    val position: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
)

internal data class MonitorPlacement(
    val logicalMonitorUuid: String,
    val hostSourceId: String,
    val pose: Pose,
    val physicalWidthM: Float,
    val physicalHeightM: Float,
    val curvature: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val preferredRefreshHz: Int,
    val viewCenterU:Float=.5f,
    val viewCenterV:Float=.5f,
    val freePose:Boolean=false,
)

internal data class PanelPlacement(
    val pose: Pose = Pose(floatArrayOf(0f, -0.55f, -1.0f)),
    val widthM: Float = 0.80f,
    val heightM: Float = 0.48f,
)

internal data class FixedMagnifierPlacement(val id:String,val pose:Pose,val widthM:Float,val heightM:Float,val freePose:Boolean=false)

internal enum class InteractionMode(val label:String){
    FOCUS("Focus"), WINDOW_GRAB("Window grab");
    fun next()=entries[(ordinal+1)%entries.size]
}

internal data class WorkspaceFile(
    val schemaVersion: Int = 1,
    val activeLayout: String = "Desk",
    var anchorUuid: String? = null,
    val layouts: MutableMap<String, MutableList<MonitorPlacement>> = mutableMapOf(),
    val control: PanelPlacement = PanelPlacement(),
    val sphericalWarp: Boolean = false,
    val handMouse: Boolean = false,
    val reverseHandScroll:Boolean=true,
    val aimToHover:Boolean=true,
    val grabToMove: Boolean = false,
    val freeManipulation: Boolean = false,
    val desktopReference: Boolean = false,
    val referencePlacement: FixedMagnifierPlacement? = null,
    val layoutOrigin:Pose?=null,
    val desktopHome:Pose?=null,
    val cursorMapReference:Pose?=null,
    val screenSpacing:Float=0f,
    val viewerReference:Pose?=null,
    val referenceUnitsPerMeter:Float?=null,
    val interactionMode:InteractionMode=InteractionMode.FOCUS,
    val cursorRemap:Boolean=true,
    val gazeToFocus:Boolean=false,
    val magnifyWindow:Boolean=false,
    val magnifySystemPanels:Boolean=false,
    val magnifierMode:String="floating",
    val fixedMagnifiers:List<FixedMagnifierPlacement> = emptyList(),
    val systemPanels:List<FixedMagnifierPlacement> = emptyList(),
    val typingQuietMs:Long=1000,
    val experimentalViewedDetail:Boolean=false,
    val viewDetailDebug:Boolean=true,
    val encoderQuality:Int=0,
    val encoderMbps:Float=0f,
    val sharpening:Float=0f,
    val denseFraction:Float=.35f, // Legacy transport compatibility only.
    val detailAreaPixels:Long=0, // Zero selects automatic allocation.
    val detailTargetFps:Int=30,
    val showResources:Boolean=true,
    val pinchThresholds:PinchThresholds=PinchThresholds.Default,
    val nearestDistanceM:Float=ViewLayers.DefaultNearestM,
    val farthestDistanceM:Float=ViewLayers.DefaultFarthestM,
    /** Loaded from a file laid out for the fixed 0.9–1.5 m layers; not persisted. */
    val legacyDistances:Boolean=false,
){
    val layoutModeActive get()=grabToMove || freeManipulation
    val handMouseActive get()=handMouse && !layoutModeActive
    val focusMode get()=!layoutModeActive && !handMouseActive && interactionMode==InteractionMode.FOCUS
    val windowGrabMode get()=!layoutModeActive && !handMouseActive && interactionMode==InteractionMode.WINDOW_GRAB
}

internal class WorkspaceStore(private val file: File) {
    private val lock = Any()
    @Volatile var viewerReference:Pose?=null
    @Volatile var viewerUnitsPerMeter:Float=1f

    fun load(): WorkspaceFile = synchronized(lock) {
        if (!file.exists()) return WorkspaceFile()
        return runCatching { decode(JSONObject(file.readText())) }.getOrElse { WorkspaceFile() }
    }

    private val historyFile = File(file.parentFile, "layout-history.json")
    private val history by lazy {
        runCatching { JSONObject(historyFile.readText()) }
            .getOrElse { JSONObject().put("entries", JSONArray()).put("index", -1) }
    }
    private val layoutKeys = listOf("active_layout", "layouts", "layout_origin", "desktop_home",
        "cursor_map_reference", "fixed_magnifiers", "system_panels", "reference_placement")

    private val savedLayoutsFile = File(file.parentFile, "saved-layouts.json")
    private val savedLayouts by lazy {
        runCatching { JSONObject(savedLayoutsFile.readText()) }.getOrElse { JSONObject() }
    }

    fun layoutPreviews(): List<FloatArray?> = synchronized(lock) {
        (0 until 3).map { slot ->
            savedLayouts.optJSONObject(slot.toString())?.optJSONArray("map")?.let { values ->
                FloatArray(values.length()) { values.optDouble(it).toFloat() }
            }
        }
    }

    fun saveLayoutSlot(slot: Int, workspace: WorkspaceFile, preview: FloatArray) = synchronized(lock) {
        if (workspace.layouts[workspace.activeLayout].isNullOrEmpty() || preview.isEmpty()) return
        savedLayouts.put(slot.toString(), JSONObject().put("layout", layoutSnapshot(encode(workspace)))
            .put("map", JSONArray(preview.toList())))
        write(savedLayoutsFile, savedLayouts)
    }

    fun restoreLayoutSlot(slot: Int, workspace: WorkspaceFile): WorkspaceFile? = synchronized(lock) {
        val saved = savedLayouts.optJSONObject(slot.toString())?.optJSONObject("layout") ?: return null
        val restored = applyLayout(workspace, saved)
        recordLayout(restored, "restore saved layout ${slot+1}")
        restored
    }

    private fun applyLayout(workspace: WorkspaceFile, snapshot: JSONObject): WorkspaceFile {
        val saved = decode(snapshot)
        return workspace.copy(activeLayout = saved.activeLayout, layouts = saved.layouts,
            layoutOrigin = saved.layoutOrigin, desktopHome = saved.desktopHome, cursorMapReference = saved.cursorMapReference,
            fixedMagnifiers = saved.fixedMagnifiers, systemPanels = saved.systemPanels, referencePlacement = saved.referencePlacement)
    }

    fun save(workspace: WorkspaceFile) = synchronized(lock) {
        write(file, encode(withViewer(workspace)))
    }

    fun recordLayout(workspace: WorkspaceFile, reason: String) = synchronized(lock) {
        val encoded = encode(withViewer(workspace))
        val snapshot = layoutSnapshot(encoded)
        val entries = history.getJSONArray("entries")
        val index = history.getInt("index")
        val current = entries.optJSONObject(index)?.getJSONObject("layout")
        if (current != null && layoutKeys.all { current.opt(it)?.toString() == snapshot.opt(it)?.toString() }) return
        val next = JSONArray()
        for (i in maxOf(0, index - 38)..index) next.put(entries.getJSONObject(i))
        next.put(JSONObject().put("time_ms", System.currentTimeMillis()).put("action", reason).put("layout", snapshot))
        updateHistory(next, next.length() - 1, reason)
        write(file, encoded)
    }

    fun hasLayoutHistory(): Boolean = synchronized(lock) {
        history.getJSONArray("entries").length() > 0
    }

    fun hasPlacementChange(workspace: WorkspaceFile): Boolean = synchronized(lock) {
        val current = history.getJSONArray("entries").optJSONObject(history.getInt("index"))
            ?.getJSONObject("layout") ?: return true
        val encoded = encode(workspace)
        layoutKeys.any { it != "cursor_map_reference" && current.opt(it)?.toString() != encoded.opt(it)?.toString() }
    }

    fun stepLayout(workspace: WorkspaceFile, delta: Int): WorkspaceFile? = synchronized(lock) {
        val entries = history.getJSONArray("entries")
        val index = history.getInt("index") + delta
        val entry = entries.optJSONObject(index) ?: return null
        val restored = applyLayout(workspace, entry.getJSONObject("layout"))
        write(file, encode(withViewer(restored)))
        updateHistory(entries, index, if (delta < 0) "undo" else "redo")
        restored
    }

    // Apply the same XR tracking-space recovery to every undo/redo entry.
    fun rebaseLayoutHistory(prior: Pose, priorUnits: Float, viewer: Pose, units: Float) = synchronized(lock) {
        fun rebase(entry: JSONObject) {
            val saved = decode(entry.getJSONObject("layout"))
            val restored = saved.copy(viewerReference = prior, referenceUnitsPerMeter = priorUnits)
                .restoreViewer(viewer, units)
            entry.put("layout", layoutSnapshot(encode(restored)))
        }
        if (historyFile.exists()) {
            val entries = history.getJSONArray("entries")
            for (i in 0 until entries.length()) rebase(entries.getJSONObject(i))
            write(historyFile, history)
        }
        if (savedLayoutsFile.exists()) {
            savedLayouts.keys().forEach { rebase(savedLayouts.getJSONObject(it)) }
            write(savedLayoutsFile, savedLayouts)
        }
    }

    private fun layoutSnapshot(encoded: JSONObject) = JSONObject().also { snapshot ->
        for (key in layoutKeys + listOf("anchor_uuid")) {
            encoded.opt(key)?.let { snapshot.put(key, it) }
        }
    }

    private fun updateHistory(entries: JSONArray, index: Int, reason: String) {
        history.put("entries", entries).put("index", index)
        write(historyFile, history)
        val snapshot = entries.getJSONObject(index).getJSONObject("layout")
        android.util.Log.i("Floatdesk", "layout action=$reason step=${index + 1}/${entries.length()} state=$snapshot")
    }

    private fun withViewer(workspace: WorkspaceFile) = workspace.copy(viewerReference=viewerReference ?: workspace.viewerReference,
        referenceUnitsPerMeter=if(viewerReference!=null)viewerUnitsPerMeter else workspace.referenceUnitsPerMeter)

    private fun write(destination: File, json: JSONObject) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeText(json.toString(2))
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

}

internal fun deskLayout(monitors: List<Triple<String, Int, Int>>): MutableList<MonitorPlacement> {
    val widthM = 0.60f
    val gapM = 0.04f
    val z = -1.20f
    val count = monitors.size.toFloat()
    val span = if (monitors.isEmpty()) 0f else count * widthM + (count - 1f) * gapM
    var x = -span / 2f + widthM / 2f
    return monitors.map { (id, pxW, pxH) ->
        val aspect = max(pxW.toFloat() / max(pxH, 1).toFloat(), 0.1f)
        val placement = MonitorPlacement(
            logicalMonitorUuid = "monitor-$id",
            hostSourceId = id,
            pose = Pose(floatArrayOf(x, 0f, z)),
            physicalWidthM = widthM,
            physicalHeightM = widthM / aspect,
            curvature = 0f,
            pixelWidth = pxW,
            pixelHeight = pxH,
            preferredRefreshHz = 60,
        )
        x += widthM + gapM
        placement
    }.toMutableList()
}

internal fun WorkspaceFile.monitorPlaced(
    index: Int,
    placement: MonitorPlacement,
    store: WorkspaceStore,
    persist: Boolean,
): WorkspaceFile {
    val current = layouts[activeLayout] ?: return this
    if (index !in current.indices) return this
    val updated = current.toMutableList()
    updated[index] = if(placement.freePose || grabToMove)placement else store.viewerReference?.let{fixedDesktopDepth(placement,it,store.viewerUnitsPerMeter,sphericalWarp)} ?: placement
    val nextLayouts = layouts.toMutableMap()
    nextLayouts[activeLayout] = updated
    val home=if(index==0 && layoutModeActive)updated[index].pose else desktopHome
    val next = copy(layouts = nextLayouts,desktopHome=home)
    if (persist) store.save(next)
    return next
}

internal fun WorkspaceFile.controlPlaced(
    panel: PanelPlacement,
    store: WorkspaceStore,
    persist: Boolean,
): WorkspaceFile {
    val next = copy(control = panel)
    if (persist) store.save(next)
    return next
}

private fun encode(workspace: WorkspaceFile): JSONObject {
    val layouts = JSONObject()
    for ((name, monitors) in workspace.layouts) {
        val array = JSONArray()
        for (monitor in monitors) array.put(encodeMonitor(monitor))
        layouts.put(name, array)
    }
    return JSONObject()
        .put("schema_version", workspace.schemaVersion)
        .put("active_layout", workspace.activeLayout)
        .put("anchor_uuid", workspace.anchorUuid)
        .put("layouts", layouts)
        .put("control", encodePanel(workspace.control))
        .put("spherical_warp", workspace.sphericalWarp)
        .put("reverse_hand_scroll",workspace.reverseHandScroll).put("aim_to_hover",workspace.aimToHover).put("hand_mouse", workspace.handMouse).put("desktop_reference",workspace.desktopReference)
        .put("interaction_mode",workspace.interactionMode.name).put("cursor_remap",workspace.cursorRemap).put("window_grab_mode",workspace.windowGrabMode).put("focus_mode",workspace.focusMode).put("gaze_to_focus",workspace.gazeToFocus).put("magnify_window",workspace.magnifyWindow).put("typing_quiet_ms",workspace.typingQuietMs)
        .put("magnifier_mode",workspace.magnifierMode).put("magnify_system_panels",workspace.magnifySystemPanels)
        .put("fixed_magnifiers",encodePlacements(workspace.fixedMagnifiers))
        .put("system_panels",encodePlacements(workspace.systemPanels))
        .put("reference_placement",encodePlacements(listOfNotNull(workspace.referencePlacement)))
        .put("cursor_map_reference",workspace.cursorMapReference?.let{JSONObject().put("position",JSONArray(it.position.toList())).put("rotation",JSONArray(it.rotation.toList()))})
        .put("desktop_home",workspace.desktopHome?.let{JSONObject().put("position",JSONArray(it.position.toList())).put("rotation",JSONArray(it.rotation.toList()))})
        .put("layout_origin",workspace.layoutOrigin?.let{JSONObject().put("position",JSONArray(it.position.toList())).put("rotation",JSONArray(it.rotation.toList()))})
        .put("experimental_viewed_detail",workspace.experimentalViewedDetail)
        .put("view_detail_debug",workspace.viewDetailDebug)
        .put("encoder_quality",workspace.encoderQuality).put("encoder_mbps",workspace.encoderMbps)
        .put("sharpening",workspace.sharpening).put("dense_fraction",workspace.denseFraction).put("detail_area_pixels",workspace.detailAreaPixels).put("detail_target_fps",workspace.detailTargetFps).put("show_resources",workspace.showResources)
        .put("pinch_thresholds_mm",JSONArray(listOf(workspace.pinchThresholds.leftIndexMm,workspace.pinchThresholds.rightIndexMm,workspace.pinchThresholds.leftMiddleMm,workspace.pinchThresholds.rightMiddleMm)))

        .put("screen_spacing",workspace.screenSpacing.toDouble())
        .put("nearest_distance_m",workspace.nearestDistanceM.toDouble()).put("farthest_distance_m",workspace.farthestDistanceM.toDouble())
        .put("viewer_units_per_meter",workspace.referenceUnitsPerMeter?.toDouble())
        .put("viewer_reference",workspace.viewerReference?.let {JSONObject().put("position",JSONArray(it.position.toList())).put("rotation",JSONArray(it.rotation.toList()))})
}

private fun encodeMonitor(monitor: MonitorPlacement): JSONObject {
    return JSONObject()
        .put("logical_monitor_uuid", monitor.logicalMonitorUuid)
        .put("host_source_id", monitor.hostSourceId)
        .put(
            "pose_in_workspace",
            JSONObject()
                .put("position", JSONArray(monitor.pose.position.toList()))
                .put("rotation", JSONArray(monitor.pose.rotation.toList())),
        )
        .put("physical_width_m", monitor.physicalWidthM.toDouble())
        .put("physical_height_m", monitor.physicalHeightM.toDouble())
        .put("curvature", monitor.curvature.toDouble())
        .put("pixel_width", monitor.pixelWidth)
        .put("pixel_height", monitor.pixelHeight)
        .put("preferred_refresh_hz", monitor.preferredRefreshHz)
        .put("view_center_u",monitor.viewCenterU.toDouble()).put("view_center_v",monitor.viewCenterV.toDouble()).put("free_pose",monitor.freePose)
}

private fun decode(json: JSONObject): WorkspaceFile {
    val layoutsJson = json.optJSONObject("layouts") ?: JSONObject()
    val layouts = mutableMapOf<String, MutableList<MonitorPlacement>>()
    for (name in layoutsJson.keys()) {
        val array = layoutsJson.optJSONArray(name) ?: continue
        val monitors = mutableListOf<MonitorPlacement>()
        for (i in 0 until array.length()) {
            monitors += decodeMonitor(array.getJSONObject(i))
        }
        layouts[name] = monitors
    }
    return WorkspaceFile(
        schemaVersion = json.optInt("schema_version", 1),
        activeLayout = json.optString("active_layout", "Desk"),
        anchorUuid = json.optString("anchor_uuid").ifEmpty { null },
        layouts = layouts,
        control = json.optJSONObject("control")?.let { decodePanel(it) } ?: PanelPlacement(),
        sphericalWarp = json.optBoolean("spherical_warp", false),
        interactionMode=runCatching{InteractionMode.valueOf(json.optString("interaction_mode",if(json.optBoolean("window_grab_mode",false))"WINDOW_GRAB" else if(json.optBoolean("focus_mode",false))"FOCUS" else "MOUSE"))}.getOrDefault(InteractionMode.FOCUS),
        cursorRemap=json.optBoolean("cursor_remap",true),
        gazeToFocus=json.optBoolean("gaze_to_focus",false),
        magnifyWindow=json.optBoolean("magnify_window",false),
        magnifySystemPanels=json.optBoolean("magnify_system_panels",false),
        magnifierMode=if(json.optString("magnifier_mode")=="fixed")"fixed" else "floating",
        fixedMagnifiers=decodePlacements(json.optJSONArray("fixed_magnifiers")),
        systemPanels=decodePlacements(json.optJSONArray("system_panels")),
        typingQuietMs=json.optLong("typing_quiet_ms",1000).coerceIn(0,10000),
        experimentalViewedDetail=json.optBoolean("experimental_viewed_detail",false),
        viewDetailDebug=json.optBoolean("view_detail_debug",true),
        encoderQuality=json.optInt("encoder_quality",0).coerceIn(0,2),
        encoderMbps=json.optDouble("encoder_mbps",0.0).toFloat().takeIf{it.isFinite()}?.coerceIn(0f,200f) ?: 0f,
        sharpening=json.optDouble("sharpening",0.0).toFloat().takeIf{it.isFinite()}?.coerceIn(0f,1.5f) ?: 0f,
        detailAreaPixels=json.optLong("detail_area_pixels",0).coerceIn(0,16_777_216),
        detailTargetFps=json.optInt("detail_target_fps",30).coerceIn(5,120),
        denseFraction=json.optDouble("dense_fraction",.35).toFloat().takeIf{it.isFinite()}?.coerceIn(.2f,1f) ?: .35f,
        showResources=json.optBoolean("show_resources",true),
        pinchThresholds=json.optJSONArray("pinch_thresholds_mm")?.takeIf{it.length()==4}?.let {a->
            PinchThresholds(a.optDouble(0,Double.NaN).toFloat(),a.optDouble(1,Double.NaN).toFloat(),a.optDouble(2,Double.NaN).toFloat(),a.optDouble(3,Double.NaN).toFloat()).takeIf{it.valid()}
        } ?: PinchThresholds.Default,
        reverseHandScroll=json.optBoolean("reverse_hand_scroll",true),
        aimToHover=json.optBoolean("aim_to_hover",true),
        handMouse = json.optBoolean("hand_mouse", json.optBoolean("mouse_gestures", false) && json.optString("interaction_mode", "MOUSE")=="MOUSE"),
        desktopReference = json.optBoolean("desktop_reference",false),
        referencePlacement = decodePlacements(json.optJSONArray("reference_placement")).firstOrNull(),
        cursorMapReference=json.optJSONObject("cursor_map_reference")?.let{Pose(floatArray(it,"position",3),floatArray(it,"rotation",4))},
        desktopHome=json.optJSONObject("desktop_home")?.let{Pose(floatArray(it,"position",3),floatArray(it,"rotation",4))},
        layoutOrigin = (json.optJSONObject("layout_origin") ?: json.optJSONObject("support_basis"))?.let{Pose(floatArray(it,"position",3),floatArray(it,"rotation",4))},
        referenceUnitsPerMeter=json.optDouble("viewer_units_per_meter",Double.NaN).toFloat().takeIf{it.isFinite() && it>0f},
        viewerReference=json.optJSONObject("viewer_reference")?.let {Pose(floatArray(it,"position",3),floatArray(it,"rotation",4))},
        screenSpacing=json.optDouble("screen_spacing",0.0).toFloat().takeIf {it.isFinite()}?.coerceIn(0f,.15f)?.let { kotlin.math.round(it*1000)/1000f } ?: 0f,
    ).let{decoded->
        val legacy=!json.has("farthest_distance_m") && layouts.values.any{it.isNotEmpty()}
        val nearest=ViewLayers.clampNearest(json.optDouble("nearest_distance_m",ViewLayers.DefaultNearestM.toDouble()).toFloat())
        val farthest=if(legacy)1.5f else json.optDouble("farthest_distance_m",ViewLayers.DefaultFarthestM.toDouble()).toFloat()
        decoded.copy(nearestDistanceM=nearest,farthestDistanceM=ViewLayers.clampFarthest(nearest,farthest),legacyDistances=legacy)
    }
}

private fun encodePanel(panel: PanelPlacement): JSONObject {
    return JSONObject()
        .put(
            "pose_in_workspace",
            JSONObject()
                .put("position", JSONArray(panel.pose.position.toList()))
                .put("rotation", JSONArray(panel.pose.rotation.toList())),
        )
        .put("physical_width_m", panel.widthM.toDouble())
        .put("physical_height_m", panel.heightM.toDouble())
}

private fun decodePanel(json: JSONObject): PanelPlacement {
    val pose = json.optJSONObject("pose_in_workspace") ?: JSONObject()
    return PanelPlacement(
        pose = Pose(floatArray(pose, "position", 3), floatArray(pose, "rotation", 4)),
        widthM = json.optDouble("physical_width_m", 0.8).toFloat(),
        heightM = json.optDouble("physical_height_m", 0.48).toFloat(),
    )
}

private fun decodeMonitor(json: JSONObject): MonitorPlacement {
    val pose = json.optJSONObject("pose_in_workspace") ?: JSONObject()
    return MonitorPlacement(
        logicalMonitorUuid = json.optString("logical_monitor_uuid"),
        hostSourceId = json.optString("host_source_id"),
        pose = Pose(floatArray(pose, "position", 3), floatArray(pose, "rotation", 4)),
        physicalWidthM = json.optDouble("physical_width_m", 0.6).toFloat(),
        physicalHeightM = json.optDouble("physical_height_m", 0.34).toFloat(),
        curvature = json.optDouble("curvature", 0.0).toFloat(),
        pixelWidth = json.optInt("pixel_width"),
        pixelHeight = json.optInt("pixel_height"),
        preferredRefreshHz = json.optInt("preferred_refresh_hz", 60),
        viewCenterU=json.optDouble("view_center_u",.5).toFloat().takeIf{it.isFinite()}?.coerceIn(0f,1f) ?: .5f,
        freePose=json.optBoolean("free_pose",false),
        viewCenterV=json.optDouble("view_center_v",.5).toFloat().takeIf{it.isFinite()}?.coerceIn(0f,1f) ?: .5f,
    )
}

private fun floatArray(json: JSONObject, name: String, size: Int): FloatArray {
    val array = json.optJSONArray(name)
    val out = FloatArray(size)
    if (array == null) {
        if (size == 4) out[3] = 1f
        return out
    }
    for (i in 0 until minOf(size, array.length())) out[i] = array.optDouble(i).toFloat()
    if (size == 4 && array.length() < 4) out[3] = 1f
    return out
}


private fun encodePlacements(rows:List<FixedMagnifierPlacement>)=JSONArray(rows.map{p->JSONObject().put("id",p.id).put("pose",JSONObject().put("position",JSONArray(p.pose.position.toList())).put("rotation",JSONArray(p.pose.rotation.toList()))).put("width_m",p.widthM).put("height_m",p.heightM).put("free_pose",p.freePose)})
private fun decodePlacements(array:JSONArray?):List<FixedMagnifierPlacement> = array?.let{a->(0 until a.length()).mapNotNull{i->runCatching{val p=a.getJSONObject(i);val pose=p.getJSONObject("pose");val w=p.getDouble("width_m").toFloat();val h=p.getDouble("height_m").toFloat();require(w.isFinite() && h.isFinite() && w>0f && h>0f);FixedMagnifierPlacement(p.getString("id"),Pose(floatArray(pose,"position",3),floatArray(pose,"rotation",4)),w,h,p.optBoolean("free_pose",false))}.getOrNull()}} ?: emptyList()

/** Reconcile stable source identities without resetting poses when a display is added or removed. */
internal fun WorkspaceFile.reconcileMonitors(sources:List<Triple<String,Int,Int>>): WorkspaceFile {
    val old=layouts[activeLayout].orEmpty()
    val defaults=deskLayout(sources)
    var right=old.maxOfOrNull { it.pose.position[0]+it.physicalWidthM*.5f } ?: -.34f
    val next=sources.mapIndexed { index,(id,w,h) ->
        val prior=old.firstOrNull { it.hostSourceId==id }
        if(prior!=null) prior.copy(pixelWidth=w,pixelHeight=h,physicalHeightM=prior.physicalWidthM*h/w.coerceAtLeast(1)) else {
            val fresh=defaults[index]
            val x=right+.04f+fresh.physicalWidthM*.5f;right=x+fresh.physicalWidthM*.5f
            fresh.copy(pose=Pose(floatArrayOf(x,0f,-1.2f)))
        }
    }.toMutableList()
    if(old==next) return this
    return copy(layouts=layouts.toMutableMap().also { it[activeLayout]=next })
}
