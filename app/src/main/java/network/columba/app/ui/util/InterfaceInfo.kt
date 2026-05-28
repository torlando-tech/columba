package network.columba.app.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import network.columba.app.data.model.InterfaceType

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
    val markerIconResId: Int,
    val defaultText: String,
    val markerColor: Int,
) {
    AUTO(Icons.Default.Wifi, com.composables.icons.lucide.R.drawable.lucide_ic_wifi, "Local Network", 0xFF2E7D32.toInt()),
    TCP(Icons.Default.Public, network.columba.app.R.drawable.ic_public_24, "TCP/IP", 0xFF1565C0.toInt()),
    BLUETOOTH(Icons.Default.Bluetooth, com.composables.icons.lucide.R.drawable.lucide_ic_bluetooth, "Bluetooth", 0xFF283593.toInt()),
    LORA(Icons.Default.CellTower, com.composables.icons.lucide.R.drawable.lucide_ic_antenna, "LoRa Radio", 0xFFE64A19.toInt()),
    I2P(Icons.Default.Cloud, network.columba.app.R.drawable.ic_incognito_24, "I2P", 0xFF7B1FA2.toInt()),
    YGGDRASIL(Icons.Default.Cloud, com.composables.icons.lucide.R.drawable.lucide_ic_tree_pine, "Yggdrasil", 0xFF00695C.toInt()),
    SERIAL(Icons.Default.SettingsInputAntenna, com.composables.icons.lucide.R.drawable.lucide_ic_antenna, "Serial", 0xFF616161.toInt()),
    UNKNOWN(Icons.Default.SettingsInputAntenna, com.composables.icons.lucide.R.drawable.lucide_ic_antenna, "", 0xFF9E9E9E.toInt()),
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
internal fun categorizeInterface(interfaceName: String): InterfaceCategory = categorizeInterface(interfaceName, host = null)

/**
 * Determine the interface category for UI display purposes (icon, color,
 * marker style) based on the interface name and optional host.
 *
 * Sources the transport classification from the canonical
 * [InterfaceType] (single source of truth — see
 * `network.columba.app.data.model.InterfaceType.fromName`), then layers
 * on UI-only sub-categories ([InterfaceCategory.YGGDRASIL] from host
 * inspection, [InterfaceCategory.I2P] / [InterfaceCategory.SERIAL] from
 * legacy substring matches that don't map to a configurable
 * [InterfaceType]).
 *
 * `host` is used to distinguish Yggdrasil TCP traffic (IPv6 in 0200::/7)
 * from regular TCP for the map-pin coloring.
 */
internal fun categorizeInterface(
    interfaceName: String,
    host: String?,
): InterfaceCategory {
    val lowerName = interfaceName.lowercase()

    // UI-only sub-categories not represented in the canonical enum.
    // Checked first because their substrings can also match the broader
    // TCP / BLE rules below (e.g. "i2p" interfaces are TCP under the hood).
    if (lowerName.contains("i2p")) return InterfaceCategory.I2P
    if (lowerName.contains("serial")) return InterfaceCategory.SERIAL

    return when (InterfaceType.fromName(interfaceName)) {
        InterfaceType.AUTO -> InterfaceCategory.AUTO
        InterfaceType.TCP_CLIENT,
        InterfaceType.TCP_SERVER,
        -> if (isYggdrasilHost(host)) InterfaceCategory.YGGDRASIL else InterfaceCategory.TCP
        InterfaceType.BLE -> InterfaceCategory.BLUETOOTH
        InterfaceType.RNODE -> InterfaceCategory.LORA
        // Shared-instance loopback (LocalServer/Client) is host-local TCP
        // by construction — same kernel path as a 127.0.0.1 TCP socket.
        // Map-pin categorisation has no useful coordinates for it, but
        // grouping with TCP keeps the announce-detail UI honest about
        // what's underneath. The list-row icon comes from the dedicated
        // SHARED_INSTANCE branch in `interfaceTypeIconData`, not this
        // category mapping.
        InterfaceType.SHARED_INSTANCE -> InterfaceCategory.TCP
        InterfaceType.UNKNOWN -> InterfaceCategory.UNKNOWN
    }
}

/**
 * Check if a host address belongs to the Yggdrasil network (IPv6 in 0200::/7 space).
 */
private fun isYggdrasilHost(host: String?): Boolean {
    if (host == null) return false
    val clean = host.trim().removePrefix("[").removeSuffix("]")
    val firstSegment = clean.takeIf { it.contains(":") }?.split(":")?.firstOrNull()
    val value = firstSegment?.toIntOrNull(16) ?: return false
    return value in 0x0200..0x03FF
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
