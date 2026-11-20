package dev.floatdesk.client

/** Temporary conservative backend while native custom-mesh material faults are investigated. */
internal object RenderPolicy {
    const val CustomMeshes=false
    const val SphericalDesktop=true
    const val GrabGuides=true
    const val CursorTopology=true
    const val DynamicQuality=true
    const val WindowFps=30
    const val WindowDimension=2048
    const val WindowPixels=2_097_152L
}
