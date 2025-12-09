package com.lxmf.messenger.reticulum.model

import org.json.JSONObject

/**
 * Extension function to convert InterfaceConfig to a JSON string.
 * Used by InterfaceRepository and InterfaceDatabase for serialization.
 */
fun InterfaceConfig.toJsonString(): String {
    return when (this) {
        is InterfaceConfig.AutoInterface ->
            JSONObject().apply {
                put("group_id", groupId)
                put("discovery_scope", discoveryScope)
                discoveryPort?.let { put("discovery_port", it) }
                dataPort?.let { put("data_port", it) }
                put("mode", mode)
            }.toString()

        is InterfaceConfig.TCPClient ->
            JSONObject().apply {
                put("target_host", targetHost)
                put("target_port", targetPort)
                put("kiss_framing", kissFraming)
                put("mode", mode)
                networkName?.let { put("network_name", it) }
                passphrase?.let { put("passphrase", it) }
            }.toString()

        is InterfaceConfig.RNode ->
            JSONObject().apply {
                put("target_device_name", targetDeviceName)
                put("connection_mode", connectionMode)
                put("frequency", frequency)
                put("bandwidth", bandwidth)
                put("tx_power", txPower)
                put("spreading_factor", spreadingFactor)
                put("coding_rate", codingRate)
                stAlock?.let { put("st_alock", it) }
                ltAlock?.let { put("lt_alock", it) }
                put("mode", mode)
                put("enable_framebuffer", enableFramebuffer)
            }.toString()

        is InterfaceConfig.UDP ->
            JSONObject().apply {
                put("listen_ip", listenIp)
                put("listen_port", listenPort)
                put("forward_ip", forwardIp)
                put("forward_port", forwardPort)
                put("mode", mode)
            }.toString()

        is InterfaceConfig.AndroidBLE ->
            JSONObject().apply {
                put("device_name", deviceName)
                put("max_connections", maxConnections)
                put("mode", mode)
            }.toString()
    }
}

/**
 * Get the type name string for this InterfaceConfig.
 * Used for database storage.
 */
val InterfaceConfig.typeName: String
    get() = this::class.simpleName ?: "Unknown"
