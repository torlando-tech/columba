package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Typed location-telemetry payload — the API surface for
 * `RnsTelemetry.sendLocationTelemetry` and the element type emitted by
 * `RnsTelemetry.locationTelemetryFlow`.
 *
 * Replaces the previous JSON-string contract: in-process consumers no
 * longer pay a JSON encode/decode per location update, and cross-process
 * callers cross AIDL as a single Parcelable rather than a string that
 * each side has to parse.
 *
 * Wire format on the LXMF side (Sideband-compatible):
 *   - `FIELD_TELEMETRY` (0x02) — upstream Telemeter msgpack
 *     `{SID_TIME: <int seconds>, SID_LOCATION: [<struct-packed
 *     lat/lon/alt/speed/bearing/accuracy + last_update>]}`. Sideband's
 *     `Telemeter.from_packed` accepts this directly.
 *   - `FIELD_CUSTOM_META` (0xFD) — msgpack
 *     `{cease?, expires?, approxRadius?, ts?}` for Columba-specific
 *     extras. Sideband ignores this field entirely.
 *
 * Marshaling between this data class and the LXMF wire format happens
 * inside the backend implementations (`PythonRnsTelemetry`,
 * `NativeTelemetryHandler`). The interface itself stays free of
 * wire-format concerns.
 *
 * @property type Message type identifier (always "location_share")
 * @property lat Latitude in WGS84 decimal degrees
 * @property lng Longitude in WGS84 decimal degrees
 * @property acc Accuracy in meters
 * @property ts Timestamp when location was captured (millis since epoch)
 * @property altitude Altitude in meters above sea level (Telemeter field;
 *   0.0 when the sender didn't supply or the recipient is pre-Telemeter Columba)
 * @property speed Speed over ground in m/s (Telemeter field)
 * @property bearing Direction of travel in degrees from true north
 * @property expires When sharing ends (millis since epoch), null for indefinite
 * @property cease If true, recipient should delete sender's location (sharing stopped)
 * @property approxRadius Coarsening radius in meters (0 = precise, >0 = approximate)
 * @property sourceHash Sender's LXMF identity hash (hex). Populated on
 *   the receive path from the LXMessage envelope; null on outbound
 *   construction (sender's own identity is implicit).
 * @property appearance Sender's icon appearance, when carried alongside
 *   the telemetry via `FIELD_ICON_APPEARANCE`. Null when absent.
 */
@Parcelize
data class LocationTelemetry(
    val type: String = TYPE_LOCATION_SHARE,
    val lat: Double,
    val lng: Double,
    val acc: Float,
    val ts: Long,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val bearing: Double = 0.0,
    val expires: Long? = null,
    val cease: Boolean = false,
    val approxRadius: Int = 0,
    val sourceHash: String? = null,
    val appearance: IconAppearance? = null,
) : Parcelable {
    companion object {
        const val TYPE_LOCATION_SHARE = "location_share"

        /** LXMF `FIELD_TELEMETRY` — Sideband-compatible Telemeter blob. */
        const val LXMF_FIELD_ID = 0x02

        /**
         * Upstream LXMF `FIELD_CUSTOM_META` — documented extension point
         * for app-specific metadata; Sideband ignores it entirely (zero
         * references in `sbapp/sideband/core.py`).
         *
         * Previously this was `0x70` (a Columba-invented unassigned ID
         * that risked collision if upstream LXMF later assigned numbers
         * in the unassigned range).
         */
        const val COLUMBA_META_FIELD_ID = 0xFD

        /** Legacy field ID for backwards compatibility with old Columba clients */
        const val LEGACY_FIELD_ID = 7
    }
}
