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

fun getReceivingInterfaceInfo(interfaceName: String): ReceivingInterfaceInfo {
    return when {
        interfaceName.contains("AutoInterface", ignoreCase = true) ||
            interfaceName.contains("Auto Discovery", ignoreCase = true) ||
            interfaceName.startsWith("Auto", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.Wifi,
                text = "Local Network",
                subtitle = "Received via automatic local network discovery",
            )
        interfaceName.contains("TCP", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.Cloud,
                text = "TCP/IP",
                subtitle = "Received via TCP network connection",
            )
        interfaceName.contains("BLE", ignoreCase = true) ||
            interfaceName.contains("Bluetooth", ignoreCase = true) ||
            interfaceName.contains("AndroidBle", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.Bluetooth,
                text = "Bluetooth",
                subtitle = "Received via Bluetooth Low Energy",
            )
        interfaceName.contains("RNode", ignoreCase = true) ||
            interfaceName.contains("LoRa", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.CellTower,
                text = "LoRa Radio",
                subtitle = "Received via RNode LoRa radio",
            )
        interfaceName.contains("Serial", ignoreCase = true) ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.SettingsInputAntenna,
                text = "Serial",
                subtitle = "Received via serial interface",
            )
        else ->
            ReceivingInterfaceInfo(
                icon = Icons.Default.SettingsInputAntenna,
                text = interfaceName.take(30),
                subtitle = "Received via network interface",
            )
    }
}
