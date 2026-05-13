package network.columba.app.rns.ipc.server

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.ipc.BundleKeys
import network.columba.app.rns.ipc.IRnsTransportAdmin
import network.columba.app.rns.ipc.callback.IRnsBoolCallback
import network.columba.app.rns.ipc.callback.IRnsIntCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback
import network.columba.app.rns.ipc.callback.IRnsStringListCallback
import network.columba.app.rns.ipc.callback.IRnsUnitEventCallback
import network.columba.app.rns.ipc.toBundle

internal class ServerRnsTransportAdmin(
    private val impl: RnsTransportAdmin,
    private val scope: CoroutineScope,
) : IRnsTransportAdmin.Stub() {
    private val interfaceStatusChangedHub = ObserverHub<Unit, IRnsUnitEventCallback>(
        scope = scope,
        upstream = { impl.interfaceStatusChanged },
        callbackBinder = { it.asBinder() },
        emit = { cb, _ -> cb.onEvent() },
    )
    private val bleHub = stringHub(scope) { impl.bleConnectionsFlow }
    private val debugHub = stringHub(scope) { impl.debugInfoFlow }
    private val ifStatusHub = stringHub(scope) { impl.interfaceStatusFlow }
    private val reactionHub = stringHub(scope) { impl.reactionReceivedFlow }

    override fun setBatteryProfile(profile: BatteryProfile) {
        // Fire-and-forget; errors logged on host (no callback per the AIDL contract).
        runCatching { impl.setBatteryProfile(profile) }
    }

    override fun reloadInterfaces(configs: MutableList<InterfaceConfig>, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.reloadInterfaces(configs); Bundle.EMPTY }

    override fun setDiscoveryEnabled(enabled: Boolean, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.setDiscoveryEnabled(enabled); Bundle.EMPTY }

    override fun setAutoconnectLimit(count: Int, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.setAutoconnectLimit(count); Bundle.EMPTY }

    override fun setAutoconnectIfacOnly(enabled: Boolean, cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.setAutoconnectIfacOnly(enabled); Bundle.EMPTY }

    override fun getDiscoveredInterfaces(cb: IRnsResultCallback) = dispatch(cb, scope) {
        Bundle().apply {
            putParcelableArrayList(BundleKeys.INTERFACES, ArrayList(impl.getDiscoveredInterfaces()))
        }
    }

    override fun isDiscoveryEnabled(cb: IRnsBoolCallback) = dispatchBool(cb, scope) {
        impl.isDiscoveryEnabled()
    }

    override fun getAutoconnectedEndpoints(cb: IRnsStringListCallback) = dispatchStringList(cb, scope) {
        impl.getAutoconnectedEndpoints().toList()
    }

    override fun isSharedInstanceAvailable(cb: IRnsBoolCallback) = dispatchBool(cb, scope) {
        impl.isSharedInstanceAvailable()
    }

    override fun getDebugInfo(cb: IRnsResultCallback) = dispatch(cb, scope) {
        impl.getDebugInfo().toBundle()
    }

    override fun getFailedInterfaces(cb: IRnsResultCallback) = dispatch(cb, scope) {
        Bundle().apply {
            putParcelableArrayList(BundleKeys.INTERFACES, ArrayList(impl.getFailedInterfaces()))
        }
    }

    override fun getInterfaceStats(interfaceName: String, cb: IRnsResultCallback) = dispatch(cb, scope) {
        val stats = impl.getInterfaceStats(interfaceName)
        if (stats == null) {
            Bundle().apply { putBoolean(BundleKeys.HAS_STATS, false) }
        } else {
            stats.toBundle().apply { putBoolean(BundleKeys.HAS_STATS, true) }
        }
    }

    override fun reconnectRNodeInterface(cb: IRnsResultCallback) =
        dispatch(cb, scope) { impl.reconnectRNodeInterface(); Bundle.EMPTY }

    override fun getRNodeRssi(cb: IRnsIntCallback) = dispatchNullableInt(cb, scope) {
        // The Kotlin contract returns Int (default -100); marshal as
        // hasValue=true on every call so the client cache always updates.
        impl.getRNodeRssi()
    }

    override fun getBleConnectionDetails(cb: IRnsStringCallback) = dispatchNullableString(cb, scope) {
        impl.getBleConnectionDetails()
    }

    override fun registerInterfaceStatusChangedObserver(cb: IRnsUnitEventCallback) =
        interfaceStatusChangedHub.registerObserver(cb)
    override fun unregisterInterfaceStatusChangedObserver(cb: IRnsUnitEventCallback) =
        interfaceStatusChangedHub.unregisterObserver(cb)

    override fun registerBleConnectionsObserver(cb: IRnsStringEventCallback) = bleHub.registerObserver(cb)
    override fun unregisterBleConnectionsObserver(cb: IRnsStringEventCallback) = bleHub.unregisterObserver(cb)

    override fun registerDebugInfoObserver(cb: IRnsStringEventCallback) = debugHub.registerObserver(cb)
    override fun unregisterDebugInfoObserver(cb: IRnsStringEventCallback) = debugHub.unregisterObserver(cb)

    override fun registerInterfaceStatusObserver(cb: IRnsStringEventCallback) = ifStatusHub.registerObserver(cb)
    override fun unregisterInterfaceStatusObserver(cb: IRnsStringEventCallback) = ifStatusHub.unregisterObserver(cb)

    override fun registerReactionReceivedObserver(cb: IRnsStringEventCallback) = reactionHub.registerObserver(cb)
    override fun unregisterReactionReceivedObserver(cb: IRnsStringEventCallback) = reactionHub.unregisterObserver(cb)
}

private fun stringHub(
    scope: CoroutineScope,
    upstream: () -> kotlinx.coroutines.flow.Flow<String>,
): ObserverHub<String, IRnsStringEventCallback> =
    ObserverHub(
        scope = scope,
        upstream = upstream,
        callbackBinder = { it.asBinder() },
        emit = { cb, value -> cb.onString(value) },
    )
