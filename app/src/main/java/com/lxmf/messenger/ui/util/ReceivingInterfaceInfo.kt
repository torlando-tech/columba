package com.lxmf.messenger.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

data class ReceivingInterfaceInfo(
    val icon: ImageVector,
    val text: String,
    val subtitle: String,
)

/**
 * Extract the user-friendly name from an interface string.
 * Interface names have formats like:
 * - "TCPInterface[Sideband Server/192.168.1.100:4965]" -> "Sideband Server"
 * - "TCPClientInterface[192.168.1.100:4965]" -> null (no friendly name)
 * - "AutoInterface[Local]" -> "Local"
 * - "RNodeInterface[My Radio]" -> "My Radio"
 */
private fun extractFriendlyName(interfaceName: String): String? {
    val bracketContent = interfaceName.substringAfter("[", "").substringBefore("]", "")
    if (bracketContent.isEmpty()) return null

    // For TCP interfaces, the format is often "Name/address:port" or just "address:port"
    return if (bracketContent.contains("/")) {
        bracketContent.substringBefore("/").takeIf { it.isNotBlank() }
    } else if (!bracketContent.contains(":") && !bracketContent.contains(".") && !bracketContent.startsWith("fe80")) {
        // If it doesn't look like an address (no colons, dots, or IPv6 link-local), it's probably a name
        bracketContent.takeIf { it.isNotBlank() }
    } else {
        null
    }
}

/**
 * Extract the interface type/class name from an interface string.
 * - "TCPClientInterface[Sideband Server/192.168.1.100:4965]" -> "TCPClientInterface"
 * - "AutoInterface" -> "AutoInterface"
 */
private fun extractInterfaceType(interfaceName: String): String {
    return interfaceName.substringBefore("[").ifEmpty { interfaceName }
}

fun getReceivingInterfaceInfo(interfaceName: String): ReceivingInterfaceInfo {
    val friendlyName = extractFriendlyName(interfaceName)
    val interfaceType = extractInterfaceType(interfaceName)

    return when {
        interfaceName.contains("AutoInterface", ignoreCase = true) ||
            interfaceName.contains("Auto Discovery", ignoreCase = true) ||
            interfaceName.startsWith("Auto", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.Wifi,
                text = friendlyName ?: "Local Network",
                subtitle = interfaceType,
            )
        interfaceName.contains("TCP", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.Cloud,
                text = friendlyName ?: "TCP/IP",
                subtitle = interfaceType,
            )
        interfaceName.contains("BLE", ignoreCase = true) ||
            interfaceName.contains("Bluetooth", ignoreCase = true) ||
            interfaceName.contains("AndroidBle", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.Bluetooth,
                text = friendlyName ?: "Bluetooth",
                subtitle = interfaceType,
            )
        interfaceName.contains("RNode", ignoreCase = true) ||
            interfaceName.contains("LoRa", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.CellTower,
                text = friendlyName ?: "LoRa Radio",
                subtitle = interfaceType,
            )
        interfaceName.contains("Serial", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.SettingsInputAntenna,
                text = friendlyName ?: "Serial",
                subtitle = interfaceType,
            )
        else ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.SettingsInputAntenna,
                text = friendlyName ?: interfaceName.take(30),
                subtitle = interfaceType,
            )
    }
}
