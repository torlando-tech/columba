package network.columba.app.rns.host.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.model.NomadnetPageResult

/**
 * UI-side proxy that delegates every [RnsNomadnet] member to the currently-
 * bound [RnsBackend].
 */
internal class BoundRnsNomadnet(
    private val backendFlow: StateFlow<RnsBackend?>,
    scope: CoroutineScope,
) : RnsNomadnet {
    private suspend fun awaitBound(): RnsBackend = backendFlow.filterNotNull().first()

    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<NomadnetPageResult> =
        awaitBound().nomadnet.requestNomadnetPage(
            destinationHash,
            path,
            formDataJson,
            timeoutSeconds,
        )

    override suspend fun cancelNomadnetPageRequest() {
        awaitBound().nomadnet.cancelNomadnetPageRequest()
    }

    override suspend fun getNomadnetRequestStatus(): String =
        awaitBound().nomadnet.getNomadnetRequestStatus()

    override suspend fun getNomadnetDownloadProgress(): Float =
        awaitBound().nomadnet.getNomadnetDownloadProgress()

    override suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean> =
        awaitBound().nomadnet.identifyNomadnetLink(destinationHash)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val nomadnetRequestStatusFlow: StateFlow<String> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.nomadnet.nomadnetRequestStatusFlow }
            .stateIn(scope, SharingStarted.Eagerly, "idle")

    @OptIn(ExperimentalCoroutinesApi::class)
    override val nomadnetDownloadProgressFlow: StateFlow<Float> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.nomadnet.nomadnetDownloadProgressFlow }
            .stateIn(scope, SharingStarted.Eagerly, 0f)
}
