package com.lxmf.messenger.ui.screens

/**
 * Check if a coordinate value is valid (non-null and non-zero).
 * Used for validating latitude/longitude from navigation arguments.
 */
internal fun isValidCoordinate(value: Double?) = value != null && value != 0.0

/**
 * Build a FocusInterfaceDetails from navigation arguments.
 * Returns null if coordinates are invalid.
 *
 * @param lat Latitude from navigation args
 * @param lon Longitude from navigation args
 * @param label Interface name/label
 * @param type Interface type
 * @param height Height (NaN or null treated as missing)
 * @param reachableOn Host address for TCP interfaces
 * @param port Port number (-1 treated as missing)
 * @param frequency Radio frequency (-1L treated as missing)
 * @param bandwidth Radio bandwidth (-1 treated as missing)
 * @param sf Spreading factor (-1 treated as missing)
 * @param cr Coding rate (-1 treated as missing)
 * @param modulation Modulation type (empty string treated as missing)
 * @param status Interface status (empty string treated as missing)
 * @param lastHeard Last heard timestamp (-1L treated as missing)
 * @param hops Hop count (-1 treated as missing)
 */
@Suppress("LongParameterList")
internal fun buildFocusInterfaceDetails(
    lat: Double?,
    lon: Double?,
    label: String?,
    type: String?,
    height: Double? = null,
    reachableOn: String? = null,
    port: Int? = null,
    frequency: Long? = null,
    bandwidth: Int? = null,
    sf: Int? = null,
    cr: Int? = null,
    modulation: String? = null,
    status: String? = null,
    lastHeard: Long? = null,
    hops: Int? = null,
): FocusInterfaceDetails? {
    if (!isValidCoordinate(lat) || !isValidCoordinate(lon)) return null

    return FocusInterfaceDetails(
        name = label ?: "Unknown",
        type = type?.ifEmpty { null } ?: "Unknown",
        latitude = lat!!,
        longitude = lon!!,
        height = if (height?.isNaN() == false) height else null,
        reachableOn = reachableOn?.ifEmpty { null },
        port = if (port != -1) port else null,
        frequency = if (frequency != -1L) frequency else null,
        bandwidth = if (bandwidth != -1) bandwidth else null,
        spreadingFactor = if (sf != -1) sf else null,
        codingRate = if (cr != -1) cr else null,
        modulation = modulation?.ifEmpty { null },
        status = status?.ifEmpty { null },
        lastHeard = if (lastHeard != -1L) lastHeard else null,
        hops = if (hops != -1) hops else null,
    )
}

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
