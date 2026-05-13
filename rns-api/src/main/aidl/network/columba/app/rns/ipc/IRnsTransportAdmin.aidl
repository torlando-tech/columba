// AIDL surface mirroring the Kotlin RnsTransportAdmin interface.
//
// Bundle key conventions for IRnsResultCallback payloads:
//   - getDiscoveredInterfaces → "interfaces": ArrayList<DiscoveredInterface>
//   - getFailedInterfaces     → "interfaces": ArrayList<FailedInterface>
//   - getDebugInfo            → Bundle of mixed keys (free-form key/value snapshot)
//   - getInterfaceStats       → Bundle of stat keys, or Bundle.EMPTY when null
//                               (with "has_stats": Boolean key indicating presence)
//   - getAutoconnectedEndpoints → uses IRnsStringListCallback (Set<String> → List<String>)
//   - Result<Unit>            → Bundle.EMPTY
package network.columba.app.rns.ipc;

import network.columba.app.rns.api.model.BatteryProfile;
import network.columba.app.rns.api.model.InterfaceConfig;
import network.columba.app.rns.ipc.callback.IRnsBoolCallback;
import network.columba.app.rns.ipc.callback.IRnsIntCallback;
import network.columba.app.rns.ipc.callback.IRnsResultCallback;
import network.columba.app.rns.ipc.callback.IRnsStringCallback;
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback;
import network.columba.app.rns.ipc.callback.IRnsStringListCallback;
import network.columba.app.rns.ipc.callback.IRnsUnitEventCallback;

oneway interface IRnsTransportAdmin {
    // ==================== Battery / Performance ====================
    //
    // setBatteryProfile is fire-and-forget (non-suspend `fun`). Errors logged
    // on host; no callback.
    void setBatteryProfile(in BatteryProfile profile);

    // ==================== Hot-reload Interfaces ====================

    void reloadInterfaces(in List<InterfaceConfig> configs, in IRnsResultCallback cb);
    void setDiscoveryEnabled(boolean enabled, in IRnsResultCallback cb);
    void setAutoconnectLimit(int count, in IRnsResultCallback cb);
    void setAutoconnectIfacOnly(boolean enabled, in IRnsResultCallback cb);

    // ==================== RNS 1.1.x Interface Discovery ====================

    void getDiscoveredInterfaces(in IRnsResultCallback cb);
    void isDiscoveryEnabled(in IRnsBoolCallback cb);
    void getAutoconnectedEndpoints(in IRnsStringListCallback cb);

    // ==================== Shared instance ====================

    void isSharedInstanceAvailable(in IRnsBoolCallback cb);

    // ==================== Diagnostics ====================

    void getDebugInfo(in IRnsResultCallback cb);
    void getFailedInterfaces(in IRnsResultCallback cb);
    void getInterfaceStats(String interfaceName, in IRnsResultCallback cb);

    // ==================== RNode ====================

    void reconnectRNodeInterface(in IRnsResultCallback cb);

    // getRNodeRssi: synchronous getter on Kotlin (returns Int, default -100).
    // AIDL exposes as oneway+IRnsIntCallback; :rns-ipc client either caches
    // the value via an observer or wraps with suspendCancellableCoroutine.
    void getRNodeRssi(in IRnsIntCallback cb);

    // ==================== BLE ====================

    // getBleConnectionDetails: synchronous getter on Kotlin (returns String,
    // empty array "[]" when no peers). AIDL exposes as oneway+callback.
    void getBleConnectionDetails(in IRnsStringCallback cb);

    // ==================== Observable diagnostic flows ====================
    //
    // All use observer register/unregister with the matching event callback type.

    void registerInterfaceStatusChangedObserver(in IRnsUnitEventCallback cb);
    void unregisterInterfaceStatusChangedObserver(in IRnsUnitEventCallback cb);

    void registerBleConnectionsObserver(in IRnsStringEventCallback cb);
    void unregisterBleConnectionsObserver(in IRnsStringEventCallback cb);

    void registerDebugInfoObserver(in IRnsStringEventCallback cb);
    void unregisterDebugInfoObserver(in IRnsStringEventCallback cb);

    void registerInterfaceStatusObserver(in IRnsStringEventCallback cb);
    void unregisterInterfaceStatusObserver(in IRnsStringEventCallback cb);

    void registerReactionReceivedObserver(in IRnsStringEventCallback cb);
    void unregisterReactionReceivedObserver(in IRnsStringEventCallback cb);
}
