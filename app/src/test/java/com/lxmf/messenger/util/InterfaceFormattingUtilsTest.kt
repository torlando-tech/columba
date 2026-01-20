package com.lxmf.messenger.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceFormattingUtilsTest {

    // ==================== formatFrequency Tests ====================

    @Test
    fun `formatFrequency formats 915 MHz correctly`() {
        val result = InterfaceFormattingUtils.formatFrequency(915_000_000)
        assertEquals("915.000 MHz", result)
    }

    @Test
    fun `formatFrequency formats 433 MHz correctly`() {
        val result = InterfaceFormattingUtils.formatFrequency(433_000_000)
        assertEquals("433.000 MHz", result)
    }

    @Test
    fun `formatFrequency formats fractional MHz correctly`() {
        val result = InterfaceFormattingUtils.formatFrequency(868_500_000)
        assertEquals("868.500 MHz", result)
    }

    @Test
    fun `formatFrequency handles zero`() {
        val result = InterfaceFormattingUtils.formatFrequency(0)
        assertEquals("0.000 MHz", result)
    }

    @Test
    fun `formatFrequency handles very large values`() {
        val result = InterfaceFormattingUtils.formatFrequency(2_400_000_000)
        assertEquals("2400.000 MHz", result)
    }

    @Test
    fun `formatFrequency formats with three decimal places`() {
        val result = InterfaceFormattingUtils.formatFrequency(915_123_456)
        assertEquals("915.123 MHz", result)
    }

    // ==================== formatBandwidth Tests ====================

    @Test
    fun `formatBandwidth formats Hz for values below 1000`() {
        val result = InterfaceFormattingUtils.formatBandwidth(500)
        assertEquals("500 Hz", result)
    }

    @Test
    fun `formatBandwidth formats kHz for values 1000 to 999999`() {
        val result = InterfaceFormattingUtils.formatBandwidth(125_000)
        assertEquals("125.0 kHz", result)
    }

    @Test
    fun `formatBandwidth formats MHz for values 1000000 and above`() {
        val result = InterfaceFormattingUtils.formatBandwidth(1_200_000)
        assertEquals("1.2 MHz", result)
    }

    @Test
    fun `formatBandwidth formats boundary value 1000 as kHz`() {
        val result = InterfaceFormattingUtils.formatBandwidth(1_000)
        assertEquals("1.0 kHz", result)
    }

    @Test
    fun `formatBandwidth formats boundary value 999 as Hz`() {
        val result = InterfaceFormattingUtils.formatBandwidth(999)
        assertEquals("999 Hz", result)
    }

    @Test
    fun `formatBandwidth formats boundary value 1000000 as MHz`() {
        val result = InterfaceFormattingUtils.formatBandwidth(1_000_000)
        assertEquals("1.0 MHz", result)
    }

    @Test
    fun `formatBandwidth formats zero`() {
        val result = InterfaceFormattingUtils.formatBandwidth(0)
        assertEquals("0 Hz", result)
    }

    // ==================== formatBytes Tests ====================

    @Test
    fun `formatBytes formats small values in bytes`() {
        val result = InterfaceFormattingUtils.formatBytes(512)
        assertEquals("512 B", result)
    }

    @Test
    fun `formatBytes formats zero`() {
        val result = InterfaceFormattingUtils.formatBytes(0)
        assertEquals("0 B", result)
    }

    @Test
    fun `formatBytes formats KB for values 1024 to 1048575`() {
        val result = InterfaceFormattingUtils.formatBytes(2048)
        assertEquals("2.0 KB", result)
    }

    @Test
    fun `formatBytes formats MB for values 1048576 to 1073741823`() {
        val result = InterfaceFormattingUtils.formatBytes(1_572_864) // 1.5 MB
        assertEquals("1.5 MB", result)
    }

    @Test
    fun `formatBytes formats GB for values 1073741824 and above`() {
        val result = InterfaceFormattingUtils.formatBytes(2_147_483_648) // 2 GB
        assertEquals("2.00 GB", result)
    }

    @Test
    fun `formatBytes formats boundary value 1023 as bytes`() {
        val result = InterfaceFormattingUtils.formatBytes(1023)
        assertEquals("1023 B", result)
    }

    @Test
    fun `formatBytes formats boundary value 1024 as KB`() {
        val result = InterfaceFormattingUtils.formatBytes(1024)
        assertEquals("1.0 KB", result)
    }

    @Test
    fun `formatBytes formats boundary value 1048575 as KB`() {
        val result = InterfaceFormattingUtils.formatBytes(1_048_575)
        assertEquals("1024.0 KB", result)
    }

    @Test
    fun `formatBytes formats boundary value 1048576 as MB`() {
        val result = InterfaceFormattingUtils.formatBytes(1_048_576)
        assertEquals("1.0 MB", result)
    }

    @Test
    fun `formatBytes formats boundary value 1073741823 as MB`() {
        val result = InterfaceFormattingUtils.formatBytes(1_073_741_823)
        assertEquals("1024.0 MB", result)
    }

    @Test
    fun `formatBytes formats boundary value 1073741824 as GB`() {
        val result = InterfaceFormattingUtils.formatBytes(1_073_741_824)
        assertEquals("1.00 GB", result)
    }

    @Test
    fun `formatBytes uses one decimal place for KB and MB`() {
        val resultKb = InterfaceFormattingUtils.formatBytes(1536) // 1.5 KB
        assertEquals("1.5 KB", resultKb)

        val resultMb = InterfaceFormattingUtils.formatBytes(1_572_864) // 1.5 MB
        assertEquals("1.5 MB", resultMb)
    }

    @Test
    fun `formatBytes uses two decimal places for GB`() {
        val result = InterfaceFormattingUtils.formatBytes(1_610_612_736) // 1.5 GB
        assertEquals("1.50 GB", result)
    }

    // ==================== getConnectionIcon Tests ====================

    @Test
    fun `getConnectionIcon returns TCP WiFi icon for RNode with tcp mode`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("RNode", "tcp")
        assertEquals(Icons.Default.Wifi, icon)
        assertEquals("RNode (TCP/WiFi)", label)
    }

    @Test
    fun `getConnectionIcon returns USB icon for RNode with usb mode`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("RNode", "usb")
        assertEquals(Icons.Default.Usb, icon)
        assertEquals("RNode (USB)", label)
    }

    @Test
    fun `getConnectionIcon returns Bluetooth icon for RNode with ble mode`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("RNode", "ble")
        assertEquals(Icons.Default.Bluetooth, icon)
        assertEquals("RNode (BLE)", label)
    }

    @Test
    fun `getConnectionIcon returns Bluetooth icon for RNode without connection mode`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("RNode", null)
        assertEquals(Icons.Default.Bluetooth, icon)
        assertEquals("RNode (Classic)", label)
    }

    @Test
    fun `getConnectionIcon returns WiFi icon for TCPClient`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("TCPClient", null)
        assertEquals(Icons.Default.Wifi, icon)
        assertEquals("TCP Client", label)
    }

    @Test
    fun `getConnectionIcon returns WiFi icon for TCPServer`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("TCPServer", null)
        assertEquals(Icons.Default.Wifi, icon)
        assertEquals("TCP Server", label)
    }

    @Test
    fun `getConnectionIcon returns Bluetooth icon for AndroidBLE`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("AndroidBLE", null)
        assertEquals(Icons.Default.Bluetooth, icon)
        assertEquals("Bluetooth LE", label)
    }

    @Test
    fun `getConnectionIcon returns SignalCellularAlt icon for AutoInterface`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("AutoInterface", null)
        assertEquals(Icons.Default.SignalCellularAlt, icon)
        assertEquals("Auto Discovery", label)
    }

    @Test
    fun `getConnectionIcon returns WiFi icon for UDP`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("UDP", null)
        assertEquals(Icons.Default.Wifi, icon)
        assertEquals("UDP", label)
    }

    @Test
    fun `getConnectionIcon returns default icon for unknown interface type`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("UnknownType", null)
        assertEquals(Icons.Default.SignalCellularAlt, icon)
        assertEquals("UnknownType", label)
    }

    @Test
    fun `getConnectionIcon ignores connection mode for non-RNode interfaces`() {
        val (icon, label) = InterfaceFormattingUtils.getConnectionIcon("TCPClient", "usb")
        assertEquals(Icons.Default.Wifi, icon)
        assertEquals("TCP Client", label)
    }
}
