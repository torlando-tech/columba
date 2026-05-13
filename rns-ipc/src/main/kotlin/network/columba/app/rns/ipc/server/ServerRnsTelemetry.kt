package network.columba.app.rns.ipc.server

import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTelemetry
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback

internal class ServerRnsTelemetry(
    private val impl: RnsTelemetry,
    private val scope: CoroutineScope,
) : IRnsTelemetry.Stub() {
    private val telemetryHub = ObserverHub<String, IRnsStringEventCallback>(
        scope = scope,
        upstream = { impl.locationTelemetryFlow },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onString(value) },
    )

    override fun sendLocationTelemetry(
        destinationHash: ByteArray,
        locationJson: String,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val receipt = impl.sendLocationTelemetry(
            destinationHash,
            locationJson,
            sourceIdentity,
            iconAppearance,
        ).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long,
        hasTimebase: Boolean,
        isCollectorRequest: Boolean,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val receipt = impl.sendTelemetryRequest(
            destinationHash,
            sourceIdentity,
            if (hasTimebase) timebase else null,
            isCollectorRequest,
        ).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.RECEIPT, receipt) }
    }

    override fun setTelemetryCollectorMode(enabled: Boolean, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.setTelemetryCollectorMode(enabled).bundleOrThrow() }

    override fun storeOwnTelemetry(
        locationJson: String,
        iconAppearance: IconAppearance?,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        impl.storeOwnTelemetry(locationJson, iconAppearance).bundleOrThrow()
    }

    override fun setTelemetryAllowedRequesters(allowedHashes: Array<out String>, cb: IRnsResultCallback) =
        dispatch(cb, scope) {
            impl.setTelemetryAllowedRequesters(allowedHashes.toSet()).bundleOrThrow()
        }

    override fun registerTelemetryObserver(cb: IRnsStringEventCallback) {
        telemetryHub.registerObserver(cb)
    }

    override fun unregisterTelemetryObserver(cb: IRnsStringEventCallback) {
        telemetryHub.unregisterObserver(cb)
    }
}
