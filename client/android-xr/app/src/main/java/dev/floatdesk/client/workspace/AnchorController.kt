package dev.floatdesk.client.workspace

import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.runtime.AnchorPersistenceMode
import androidx.xr.runtime.Config
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import java.util.UUID

/**
 * Persists a single workspace anchor. Monitor panels stay children of that
 * anchor in the workspace file, so a later tracking update cannot shear the
 * row apart the way independent anchors can.
 */
internal class AnchorController(private val store: WorkspaceStore) {
    suspend fun ensure(session: Session): String? {
        val config = Config.Builder(session.config)
            .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
            .build()
        if (session.configure(config) !is SessionConfigureSuccess) return null
        val saved = store.load().anchorUuid
        if (saved != null) {
            val uuid = runCatching { UUID.fromString(saved) }.getOrNull()
            if (uuid != null && Anchor.load(session, uuid) is AnchorCreateSuccess) {
                return saved
            }
        }
        val pose = Pose(
            translation = Vector3(0f, 0f, -1.2f),
            rotation = Quaternion(0f, 0f, 0f, 1f),
        )
        val created = Anchor.create(session, pose)
        if (created !is AnchorCreateSuccess) return null
        val uuid = runCatching { created.anchor.persist() }.getOrNull() ?: return null
        val workspace = store.load()
        workspace.anchorUuid = uuid.toString()
        store.save(workspace)
        return uuid.toString()
    }
}
