package dev.floatdesk.client

import android.annotation.SuppressLint
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel

/** Destroy native instances before releasing the model/materials they still reference. */
@Suppress("DEPRECATION")
@SuppressLint("RestrictedApi")
internal fun releaseOwnedGltf(model: GltfModel, entity: Entity?) {
    // Detaching only changes visibility/parentage. GC may reclaim the native instance
    // much later, after model.close() has destroyed its materials in SplitEngine.
    // The pinned SDK's explicit dispose clears its Cleaner reference too.
    entity?.dispose()
    model.close()
}
