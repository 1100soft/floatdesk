package dev.floatdesk.client

/** Shared native surface and capture limits. */
internal object RenderPolicy {
    const val CustomMeshes=true
    const val SphericalDesktop=true
    const val GrabGuides=true
    const val CursorTopology=true
    const val DynamicQuality=true
    const val WindowFps=30
    const val WindowDimension=2048
    const val WindowPixels=2_097_152L
}
