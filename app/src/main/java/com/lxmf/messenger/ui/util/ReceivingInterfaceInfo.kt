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
 * Enum representing known interface categories with their display properties.
 */
private enum class InterfaceCategory(
    val icon: ImageVector,
    val defaultText: String,
) {
    AUTO(Icons.Default.Wifi, "Local Network"),
    TCP(Icons.Default.Cloud, "TCP/IP"),
    BLUETOOTH(Icons.Default.Bluetooth, "Bluetooth"),
    LORA(Icons.Default.CellTower, "LoRa Radio"),
    SERIAL(Icons.Default.SettingsInputAntenna, "Serial"),
    UNKNOWN(Icons.Default.SettingsInputAntenna, ""),
}

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
    } else if (!looksLikeAddress(bracketContent)) {
        bracketContent.takeIf { it.isNotBlank() }
    } else {
        null
    }
}

/**
 * Check if a string looks like a network address (IP, IPv6, or contains port).
 */
private fun looksLikeAddress(value: String): Boolean = value.contains(":") || value.contains(".") || value.startsWith("fe80")

/**
 * Extract the interface type/class name from an interface string.
 * - "TCPClientInterface[Sideband Server/192.168.1.100:4965]" -> "TCPClientInterface"
 * - "AutoInterface" -> "AutoInterface"
 */
private fun extractInterfaceType(interfaceName: String): String = interfaceName.substringBefore("[").ifEmpty { interfaceName }

/**
 * Determine the interface category based on the interface name.
 */
private fun categorizeInterface(interfaceName: String): InterfaceCategory {
    val lowerName = interfaceName.lowercase()
    return when {
        lowerName.contains("autointerface") ||
            lowerName.contains("auto discovery") ||
            lowerName.startsWith("auto") -> InterfaceCategory.AUTO
        lowerName.contains("tcp") -> InterfaceCategory.TCP
        lowerName.contains("ble") ||
            lowerName.contains("bluetooth") ||
            lowerName.contains("androidble") -> InterfaceCategory.BLUETOOTH
        lowerName.contains("rnode") ||
            lowerName.contains("lora") -> InterfaceCategory.LORA
        lowerName.contains("serial") -> InterfaceCategory.SERIAL
        else -> InterfaceCategory.UNKNOWN
    }
}

fun getReceivingInterfaceInfo(interfaceName: String): ReceivingInterfaceInfo {
    val friendlyName = extractFriendlyName(interfaceName)
    val interfaceType = extractInterfaceType(interfaceName)
    val category = categorizeInterface(interfaceName)

    val displayText =
        when (category) {
            InterfaceCategory.UNKNOWN -> friendlyName ?: interfaceName.take(30)
            else -> friendlyName ?: category.defaultText
        }

    return ReceivingInterfaceInfo(
        icon = category.icon,
        text = displayText,
        subtitle = interfaceType,
    )
}
