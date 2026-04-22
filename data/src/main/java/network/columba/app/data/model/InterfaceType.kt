package network.columba.app.data.model

/**
 * Represents the type of network interface through which an announce was received.
 */
enum class InterfaceType(
    val displayLabel: String,
) {
    AUTO_INTERFACE("Local"),
    TCP_CLIENT("TCP"),
    ANDROID_BLE("BLE"),
    RNODE("RNode"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        /**
         * Parse interface type from the interface name string.
         * Interface names follow patterns like:
         * - "AutoInterface[Local]", "AutoInterface[fe80::...]", or "Auto Discovery"
         * - "TCPInterface[192.168.1.100:4965]", "TCPClientInterface[...]",
         *   "TCPServerInterface[...]", or Backbone
         * - "BLE", "Bluetooth", or "AndroidBLE"
         * - Names containing "RNode"
         */
        fun fromInterfaceName(interfaceName: String?): InterfaceType {
            if (interfaceName.isNullOrBlank() || interfaceName == "None") return UNKNOWN

            val name = interfaceName.lowercase()
            return when {
                name.contains("autointerface") || name.contains("auto discovery") -> AUTO_INTERFACE
                name.contains("tcpclient") || name.contains("tcpinterface") || name.contains("tcpserver") || name.contains("backbone") -> TCP_CLIENT
                name.contains("rnode") -> RNODE
                name.contains("ble") || name.contains("bluetooth") || name.contains("androidble") -> ANDROID_BLE
                else -> UNKNOWN
            }
        }
    }
}
