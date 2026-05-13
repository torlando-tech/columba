package network.columba.app.rns.ipc.server

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsNomadnet
import network.columba.app.rns.ipc.callback.IRnsBoolCallback
import network.columba.app.rns.ipc.callback.IRnsFloatCallback
import network.columba.app.rns.ipc.callback.IRnsFloatEventCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback

internal class ServerRnsNomadnet(
    private val impl: RnsNomadnet,
    private val scope: CoroutineScope,
) : IRnsNomadnet.Stub() {
    private val statusHub = ObserverHub<String, IRnsStringEventCallback>(
        scope = scope,
        upstream = { impl.nomadnetRequestStatusFlow },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onString(value) },
    )

    private val progressHub = ObserverHub<Float, IRnsFloatEventCallback>(
        scope = scope,
        upstream = { impl.nomadnetDownloadProgressFlow },
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onFloat(value) },
    )

    override fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
        cb: IRnsResultCallback,
    ) = dispatch(cb, scope) {
        val page = impl.requestNomadnetPage(destinationHash, path, formDataJson, timeoutSeconds).getOrThrow()
        Bundle().apply { putParcelable(BundleKeys.PAGE, page) }
    }

    override fun cancelNomadnetPageRequest(cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.cancelNomadnetPageRequest()
        Bundle.EMPTY
    }

    override fun getNomadnetRequestStatus(cb: IRnsStringCallback) = dispatchNullableString(cb, scope) {
        impl.getNomadnetRequestStatus()
    }

    override fun getNomadnetDownloadProgress(cb: IRnsFloatCallback) = dispatchFloat(cb, scope) {
        impl.getNomadnetDownloadProgress()
    }

    override fun identifyNomadnetLink(destinationHash: String, cb: IRnsBoolCallback) =
        dispatchBool(cb, scope) {
            impl.identifyNomadnetLink(destinationHash).getOrThrow()
        }

    override fun registerRequestStatusObserver(cb: IRnsStringEventCallback) = statusHub.registerObserver(cb)
    override fun unregisterRequestStatusObserver(cb: IRnsStringEventCallback) = statusHub.unregisterObserver(cb)

    override fun registerDownloadProgressObserver(cb: IRnsFloatEventCallback) = progressHub.registerObserver(cb)
    override fun unregisterDownloadProgressObserver(cb: IRnsFloatEventCallback) = progressHub.unregisterObserver(cb)
}
