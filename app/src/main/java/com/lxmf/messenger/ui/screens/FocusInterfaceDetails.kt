package com.lxmf.messenger.ui.screens

/**
 * Data class for discovered interface details to display on map.
 */
data class FocusInterfaceDetails(
    val name: String,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val height: Double? = null,
    // TCP-specific
    val reachableOn: String? = null,
    val port: Int? = null,
    // Radio-specific (LoRa)
    val frequency: Long? = null,
    val bandwidth: Int? = null,
    val spreadingFactor: Int? = null,
    val codingRate: Int? = null,
    val modulation: String? = null,
    // Status
    val status: String? = null,
    val lastHeard: Long? = null,
    val hops: Int? = null,
)
