package dev.floatdesk.client.net

import dev.floatdesk.client.FocusRect

/** Candidate coverage and transport facts; GL reports actual uploads/expiry separately. */
internal data class DetailDiagnostics @JvmOverloads constructor(val enabled:Boolean=false,val candidate:FocusRect?=null,
    val reason:String="Waiting for view",val transport:String="REQ 0 / RX 0 / late 0 / errors 0",
    val gainX:Float=0f,val gainY:Float=0f,val showOverlay:Boolean=true,val showResources:Boolean=false,val hostResources:String="Host: waiting for telemetry",val localResources:String="XR: measuring resources")

internal data class DiagnosticSnapshot @JvmOverloads constructor(val state:DetailDiagnostics,val frame:ViewFrame?,val presentation:String,val sampling:String="")
