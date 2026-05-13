package network.columba.app.rns.api

import kotlinx.coroutines.flow.SharedFlow
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.InterfaceConfig

/**
 * Interface management, battery profile tuning, RNode control, BLE
 * diagnostics, and the interface/diagnostic observable flows.
 *
 * Capability gating: the kotlin backend can hot-reload interface configs
 * without restarting the protocol stack
 * ([BackendCapabilities.InterfaceCaps.hotReloadInterfaces] = `true`); the
 * python backend requires a full restart and surfaces "Apply & Restart"
 * to the user. Battery-profile tuning is kotlin-only
 * ([BackendCapabilities.PerformanceCaps.batteryProfileTuning]).
 *
 * The reaction-received observable lives here (rather than in [RnsLxmf])
 * because it is implemented as a low-level diagnostic event channel
 * alongside `bleConnectionsFlow` / `debugInfoFlow` / `interfaceStatusFlow`,
 * not as a structured LXMF receive event.
 */
@Suppress("TooManyFunctions") // Mirrors Android-specific transport-admin surface; not splitting further.
interface RnsTransportAdmin {
    // ==================== Battery / Performance ====================

    /**
     * Apply a runtime battery/performance profile. Tunes BLE scan
     * intervals, multicast lock acquisition, and AutoInterface
     * aggressiveness without restarting the stack. Capability-gated by
     * [BackendCapabilities.PerformanceCaps.batteryProfileTuning].
     */
    fun setBatteryProfile(profile: BatteryProfile)

    // ==================== Hot-reload Interfaces ====================

    /** Reload network interfaces from the given config without restarting Reticulum. */
    suspend fun reloadInterfaces(configs: List<InterfaceConfig>)

    /** Enable or disable interface discovery without restarting. */
    suspend fun setDiscoveryEnabled(enabled: Boolean)

    /** Update the auto-connect limit without restarting. */
    suspend fun setAutoconnectLimit(count: Int)

    /**
     * When enabled, auto-connect only accepts discovered interfaces that
     * published an IFAC network name. Useful on mixed-trust networks where
     * the user only wants Columba to auto-join known private networks.
     */
    suspend fun setAutoconnectIfacOnly(enabled: Boolean)

    // ==================== RNS 1.1.x Interface Discovery ====================

    /**
     * Get list of discovered interfaces from RNS 1.1.x discovery system.
     * Requires RNS 1.1.0 or later.
     *
     * @return List of DiscoveredInterface objects with interface info
     */
    suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface>

    /**
     * Check if interface discovery and auto-connect is enabled.
     * Requires RNS 1.1.0 or later.
     *
     * @return true if RNS is configured to auto-connect discovered interfaces
     */
    suspend fun isDiscoveryEnabled(): Boolean

    /**
     * Get list of currently auto-connected interface endpoints.
     * Auto-connected interfaces are created dynamically by RNS discovery.
     * Requires RNS 1.1.0 or later.
     *
     * @return Set of endpoint strings like "host:port" for auto-connected interfaces
     */
    suspend fun getAutoconnectedEndpoints(): Set<String>

    // ==================== Shared instance ====================

    /**
     * Returns true if a co-located shared rnsd instance is reachable
     * (e.g., another app like Sideband published one). Capability-gated
     * by [BackendCapabilities.PerformanceCaps.sharedInstanceAvailabilityChecks].
     */
    suspend fun isSharedInstanceAvailable(): Boolean

    // ==================== Diagnostics ====================

    /** Free-form key/value debug snapshot. Surfaced on the developer screen. */
    suspend fun getDebugInfo(): Map<String, Any>

    /**
     * Get list of interfaces that failed to initialize.
     * Returns a list of FailedInterface objects containing the interface name and error message.
     */
    suspend fun getFailedInterfaces(): List<FailedInterface>

    /**
     * Get statistics for a specific interface.
     *
     * @param interfaceName The name of the interface
     * @return Map containing interface stats (online, rxb, txb) or null if not found
     */
    suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>?

    // ==================== RNode ====================

    /**
     * Attempt to reconnect to the RNode interface.
     * Use this when the RNode has disconnected and automatic reconnection has failed.
     */
    suspend fun reconnectRNodeInterface()

    /**
     * Last reported RSSI from the connected RNode in dBm. Returns -100
     * (effectively the noise floor) when no RNode is connected so callers
     * don't need a separate "absent" branch in the signal-strength UI.
     */
    fun getRNodeRssi(): Int

    // ==================== BLE ====================

    /**
     * JSON-encoded snapshot of currently connected BLE peers, suitable
     * for direct rendering on the BLE diagnostic screen. Empty array
     * (`"[]"`) when no peers are connected.
     */
    fun getBleConnectionDetails(): String

    // ==================== Observable diagnostic flows ====================

    /**
     * Pulses (Unit emissions) whenever any interface status changes —
     * online/offline transitions, autoconnect link up/down, etc. UI
     * subscribes to refresh the network panel without re-querying every
     * tick.
     */
    val interfaceStatusChanged: SharedFlow<Unit>

    /**
     * Stream of BLE connection event descriptions. Emitted on connect/
     * disconnect/RSSI-change for each tracked BLE peer. Format is
     * implementation-defined (JSON string today) — diagnostic-only.
     */
    val bleConnectionsFlow: SharedFlow<String>

    /** Stream of free-form debug log lines for the developer screen. */
    val debugInfoFlow: SharedFlow<String>

    /** Stream of human-readable interface status descriptions. */
    val interfaceStatusFlow: SharedFlow<String>

    /**
     * Stream of inbound LXMF reaction frames (Field 16). Surfaced as a
     * low-level diagnostic channel here rather than in [RnsLxmf] because
     * the transport reads them off the same packet pipeline as the BLE/
     * interface diagnostic events; the message-list ViewModel parses the
     * JSON payloads to update reaction badges.
     */
    val reactionReceivedFlow: SharedFlow<String>
}
