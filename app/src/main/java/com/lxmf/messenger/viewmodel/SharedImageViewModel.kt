package com.lxmf.messenger.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SharedImageViewModel : ViewModel() {
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
}
