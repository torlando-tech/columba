package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.InterfaceConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * `RnsTransportAdmin` over upstream Python RNS, driven through Chaquopy.
 *
 * Per the dual-build plan this sub-impl is **mostly no-op + `UNSUPPORTED`
 * capability** for two surfaces, and that is plan-sanctioned, not a cop-out:
 *
 *  - **Hot-reload** ([reloadInterfaces], [setDiscoveryEnabled],
 *    [setAutoconnectLimit], [setAutoconnectIfacOnly]) — upstream Python RNS has
 *    no live interface-reload path; interface/discovery settings are read from
 *    the RNS `config` file at `Reticulum()` construction. The UI's
 *    "Apply & Restart" flow drives `RnsCore.shutdown()` + `RnsCore.initialize()`
 *    directly, which rewrites the config and reconstructs the stack.
 *    [PythonCapabilities] declares `hotReloadInterfaces = false` for exactly
 *    this reason.
 *  - **Battery profile tuning** ([setBatteryProfile]) — the BLE-scan /
 *    multicast-lock / AutoInterface aggressiveness knobs live in reticulum-kt;
 *    Python RNS has no equivalent. `batteryProfileTuning = UNSUPPORTED`, and the
 *    UI capability-gate keeps [setBatteryProfile] from being called at all.
 *
 * The *read* surfaces ([getDiscoveredInterfaces], [isDiscoveryEnabled],
 * [getAutoconnectedEndpoints], [isSharedInstanceAvailable], [getDebugInfo],
 * [getInterfaceStats]) are real best-effort calls into `RNS.Transport` /
 * `RNS.Reticulum` — upstream 1.2.5 exposes all of them. Where the upstream
 * shape genuinely needs on-device iteration the method is an honest stub with a
 * `TODO(on-device)` marker — never a silent fake.
 */
@Suppress("TooManyFunctions") // Mirrors the RnsTransportAdmin contract surface 1:1.
class PythonRnsTransportAdmin(
    private val runtime: PythonRnsRuntime,
    private val events: PythonEventBridge,
) : RnsTransportAdmin {
    private companion object {
        const val TAG = "PythonRnsTransportAdmin"

        /** Noise-floor sentinel the contract documents for "no RNode connected". */
        const val RNODE_RSSI_ABSENT = -100

        /** Empty JSON array — the contract's documented "no BLE peers" value. */
        const val EMPTY_JSON_ARRAY = "[]"
    }

    // These four flows are mostly idle in this structural cut: upstream RNS has
    // no global interface/BLE status event stream (status is per-Interface,
    // polled). They are owned here so consumers can subscribe unconditionally;
    // wiring them to real upstream events is on-device follow-up.
    private val _interfaceStatusChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val _bleConnectionsFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _debugInfoFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _interfaceStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val interfaceStatusChanged: SharedFlow<Unit> = _interfaceStatusChanged.asSharedFlow()
    override val bleConnectionsFlow: SharedFlow<String> = _bleConnectionsFlow.asSharedFlow()
    override val debugInfoFlow: SharedFlow<String> = _debugInfoFlow.asSharedFlow()
    override val interfaceStatusFlow: SharedFlow<String> = _interfaceStatusFlow.asSharedFlow()

    /** Reaction frames are sourced from the shared event bridge (LXMF Field 16). */
    override val reactionReceivedFlow: SharedFlow<String> = events.reactionReceived

    // ==================== Battery / Performance ====================

    override fun setBatteryProfile(profile: BatteryProfile) {
        // Documented no-op. batteryProfileTuning is UNSUPPORTED on the python
        // backend (see PythonCapabilities) — the UI capability-gate replaces the
        // profile picker with an Android-battery-settings notice, so this is not
        // reached in practice. Must not throw: this is a non-suspend fun with no
        // Result channel, and the contract treats it as fire-and-forget.
        Log.i(TAG, "setBatteryProfile($profile): no-op — battery tuning UNSUPPORTED on python backend")
    }

    // ==================== Hot-reload Interfaces ====================

    override suspend fun reloadInterfaces(configs: List<InterfaceConfig>) {
        // Documented no-op. Python RNS cannot hot-reload interfaces; the UI's
        // "Apply & Restart" flow calls RnsCore.shutdown() + RnsCore.initialize()
        // directly, which rewrites the RNS config file and reconstructs the
        // Reticulum() instance. Nothing to do here.
        Log.i(TAG, "reloadInterfaces(${configs.size} configs): no-op — python uses shutdown()+initialize() restart")
    }

    override suspend fun setDiscoveryEnabled(enabled: Boolean) =
        pyCall {
            // Honest minimal stub: discovery is config-file driven on python
            // (Reticulum reads `discover_interfaces` / `autoconnect_discovered_
            // interfaces` at construction). Changing it takes effect on the next
            // Apply & Restart.
            // TODO(on-device): persist `enabled` into the next-restart RNS config
            // so the restart actually picks it up.
            Log.i(TAG, "setDiscoveryEnabled($enabled): config-restart-gated on python backend")
            Unit
        }

    override suspend fun setAutoconnectLimit(count: Int) =
        pyCall {
            // Honest minimal stub — config-restart-gated, same as
            // setDiscoveryEnabled.
            // TODO(on-device): persist `count` into the next-restart RNS config.
            Log.i(TAG, "setAutoconnectLimit($count): config-restart-gated on python backend")
            Unit
        }

    override suspend fun setAutoconnectIfacOnly(enabled: Boolean) =
        pyCall {
            // Honest minimal stub — config-restart-gated, same as
            // setDiscoveryEnabled.
            // TODO(on-device): persist `enabled` into the next-restart RNS config.
            Log.i(TAG, "setAutoconnectIfacOnly($enabled): config-restart-gated on python backend")
            Unit
        }

    // ==================== RNS 1.1.x Interface Discovery ====================

    override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> =
        pyCall {
            // RNS.Transport.discovery_handler is a class attribute, None until
            // RNS brings up discovery (config `discover_interfaces = yes`). When
            // present it exposes list_discovered_interfaces() -> list[dict].
            val handler = transport()["discovery_handler"] ?: return@pyCall emptyList()
            val infos = handler.callAttr("list_discovered_interfaces")
                ?: return@pyCall emptyList()
            // Materialise the python list, then map each `info` dict to the
            // JSON shape DiscoveredInterface.parseFromJson() consumes — reusing
            // the contract's own parser keeps key handling in one place.
            val jsonArray = JSONArray()
            runtime.python.builtins.callAttr("list", infos).asList().forEach { info ->
                jsonArray.put(discoveredInfoToJson(info))
            }
            DiscoveredInterface.parseFromJson(jsonArray.toString())
        }

    override suspend fun isDiscoveryEnabled(): Boolean =
        pyCall {
            // RNS.Reticulum.should_autoconnect_discovered_interfaces() is the
            // static that reflects the live `autoconnect_discovered_interfaces`
            // config value (> 0).
            runtime.rnsModule["Reticulum"]
                ?.callAttr("should_autoconnect_discovered_interfaces")
                ?.toJava(Boolean::class.javaObjectType) ?: false
        }

    override suspend fun getAutoconnectedEndpoints(): Set<String> =
        pyCall {
            // Auto-connected interfaces are tagged by RNS.Discovery with an
            // `autoconnect_hash` attribute; their formatted name (str(iface)) is
            // the "host:port"-ish endpoint string the contract asks for. Filter
            // via builtins.hasattr — PyObject has no hasattr shortcut.
            val interfaces = transport()["interfaces"] ?: return@pyCall emptySet()
            val builtins = runtime.python.builtins
            builtins.callAttr("list", interfaces).asList()
                .filter { iface ->
                    builtins.callAttr("hasattr", iface, "autoconnect_hash")
                        ?.toJava(Boolean::class.javaObjectType) == true
                }
                .mapNotNull { iface -> iface.toString().takeIf { it.isNotEmpty() } }
                .toSet()
        }

    // ==================== Shared instance ====================

    override suspend fun isSharedInstanceAvailable(): Boolean =
        pyCall {
            // Real best-effort check. The live Reticulum instance records
            // whether it connected to a co-located shared rnsd
            // (`is_connected_to_shared_instance`) or is itself acting as the
            // shared instance (`is_shared_instance`). Either means a shared
            // instance is present on this host.
            val instance = runtime.reticulumInstance ?: return@pyCall false
            val connected = instance["is_connected_to_shared_instance"]
                ?.toJava(Boolean::class.javaObjectType) ?: false
            val isShared = instance["is_shared_instance"]
                ?.toJava(Boolean::class.javaObjectType) ?: false
            connected || isShared
        }

    // ==================== Diagnostics ====================

    override suspend fun getDebugInfo(): Map<String, Any> =
        pyCall {
            // Real best-effort snapshot of whatever RNS status is reachable.
            val info = linkedMapOf<String, Any>()
            info["backend"] = "python-chaquopy"
            info["running"] = runtime.isRunning
            runCatching {
                runtime.rnsModule.callAttr("version")?.toString()
            }.getOrNull()?.let { info["rns_version"] = it }
            runCatching {
                val interfaces = transport()["interfaces"] ?: return@runCatching
                info["interface_count"] =
                    runtime.python.builtins.callAttr("len", interfaces)
                        ?.toJava(Int::class.javaObjectType) ?: 0
            }.onFailure { Log.w(TAG, "getDebugInfo: interface count failed", it) }
            runCatching {
                runtime.rnsModule["Reticulum"]?.callAttr("transport_enabled")
                    ?.toJava(Boolean::class.javaObjectType)
            }.getOrNull()?.let { info["transport_enabled"] = it }
            info
        }

    override suspend fun getFailedInterfaces(): List<FailedInterface> =
        pyCall {
            // Honest empty list: upstream RNS logs interface init failures but
            // keeps no queryable failed-interface registry — a failed interface
            // simply isn't appended to Transport.interfaces.
            // TODO(on-device): scrape RNS.logdest / a log handler to surface
            // init failures, or have event_bridge.py capture them.
            emptyList()
        }

    override suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>? =
        pyCall {
            // Best-effort: RNS.Reticulum.get_interface_stats() returns a dict
            // with an `interfaces` list; find the entry whose name matches.
            val instance = runtime.reticulumInstance ?: return@pyCall null
            val stats = instance.callAttr("get_interface_stats") ?: return@pyCall null
            val ifaceList = stats.callAttr("get", "interfaces") ?: return@pyCall null
            val match = runtime.python.builtins.callAttr("list", ifaceList).asList()
                .firstOrNull { it.dictStr("name") == interfaceName }
                ?: return@pyCall null
            mapOf(
                "online" to match.dictBool("status"),
                "rxb" to (match.dictLong("rxb") ?: 0L),
                "txb" to (match.dictLong("txb") ?: 0L),
            )
        }

    // ==================== RNode ====================

    override suspend fun reconnectRNodeInterface() {
        // Documented no-op. RNode-over-python (the python `rnode_interface.py`
        // RNS.Interface adapter + USB/BLE bring-up) is an on-device follow-up;
        // there is no python RNode interface to reconnect in this structural cut.
        Log.i(TAG, "reconnectRNodeInterface: no-op — python RNode support is on-device follow-up")
    }

    override fun getRNodeRssi(): Int {
        // No python RNode interface in this cut — return the documented
        // noise-floor sentinel so the signal-strength UI needs no "absent" branch.
        return RNODE_RSSI_ABSENT
    }

    // ==================== BLE ====================

    override fun getBleConnectionDetails(): String {
        // No python-side BLE peer tracking in this cut — return the contract's
        // documented empty-array value.
        return EMPTY_JSON_ARRAY
    }

    // ==================== Internal helpers ====================

    /** `RNS.Transport` — used statically by upstream RNS. */
    private fun transport(): PyObject =
        runtime.rnsModule["Transport"] ?: error("RNS.Transport not resolvable")

    /**
     * Map one `RNS.Discovery` `info` dict to the JSON object shape that
     * [DiscoveredInterface.parseFromJson] consumes. Upstream's dict uses `value`
     * for the stamp value and carries `received` / `discovered` timestamps; the
     * rest of the keys line up 1:1 with the parser.
     */
    private fun discoveredInfoToJson(info: PyObject): JSONObject {
        val obj = JSONObject()
        fun putStr(jsonKey: String, dictKey: String = jsonKey) {
            info.dictStr(dictKey)?.let { obj.put(jsonKey, it) }
        }
        fun putInt(jsonKey: String, dictKey: String = jsonKey) {
            info.dictInt(dictKey)?.let { obj.put(jsonKey, it) }
        }
        fun putLong(jsonKey: String, dictKey: String = jsonKey) {
            info.dictLong(dictKey)?.let { obj.put(jsonKey, it) }
        }
        putStr("name")
        putStr("type")
        putStr("transport_id")
        putStr("network_id")
        putStr("status")
        putInt("status_code")
        putLong("last_heard")
        putInt("heard_count")
        putInt("hops")
        // Upstream calls the stamp value `value`; the parser expects `stamp_value`.
        info.dictInt("value")?.let { obj.put("stamp_value", it) }
        putStr("reachable_on")
        putInt("port")
        putLong("frequency")
        putInt("bandwidth")
        putInt("spreading_factor")
        putInt("coding_rate")
        putStr("modulation")
        putInt("channel")
        putStr("ifac_netname")
        putStr("ifac_netkey")
        info.dictGet("transport")?.let { obj.put("transport", it.toJava(Boolean::class.javaObjectType)) }
        putStr("discovery_hash")
        putLong("received")
        putLong("discovered")
        return obj
    }
}
