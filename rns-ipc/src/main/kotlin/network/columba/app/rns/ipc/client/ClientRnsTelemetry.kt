package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LocationTelemetry
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTelemetry
import network.columba.app.rns.ipc.callback.IRnsLocationTelemetryCallback

internal class ClientRnsTelemetry(
    private val remote: IRnsTelemetry,
    private val scope: CoroutineScope,
) : RnsTelemetry {
    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        telemetry: LocationTelemetry,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> = runCatching {
        val bundle = awaitResult { cb ->
            remote.sendLocationTelemetry(destinationHash, telemetry, sourceIdentity, iconAppearance, cb)
        }
        bundle.classLoader = MessageReceipt::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<MessageReceipt>(BundleKeys.RECEIPT)
            ?: throw RnsException(RnsError.Generic("sendLocationTelemetry payload missing 'receipt'", null))
    }

    override suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long?,
        isCollectorRequest: Boolean,
    ): Result<MessageReceipt> = runCatching {
        val bundle = awaitResult { cb ->
            remote.sendTelemetryRequest(
                destinationHash,
                sourceIdentity,
                timebase ?: 0L,
                timebase != null,
                isCollectorRequest,
                cb,
            )
        }
        bundle.classLoader = MessageReceipt::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<MessageReceipt>(BundleKeys.RECEIPT)
            ?: throw RnsException(RnsError.Generic("sendTelemetryRequest payload missing 'receipt'", null))
    }

    override suspend fun setTelemetryCollectorMode(enabled: Boolean): Result<Unit> = runCatching {
        awaitResult { cb -> remote.setTelemetryCollectorMode(enabled, cb) }
        Unit
    }

    override suspend fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance?,
    ): Result<Unit> = runCatching {
        awaitResult { cb -> remote.storeOwnTelemetry(locationJson, iconAppearance, cb) }
        Unit
    }

    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>): Result<Unit> = runCatching {
        awaitResult { cb -> remote.setTelemetryAllowedRequesters(allowedHashes.toTypedArray(), cb) }
        Unit
    }

    // Replay-0 buffer with burst headroom — UI observers that subscribe
    // after the host emitted aren't entitled to a stale frame, but the
    // burst case (telemetry-stream batch arriving faster than the UI
    // can consume) needs the headroom.
    private val locationTelemetryShared = MutableSharedFlow<LocationTelemetry>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    init {
        callbackFlow<LocationTelemetry> {
            val cb = object : IRnsLocationTelemetryCallback.Stub() {
                override fun onLocationTelemetry(payload: LocationTelemetry?) {
                    if (payload != null) trySend(payload)
                }
            }
            remote.registerTelemetryObserver(cb)
            awaitClose { runCatching { remote.unregisterTelemetryObserver(cb) } }
        }.onEach { locationTelemetryShared.emit(it) }.launchIn(scope)
    }

    override val locationTelemetryFlow: SharedFlow<LocationTelemetry>
        get() = locationTelemetryShared.asSharedFlow()
}
