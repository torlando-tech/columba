package com.lxmf.messenger.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared signal for interface reconnection events.
 * MainActivity triggers this when USB is reattached, and the InterfaceStatsViewModel
 * observes it to show the connecting spinner.
 */
object InterfaceReconnectSignal {
    private val _reconnectTimestamp = MutableStateFlow(0L)
    val reconnectTimestamp: StateFlow<Long> = _reconnectTimestamp.asStateFlow()

    /**
     * Signal that a reconnection is starting.
     * Call this when USB device is attached and reconnect is about to happen.
     */
    fun triggerReconnect() {
        _reconnectTimestamp.value = System.currentTimeMillis()
    }
}
