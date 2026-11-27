package dev.floatdesk.client

import android.annotation.SuppressLint
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel

/** Destroy native instances before releasing the model/materials they still reference. */
@Suppress("DEPRECATION")
@SuppressLint("RestrictedApi")
internal fun releaseOwnedGltf(model: GltfModel, entity: Entity?, tag: NativeTag = NativeTag("gltf")) {
    // Detaching only changes visibility/parentage. GC may reclaim the native instance
    // much later, after model.close() has destroyed its materials in SplitEngine.
    // The pinned SDK's explicit dispose clears its Cleaner reference too.
    if(entity?.isDisposed==true)return // Session teardown already owns the native model resources.
    entity?.let { NativeJournal.native(tag, "dispose", "target=gltf-entity") { it.dispose() } }
    NativeJournal.native(tag, "gltf-release") { model.close() }
}
