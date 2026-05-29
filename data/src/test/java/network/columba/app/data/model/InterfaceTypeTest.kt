package network.columba.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceTypeTest {
    @Test
    fun `fromName returns AUTO for AutoInterface names`() {
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AutoInterface[Local]"))
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AutoInterface[fe80::1%wlan0]"))
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AutoInterface"))
    }

    @Test
    fun `fromName returns AUTO for AutoInterfacePeer names`() {
        // RNS's AutoInterfacePeer is the per-peer child class created when an
        // AutoInterface discovers a remote peer. Its toString() format is
        // `AutoInterfacePeer[wlan0/fe80::…]` and that's what shows up on
        // messages.sentInterface for propagation-link traffic via LAN.
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AutoInterfacePeer[wlan0/fe80::10af:905f:7605:214e]"))
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AutoInterfacePeer"))
    }

    @Test
    fun `fromName returns AUTO for Auto Discovery string`() {
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("Auto Discovery"))
    }

    @Test
    fun `fromName returns TCP_CLIENT for TCPClientInterface names`() {
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPClientInterface[192.168.1.100:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPClientInterface[example.com:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPClient"))
    }

    @Test
    fun `fromName returns TCP_CLIENT for TCPInterface names`() {
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPInterface[192.168.1.100:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPInterface[Testnet/127.0.0.1:4242]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPInterface"))
    }

    @Test
    fun `fromName returns TCP_CLIENT for BackboneInterface names`() {
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("BackboneInterface[noDNS2/193.26.158.230:4965]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("BackboneClientInterface[Beleth RNS Hub]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("BackboneInterface"))
    }

    @Test
    fun `fromName returns TCP_SERVER for TCPServerInterface names`() {
        // TCPServer must be matched BEFORE the looser tcp* patterns,
        // otherwise "TCPServerInterface" gets caught by the
        // "tcpinterface" rule and misclassifies as TCP_CLIENT.
        assertEquals(InterfaceType.TCP_SERVER, InterfaceType.fromName("TCPServerInterface[0.0.0.0:4242]"))
        assertEquals(InterfaceType.TCP_SERVER, InterfaceType.fromName("TCPServerInterface[Listen/192.168.1.5:4965]"))
        assertEquals(InterfaceType.TCP_SERVER, InterfaceType.fromName("TCPServer"))
        assertEquals(InterfaceType.TCP_SERVER, InterfaceType.fromName("tcpserverinterface"))
    }

    @Test
    fun `fromName returns BLE for BLE interface names`() {
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("AndroidBLEInterface[AA:BB:CC:DD:EE:FF]"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("BLE Device"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("My BLE"))
    }

    @Test
    fun `fromName returns BLE for AndroidBLE interface names`() {
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("AndroidBLEInterface"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("MyAndroidBLEDevice"))
    }

    @Test
    fun `fromName returns BLE for BLEPeerInterface names`() {
        // BLEPeerInterface is a spawned interface from BLEInterface - contains "ble"
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("BLEPeerInterface[AA:BB:CC:DD:EE:FF]"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("BLEPeerInterface[RNS-1b85e7]"))
    }

    @Test
    fun `fromName returns RNODE for RNode interface names`() {
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("RNodeInterface[/dev/ttyUSB0]"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("RNode LoRa"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("My RNode Device"))
    }

    @Test
    fun `fromName returns RNODE for BLE-connected RNode interfaces`() {
        // RNode connected via BLE gets a name containing both "RNode" and "BLE"
        // Must resolve to RNODE, not BLE
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("ColumbaRNodeInterface[RNode 5A3F BLE]"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("RNodeInterface[RNode ABC1 BLE]"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("RNodeMultiInterface[/dev/ttyUSB0]"))
    }

    @Test
    fun `fromName recognises legacy stored values for backwards compat`() {
        // Pre-rename DB rows on the `announces.receivingInterfaceType`
        // column will have these literal strings. The parser must still
        // classify them correctly so the announce-stream filter UI works
        // against pre-rename data without requiring a DB migration.
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AUTO_INTERFACE"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("ANDROID_BLE"))
        // And the new canonical storage names round-trip:
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AUTO"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCP_CLIENT"))
        assertEquals(InterfaceType.TCP_SERVER, InterfaceType.fromName("TCP_SERVER"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("BLE"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("RNODE"))
    }

    @Test
    fun `fromName returns UNKNOWN for null`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName(null))
    }

    @Test
    fun `fromName returns UNKNOWN for None string`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName("None"))
    }

    @Test
    fun `fromName returns UNKNOWN for blank string`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName(""))
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName("   "))
    }

    @Test
    fun `fromName returns UNKNOWN for unrecognized names`() {
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName("UnknownInterface"))
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName("SomeOtherDevice"))
        assertEquals(InterfaceType.UNKNOWN, InterfaceType.fromName("UDP"))
    }

    @Test
    fun `fromName is case insensitive`() {
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("autointerface[local]"))
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("AUTOINTERFACE[LOCAL]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("tcpclientinterface"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPCLIENT"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("tcpinterface"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("TCPINTERFACE"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("backboneinterface"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("BACKBONE"))
        assertEquals(InterfaceType.TCP_SERVER, InterfaceType.fromName("tcpserver"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("ble"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("ANDROIDBLE"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("androidble"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("rnode"))
    }

    @Test
    fun `fromName detects embedded interface type names`() {
        // Interface names may be embedded in longer strings (e.g., peer interfaces)
        assertEquals(InterfaceType.AUTO, InterfaceType.fromName("PeerAutoInterface[local]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("PeerTCPClientInterface[host]"))
        assertEquals(InterfaceType.TCP_CLIENT, InterfaceType.fromName("PeerTCPInterface[host]"))
        assertEquals(InterfaceType.BLE, InterfaceType.fromName("PeerBLEInterface[addr]"))
        assertEquals(InterfaceType.RNODE, InterfaceType.fromName("PeerRNodeInterface[dev]"))
    }

    @Test
    fun `enum values are correct`() {
        assertEquals(7, InterfaceType.entries.size)
        assertEquals(
            setOf(
                InterfaceType.AUTO,
                InterfaceType.TCP_CLIENT,
                InterfaceType.TCP_SERVER,
                InterfaceType.BLE,
                InterfaceType.RNODE,
                InterfaceType.SHARED_INSTANCE,
                InterfaceType.UNKNOWN,
            ),
            InterfaceType.entries.toSet(),
        )
    }

    @Test
    fun `shared instance classifier matches the labelLocalInterface output`() {
        // PythonRnsTransportAdmin.labelLocalInterface() emits these exact
        // type strings for `LocalServerInterface` / `LocalClientInterface`.
        // Keep this in lockstep with that helper or the InterfaceTypeIcon
        // / map-pin categorisation downstream silently falls to UNKNOWN.
        assertEquals(InterfaceType.SHARED_INSTANCE, InterfaceType.fromName("Shared Instance (host)"))
        assertEquals(InterfaceType.SHARED_INSTANCE, InterfaceType.fromName("Shared Instance (client)"))
        // Storage-name round-trip.
        assertEquals(InterfaceType.SHARED_INSTANCE, InterfaceType.fromName("SHARED_INSTANCE"))
    }

    @Test
    fun `deprecated fromInterfaceName delegates to fromName`() {
        // Just verify backwards-compat alias still works.
        @Suppress("DEPRECATION")
        assertEquals(InterfaceType.AUTO, InterfaceType.fromInterfaceName("AutoInterface"))
    }
}
