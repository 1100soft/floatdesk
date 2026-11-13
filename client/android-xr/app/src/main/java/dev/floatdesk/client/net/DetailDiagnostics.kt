package dev.floatdesk.client.net

import dev.floatdesk.client.FocusRect

/** Candidate coverage and transport facts; GL reports actual uploads/expiry separately. */
internal data class DetailDiagnostics(val enabled:Boolean=false,val candidate:FocusRect?=null,
    val reason:String="Waiting for view",val transport:String="TX 0 / RX 0 / late 0 / errors 0",
    val gainX:Float=0f,val gainY:Float=0f)
