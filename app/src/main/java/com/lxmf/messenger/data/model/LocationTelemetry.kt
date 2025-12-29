package com.lxmf.messenger.data.model

import kotlinx.serialization.Serializable

/**
 * Location telemetry data sent/received over LXMF.
 *
 * Location updates are sent as LXMF FIELD_TELEMETRY (0x02) using Sideband's
 * msgpack-packed Telemeter format for interoperability.
 *
 * Columba-specific signals (like cease) are sent via FIELD_COLUMBA_META (0x70).
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
        /** LXMF FIELD_TELEMETRY - Standard telemetry field for Sideband interoperability */
        const val LXMF_FIELD_ID = 0x02
        /** Custom field for Columba-specific metadata (cease signals, etc.) */
        const val COLUMBA_META_FIELD_ID = 0x70
        /** Legacy field ID for backwards compatibility with old Columba clients */
        const val LEGACY_FIELD_ID = 7
    }
}
