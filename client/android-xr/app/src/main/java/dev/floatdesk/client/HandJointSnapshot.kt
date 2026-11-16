package dev.floatdesk.client

import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.math.Pose

/** Only joints consumed by scene aiming, identity guards, palm controls and fist detection. */
internal val sceneHandJoints = listOf(
    HandJointType.PALM,
    HandJointType.WRIST,
    HandJointType.THUMB_TIP,
    HandJointType.INDEX_PROXIMAL,
    HandJointType.INDEX_TIP,
    HandJointType.MIDDLE_PROXIMAL,
    HandJointType.MIDDLE_TIP,
    HandJointType.RING_TIP,
    HandJointType.LITTLE_PROXIMAL,
    HandJointType.LITTLE_TIP,
)

internal fun snapshotHandJoints(state: Hand.State): Map<HandJointType, Pose> {
    if (state.trackingState != TrackingState.TRACKING) return emptyMap()
    // SDK Map.entries materializes all joints and hashes their Poses. Individual get()
    // reads only that joint from the runtime buffer, without enumerating the map.
    val joints = state.handJoints
    val snapshot = java.util.EnumMap<HandJointType, Pose>(HandJointType::class.java)
    for (joint in sceneHandJoints) joints[joint]?.let { snapshot[joint] = it }
    return snapshot
}
