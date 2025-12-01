package com.lxmf.messenger.reticulum.model

data class ReticulumConfig(
    val storagePath: String,
    val enabledInterfaces: List<InterfaceConfig>,
    val identityFilePath: String? = null,
    val displayName: String? = null,
    val logLevel: LogLevel = LogLevel.INFO,
    val allowAnonymous: Boolean = false,
)

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
     * @param discoveryPort UDP port for peer discovery announcements (default: 48555)
     * @param dataPort UDP port for data communication (default: 49555)
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     */
    data class AutoInterface(
        override val name: String = "Auto Discovery",
        override val enabled: Boolean = true,
        val groupId: String = "",
        val discoveryScope: String = "link",
        val discoveryPort: Int = 48555,
        val dataPort: Int = 49555,
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
     */
    data class TCPClient(
        override val name: String = "TCP Connection",
        override val enabled: Boolean = true,
        val targetHost: String,
        val targetPort: Int,
        val kissFraming: Boolean = false,
        val mode: String = "full",
    ) : InterfaceConfig()

    /**
     * RNode - LoRa radio interface using RNode hardware.
     * Provides long-range, low-bandwidth communication.
     *
     * @param name User-friendly name for this interface
     * @param enabled Whether this interface should be initialized
     * @param port Serial port path (e.g., /dev/ttyUSB0) or BLE address
     * @param frequency LoRa frequency in Hz
     * @param bandwidth LoRa bandwidth in Hz
     * @param txPower Transmission power in dBm
     * @param spreadingFactor LoRa spreading factor (5-12)
     * @param codingRate LoRa coding rate (5-8)
     * @param mode Interface mode: "full", "gateway", "access_point", "roaming", "boundary"
     */
    data class RNode(
        override val name: String = "RNode LoRa",
        override val enabled: Boolean = true,
        val port: String,
        val frequency: Long = 915000000, // 915 MHz (US)
        val bandwidth: Int = 125000, // 125 KHz
        val txPower: Int = 7, // 7 dBm
        val spreadingFactor: Int = 7,
        val codingRate: Int = 5,
        val mode: String = "full",
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

/**
 * Discovery scopes for AutoInterface
 */
enum class DiscoveryScope(val value: String) {
    LINK("link"), // Only local network segment
    ADMIN("admin"), // Administrative scope
    SITE("site"), // Site-wide scope
    ORGANISATION("organisation"), // Organization-wide
    GLOBAL("global"), // Global discovery
}

/**
 * Interface operating modes
 */
enum class InterfaceMode(val value: String) {
    FULL("full"), // All functionality enabled
    GATEWAY("gateway"), // Gateway mode (path discovery for others)
    ACCESS_POINT("access_point"), // Access point mode (quiet unless active)
    ROAMING("roaming"), // Roaming mode
    BOUNDARY("boundary"), // Boundary mode
}
