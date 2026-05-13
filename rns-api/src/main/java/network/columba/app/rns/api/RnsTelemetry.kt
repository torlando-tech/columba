package network.columba.app.rns.api

import kotlinx.coroutines.flow.SharedFlow
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.MessageReceipt

/**
 * Location telemetry & collector host mode.
 *
 * Wraps the LXMF telemetry fields (FIELD_TELEMETRY / FIELD_COMMANDS /
 * FIELD_TELEMETRY_STREAM) so the higher-level location/group features
 * don't have to know which LXMF field numbers encode what. Collector
 * (host) mode is gated by [BackendCapabilities.TelemetryCaps] — see
 * `collectorHostMode`, `storeOwnTelemetry`, `allowedRequestersFilter`.
 */
interface RnsTelemetry {
    /**
     * Send location telemetry to a destination via LXMF field 7.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash)
     * @param locationJson JSON string with location data
     * @param sourceIdentity Identity of the sender
     * @return Result containing MessageReceipt or failure
     */
    suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance? = null,
    ): Result<MessageReceipt>

    /**
     * Send a telemetry request to a collector via LXMF FIELD_COMMANDS.
     *
     * The collector will respond with FIELD_TELEMETRY_STREAM containing
     * all known telemetry entries since the specified timebase.
     *
     * @param destinationHash Destination hash bytes (16 bytes identity hash) of the collector
     * @param sourceIdentity Identity of the sender
     * @param timebase Optional Unix timestamp (milliseconds) to request telemetry since
     * @param isCollectorRequest True if requesting from a collector (default)
     * @return Result containing MessageReceipt or failure
     */
    suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long? = null,
        isCollectorRequest: Boolean = true,
    ): Result<MessageReceipt>

    /**
     * Enable or disable telemetry collector (host) mode.
     *
     * When enabled, this device will:
     * - Store incoming FIELD_TELEMETRY location data from peers
     * - Handle FIELD_COMMANDS telemetry requests
     * - Respond with FIELD_TELEMETRY_STREAM containing all stored entries
     *
     * @param enabled True to enable host mode, False to disable
     * @return Result indicating success
     */
    suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit>

    /**
     * Store the host's own location in the collected telemetry so it is included
     * in FIELD_TELEMETRY_STREAM responses sent to group members.
     *
     * @param locationJson JSON string with location data (lat, lng, acc, ts, etc.)
     * @param iconAppearance Optional icon appearance for the host
     * @return Result indicating success
     */
    suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance? = null,
    ): Result<Unit>

    /**
     * Set the list of identity hashes allowed to request telemetry in host mode.
     *
     * Only requesters whose identity hash is in the set will receive responses;
     * requests from others will be silently ignored. If the set is empty,
     * all requests will be blocked.
     *
     * @param allowedHashes Set of 32-character hex identity hash strings
     * @return Result indicating success
     */
    suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit>

    /**
     * Observable stream of incoming FIELD_TELEMETRY location updates from
     * peers (or, when the device is in collector host mode, also the
     * FIELD_TELEMETRY_STREAM batches received from upstream collectors).
     * Each emission is the JSON payload of the inbound telemetry frame.
     */
    val locationTelemetryFlow: SharedFlow<String>
}
