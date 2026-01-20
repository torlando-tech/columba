package com.lxmf.messenger.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/**
 * Utility functions for formatting interface statistics and information.
 * Extracted for testability and reusability.
 */
object InterfaceFormattingUtils {

    /**
     * Formats a frequency value in Hz to a human-readable MHz string.
     *
     * @param hz Frequency in hertz
     * @return Formatted string with 3 decimal places (e.g., "915.000 MHz")
     */
    fun formatFrequency(hz: Long): String {
        val mhz = hz / 1_000_000.0
        return String.format(Locale.US, "%.3f MHz", mhz)
    }

    /**
     * Formats a bandwidth value in Hz to a human-readable string.
     * Uses MHz, kHz, or Hz depending on the magnitude.
     *
     * @param hz Bandwidth in hertz
     * @return Formatted string (e.g., "125.0 kHz", "1.2 MHz", "500 Hz")
     */
    fun formatBandwidth(hz: Int): String {
        return when {
            hz >= 1_000_000 -> String.format(Locale.US, "%.1f MHz", hz / 1_000_000.0)
            hz >= 1_000 -> String.format(Locale.US, "%.1f kHz", hz / 1_000.0)
            else -> "$hz Hz"
        }
    }

    /**
     * Formats byte counts to human-readable strings using binary units (1024 base).
     * Uses B, KB, MB, or GB depending on the magnitude.
     *
     * @param bytes Byte count
     * @return Formatted string (e.g., "1.5 MB", "2.34 GB", "512 B")
     */
    @Suppress("ReturnCount") // Early returns for each unit threshold improve readability
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /**
     * Determines the appropriate icon and display label for an interface based on its type
     * and connection mode.
     *
     * @param interfaceType The interface type (e.g., "RNode", "TCPClient", "AndroidBLE")
     * @param connectionMode Optional connection mode for RNode interfaces ("tcp", "usb", "ble")
     * @return Pair of icon and human-readable type label
     */
    fun getConnectionIcon(interfaceType: String, connectionMode: String?): Pair<ImageVector, String> {
        return when {
            interfaceType == "RNode" && connectionMode == "tcp" -> Icons.Default.Wifi to "RNode (TCP/WiFi)"
            interfaceType == "RNode" && connectionMode == "usb" -> Icons.Default.Usb to "RNode (USB)"
            interfaceType == "RNode" && connectionMode == "ble" -> Icons.Default.Bluetooth to "RNode (BLE)"
            interfaceType == "RNode" -> Icons.Default.Bluetooth to "RNode (Classic)"
            interfaceType == "TCPClient" -> Icons.Default.Wifi to "TCP Client"
            interfaceType == "TCPServer" -> Icons.Default.Wifi to "TCP Server"
            interfaceType == "AndroidBLE" -> Icons.Default.Bluetooth to "Bluetooth LE"
            interfaceType == "AutoInterface" -> Icons.Default.SignalCellularAlt to "Auto Discovery"
            interfaceType == "UDP" -> Icons.Default.Wifi to "UDP"
            else -> Icons.Default.SignalCellularAlt to interfaceType
        }
    }
}
