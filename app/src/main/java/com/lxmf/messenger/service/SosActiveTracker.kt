package com.lxmf.messenger.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Tracks which contacts currently have an active SOS (receiver side).
 * Shared between MessageCollector and UI ViewModels.
 */
object SosActiveTracker {
    private val _activeSenders = MutableStateFlow<Set<String>>(emptySet())
    val activeSenders: StateFlow<Set<String>> = _activeSenders.asStateFlow()

    fun addSender(hash: String) {
        _activeSenders.update { it + hash }
    }

    fun removeSender(hash: String) {
        _activeSenders.update { it - hash }
    }

    fun isActive(hash: String): Flow<Boolean> = _activeSenders.map { it.contains(hash) }

    fun restoreFromSenders(senders: Set<String>) {
        _activeSenders.update { it + senders }
    }
}
