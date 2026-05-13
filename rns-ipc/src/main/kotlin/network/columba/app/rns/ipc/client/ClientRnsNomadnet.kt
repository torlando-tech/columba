package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.model.NomadnetPageResult
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsNomadnet
import network.columba.app.rns.ipc.callback.IRnsFloatEventCallback
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback

internal class ClientRnsNomadnet(
    private val remote: IRnsNomadnet,
    private val scope: CoroutineScope,
) : RnsNomadnet {
    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<NomadnetPageResult> = runCatching {
        val bundle = awaitResult { cb ->
            remote.requestNomadnetPage(destinationHash, path, formDataJson, timeoutSeconds, cb)
        }
        bundle.classLoader = NomadnetPageResult::class.java.classLoader
        @Suppress("DEPRECATION")
        bundle.getParcelable<NomadnetPageResult>(BundleKeys.PAGE)
            ?: throw RnsException(RnsError.Generic("requestNomadnetPage payload missing 'page'", null))
    }

    override suspend fun cancelNomadnetPageRequest() {
        awaitResult { cb -> remote.cancelNomadnetPageRequest(cb) }
    }

    override suspend fun getNomadnetRequestStatus(): String =
        awaitNullableString { cb -> remote.getNomadnetRequestStatus(cb) } ?: ""

    override suspend fun getNomadnetDownloadProgress(): Float =
        awaitFloat { cb -> remote.getNomadnetDownloadProgress(cb) }

    override suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean> = runCatching {
        awaitBool { cb -> remote.identifyNomadnetLink(destinationHash, cb) }
    }

    private val statusState = MutableStateFlow("")
    private val progressState = MutableStateFlow(0f)

    init {
        // Pump remote observer events into the local MutableStateFlows so UI
        // collectors see a real StateFlow rather than a callbackFlow with
        // surprise replay semantics. Snapshot fetch races the observer's first
        // emission — the observer is authoritative; the snapshot only seeds the
        // initial value if it lands first.
        callbackFlow<String> {
            val cb = object : IRnsStringEventCallback.Stub() {
                override fun onString(value: String?) { if (value != null) trySend(value) }
            }
            remote.registerRequestStatusObserver(cb)
            awaitClose { runCatching { remote.unregisterRequestStatusObserver(cb) } }
        }.onEach { statusState.value = it }.launchIn(scope)

        callbackFlow<Float> {
            val cb = object : IRnsFloatEventCallback.Stub() {
                override fun onFloat(value: Float) { trySend(value) }
            }
            remote.registerDownloadProgressObserver(cb)
            awaitClose { runCatching { remote.unregisterDownloadProgressObserver(cb) } }
        }.onEach { progressState.value = it }.launchIn(scope)

        scope.launch {
            runCatching { statusState.value = getNomadnetRequestStatus() }
            runCatching { progressState.value = getNomadnetDownloadProgress() }
        }
    }

    override val nomadnetRequestStatusFlow: StateFlow<String> get() = statusState.asStateFlow()
    override val nomadnetDownloadProgressFlow: StateFlow<Float> get() = progressState.asStateFlow()
}
