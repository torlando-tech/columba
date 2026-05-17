package network.columba.app.rns.host.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LocationTelemetry
import network.columba.app.rns.api.model.MessageReceipt

/**
 * UI-side proxy that delegates every [RnsTelemetry] member to the currently-
 * bound [RnsBackend]. Republishes [locationTelemetryFlow] across rebinds with
 * a small replay so a freshly-bound subscriber doesn't miss the previous
 * batch of pre-rebind telemetry.
 */
internal class BoundRnsTelemetry(
    private val backendFlow: StateFlow<RnsBackend?>,
    scope: CoroutineScope,
) : RnsTelemetry {
    private suspend fun awaitBound(): RnsBackend = backendFlow.filterNotNull().first()

    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        telemetry: LocationTelemetry,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> =
        awaitBound().telemetry.sendLocationTelemetry(
            destinationHash,
            telemetry,
            sourceIdentity,
            iconAppearance,
        )

    override suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long?,
        isCollectorRequest: Boolean,
    ): Result<MessageReceipt> =
        awaitBound().telemetry.sendTelemetryRequest(
            destinationHash,
            sourceIdentity,
            timebase,
            isCollectorRequest,
        )

    override suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit> =
        awaitBound().telemetry.setTelemetryCollectorMode(enabled)

    override suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance?,
    ): Result<Unit> = awaitBound().telemetry.storeOwnTelemetry(locationJson, iconAppearance)

    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit> =
        awaitBound().telemetry.setTelemetryAllowedRequesters(allowedHashes)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val locationTelemetryFlow: SharedFlow<LocationTelemetry> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.telemetry.locationTelemetryFlow }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)
}
