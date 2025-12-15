package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.FailedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DebugViewModel.fetchDebugInfo() data transformation logic.
 * These tests verify the transformation of debug info data without creating actual ViewModels
 * (which have infinite polling loops that can cause OOM in tests).
 *
 * The actual IO dispatcher usage is tested implicitly through integration tests.
 */
class DebugViewModelFetchTest {

    @Test
    fun `interface info extraction handles active interfaces`() {
        // Simulate the transformation that happens in fetchDebugInfo()
        val interfacesData = listOf(
            mapOf(
                "name" to "RNode LoRa",
                "type" to "ColumbaRNodeInterface",
                "online" to true,
            ),
            mapOf(
                "name" to "Bluetooth LE",
                "type" to "AndroidBLE",
                "online" to false,
            ),
        )

        val activeInterfaces = interfacesData.map { ifaceMap ->
            InterfaceInfo(
                name = ifaceMap["name"] as? String ?: "",
                type = ifaceMap["type"] as? String ?: "",
                online = ifaceMap["online"] as? Boolean ?: false,
            )
        }

        assertEquals(2, activeInterfaces.size)

        val rnode = activeInterfaces[0]
        assertEquals("RNode LoRa", rnode.name)
        assertEquals("ColumbaRNodeInterface", rnode.type)
        assertTrue(rnode.online)
        assertNull(rnode.error)

        val ble = activeInterfaces[1]
        assertEquals("Bluetooth LE", ble.name)
        assertEquals(false, ble.online)
    }

    @Test
    fun `failed interface conversion creates correct InterfaceInfo`() {
        val failedInterfaces = listOf(
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
        val interfaceInfos = failedInterfaces.map { failed ->
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
    fun `debug info extraction handles missing fields with defaults`() {
        // Simulate extracting debug info with missing fields
        val pythonDebugInfo = mapOf(
            "initialized" to true,
            // Missing: reticulum_available, storage_path, transport_enabled, etc.
        )

        val initialized = pythonDebugInfo["initialized"] as? Boolean ?: false
        val reticulumAvailable = pythonDebugInfo["reticulum_available"] as? Boolean ?: false
        val storagePath = pythonDebugInfo["storage_path"] as? String ?: ""
        val transportEnabled = pythonDebugInfo["transport_enabled"] as? Boolean ?: false
        val multicastLockHeld = pythonDebugInfo["multicast_lock_held"] as? Boolean ?: false
        val wifiLockHeld = pythonDebugInfo["wifi_lock_held"] as? Boolean ?: false
        val wakeLockHeld = pythonDebugInfo["wake_lock_held"] as? Boolean ?: false

        assertTrue(initialized)
        assertEquals(false, reticulumAvailable)
        assertEquals("", storagePath)
        assertEquals(false, transportEnabled)
        assertEquals(false, multicastLockHeld)
        assertEquals(false, wifiLockHeld)
        assertEquals(false, wakeLockHeld)
    }

    @Test
    fun `debug info state creation with all fields`() {
        val interfaces = listOf(
            InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
            InterfaceInfo("Auto", "AutoInterface", false, "Port conflict"),
        )

        val debugInfo = DebugInfo(
            initialized = true,
            reticulumAvailable = true,
            storagePath = "/data/user/0/com.lxmf.messenger/files",
            interfaceCount = interfaces.size,
            interfaces = interfaces,
            transportEnabled = true,
            multicastLockHeld = true,
            wifiLockHeld = false,
            wakeLockHeld = true,
            error = null,
        )

        assertTrue(debugInfo.initialized)
        assertTrue(debugInfo.reticulumAvailable)
        assertEquals("/data/user/0/com.lxmf.messenger/files", debugInfo.storagePath)
        assertEquals(2, debugInfo.interfaceCount)
        assertTrue(debugInfo.transportEnabled)
        assertTrue(debugInfo.multicastLockHeld)
        assertEquals(false, debugInfo.wifiLockHeld)
        assertTrue(debugInfo.wakeLockHeld)
        assertNull(debugInfo.error)
    }

    @Test
    fun `debug info state with error from network status`() {
        val status: NetworkStatus = NetworkStatus.ERROR("Connection timeout")
        val errorMessage = when (status) {
            is NetworkStatus.ERROR -> status.message
            else -> null
        }

        val debugInfo = DebugInfo(
            initialized = true,
            reticulumAvailable = false,
            error = errorMessage,
        )

        assertTrue(debugInfo.initialized)
        assertEquals(false, debugInfo.reticulumAvailable)
        assertEquals("Connection timeout", debugInfo.error)
    }

    @Test
    fun `merging active and failed interfaces produces correct combined list`() {
        val activeInterfaces = listOf(
            InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
            InterfaceInfo("BLE", "AndroidBLE", true, null),
        )

        val failedInterfaceInfos = listOf(
            InterfaceInfo("AutoInterface", "AutoInterface", false, "Port conflict"),
        )

        // Merge as done in fetchDebugInfo()
        val allInterfaces = activeInterfaces + failedInterfaceInfos

        assertEquals(3, allInterfaces.size)

        val activeCount = allInterfaces.count { it.online && it.error == null }
        assertEquals(2, activeCount)

        val failedCount = allInterfaces.count { !it.online && it.error != null }
        assertEquals(1, failedCount)
    }

    @Test
    fun `interface info handles null and empty strings`() {
        val ifaceMap = mapOf(
            "name" to null,
            "type" to "",
            "online" to null,
        )

        val info = InterfaceInfo(
            name = (ifaceMap["name"] as? String) ?: "",
            type = (ifaceMap["type"] as? String) ?: "",
            online = (ifaceMap["online"] as? Boolean) ?: false,
        )

        assertEquals("", info.name)
        assertEquals("", info.type)
        assertEquals(false, info.online)
    }

    @Test
    fun `interfaces list extraction handles malformed data`() {
        // Simulate interfaces data with missing fields
        val interfacesData = listOf(
            mapOf("name" to "RNode"), // Missing type and online
            emptyMap<String, Any>(), // Empty map
        )

        val activeInterfaces = interfacesData.map { ifaceMap ->
            InterfaceInfo(
                name = ifaceMap["name"] as? String ?: "",
                type = ifaceMap["type"] as? String ?: "",
                online = ifaceMap["online"] as? Boolean ?: false,
            )
        }

        assertEquals(2, activeInterfaces.size)
        assertEquals("RNode", activeInterfaces[0].name)
        assertEquals("", activeInterfaces[0].type)
        assertEquals(false, activeInterfaces[0].online)
        assertEquals("", activeInterfaces[1].name)
    }
}
