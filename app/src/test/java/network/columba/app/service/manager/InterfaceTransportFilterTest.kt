package network.columba.app.service.manager

import network.columba.app.reticulum.model.InterfaceConfig
import network.columba.app.reticulum.model.NetworkRestriction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tight-assertion tests for `InterfaceTransportFilter`. Each case asserts both size and
 * identity (by name) of the filtered set so behaviour change is caught precisely.
 */
class InterfaceTransportFilterTest {
    @Test
    fun `filter anyRestriction passes on all transports`() {
        val cfg = tcp("x", NetworkRestriction.ANY)
        assertEquals(listOf("x"), filterByTransport(listOf(cfg), CurrentTransport.WIFI_LIKE).map { it.name })
        assertEquals(listOf("x"), filterByTransport(listOf(cfg), CurrentTransport.CELLULAR).map { it.name })
        // NONE drops IP interfaces regardless of restriction — no route, nothing to attach.
        assertEquals(emptyList<String>(), filterByTransport(listOf(cfg), CurrentTransport.NONE).map { it.name })
    }

    @Test
    fun `filter wifiOnly passes on wifiLike drops on cellular`() {
        val cfg = tcp("x", NetworkRestriction.WIFI_ONLY)
        val onWifi = filterByTransport(listOf(cfg), CurrentTransport.WIFI_LIKE)
        val onCell = filterByTransport(listOf(cfg), CurrentTransport.CELLULAR)
        assertEquals(1, onWifi.size)
        assertEquals("x", onWifi.single().name)
        assertEquals(0, onCell.size)
    }

    @Test
    fun `filter cellularOnly drops on wifiLike passes on cellular`() {
        val cfg = tcp("x", NetworkRestriction.CELLULAR_ONLY)
        val onWifi = filterByTransport(listOf(cfg), CurrentTransport.WIFI_LIKE)
        val onCell = filterByTransport(listOf(cfg), CurrentTransport.CELLULAR)
        assertEquals(0, onWifi.size)
        assertEquals(1, onCell.size)
        assertEquals("x", onCell.single().name)
    }

    @Test
    fun `filter androidBle alwaysPasses even when wifiOnly and on cellular`() {
        // Non-IP transports bypass the restriction entirely — the filter is meaningless
        // for them since they don't ride on the IP carrier.
        val cfg =
            InterfaceConfig.AndroidBLE(
                name = "ble",
                networkRestriction = NetworkRestriction.WIFI_ONLY,
            )
        val onCell = filterByTransport(listOf(cfg), CurrentTransport.CELLULAR)
        val onNone = filterByTransport(listOf(cfg), CurrentTransport.NONE)
        assertEquals(1, onCell.size)
        assertEquals("ble", onCell.single().name)
        // BLE survives even NONE — Bluetooth doesn't depend on Android's IP carrier.
        assertEquals(1, onNone.size)
        assertEquals("ble", onNone.single().name)
    }

    @Test
    fun `filter rnodeTcpMode obeys restriction`() {
        val cfg =
            InterfaceConfig.RNode(
                name = "rnode-tcp",
                targetDeviceName = "",
                connectionMode = "tcp",
                tcpHost = "10.0.0.5",
                tcpPort = 7633,
                networkRestriction = NetworkRestriction.WIFI_ONLY,
            )
        assertEquals(1, filterByTransport(listOf(cfg), CurrentTransport.WIFI_LIKE).size)
        assertEquals(0, filterByTransport(listOf(cfg), CurrentTransport.CELLULAR).size)
    }

    @Test
    fun `filter rnodeBleMode bypasses restriction`() {
        val cfg =
            InterfaceConfig.RNode(
                name = "rnode-ble",
                targetDeviceName = "RNode 1234",
                connectionMode = "ble",
                networkRestriction = NetworkRestriction.WIFI_ONLY,
            )
        // connectionMode != "tcp" → not riding IP → restriction ignored.
        val onCell = filterByTransport(listOf(cfg), CurrentTransport.CELLULAR)
        assertEquals(1, onCell.size)
        assertEquals("rnode-ble", onCell.single().name)
    }

    @Test
    fun `filter rnodeUsbMode bypasses restriction`() {
        val cfg =
            InterfaceConfig.RNode(
                name = "rnode-usb",
                targetDeviceName = "",
                connectionMode = "usb",
                usbDeviceId = 42,
                networkRestriction = NetworkRestriction.CELLULAR_ONLY,
            )
        val onWifi = filterByTransport(listOf(cfg), CurrentTransport.WIFI_LIKE)
        assertEquals(1, onWifi.size)
        assertEquals("rnode-usb", onWifi.single().name)
    }

    @Test
    fun `filter mixedList retains correct subset on cellular`() {
        // Realistic mix: home-LAN TCP wifi-only, public TCP cellular-only, BLE always,
        // RNode-BLE always. On cellular only the cellular-only TCP and the two BLE
        // entries should survive.
        val configs =
            listOf(
                tcp("home-lan", NetworkRestriction.WIFI_ONLY),
                tcp("public", NetworkRestriction.CELLULAR_ONLY),
                InterfaceConfig.AndroidBLE(name = "ble"),
                InterfaceConfig.RNode(
                    name = "rnode-bt",
                    targetDeviceName = "RNode 99",
                    connectionMode = "classic",
                ),
            )
        val onCell = filterByTransport(configs, CurrentTransport.CELLULAR)
        assertEquals(listOf("public", "ble", "rnode-bt"), onCell.map { it.name })

        val onWifi = filterByTransport(configs, CurrentTransport.WIFI_LIKE)
        assertEquals(listOf("home-lan", "ble", "rnode-bt"), onWifi.map { it.name })
    }

    private fun tcp(
        name: String,
        restriction: NetworkRestriction,
    ): InterfaceConfig.TCPClient =
        InterfaceConfig.TCPClient(
            name = name,
            targetHost = "10.0.0.1",
            targetPort = 4242,
            networkRestriction = restriction,
        )
}
