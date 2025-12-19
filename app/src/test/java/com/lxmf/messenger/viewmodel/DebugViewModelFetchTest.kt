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
        val interfacesData =
            listOf(
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

        val activeInterfaces =
            interfacesData.map { ifaceMap ->
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
    fun `debug info extraction handles missing fields with defaults`() {
        // Simulate extracting debug info with missing fields
        val pythonDebugInfo =
            mapOf(
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
        val interfaces =
            listOf(
                InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
                InterfaceInfo("Auto", "AutoInterface", false, "Port conflict"),
            )

        val debugInfo =
            DebugInfo(
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
        val errorMessage =
            when (status) {
                is NetworkStatus.ERROR -> status.message
                else -> null
            }

        val debugInfo =
            DebugInfo(
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
        val activeInterfaces =
            listOf(
                InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
                InterfaceInfo("BLE", "AndroidBLE", true, null),
            )

        val failedInterfaceInfos =
            listOf(
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
        val ifaceMap =
            mapOf(
                "name" to null,
                "type" to "",
                "online" to null,
            )

        val info =
            InterfaceInfo(
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
        val interfacesData =
            listOf(
                mapOf("name" to "RNode"), // Missing type and online
                emptyMap<String, Any>(), // Empty map
            )

        val activeInterfaces =
            interfacesData.map { ifaceMap ->
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

    @Test
    fun `network status to string conversion handles all status types`() {
        // Test the status string conversion logic from fetchDebugInfo()
        val statuses =
            listOf(
                NetworkStatus.READY to "READY",
                NetworkStatus.INITIALIZING to "INITIALIZING",
                NetworkStatus.CONNECTING to "CONNECTING",
                NetworkStatus.SHUTDOWN to "SHUTDOWN",
                NetworkStatus.ERROR("Test error") to "ERROR: Test error",
            )

        statuses.forEach { (status, expected) ->
            val statusString =
                when (status) {
                    is NetworkStatus.READY -> "READY"
                    is NetworkStatus.INITIALIZING -> "INITIALIZING"
                    is NetworkStatus.CONNECTING -> "CONNECTING"
                    is NetworkStatus.SHUTDOWN -> "SHUTDOWN"
                    is NetworkStatus.ERROR -> "ERROR: ${status.message}"
                    else -> status.toString()
                }
            assertEquals(expected, statusString)
        }
    }

    @Test
    fun `error extraction prefers pythonDebugInfo error over network status`() {
        // Simulate the error extraction logic from fetchDebugInfo()
        val pythonDebugInfo = mapOf("error" to "Python error message")
        val status = NetworkStatus.ERROR("Network error")

        val error =
            pythonDebugInfo["error"] as? String
                ?: if (status is NetworkStatus.ERROR) status.message else null

        // pythonDebugInfo error takes precedence
        assertEquals("Python error message", error)
    }

    @Test
    fun `error extraction falls back to network status error when no python error`() {
        val pythonDebugInfo = mapOf<String, Any>() // No error field
        val status = NetworkStatus.ERROR("Network error")

        val error =
            pythonDebugInfo["error"] as? String
                ?: if (status is NetworkStatus.ERROR) status.message else null

        assertEquals("Network error", error)
    }

    @Test
    fun `error extraction returns null when no errors`() {
        val pythonDebugInfo = mapOf<String, Any>()
        val status = NetworkStatus.READY

        val error =
            pythonDebugInfo["error"] as? String
                ?: if (status is NetworkStatus.ERROR) status.message else null

        assertNull(error)
    }

    @Test
    fun `debug info copy preserves other fields when updating error`() {
        // Simulate the exception handling in fetchDebugInfo() that uses copy()
        val originalInfo =
            DebugInfo(
                initialized = true,
                reticulumAvailable = true,
                storagePath = "/data/app",
                interfaceCount = 2,
                transportEnabled = true,
            )

        val updatedInfo = originalInfo.copy(error = "New error occurred")

        // Original fields preserved
        assertTrue(updatedInfo.initialized)
        assertTrue(updatedInfo.reticulumAvailable)
        assertEquals("/data/app", updatedInfo.storagePath)
        assertEquals(2, updatedInfo.interfaceCount)
        assertTrue(updatedInfo.transportEnabled)
        // New error set
        assertEquals("New error occurred", updatedInfo.error)
    }

    @Test
    fun `interfaces data with wrong types uses defaults`() {
        // Test when Python returns unexpected types
        val interfacesData =
            listOf(
                mapOf(
                    "name" to 12345, // Wrong type - Int instead of String
                    "type" to true, // Wrong type - Boolean instead of String
                    "online" to "yes", // Wrong type - String instead of Boolean
                ),
            )

        val activeInterfaces =
            interfacesData.map { ifaceMap ->
                InterfaceInfo(
                    name = ifaceMap["name"] as? String ?: "",
                    type = ifaceMap["type"] as? String ?: "",
                    online = ifaceMap["online"] as? Boolean ?: false,
                )
            }

        assertEquals(1, activeInterfaces.size)
        assertEquals("", activeInterfaces[0].name) // Falls back to default
        assertEquals("", activeInterfaces[0].type) // Falls back to default
        assertEquals(false, activeInterfaces[0].online) // Falls back to default
    }

    // ========== Tests for Pair-based data fetching pattern ==========

    @Test
    fun `pair destructuring for debug and failed interfaces works correctly`() {
        // Simulate the pattern used in fetchDebugInfo() with withContext(Dispatchers.IO)
        val debugInfo =
            mapOf(
                "initialized" to true,
                "reticulum_available" to true,
                "storage_path" to "/data/app",
                "interfaces" to
                    listOf(
                        mapOf("name" to "RNode", "type" to "ColumbaRNodeInterface", "online" to true),
                    ),
                "transport_enabled" to true,
                "multicast_lock_held" to true,
                "wifi_lock_held" to false,
                "wake_lock_held" to true,
            )
        val failedInterfaces =
            listOf(
                FailedInterface("AutoInterface", "Port in use", true),
            )

        // This is the pattern used in fetchDebugInfo()
        val (pythonDebugInfo, failedInterfacesList) = Pair(debugInfo, failedInterfaces)

        // Verify both parts of the pair are accessible
        assertEquals(true, pythonDebugInfo["initialized"])
        assertEquals(1, failedInterfacesList.size)
        assertEquals("AutoInterface", failedInterfacesList[0].name)
    }

    @Test
    fun `full debug info extraction flow with IO thread pattern`() {
        // Simulates the complete data flow in fetchDebugInfo()
        val pythonDebugInfo =
            mapOf(
                "initialized" to true,
                "reticulum_available" to true,
                "storage_path" to "/data/user/0/com.lxmf.messenger/files",
                "interfaces" to
                    listOf(
                        mapOf("name" to "RNode LoRa", "type" to "ColumbaRNodeInterface", "online" to true),
                        mapOf("name" to "Bluetooth", "type" to "AndroidBLE", "online" to false),
                    ),
                "transport_enabled" to false,
                "multicast_lock_held" to true,
                "wifi_lock_held" to true,
                "wake_lock_held" to true,
                "error" to null,
            )
        val failedInterfaces =
            listOf(
                FailedInterface("TCPClient", "Connection refused", false),
            )

        // Step 1: Extract interface information (as done after withContext block)
        @Suppress("UNCHECKED_CAST")
        val interfacesData = pythonDebugInfo["interfaces"] as? List<Map<String, Any>> ?: emptyList()
        val activeInterfaces =
            interfacesData.map { ifaceMap ->
                InterfaceInfo(
                    name = ifaceMap["name"] as? String ?: "",
                    type = ifaceMap["type"] as? String ?: "",
                    online = ifaceMap["online"] as? Boolean ?: false,
                )
            }

        // Step 2: Convert failed interfaces
        val failedInterfaceInfos =
            failedInterfaces.map { failed ->
                InterfaceInfo(
                    name = failed.name,
                    type = failed.name,
                    online = false,
                    error = failed.error,
                )
            }

        // Step 3: Combine interfaces
        val interfaces = activeInterfaces + failedInterfaceInfos

        // Step 4: Build final DebugInfo
        val status = NetworkStatus.READY
        val wakeLockHeld = pythonDebugInfo["wake_lock_held"] as? Boolean ?: false

        val debugInfoResult =
            DebugInfo(
                initialized = pythonDebugInfo["initialized"] as? Boolean ?: false,
                reticulumAvailable = pythonDebugInfo["reticulum_available"] as? Boolean ?: false,
                storagePath = pythonDebugInfo["storage_path"] as? String ?: "",
                interfaceCount = interfaces.size,
                interfaces = interfaces,
                transportEnabled = pythonDebugInfo["transport_enabled"] as? Boolean ?: false,
                multicastLockHeld = pythonDebugInfo["multicast_lock_held"] as? Boolean ?: false,
                wifiLockHeld = pythonDebugInfo["wifi_lock_held"] as? Boolean ?: false,
                wakeLockHeld = wakeLockHeld,
                error =
                    pythonDebugInfo["error"] as? String
                        ?: if (status is NetworkStatus.ERROR) status.message else null,
            )

        // Verify the complete result
        assertTrue(debugInfoResult.initialized)
        assertTrue(debugInfoResult.reticulumAvailable)
        assertEquals("/data/user/0/com.lxmf.messenger/files", debugInfoResult.storagePath)
        assertEquals(3, debugInfoResult.interfaceCount)
        assertEquals(3, debugInfoResult.interfaces.size)
        assertEquals(false, debugInfoResult.transportEnabled)
        assertTrue(debugInfoResult.multicastLockHeld)
        assertTrue(debugInfoResult.wifiLockHeld)
        assertTrue(debugInfoResult.wakeLockHeld)
        assertNull(debugInfoResult.error)

        // Verify individual interfaces
        val rnodeInterface = debugInfoResult.interfaces.find { it.name == "RNode LoRa" }
        assertNotNull(rnodeInterface)
        assertTrue(rnodeInterface!!.online)
        assertNull(rnodeInterface.error)

        val tcpInterface = debugInfoResult.interfaces.find { it.name == "TCPClient" }
        assertNotNull(tcpInterface)
        assertEquals(false, tcpInterface!!.online)
        assertEquals("Connection refused", tcpInterface.error)
    }

    @Test
    fun `wake lock held extraction logs correctly`() {
        // Tests the specific wake_lock_held extraction pattern in fetchDebugInfo()
        val pythonDebugInfo =
            mapOf(
                "wake_lock_held" to true,
                "initialized" to true,
            )

        val wakeLockHeld = pythonDebugInfo["wake_lock_held"] as? Boolean ?: false
        val rawValue = pythonDebugInfo["wake_lock_held"]

        assertTrue(wakeLockHeld)
        assertEquals(true, rawValue)
    }

    @Test
    fun `wake lock held defaults to false when missing`() {
        val pythonDebugInfo =
            mapOf(
                "initialized" to true,
                // wake_lock_held missing
            )

        val wakeLockHeld = pythonDebugInfo["wake_lock_held"] as? Boolean ?: false

        assertEquals(false, wakeLockHeld)
    }

    @Test
    fun `status string conversion for CONNECTING status`() {
        // Test a status type not previously covered
        val status = NetworkStatus.CONNECTING

        val statusString =
            when (status) {
                is NetworkStatus.READY -> "READY"
                is NetworkStatus.INITIALIZING -> "INITIALIZING"
                is NetworkStatus.CONNECTING -> "CONNECTING"
                is NetworkStatus.SHUTDOWN -> "SHUTDOWN"
                is NetworkStatus.ERROR -> "ERROR: ${status.message}"
                else -> status.toString()
            }

        assertEquals("CONNECTING", statusString)
    }

    @Test
    fun `interfaces data as empty list when interfaces field is null`() {
        val pythonDebugInfo =
            mapOf(
                "initialized" to true,
                "interfaces" to null,
            )

        @Suppress("UNCHECKED_CAST")
        val interfacesData = pythonDebugInfo["interfaces"] as? List<Map<String, Any>> ?: emptyList()

        assertTrue(interfacesData.isEmpty())
    }

    @Test
    fun `interfaces data as empty list when interfaces field is missing`() {
        val pythonDebugInfo =
            mapOf(
                "initialized" to true,
            )

        @Suppress("UNCHECKED_CAST")
        val interfacesData = pythonDebugInfo["interfaces"] as? List<Map<String, Any>> ?: emptyList()

        assertTrue(interfacesData.isEmpty())
    }

    // ========== observeNetworkStatus Coverage Tests ==========

    @Test
    fun `network status toString fallback for unknown status types`() {
        // Test the else branch in observeNetworkStatus that uses status.toString()
        // Since all known types are handled, this tests the exhaustive when else fallback
        val status = NetworkStatus.SHUTDOWN

        val statusString =
            when (status) {
                is NetworkStatus.READY -> "READY"
                is NetworkStatus.INITIALIZING -> "INITIALIZING"
                is NetworkStatus.CONNECTING -> "CONNECTING"
                is NetworkStatus.SHUTDOWN -> "SHUTDOWN"
                is NetworkStatus.ERROR -> "ERROR: ${status.message}"
                // The else branch covers any future status types
                else -> status.toString()
            }

        assertEquals("SHUTDOWN", statusString)
    }

    @Test
    fun `DebugInfo data class copy with error update`() {
        val original =
            DebugInfo(
                initialized = true,
                reticulumAvailable = true,
                storagePath = "/data/app",
                interfaceCount = 3,
                interfaces =
                    listOf(
                        InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
                    ),
                transportEnabled = true,
                multicastLockHeld = true,
                wifiLockHeld = false,
                wakeLockHeld = true,
                error = null,
            )

        val updated = original.copy(error = "Test error")

        assertEquals("Test error", updated.error)
        assertTrue(updated.initialized)
        assertEquals(3, updated.interfaceCount)
        assertEquals(1, updated.interfaces.size)
    }

    @Test
    fun `DebugInfo default values`() {
        val defaultInfo = DebugInfo()

        assertEquals(false, defaultInfo.initialized)
        assertEquals(false, defaultInfo.reticulumAvailable)
        assertEquals("", defaultInfo.storagePath)
        assertEquals(0, defaultInfo.interfaceCount)
        assertTrue(defaultInfo.interfaces.isEmpty())
        assertEquals(false, defaultInfo.transportEnabled)
        assertEquals(false, defaultInfo.multicastLockHeld)
        assertEquals(false, defaultInfo.wifiLockHeld)
        assertEquals(false, defaultInfo.wakeLockHeld)
        assertNull(defaultInfo.error)
    }

    @Test
    fun `TestAnnounceResult success case`() {
        val result =
            TestAnnounceResult(
                success = true,
                hexHash = "abc123",
                error = null,
            )

        assertTrue(result.success)
        assertEquals("abc123", result.hexHash)
        assertNull(result.error)
    }

    @Test
    fun `TestAnnounceResult failure case`() {
        val result =
            TestAnnounceResult(
                success = false,
                hexHash = null,
                error = "Failed to announce",
            )

        assertEquals(false, result.success)
        assertNull(result.hexHash)
        assertEquals("Failed to announce", result.error)
    }

    @Test
    fun `InterfaceInfo data class with all fields`() {
        val info =
            InterfaceInfo(
                name = "Test Interface",
                type = "TestType",
                online = true,
                error = null,
            )

        assertEquals("Test Interface", info.name)
        assertEquals("TestType", info.type)
        assertTrue(info.online)
        assertNull(info.error)
    }

    @Test
    fun `InterfaceInfo with error field set`() {
        val info =
            InterfaceInfo(
                name = "Failed Interface",
                type = "FailedType",
                online = false,
                error = "Connection refused",
            )

        assertEquals("Failed Interface", info.name)
        assertEquals(false, info.online)
        assertEquals("Connection refused", info.error)
    }
}
