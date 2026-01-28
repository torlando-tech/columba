package com.lxmf.messenger.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SharedTextViewModel
    @Inject
    constructor() : ViewModel() {
        data class PendingSharedText(
            val text: String,
            val targetDestinationHash: String? = null,
        )

        private val _sharedText = MutableStateFlow<PendingSharedText?>(null)
        val sharedText: StateFlow<PendingSharedText?> = _sharedText

        fun setText(text: String) {
            _sharedText.value = PendingSharedText(text = text)
        }

        fun assignToDestination(destinationHash: String) {
            val current = _sharedText.value ?: return
            if (current.targetDestinationHash != null) return
            _sharedText.value = current.copy(targetDestinationHash = destinationHash)
        }

        fun consumeForDestination(destinationHash: String): String? {
            val current = _sharedText.value ?: return null
            if (current.targetDestinationHash != destinationHash) return null
            _sharedText.value = null
            return current.text
        }

        fun clear() {
            _sharedText.value = null
        }
    }
