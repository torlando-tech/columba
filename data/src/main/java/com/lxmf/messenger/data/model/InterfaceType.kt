package com.lxmf.messenger.data.model

/**
 * Represents the type of network interface through which an announce was received.
 */
enum class InterfaceType {
    AUTO_INTERFACE,
    TCP_CLIENT,
    ANDROID_BLE,
    RNODE,
    UNKNOWN,
    ;

    companion object {
        /**
         * Parse interface type from the interface name string.
         * Interface names follow patterns like:
         * - "AutoInterface[Local]" or "AutoInterface[fe80::...]"
         * - "TCPInterface[192.168.1.100:4965]" or "TCPClientInterface[...]"
         * - Names containing "BLE" or "Bluetooth"
         * - Names containing "RNode"
         */
        fun fromInterfaceName(interfaceName: String?): InterfaceType {
            if (interfaceName.isNullOrBlank() || interfaceName == "None") return UNKNOWN

            val name = interfaceName.lowercase()
            return when {
                name.contains("autointerface") -> AUTO_INTERFACE
                name.contains("tcpclient") || name.contains("tcpinterface") || name.contains("backbone") -> TCP_CLIENT
                name.contains("ble") || name.contains("androidble") -> ANDROID_BLE
                name.contains("rnode") -> RNODE
                else -> UNKNOWN
            }
        }
    }
}
