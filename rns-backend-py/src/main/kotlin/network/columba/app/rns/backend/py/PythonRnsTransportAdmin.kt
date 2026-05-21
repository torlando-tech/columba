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
    private val _bleConnectionsFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    private val _debugInfoFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _interfaceStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val interfaceStatusChanged: SharedFlow<Unit> = _interfaceStatusChanged.asSharedFlow()
    override val bleConnectionsFlow: SharedFlow<String> = _bleConnectionsFlow.asSharedFlow()
    override val debugInfoFlow: SharedFlow<String> = _debugInfoFlow.asSharedFlow()
    override val interfaceStatusFlow: SharedFlow<String> = _interfaceStatusFlow.asSharedFlow()

    // ===== BLE peer connection details (Network Status "BLE Connections" card) =====
    // The host wires the live KotlinBLEBridge in via attachBleSource() right after
    // creating it (HostBackendModule), as the rns-api BleConnectionSource seam — this
    // module can't reference KotlinBLEBridge directly (reverse dep). We observe it
    // (push -> _bleConnectionsFlow) and query it (pull -> getBleConnectionDetails).
    // replay=1 means a UI subscriber that opens Network Status *after* a peer
    // connected still sees the current peers.
    @Volatile private var bleSource: network.columba.app.rns.api.BleConnectionSource? = null
    private val bleListener =
        network.columba.app.rns.api.BleConnectionsListener { json -> _bleConnectionsFlow.tryEmit(json) }

    /**
     * Attach the host-side BLE bridge as the live connection source. Idempotent:
     * re-attaching swaps the source and re-seeds the flow with current peers.
     */
    fun attachBleSource(source: network.columba.app.rns.api.BleConnectionSource) {
        bleSource?.removeBleConnectionsListener(bleListener)
        bleSource = source
        source.addBleConnectionsListener(bleListener)
        // Seed current peers so the replay-1 flow + first pull reflect connections
        // established before this wiring (or before the UI subscribed).
        _bleConnectionsFlow.tryEmit(source.currentBleConnectionsJson())
    }

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
            // config value (> 0). Also peek at the name-mangled class attr
            // directly so we can disambiguate "class attribute is False"
            // from "method returned False through some other path" while
            // debugging the post-restart subtitle bug.
            val reticulumClass = runtime.rnsModule["Reticulum"]
            val result = reticulumClass
                ?.callAttr("should_autoconnect_discovered_interfaces")
                ?.toJava(Boolean::class.javaObjectType) ?: false
            val rawAttr = runCatching {
                reticulumClass?.get("_Reticulum__autoconnect_discovered_interfaces")?.toString()
            }.getOrNull()
            val discoverAttr = runCatching {
                reticulumClass?.get("_Reticulum__discover_interfaces")?.toString()
            }.getOrNull()
            Log.i(
                TAG,
                "isDiscoveryEnabled() → $result " +
                    "(_Reticulum__autoconnect_discovered_interfaces=$rawAttr, " +
                    "_Reticulum__discover_interfaces=$discoverAttr)",
            )
            result
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

    override suspend fun isHostingSharedInstance(): Boolean =
        pyCall {
            // Distinct from isSharedInstanceAvailable: only true when WE
            // own TCP 37428, not when we're a client of someone else who
            // does. Upstream Reticulum tracks this in `is_shared_instance`
            // (set true in `__create_default_config` when share_instance=yes
            // and the bind succeeds; left false when first-to-bind was
            // already taken and we fell back to RPC client mode).
            val instance = runtime.reticulumInstance ?: return@pyCall false
            instance["is_shared_instance"]
                ?.toJava(Boolean::class.javaObjectType) ?: false
        }

    // ==================== Diagnostics ====================

    override suspend fun getDebugInfo(): Map<String, Any> =
        pyCall {
            // Mirror NativeRnsBackendImpl.getDebugInfo()'s key set exactly. The
            // Network Status screen (DebugViewModel) and InterfaceManagementViewModel
            // read these specific keys — a divergent shape leaves the screen
            // looking broken (red "Reticulum Status" card, "RNS Available: No",
            // empty interface list, every diagnostic line at its default). The
            // host-side fields (multicast/wake lock, health/monitor/maintenance
            // jobs) are :rns-host peripheral-manager state, not this backend's —
            // the kotlin backend hardcodes them false too.
            val running = runtime.isRunning
            val t = transport()
            linkedMapOf<String, Any>(
                "backend" to "python-chaquopy",
                "initialized" to running,
                "reticulum_available" to running,
                "storage_path" to (runtime.storagePath ?: ""),
                "transport_enabled" to (
                    runCatching {
                        runtime.reticulumInstance?.callAttr("transport_enabled")
                            ?.toJava(Boolean::class.javaObjectType)
                    }.getOrNull() ?: false
                ),
                "interfaces" to collectInterfaces(),
                "path_table_size" to pyLen(t["path_table"]),
                "announce_table_size" to pyLen(t["announce_table"]),
                "link_table_size" to pyLen(t["link_table"]),
                // Host-side peripheral state — owned by :rns-host's managers, not
                // the RNS backend. Hardcoded false/0 for parity with
                // NativeRnsBackendImpl, which does the same.
                "multicast_lock_held" to false,
                "wake_lock_held" to false,
                "heartbeat_age_seconds" to 0L,
                "health_check_running" to false,
                "network_monitor_running" to false,
                "maintenance_running" to false,
                "last_lock_refresh_age_seconds" to 0L,
                "failed_interface_count" to 0,
            )
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

    override fun getBleConnectionDetails(): String =
        // Pull live details from the host-side bridge (wired via attachBleSource).
        // Falls back to the contract's empty-array value before wiring / on no peers.
        bleSource?.currentBleConnectionsJson() ?: EMPTY_JSON_ARRAY

    // ==================== Internal helpers ====================

    /** `RNS.Transport` — used statically by upstream RNS. */
    private fun transport(): PyObject =
        runtime.rnsModule["Transport"] ?: error("RNS.Transport not resolvable")

    /** `len(pyObj)` as Int — 0 on null/failure. */
    private fun pyLen(pyObj: PyObject?): Int =
        pyObj?.let {
            runCatching {
                runtime.python.builtins.callAttr("len", it).toJava(Int::class.javaObjectType)
            }.getOrNull()
        } ?: 0

    /**
     * Enumerate `RNS.Transport.interfaces` into the same per-interface shape
     * `NativeRnsBackendImpl.getDebugInfo()` emits — `{name, type, online,
     * parent_name, can_send, rx_bytes, tx_bytes}`. `name` is the configured
     * interface name (`interface.name` — the RNS config `[[section]]` header),
     * matching `NativeRnsBackendImpl`'s `iface.name` and what the UI keys its
     * online-status overlay on (`InterfaceEntity.name`). Per-interface reads
     * are wrapped so one malformed interface can't blank the whole list.
     */
    private fun collectInterfaces(): List<Map<String, Any>> =
        runCatching {
            val interfaces = transport()["interfaces"] ?: return emptyList()
            runtime.python.builtins.callAttr("list", interfaces).asList().mapNotNull { iface ->
                runCatching {
                    // `interface.name` is the configured name — the RNS config
                    // `[[section]]` header — which is what the UI matches against
                    // `InterfaceEntity.name` (the interface-management toggle rows
                    // + `parentName` filtering) and what NativeRnsBackendImpl emits
                    // as `iface.name`. `str(interface)` is the verbose
                    // "TCPInterface[name/host:port]" form — keying on that left the
                    // toggle rows all showing offline because the lookup missed.
                    val name = iface["name"]?.toString() ?: iface.toString()
                    val parentName = runCatching {
                        iface["parent_interface"]
                            ?.takeIf { it.toString() != "None" }
                            ?.get("name")?.toString()
                    }.getOrNull()
                    linkedMapOf<String, Any>(
                        "name" to name,
                        "type" to name.substringBefore("[").trim(),
                        "online" to (iface["online"]?.toJava(Boolean::class.javaObjectType) ?: false),
                        "parent_name" to (parentName ?: ""),
                        "can_send" to (iface["OUT"]?.toJava(Boolean::class.javaObjectType) ?: false),
                        "rx_bytes" to (iface["rxb"]?.toJava(Long::class.javaObjectType) ?: 0L),
                        "tx_bytes" to (iface["txb"]?.toJava(Long::class.javaObjectType) ?: 0L),
                    )
                }.getOrElse {
                    Log.w(TAG, "getDebugInfo: skipping an interface (attr read failed)", it)
                    null
                }
            }.also { collected ->
                Log.d(
                    TAG,
                    "getDebugInfo interfaces: " +
                        collected.joinToString { "${it["name"]}=${if (it["online"] == true) "online" else "offline"}" },
                )
            }
        }.getOrElse {
            Log.w(TAG, "getDebugInfo: interface enumeration failed", it)
            emptyList()
        }

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
        fun putDouble(jsonKey: String, dictKey: String = jsonKey) {
            info.dictDouble(dictKey)?.let { obj.put(jsonKey, it) }
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
        // Discovery location: upstream RNS stores `latitude` / `longitude` /
        // `height` as floats (or None) in the announce dict. Without these,
        // `DiscoveredInterface.hasLocation` is false and the Map screen's
        // marker layer + the layers-sheet "Show on map" chip row stay empty
        // for every Python-flavor interface.
        putDouble("latitude")
        putDouble("longitude")
        putDouble("height")
        putStr("ifac_netname")
        putStr("ifac_netkey")
        info.dictGet("transport")?.let { obj.put("transport", it.toJava(Boolean::class.javaObjectType)) }
        putStr("discovery_hash")
        putLong("received")
        putLong("discovered")
        return obj
    }
}
