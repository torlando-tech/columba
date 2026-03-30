package com.lxmf.messenger.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InterfaceInfoTest {
    // ===========================================
    // TCP Interface Tests
    // ===========================================

    @Test
    fun `TCP interface with user-configured name extracts friendly name`() {
        val info = getInterfaceInfo("TCPClientInterface[Sideband Server]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("Sideband Server", info.text)
        assertEquals("TCPClientInterface", info.subtitle)
    }

    @Test
    fun `TCP interface with name and address extracts friendly name before slash`() {
        val info = getInterfaceInfo("TCPClientInterface[Sideband Server/192.168.1.100:4965]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("Sideband Server", info.text)
        assertEquals("TCPClientInterface", info.subtitle)
    }

    @Test
    fun `TCP interface with only address shows address in subtitle`() {
        val info = getInterfaceInfo("TCPClientInterface[192.168.1.100:4965]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface — 192.168.1.100:4965", info.subtitle)
    }

    @Test
    fun `TCP interface without brackets falls back to TCP IP`() {
        val info = getInterfaceInfo("TCPClientInterface")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface", info.subtitle)
    }

    @Test
    fun `TCPInterface variant also recognized`() {
        val info = getInterfaceInfo("TCPInterface[My Server]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("My Server", info.text)
        assertEquals("TCPInterface", info.subtitle)
    }

    // ===========================================
    // BackboneInterface Tests
    // ===========================================

    @Test
    fun `BackboneInterface with user-configured name extracts friendly name`() {
        val info = getInterfaceInfo("BackboneInterface[Beleth RNS Hub]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("Beleth RNS Hub", info.text)
        assertEquals("BackboneInterface", info.subtitle)
    }

    @Test
    fun `BackboneInterface with name and address extracts friendly name before slash`() {
        val info = getInterfaceInfo("BackboneInterface[noDNS2/193.26.158.230:4965]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("noDNS2", info.text)
        assertEquals("BackboneInterface", info.subtitle)
    }

    @Test
    fun `BackboneClientInterface variant also recognized`() {
        val info = getInterfaceInfo("BackboneClientInterface[My Backbone]")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("My Backbone", info.text)
        assertEquals("BackboneClientInterface", info.subtitle)
    }

    @Test
    fun `BackboneInterface without brackets falls back to TCP IP`() {
        val info = getInterfaceInfo("BackboneInterface")

        assertEquals(Icons.Default.Public, info.icon)
        assertEquals("TCP/IP", info.text)
        assertEquals("BackboneInterface", info.subtitle)
    }

    // ===========================================
    // AutoInterface Tests
    // ===========================================

    @Test
    fun `AutoInterface with user name extracts friendly name`() {
        val info = getInterfaceInfo("AutoInterface[Home Network]")

        assertEquals(Icons.Default.Wifi, info.icon)
        assertEquals("Home Network", info.text)
        assertEquals("AutoInterface", info.subtitle)
    }

    @Test
    fun `AutoInterface without brackets falls back to Local Network`() {
        val info = getInterfaceInfo("AutoInterface")

        assertEquals(Icons.Default.Wifi, info.icon)
        assertEquals("Local Network", info.text)
        assertEquals("AutoInterface", info.subtitle)
    }

    @Test
    fun `Auto Discovery variant recognized`() {
        val info = getInterfaceInfo("Auto Discovery[LAN]")

        assertEquals(Icons.Default.Wifi, info.icon)
        assertEquals("LAN", info.text)
        assertEquals("Auto Discovery", info.subtitle)
    }

    // ===========================================
    // Bluetooth/BLE Interface Tests
    // ===========================================

    @Test
    fun `BLE interface with user name extracts friendly name`() {
        val info = getInterfaceInfo("AndroidBleInterface[My Phone]")

        assertEquals(Icons.Default.Bluetooth, info.icon)
        assertEquals("My Phone", info.text)
        assertEquals("AndroidBleInterface", info.subtitle)
    }

    @Test
    fun `Bluetooth interface without brackets falls back to Bluetooth`() {
        val info = getInterfaceInfo("BluetoothInterface")

        assertEquals(Icons.Default.Bluetooth, info.icon)
        assertEquals("Bluetooth", info.text)
        assertEquals("BluetoothInterface", info.subtitle)
    }

    @Test
    fun `AndroidBle variant recognized`() {
        val info = getInterfaceInfo("AndroidBle[Tablet]")

        assertEquals(Icons.Default.Bluetooth, info.icon)
        assertEquals("Tablet", info.text)
        assertEquals("AndroidBle", info.subtitle)
    }

    // ===========================================
    // RNode/LoRa Interface Tests
    // ===========================================

    @Test
    fun `RNode interface with user name extracts friendly name`() {
        val info = getInterfaceInfo("RNodeInterface[My Radio]")

        assertEquals(Icons.Default.CellTower, info.icon)
        assertEquals("My Radio", info.text)
        assertEquals("RNodeInterface", info.subtitle)
    }

    @Test
    fun `RNode connected via BLE categorized as LoRa not Bluetooth`() {
        // RNode connected via BLE gets a name containing both "RNode" and "BLE"
        val info = getInterfaceInfo("ColumbaRNodeInterface[RNode 5A3F BLE]")

        assertEquals(Icons.Default.CellTower, info.icon)
        assertEquals("RNode 5A3F BLE", info.text)
        assertEquals("ColumbaRNodeInterface", info.subtitle)
    }

    @Test
    fun `RNode interface without brackets falls back to LoRa Radio`() {
        val info = getInterfaceInfo("RNodeInterface")

        assertEquals(Icons.Default.CellTower, info.icon)
        assertEquals("LoRa Radio", info.text)
        assertEquals("RNodeInterface", info.subtitle)
    }

    @Test
    fun `LoRa variant recognized`() {
        val info = getInterfaceInfo("LoRaInterface[Field Radio]")

        assertEquals(Icons.Default.CellTower, info.icon)
        assertEquals("Field Radio", info.text)
        assertEquals("LoRaInterface", info.subtitle)
    }

    // ===========================================
    // Serial Interface Tests
    // ===========================================

    @Test
    fun `Serial interface with user name extracts friendly name`() {
        val info = getInterfaceInfo("SerialInterface[USB Modem]")

        assertEquals(Icons.Default.SettingsInputAntenna, info.icon)
        assertEquals("USB Modem", info.text)
        assertEquals("SerialInterface", info.subtitle)
    }

    @Test
    fun `Serial interface without brackets falls back to Serial`() {
        val info = getInterfaceInfo("SerialInterface")

        assertEquals(Icons.Default.SettingsInputAntenna, info.icon)
        assertEquals("Serial", info.text)
        assertEquals("SerialInterface", info.subtitle)
    }

    // ===========================================
    // Unknown Interface Tests
    // ===========================================

    @Test
    fun `Unknown interface with user name extracts friendly name`() {
        val info = getInterfaceInfo("CustomInterface[My Custom]")

        assertEquals(Icons.Default.SettingsInputAntenna, info.icon)
        assertEquals("My Custom", info.text)
        assertEquals("CustomInterface", info.subtitle)
    }

    @Test
    fun `Unknown interface without brackets uses interface name`() {
        val info = getInterfaceInfo("SomeOtherInterface")

        assertEquals(Icons.Default.SettingsInputAntenna, info.icon)
        assertEquals("SomeOtherInterface", info.text)
        assertEquals("SomeOtherInterface", info.subtitle)
    }

    @Test
    fun `Very long unknown interface name is truncated in text`() {
        val longName = "A".repeat(50)
        val info = getInterfaceInfo(longName)

        assertEquals(30, info.text.length)
        assertEquals(longName, info.subtitle)
    }

    // ===========================================
    // Edge Cases
    // ===========================================

    @Test
    fun `Empty brackets returns fallback text`() {
        val info = getInterfaceInfo("TCPClientInterface[]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface", info.subtitle)
    }

    @Test
    fun `Brackets with only address shows address in subtitle`() {
        val info = getInterfaceInfo("TCPClientInterface[10.0.0.1:4242]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface — 10.0.0.1:4242", info.subtitle)
    }

    @Test
    fun `Brackets with IPv6 address shows address in subtitle`() {
        val info = getInterfaceInfo("TCPClientInterface[fe80::1]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface — fe80::1", info.subtitle)
    }

    @Test
    fun `Name with slash extracts part before slash`() {
        val info = getInterfaceInfo("TCPClientInterface[Server Name/10.0.0.1:4242]")

        assertEquals("Server Name", info.text)
        assertEquals("TCPClientInterface", info.subtitle)
    }

    @Test
    fun `Whitespace-only name in brackets shows whitespace in subtitle`() {
        val info = getInterfaceInfo("TCPClientInterface[   ]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface —    ", info.subtitle)
    }

    @Test
    fun `Case insensitive matching for interface types`() {
        val tcpInfo = getInterfaceInfo("tcpclientinterface[Test]")
        assertEquals(Icons.Default.Public, tcpInfo.icon)

        val bleInfo = getInterfaceInfo("ANDROIDBLEINTERFACE[Test]")
        assertEquals(Icons.Default.Bluetooth, bleInfo.icon)

        val autoInfo = getInterfaceInfo("AUTOINTERFACE[Test]")
        assertEquals(Icons.Default.Wifi, autoInfo.icon)
    }

    @Test
    fun `Nested brackets handled correctly`() {
        // Only extract content between first [ and last ]
        val info = getInterfaceInfo("TCPClientInterface[Name[with]brackets]")

        // substringAfter("[") gets "Name[with]brackets]"
        // substringBefore("]") gets "Name[with"
        // No slash, no dots/colons, so it's treated as a name
        assertEquals("Name[with", info.text)
        assertEquals("TCPClientInterface", info.subtitle)
    }

    @Test
    fun `Name with slash but blank before slash shows full bracket in subtitle`() {
        // Test when there's a slash but nothing before it
        val info = getInterfaceInfo("TCPClientInterface[/192.168.1.100:4965]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface — /192.168.1.100:4965", info.subtitle)
    }

    @Test
    fun `Auto prefix without full AutoInterface name recognized`() {
        // Test the startsWith("auto") branch
        val info = getInterfaceInfo("AutoDiscovery[LAN]")

        assertEquals(Icons.Default.Wifi, info.icon)
        assertEquals("LAN", info.text)
        assertEquals("AutoDiscovery", info.subtitle)
    }

    @Test
    fun `IPv6 link-local address starting with fe80 shows address in subtitle`() {
        val info = getInterfaceInfo("TCPClientInterface[fe80::a00:27ff:fe4e:66a1%eth0]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface — fe80::a00:27ff:fe4e:66a1%eth0", info.subtitle)
    }

    @Test
    fun `Address with dot but no colon shows address in subtitle`() {
        // Test looksLikeAddress with dot only (like hostname.local)
        val info = getInterfaceInfo("TCPClientInterface[server.local]")

        assertEquals("TCP/IP", info.text)
        assertEquals("TCPClientInterface — server.local", info.subtitle)
    }

    @Test
    fun `extractInterfaceType with no bracket uses full name`() {
        val info = getInterfaceInfo("SomeInterface")

        assertEquals("SomeInterface", info.subtitle)
    }

    @Test
    fun `Unknown interface shorter than 30 chars shows full name`() {
        val shortName = "ShortUnknown"
        val info = getInterfaceInfo(shortName)

        assertEquals(shortName, info.text)
        assertEquals(shortName, info.subtitle)
    }
}
