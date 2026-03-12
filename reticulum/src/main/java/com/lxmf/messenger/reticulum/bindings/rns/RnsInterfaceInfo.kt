package com.lxmf.messenger.reticulum.bindings.rns

/**
 * Snapshot of a Reticulum network interface's state and traffic counters.
 */
data class RnsInterfaceInfo(
    val name: String,
    val online: Boolean,
    val type: String,
    val rxBytes: Long,
    val txBytes: Long,
)
