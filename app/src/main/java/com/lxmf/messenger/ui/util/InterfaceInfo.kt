package com.lxmf.messenger.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

data class InterfaceInfo(
    val icon: ImageVector,
    val text: String,
    val subtitle: String,
)

/**
 * Enum representing known interface categories with their display properties.
 * Used by both the announce detail screen and the map interface pins.
 */
enum class InterfaceCategory(
    val icon: ImageVector,
    val mdiIconName: String,
    val defaultText: String,
    val markerColor: Int,
) {
    AUTO(Icons.Default.Wifi, "access-point", "Local Network", 0xFF2E7D32.toInt()),
    TCP(Icons.Default.Cloud, "cloud", "TCP/IP", 0xFF1565C0.toInt()),
    BLUETOOTH(Icons.Default.Bluetooth, "bluetooth", "Bluetooth", 0xFF283593.toInt()),
    LORA(Icons.Default.CellTower, "radio-tower", "LoRa Radio", 0xFFE64A19.toInt()),
    SERIAL(Icons.Default.SettingsInputAntenna, "antenna", "Serial", 0xFF616161.toInt()),
    UNKNOWN(Icons.Default.SettingsInputAntenna, "antenna", "", 0xFF9E9E9E.toInt()),
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
internal fun categorizeInterface(interfaceName: String): InterfaceCategory {
    val lowerName = interfaceName.lowercase()
    return when {
        lowerName.contains("autointerface") ||
            lowerName.contains("auto discovery") ||
            lowerName.startsWith("auto") -> InterfaceCategory.AUTO
        lowerName.contains("tcp") || lowerName.contains("backbone") -> InterfaceCategory.TCP
        lowerName.contains("rnode") ||
            lowerName.contains("lora") -> InterfaceCategory.LORA
        lowerName.contains("ble") ||
            lowerName.contains("bluetooth") ||
            lowerName.contains("androidble") -> InterfaceCategory.BLUETOOTH
        lowerName.contains("serial") -> InterfaceCategory.SERIAL
        else -> InterfaceCategory.UNKNOWN
    }
}

fun getInterfaceInfo(interfaceName: String): InterfaceInfo {
    val friendlyName = extractFriendlyName(interfaceName)
    val interfaceType = extractInterfaceType(interfaceName)
    val category = categorizeInterface(interfaceName)
    val bracketContent = interfaceName.substringAfter("[", "").substringBefore("]", "")

    val displayText =
        when (category) {
            InterfaceCategory.UNKNOWN -> friendlyName ?: interfaceName.take(30)
            else -> friendlyName ?: category.defaultText
        }

    // When there's no friendly name but bracket content exists (e.g., an IP address),
    // show it as the subtitle instead of just the interface class name
    val subtitle =
        if (friendlyName == null && bracketContent.isNotEmpty()) {
            "$interfaceType — $bracketContent"
        } else {
            interfaceType
        }

    return InterfaceInfo(
        icon = category.icon,
        text = displayText,
        subtitle = subtitle,
    )
}
