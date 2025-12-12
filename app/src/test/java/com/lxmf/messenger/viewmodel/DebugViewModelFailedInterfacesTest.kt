package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.reticulum.protocol.FailedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DebugViewModel data classes and interface merging logic.
 *
 * Tests that failed interfaces (like AutoInterface port conflicts)
 * are properly represented in the InterfaceInfo data class.
 */
class DebugViewModelFailedInterfacesTest {
    @Test
    fun `InterfaceInfo with error correctly identifies failed interfaces`() {
        val failedInterface =
            InterfaceInfo(
                name = "AutoInterface",
                type = "AutoInterface",
                online = false,
                error = "Port 29716 already in use",
            )

        assertNotNull(failedInterface.error)
        assertEquals("Port 29716 already in use", failedInterface.error)
        assertEquals(false, failedInterface.online)
    }

    @Test
    fun `InterfaceInfo without error represents healthy interface`() {
        val healthyInterface =
            InterfaceInfo(
                name = "RNode",
                type = "ColumbaRNodeInterface",
                online = true,
                error = null,
            )

        assertNull(healthyInterface.error)
        assertTrue(healthyInterface.online)
    }

    @Test
    fun `InterfaceInfo offline without error represents temporary disconnection`() {
        val offlineInterface =
            InterfaceInfo(
                name = "RNode",
                type = "ColumbaRNodeInterface",
                online = false,
                error = null,
            )

        assertNull(offlineInterface.error)
        assertEquals(false, offlineInterface.online)
    }

    @Test
    fun `FailedInterface to InterfaceInfo conversion logic`() {
        // Simulate the conversion that happens in DebugViewModel.fetchDebugInfo()
        val failedInterfaces =
            listOf(
                FailedInterface(
                    name = "AutoInterface",
                    error = "Port 29716 already in use",
                    recoverable = true,
                ),
                FailedInterface(
                    name = "TCPClient",
                    error = "Connection refused",
                    recoverable = false,
                ),
            )

        // Convert to InterfaceInfo as done in fetchDebugInfo()
        val interfaceInfos =
            failedInterfaces.map { failed ->
                InterfaceInfo(
                    name = failed.name,
                    type = failed.name,
                    online = false,
                    error = failed.error,
                )
            }

        assertEquals(2, interfaceInfos.size)

        val autoInterface = interfaceInfos.find { it.name == "AutoInterface" }
        assertNotNull(autoInterface)
        assertEquals("Port 29716 already in use", autoInterface!!.error)
        assertEquals(false, autoInterface.online)

        val tcpClient = interfaceInfos.find { it.name == "TCPClient" }
        assertNotNull(tcpClient)
        assertEquals("Connection refused", tcpClient!!.error)
    }

    @Test
    fun `merging active and failed interfaces`() {
        // Simulate active interfaces from getDebugInfo()
        val activeInterfaces =
            listOf(
                InterfaceInfo(
                    name = "RNode LoRa",
                    type = "ColumbaRNodeInterface",
                    online = true,
                    error = null,
                ),
                InterfaceInfo(
                    name = "Bluetooth LE",
                    type = "AndroidBLE",
                    online = true,
                    error = null,
                ),
            )

        // Simulate failed interfaces from getFailedInterfaces()
        val failedInterfaceInfos =
            listOf(
                InterfaceInfo(
                    name = "AutoInterface",
                    type = "AutoInterface",
                    online = false,
                    error = "Port conflict",
                ),
            )

        // Merge as done in fetchDebugInfo()
        val allInterfaces = activeInterfaces + failedInterfaceInfos

        assertEquals(3, allInterfaces.size)

        // Active interfaces should be online with no error
        val activeCount = allInterfaces.count { it.online && it.error == null }
        assertEquals(2, activeCount)

        // Failed interfaces should be offline with error
        val failedCount = allInterfaces.count { !it.online && it.error != null }
        assertEquals(1, failedCount)
    }

    @Test
    fun `DebugInfo interfaceCount reflects merged interfaces`() {
        val interfaces =
            listOf(
                InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
                InterfaceInfo("BLE", "AndroidBLE", true, null),
                InterfaceInfo("Auto", "AutoInterface", false, "Port conflict"),
            )

        val debugInfo =
            DebugInfo(
                initialized = true,
                reticulumAvailable = true,
                storagePath = "/test",
                interfaceCount = interfaces.size,
                interfaces = interfaces,
                transportEnabled = true,
            )

        assertEquals(3, debugInfo.interfaceCount)
        assertEquals(3, debugInfo.interfaces.size)
    }

    @Test
    fun `interface list correctly separates healthy, offline, and failed`() {
        val interfaces =
            listOf(
                InterfaceInfo("RNode", "ColumbaRNodeInterface", online = true, error = null),
                InterfaceInfo("BLE", "AndroidBLE", online = true, error = null),
                InterfaceInfo("AutoInterface", "AutoInterface", online = false, error = "Port conflict"),
                InterfaceInfo("TCPClient", "TCPClient", online = false, error = null),
            )

        val healthy = interfaces.filter { it.online && it.error == null }
        val failed = interfaces.filter { it.error != null }
        val offline = interfaces.filter { !it.online && it.error == null }

        assertEquals(2, healthy.size)
        assertEquals(1, failed.size)
        assertEquals(1, offline.size)

        assertTrue(healthy.all { it.online })
        assertTrue(failed.all { !it.online })
        assertTrue(offline.all { !it.online })
    }
}
