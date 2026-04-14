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

    // Senders explicitly removed since last clear — prevents stale restore after app restart
    private val explicitlyRemoved = mutableSetOf<String>()

    fun addSender(hash: String) {
        synchronized(explicitlyRemoved) {
            explicitlyRemoved.remove(hash)
            _activeSenders.update { it + hash }
        }
    }

    fun removeSender(hash: String) {
        synchronized(explicitlyRemoved) {
            explicitlyRemoved.add(hash)
            _activeSenders.update { it - hash }
        }
    }

    fun isActive(hash: String): Flow<Boolean> = _activeSenders.map { it.contains(hash) }

    fun restoreFromSenders(senders: Set<String>) {
        synchronized(explicitlyRemoved) {
            val safeSet = senders - explicitlyRemoved
            _activeSenders.update { it + safeSet }
        }
    }

    fun clear() {
        synchronized(explicitlyRemoved) {
            explicitlyRemoved.clear()
            _activeSenders.value = emptySet()
        }
    }
}
