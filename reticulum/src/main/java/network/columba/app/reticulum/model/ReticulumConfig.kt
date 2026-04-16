package network.columba.app.reticulum.model

data class ReticulumConfig(
    val storagePath: String,
    val enabledInterfaces: List<InterfaceConfig>,
    val identityFilePath: String? = null,
    /**
     * Raw 64-byte delivery-identity private key (X25519_prv || Ed25519_prv) kept in
     * memory for the lifetime of this init. When non-null, the native stack constructs
     * the delivery identity via `NativeIdentity.fromBytes(deliveryIdentityKey)` and
     * never writes or reads `identityFilePath`. Callers that hold the key encrypted
     * at rest (e.g. Android Keystore-wrapped in the app's DB) should pass the
     * decrypted bytes here and leave `identityFilePath` null so the plaintext key
     * does not touch disk.
     */
    val deliveryIdentityKey: ByteArray? = null,
    val displayName: String? = null,
    val logLevel: LogLevel = LogLevel.INFO,
    val allowAnonymous: Boolean = false,
    /**
     * User-selected runtime battery/performance profile.
     * This tunes native Reticulum transport/interface aggressiveness without changing
     * explicit user feature schedules like telemetry or propagation retrieval intervals.
     */
    val batteryProfile: BatteryProfile = BatteryProfile.BALANCED,
    /**
     * When false (default), Columba will attempt to connect to a shared RNS instance
     * (e.g., from Sideband) if one is available on the device.
     * When true, Columba will always create its own RNS instance.
     */
    val preferOwnInstance: Boolean = false,
    /**
     * RPC authentication key for shared instance communication.
     * Required on Android when connecting to another app's shared instance (e.g., Sideband)
     * because apps have separate config directories with different RPC keys.
     * Export from Sideband: Connectivity → Share Instance Access
     * Format: Hexadecimal string (e.g., "e5c032d3ec4e64a6aca9927ba8ab73336780f6d71790")
     */
    val rpcKey: String? = null,
    /**
     * When true (default), this device acts as a transport node and forwards
     * traffic for the mesh network (caching path announces, relaying packets).
     * When false, only handles its own traffic without relaying for other peers.
     */
    val enableTransport: Boolean = true,
    // ==================== RNS 1.1.x Interface Discovery ====================
    /**
     * Enable automatic interface discovery (RNS 1.1.0+).
     * When enabled, RNS will discover and potentially auto-connect to interfaces
     * announced by other nodes on the network.
     */
    val discoverInterfaces: Boolean = false,
    /**
     * Maximum number of discovered interfaces to auto-connect.
     * 0 = disabled (discovery only, no auto-connect)
     * 1-10 = auto-connect up to this many discovered interfaces
     * Default: 0 (disabled)
     */
    val autoconnectDiscoveredInterfaces: Int = 0,
    /**
     * When true, the auto-connect factory only accepts discovered interfaces
     * whose announce included an IFAC network name. Non-IFAC interfaces are
     * skipped, freeing the autoconnect slot for an IFAC-advertised peer.
     */
    val autoconnectIfacOnly: Boolean = false,
    /**
     * List of identity hashes (hex) of trusted discovery sources.
     * If null or empty, all discovered interfaces are considered.
     * If set, only interfaces from these sources will be auto-connected.
     */
    val interfaceDiscoverySources: List<String>? = null,
    /**
     * Required proof-of-work stamp value for discovery announces.
     * Higher values require more computational effort to create announces,
     * providing spam prevention. Range: 8-20, default: 14.
     */
    val requiredDiscoveryValue: Int = 14,
) {
    // Data-class-generated equals/hashCode use reference equality for ByteArray, so
    // two configs with identical key bytes would otherwise never compare equal. Override
    // to use contentEquals/contentHashCode for the key field; everything else is handled
    // by the standard == on each property.
    @Suppress("CyclomaticComplexMethod") // Field-by-field equality: one branch per property
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReticulumConfig) return false
        return storagePath == other.storagePath &&
            enabledInterfaces == other.enabledInterfaces &&
            identityFilePath == other.identityFilePath &&
            deliveryIdentityKey.contentEqualsNullable(other.deliveryIdentityKey) &&
            displayName == other.displayName &&
            logLevel == other.logLevel &&
            allowAnonymous == other.allowAnonymous &&
            batteryProfile == other.batteryProfile &&
            preferOwnInstance == other.preferOwnInstance &&
            rpcKey == other.rpcKey &&
            enableTransport == other.enableTransport &&
            discoverInterfaces == other.discoverInterfaces &&
            autoconnectDiscoveredInterfaces == other.autoconnectDiscoveredInterfaces &&
            autoconnectIfacOnly == other.autoconnectIfacOnly &&
            interfaceDiscoverySources == other.interfaceDiscoverySources &&
            requiredDiscoveryValue == other.requiredDiscoveryValue
    }

    override fun hashCode(): Int {
        var result = storagePath.hashCode()
        result = 31 * result + enabledInterfaces.hashCode()
        result = 31 * result + (identityFilePath?.hashCode() ?: 0)
        result = 31 * result + (deliveryIdentityKey?.contentHashCode() ?: 0)
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + logLevel.hashCode()
        result = 31 * result + allowAnonymous.hashCode()
        result = 31 * result + batteryProfile.hashCode()
        result = 31 * result + preferOwnInstance.hashCode()
        result = 31 * result + (rpcKey?.hashCode() ?: 0)
        result = 31 * result + enableTransport.hashCode()
        result = 31 * result + discoverInterfaces.hashCode()
        result = 31 * result + autoconnectDiscoveredInterfaces
        result = 31 * result + autoconnectIfacOnly.hashCode()
        result = 31 * result + (interfaceDiscoverySources?.hashCode() ?: 0)
        result = 31 * result + requiredDiscoveryValue
        return result
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}

/**
 * Sealed class representing different Reticulum network interface types.
 * Each type contains the configuration parameters specific to that interface.
 */
sealed class InterfaceConfig {
    abstract val name: String
    abstract val enabled: Boolean

    /**
     * AutoInterface - Automatically discovers peers on the local network.
     * Uses UDP multicast for peer discovery and establishes direct connections.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param groupId Custom network identifier (empty for default)
     * @param discoveryScope Discovery scope: "link", "admin", "site", "organisation", or "global"
     * @param discoveryPort UDP port for peer discovery announcements (null = RNS default 29716)
     * @param dataPort UDP port for data communication (null = RNS default 42671)
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     */
    data class AutoInterface(
        override val name: String = "Auto Discovery",
        override val enabled: Boolean = true,
        val groupId: String = "",
        val discoveryScope: String = "link",
        val discoveryPort: Int? = null,
        val dataPort: Int? = null,
        val mode: String = "full",
    ) : InterfaceConfig()

    /**
     * TCPClient - Connects to a remote TCP server for Reticulum networking.
     * Used to connect to bridges, relays, or other Reticulum nodes.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param targetHost IP address or hostname of the remote server
     * @param targetPort TCP port number of the remote server
     * @param kissFraming Whether to use KISS framing (for connecting to TNCs/modems)
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     * @param networkName Optional IFAC network name for cryptographic authentication
     * @param passphrase Optional IFAC passphrase for cryptographic authentication
     * @param bootstrapOnly When true, this interface auto-detaches once sufficient discovered
     *                      interfaces are connected (RNS 1.1.0+ bootstrap feature)
     */
    data class TCPClient(
        override val name: String = "TCP Connection",
        override val enabled: Boolean = true,
        val targetHost: String,
        val targetPort: Int,
        val kissFraming: Boolean = false,
        val mode: String = "full",
        val networkName: String? = null,
        val passphrase: String? = null,
        val bootstrapOnly: Boolean = false,
        /**
         * When true, route this TCP connection through a SOCKS5 proxy (e.g., Orbot for Tor).
         * Required for connecting to .onion addresses.
         */
        val socksProxyEnabled: Boolean = false,
        /** SOCKS5 proxy host. Defaults to 127.0.0.1 (Orbot's default). */
        val socksProxyHost: String = "127.0.0.1",
        /** SOCKS5 proxy port. Defaults to 9050 (Orbot's default SOCKS port). */
        val socksProxyPort: Int = 9050,
    ) : InterfaceConfig()

    /**
     * RNode - LoRa radio interface using RNode hardware.
     * Provides long-range, low-bandwidth communication.
     * Supports Bluetooth (Classic SPP or BLE), TCP/WiFi, or USB serial connections.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param targetDeviceName Bluetooth device name of the paired RNode (required for Bluetooth)
     * @param connectionMode Connection mode: "classic" (SPP/RFCOMM), "ble" (GATT), "tcp" (WiFi), or "usb" (serial)
     * @param tcpHost IP address or hostname for TCP/WiFi mode (required when connectionMode="tcp")
     * @param tcpPort TCP port for WiFi mode (default: 7633, the RNode standard port)
     * @param usbDeviceId Android USB device ID for USB serial mode (required when connectionMode="usb")
     * @param frequency LoRa frequency in Hz (137000000 - 3000000000)
     * @param bandwidth LoRa bandwidth in Hz (7800 - 1625000)
     * @param txPower Transmission power in dBm (0-22)
     * @param spreadingFactor LoRa spreading factor (5-12)
     * @param codingRate LoRa coding rate (5-8)
     * @param stAlock Short-term airtime limit percentage (optional)
     * @param ltAlock Long-term airtime limit percentage (optional)
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     * @param networkName Optional IFAC network name for cryptographic authentication
     * @param passphrase Optional IFAC passphrase for cryptographic authentication
     * @param enableFramebuffer Display Columba logo on RNode's screen
     */
    data class RNode(
        override val name: String = "RNode LoRa",
        override val enabled: Boolean = true,
        val targetDeviceName: String = "", // Required for Bluetooth, empty for TCP/USB
        val connectionMode: String = "classic", // "classic", "ble", "tcp", or "usb"
        val tcpHost: String? = null, // IP/hostname for TCP mode
        val tcpPort: Int = 7633, // RNode TCP port (default)
        val usbDeviceId: Int? = null, // Android USB device ID for USB mode (runtime, may change)
        val usbVendorId: Int? = null, // USB Vendor ID for USB mode (stable identifier)
        val usbProductId: Int? = null, // USB Product ID for USB mode (stable identifier)
        val frequency: Long = 915000000, // 915 MHz (US)
        val bandwidth: Int = 125000, // 125 KHz
        val txPower: Int = 7, // 7 dBm
        val spreadingFactor: Int = 7,
        val codingRate: Int = 5,
        val stAlock: Double? = null, // Short-term airtime limit %
        val ltAlock: Double? = null, // Long-term airtime limit %
        val mode: String = "access_point",
        val networkName: String? = null,
        val passphrase: String? = null,
        val enableFramebuffer: Boolean = true, // Display logo on RNode screen
    ) : InterfaceConfig()

    /**
     * UDP - UDP interface for IP networking.
     * Can be used for broadcast or point-to-point UDP communication.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param listenIp IP address to listen on
     * @param listenPort UDP port to listen on
     * @param forwardIp IP address to forward packets to
     * @param forwardPort UDP port to forward packets to
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     */
    data class UDP(
        override val name: String = "UDP Interface",
        override val enabled: Boolean = true,
        val listenIp: String = "0.0.0.0",
        val listenPort: Int = 4242,
        val forwardIp: String = "255.255.255.255",
        val forwardPort: Int = 4242,
        val mode: String = "full",
    ) : InterfaceConfig()

    /**
     * AndroidBLE - Bluetooth Low Energy interface for mobile mesh networking.
     * Implements BLE Protocol v2.2 with identity-based peer tracking.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param deviceName BLE device name (optional, empty to omit from advertisement).
     *                   Keep short (max 8 chars) if used. Not required for peer discovery.
     * @param maxConnections Maximum simultaneous BLE connections (default: 7)
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     */
    data class AndroidBLE(
        override val name: String = "Bluetooth LE",
        override val enabled: Boolean = true,
        val deviceName: String = "",
        val maxConnections: Int = 7,
        val mode: String = "roaming",
        val blePowerPreset: String = "balanced",
        val bleDiscoveryIntervalMs: Long = 5000L,
        val bleDiscoveryIntervalIdleMs: Long = 30000L,
        val bleScanDurationMs: Long = 10000L,
        val bleAdvertisingRefreshIntervalMs: Long = 60_000L,
    ) : InterfaceConfig()

    /**
     * TCPServer - Accepts incoming TCP connections from other Reticulum nodes.
     * Useful for Yggdrasil connectivity and scenarios where the phone acts as a server.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param listenIp IP address to bind to (0.0.0.0 for all interfaces)
     * @param listenPort TCP port to listen on
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     */
    data class TCPServer(
        override val name: String = "TCP Server",
        override val enabled: Boolean = true,
        val listenIp: String = "0.0.0.0",
        val listenPort: Int = 4242,
        val mode: String = "full",
    ) : InterfaceConfig()
}

enum class LogLevel {
    CRITICAL,
    ERROR,
    WARNING,
    INFO,
    DEBUG,
    VERBOSE,
}

enum class BatteryProfile(
    val displayName: String,
    val description: String,
) {
    MAXIMUM_BATTERY(
        displayName = "Maximum Battery",
        description = "Best battery life with slower background networking and discovery.",
    ),
    BALANCED(
        displayName = "Balanced",
        description = "Good battery life with normal responsiveness for most users.",
    ),
    PERFORMANCE(
        displayName = "Performance",
        description = "Fastest path recovery and networking responsiveness at higher battery cost.",
    ),
    ;

    companion object {
        fun fromName(name: String?): BatteryProfile = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/**
 * Discovery scopes for AutoInterface
 */
enum class DiscoveryScope(
    val value: String,
) {
    LINK("link"), // Only local network segment
    ADMIN("admin"), // Administrative scope
    SITE("site"), // Site-wide scope
    ORGANISATION("organisation"), // Organization-wide
    GLOBAL("global"), // Global discovery
}

/**
 * Interface operating modes
 */
enum class InterfaceMode(
    val value: String,
) {
    FULL("full"), // All functionality enabled
    GATEWAY("gateway"), // Gateway mode (path discovery for others)
    ACCESS_POINT("access_point"), // Access point mode (quiet unless active)
    ROAMING("roaming"), // Roaming mode
    BOUNDARY("boundary"), // Boundary mode
}
