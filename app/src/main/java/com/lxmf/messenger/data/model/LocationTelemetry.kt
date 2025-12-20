package com.lxmf.messenger.data.model

import kotlinx.serialization.Serializable

/**
 * Location telemetry data sent/received over LXMF.
 *
 * This is serialized to JSON and sent as LXMF field 7.
 *
 * @property type Message type identifier (always "location_share")
 * @property lat Latitude in WGS84 decimal degrees
 * @property lng Longitude in WGS84 decimal degrees
 * @property acc Accuracy in meters
 * @property ts Timestamp when location was captured (millis since epoch)
 * @property expires When sharing ends (millis since epoch), null for indefinite
 * @property cease If true, recipient should delete sender's location (sharing stopped)
 * @property approxRadius Coarsening radius in meters (0 = precise, >0 = approximate)
 */
@Serializable
data class LocationTelemetry(
    val type: String = TYPE_LOCATION_SHARE,
    val lat: Double,
    val lng: Double,
    val acc: Float,
    val ts: Long,
    val expires: Long? = null,
    val cease: Boolean = false,
    val approxRadius: Int = 0,
) {
    companion object {
        const val TYPE_LOCATION_SHARE = "location_share"
        const val LXMF_FIELD_ID = 7
    }
}
