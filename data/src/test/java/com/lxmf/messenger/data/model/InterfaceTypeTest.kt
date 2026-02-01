package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceTypeTest {
    @Test
    fun `fromInterfaceName returns AUTO_INTERFACE for AutoInterface names`() {
        assertEquals(InterfaceType.AUTO_INTERFACE, InterfaceType.fromInterfaceName("AutoInterface[Local]"))
        assertEquals(InterfaceType.AUTO_INTERFACE, InterfaceType.fromInterfaceName("AutoInterface[fe80::1%wlan0]"))
        assertEquals(InterfaceType.AUTO_INTERFACE, InterfaceType.fromInterfaceName("AutoInterface"))
    }

    @Test
    fun `fromInterfaceName returns TCP_CLIENT for TCPClientInterface names`() {
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPClientInterface[192.168.1.100:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPClientInterface[example.com:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPClient"))
    }

    @Test
    fun `fromInterfaceName returns TCP_CLIENT for TCPInterface names`() {
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPInterface[192.168.1.100:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPInterface[Testnet/127.0.0.1:4242]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPInterface"))
    }

    @Test
    fun `fromInterfaceName returns TCP_CLIENT for BackboneInterface names`() {
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("BackboneInterface[noDNS2/193.26.158.230:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("BackboneClientInterface[Beleth RNS Hub]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("BackboneInterface"))
    }

    @Test
    fun `fromInterfaceName returns ANDROID_BLE for BLE interface names`() {
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("AndroidBLEInterface[AA:BB:CC:DD:EE:FF]"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("BLE Device"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("My BLE"))
    }

    @Test
    fun `fromInterfaceName returns ANDROID_BLE for AndroidBLE interface names`() {
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("AndroidBLEInterface"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("MyAndroidBLEDevice"))
    }

    @Test
    fun `fromInterfaceName returns ANDROID_BLE for BLEPeerInterface names`() {
        // BLEPeerInterface is a spawned interface from BLEInterface - contains "ble"
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("BLEPeerInterface[AA:BB:CC:DD:EE:FF]"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("BLEPeerInterface[RNS-1b85e7]"))
    }

    @Test
    fun `fromInterfaceName returns RNODE for RNode interface names`() {
        assertEquals(InterfaceType.RNODE, InterfaceType.fromInterfaceName("RNodeInterface[/dev/ttyUSB0]"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromInterfaceName("RNode LoRa"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromInterfaceName("My RNode Device"))
    }

    @Test
    fun `fromInterfaceName returns UNKNOWN for null`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName(null))
    }

    @Test
    fun `fromInterfaceName returns UNKNOWN for None string`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName("None"))
    }

    @Test
    fun `fromInterfaceName returns UNKNOWN for blank string`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName(""))
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName("   "))
    }

    @Test
    fun `fromInterfaceName returns UNKNOWN for unrecognized names`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName("UnknownInterface"))
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName("SomeOtherDevice"))
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromInterfaceName("UDP"))
    }

    @Test
    fun `fromInterfaceName is case insensitive`() {
        assertEquals(InterfaceType.AUTO_INTERFACE, InterfaceType.fromInterfaceName("autointerface[local]"))
        assertEquals(InterfaceType.AUTO_INTERFACE, InterfaceType.fromInterfaceName("AUTOINTERFACE[LOCAL]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("tcpclientinterface"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPCLIENT"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("tcpinterface"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("TCPINTERFACE"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("backboneinterface"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("BACKBONE"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("BLE"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("ble"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("ANDROIDBLE"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("androidble"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromInterfaceName("rnode"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromInterfaceName("RNODE"))
    }

    @Test
    fun `fromInterfaceName detects embedded interface type names`() {
        // Interface names may be embedded in longer strings (e.g., peer interfaces)
        assertEquals(InterfaceType.AUTO_INTERFACE, InterfaceType.fromInterfaceName("PeerAutoInterface[local]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("PeerTCPClientInterface[host]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromInterfaceName("PeerTCPInterface[host]"))
        assertEquals(InterfaceType.ANDROID_BLE, InterfaceType.fromInterfaceName("PeerBLEInterface[addr]"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromInterfaceName("PeerRNodeInterface[dev]"))
    }

    @Test
    fun `enum values are correct`() {
        assertEquals(5, InterfaceType.entries.size)
        assertEquals(
            setOf(
                InterfaceType.AUTO_INTERFACE,
                InterfaceType.TCP_CLIENT,
                InterfaceType.ANDROID_BLE,
                InterfaceType.RNODE,
                InterfaceType.UNKNOWN,
            ),
            InterfaceType.entries.toSet(),
        )
    }
}
