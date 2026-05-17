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
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.InterfaceConfig

/**
 * UI-side proxy that delegates every [RnsTransportAdmin] member to the
 * currently-bound [RnsBackend].
 *
 * Two non-suspend getters need special handling:
 * - [getRNodeRssi] / [getBleConnectionDetails] are synchronous Kotlin calls;
 *   the underlying [network.columba.app.rns.ipc.client.ClientRnsTransportAdmin]
 *   already caches their values from the observer flows, so we just pass
 *   through to whichever backend is currently bound and return the documented
 *   sentinel (-100 / `"[]"`) when no backend is bound yet.
 * - [setBatteryProfile] is non-suspend; fire-and-forget after awaiting bind.
 */
internal class BoundRnsTransportAdmin(
    private val backendFlow: StateFlow<RnsBackend?>,
    private val scope: CoroutineScope,
) : RnsTransportAdmin {
    private suspend fun awaitBound(): RnsBackend = backendFlow.filterNotNull().first()

    override fun setBatteryProfile(profile: BatteryProfile) {
        scope.launch { awaitBound().transportAdmin.setBatteryProfile(profile) }
    }

    override suspend fun reloadInterfaces(configs: List<InterfaceConfig>) {
        awaitBound().transportAdmin.reloadInterfaces(configs)
    }

    override suspend fun setDiscoveryEnabled(enabled: Boolean) {
        awaitBound().transportAdmin.setDiscoveryEnabled(enabled)
    }

    override suspend fun setAutoconnectLimit(count: Int) {
        awaitBound().transportAdmin.setAutoconnectLimit(count)
    }

    override suspend fun setAutoconnectIfacOnly(enabled: Boolean) {
        awaitBound().transportAdmin.setAutoconnectIfacOnly(enabled)
    }

    override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> =
        awaitBound().transportAdmin.getDiscoveredInterfaces()

    override suspend fun isDiscoveryEnabled(): Boolean =
        awaitBound().transportAdmin.isDiscoveryEnabled()

    override suspend fun getAutoconnectedEndpoints(): Set<String> =
        awaitBound().transportAdmin.getAutoconnectedEndpoints()

    override suspend fun isSharedInstanceAvailable(): Boolean =
        awaitBound().transportAdmin.isSharedInstanceAvailable()

    override suspend fun getDebugInfo(): Map<String, Any> =
        awaitBound().transportAdmin.getDebugInfo()

    override suspend fun getFailedInterfaces(): List<FailedInterface> =
        awaitBound().transportAdmin.getFailedInterfaces()

    override suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>? =
        awaitBound().transportAdmin.getInterfaceStats(interfaceName)

    override suspend fun reconnectRNodeInterface() {
        awaitBound().transportAdmin.reconnectRNodeInterface()
    }

    override fun getRNodeRssi(): Int = backendFlow.value?.transportAdmin?.getRNodeRssi() ?: -100

    override fun getBleConnectionDetails(): String =
        backendFlow.value?.transportAdmin?.getBleConnectionDetails() ?: "[]"

    @OptIn(ExperimentalCoroutinesApi::class)
    override val interfaceStatusChanged: SharedFlow<Unit> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.transportAdmin.interfaceStatusChanged }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val bleConnectionsFlow: SharedFlow<String> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.transportAdmin.bleConnectionsFlow }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val debugInfoFlow: SharedFlow<String> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.transportAdmin.debugInfoFlow }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val interfaceStatusFlow: SharedFlow<String> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.transportAdmin.interfaceStatusFlow }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val reactionReceivedFlow: SharedFlow<String> =
        backendFlow
            .filterNotNull()
            .flatMapLatest { it.transportAdmin.reactionReceivedFlow }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)
}
