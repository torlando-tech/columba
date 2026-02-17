package com.lxmf.messenger.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.lxmf.messenger.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SharedImageViewModel(
    application: Application,
) : AndroidViewModel(application) {
    data class PendingSharedImages(
        val uris: List<Uri>,
        val targetDestinationHash: String? = null,
    )

    private val _sharedImages = MutableStateFlow<PendingSharedImages?>(null)
    val sharedImages: StateFlow<PendingSharedImages?> = _sharedImages

    fun setImages(uris: List<Uri>) {
        _sharedImages.value = PendingSharedImages(uris = uris)
    }

    fun assignToDestination(destinationHash: String) {
        val current = _sharedImages.value ?: return
        _sharedImages.value = current.copy(targetDestinationHash = destinationHash)
    }

    fun consumeForDestination(destinationHash: String): List<Uri>? {
        val current = _sharedImages.value ?: return null
        if (current.targetDestinationHash != destinationHash) return null
        _sharedImages.value = null
        return current.uris
    }

    fun clearIfUnassigned() {
        val current = _sharedImages.value ?: return
        if (current.targetDestinationHash == null) {
            _sharedImages.value = null
        }
    }

    fun clear() {
        _sharedImages.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up any unconsumed temp files from incoming shares.
        // If the user backed out without picking a destination, the temp
        // copies would otherwise linger until cleanupAllTempFiles() runs.
        FileUtils.cleanupIncomingShares(getApplication())
    }
}
