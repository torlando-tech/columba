package network.columba.app.rns.host.manager

import android.net.ConnectivityManager
import android.net.Network
import io.mockk.every
import io.mockk.mockk
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tight-assertion tests for `InterfaceTransportFilter`. Each case asserts both size and
 * identity (by name) of the filtered set so behaviour change is caught precisely.
 */
class InterfaceTransportFilterTest {
    @Test
    fun `snapshot with live default but unpublished capabilities is unknown`() {
        val connectivityManager = mockk<ConnectivityManager>()
        val activeNetwork = mockk<Network>()
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns null

        assertEquals(CurrentTransport.UNKNOWN, currentTransportOf(connectivityManager))
    }

    @Test
    fun `snapshot without a default route is none`() {
        val connectivityManager = mockk<ConnectivityManager>()
        every { connectivityManager.activeNetwork } returns null

        assertEquals(CurrentTransport.NONE, currentTransportOf(connectivityManager))
    }

    @Test
    fun `filter anyRestriction passes on all transports`() {
        val cfg = tcp("x", NetworkRestriction.ANY)
        assertEquals(listOf("x"), filterByTransport(listOf(cfg), CurrentTransport.WIFI_LIKE).map { it.name })
        assertEquals(listOf("x"), filterByTransport(listOf(cfg), CurrentTransport.CELLULAR).map { it.name })
        assertEquals(listOf("x"), filterByTransport(listOf(cfg), CurrentTransport.UNKNOWN).map { it.name })
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
        val onUnknown = filterByTransport(listOf(cfg), CurrentTransport.UNKNOWN)
        assertEquals(0, onWifi.size)
        assertEquals(1, onCell.size)
        assertEquals("x", onCell.single().name)
        assertEquals(0, onUnknown.size)
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
        val onUnknown = filterByTransport(listOf(cfg), CurrentTransport.UNKNOWN)
        val onNone = filterByTransport(listOf(cfg), CurrentTransport.NONE)
        assertEquals(1, onCell.size)
        assertEquals("ble", onCell.single().name)
        assertEquals(1, onUnknown.size)
        assertEquals("ble", onUnknown.single().name)
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
        val onUnknown = filterByTransport(listOf(cfg), CurrentTransport.UNKNOWN)
        assertEquals(1, onCell.size)
        assertEquals("rnode-ble", onCell.single().name)
        assertEquals(1, onUnknown.size)
        assertEquals("rnode-ble", onUnknown.single().name)
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
        val onUnknown = filterByTransport(listOf(cfg), CurrentTransport.UNKNOWN)
        assertEquals(1, onWifi.size)
        assertEquals("rnode-usb", onWifi.single().name)
        assertEquals(1, onUnknown.size)
        assertEquals("rnode-usb", onUnknown.single().name)
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

        val onUnknown = filterByTransport(configs, CurrentTransport.UNKNOWN)
        assertEquals(listOf("ble", "rnode-bt"), onUnknown.map { it.name })
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
