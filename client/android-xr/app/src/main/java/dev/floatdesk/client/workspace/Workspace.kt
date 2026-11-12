package dev.floatdesk.client.workspace

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
)

internal data class PanelPlacement(
    val pose: Pose = Pose(floatArrayOf(0f, -0.55f, -1.0f)),
    val widthM: Float = 0.80f,
    val heightM: Float = 0.48f,
)

internal data class WorkspaceFile(
    val schemaVersion: Int = 1,
    val activeLayout: String = "Desk",
    var anchorUuid: String? = null,
    val layouts: MutableMap<String, MutableList<MonitorPlacement>> = mutableMapOf(),
    val control: PanelPlacement = PanelPlacement(),
    val sphericalWarp: Boolean = false,
    val eyeAiming: Boolean = false,
    val mouseGestures: Boolean = false,
    val screenSpacing:Float=0f,
)

internal class WorkspaceStore(private val file: File) {
    private val lock = Any()

    fun load(): WorkspaceFile = synchronized(lock) {
        if (!file.exists()) return WorkspaceFile()
        return runCatching { decode(JSONObject(file.readText())) }.getOrElse { WorkspaceFile() }
    }

    fun save(workspace: WorkspaceFile) = synchronized(lock) {
        file.parentFile?.mkdirs()
        file.writeText(encode(workspace).toString(2))
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
    updated[index] = placement
    val nextLayouts = layouts.toMutableMap()
    nextLayouts[activeLayout] = updated
    val next = copy(layouts = nextLayouts)
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
        .put("eye_aiming", workspace.eyeAiming)
        .put("mouse_gestures", workspace.mouseGestures)
        .put("screen_spacing",workspace.screenSpacing.toDouble())
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
        eyeAiming = json.optBoolean("eye_aiming", json.optBoolean("mouse_control", false)),
        mouseGestures = json.optBoolean("mouse_gestures", false),
        screenSpacing=json.optDouble("screen_spacing",0.0).toFloat().takeIf {it.isFinite()}?.coerceIn(0f,.15f)?.let { kotlin.math.round(it*1000)/1000f } ?: 0f,
    )
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
