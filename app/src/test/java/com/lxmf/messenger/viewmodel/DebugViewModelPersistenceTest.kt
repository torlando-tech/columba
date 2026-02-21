package com.lxmf.messenger.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DebugInfo process persistence fields.
 * These fields track health check, network monitoring, and maintenance status
 * used for the Process Persistence debug UI section.
 */
class DebugViewModelPersistenceTest {
    @Test
    fun `DebugInfo persistence fields have correct defaults`() {
        val debugInfo = DebugInfo()

        assertEquals(-1L, debugInfo.heartbeatAgeSeconds)
        assertFalse(debugInfo.healthCheckRunning)
        assertFalse(debugInfo.networkMonitorRunning)
        assertFalse(debugInfo.maintenanceRunning)
        assertEquals(-1L, debugInfo.lastLockRefreshAgeSeconds)
        assertEquals(0, debugInfo.failedInterfaceCount)
    }

    @Test
    fun `DebugInfo persistence fields can be set`() {
        val debugInfo =
            DebugInfo(
                heartbeatAgeSeconds = 5L,
                healthCheckRunning = true,
                networkMonitorRunning = true,
                maintenanceRunning = true,
                lastLockRefreshAgeSeconds = 120L,
                failedInterfaceCount = 2,
            )

        assertEquals(5L, debugInfo.heartbeatAgeSeconds)
        assertTrue(debugInfo.healthCheckRunning)
        assertTrue(debugInfo.networkMonitorRunning)
        assertTrue(debugInfo.maintenanceRunning)
        assertEquals(120L, debugInfo.lastLockRefreshAgeSeconds)
        assertEquals(2, debugInfo.failedInterfaceCount)
    }

    @Test
    fun `DebugInfo copy preserves persistence fields`() {
        val original =
            DebugInfo(
                initialized = true,
                heartbeatAgeSeconds = 3L,
                healthCheckRunning = true,
                networkMonitorRunning = true,
                maintenanceRunning = true,
                lastLockRefreshAgeSeconds = 60L,
                failedInterfaceCount = 1,
            )

        val copied = original.copy(error = "Test error")

        assertEquals(3L, copied.heartbeatAgeSeconds)
        assertTrue(copied.healthCheckRunning)
        assertTrue(copied.networkMonitorRunning)
        assertTrue(copied.maintenanceRunning)
        assertEquals(60L, copied.lastLockRefreshAgeSeconds)
        assertEquals(1, copied.failedInterfaceCount)
        assertEquals("Test error", copied.error)
    }

    @Test
    fun `DebugInfo copy can update persistence fields`() {
        val original =
            DebugInfo(
                heartbeatAgeSeconds = 5L,
                healthCheckRunning = true,
            )

        val updated =
            original.copy(
                heartbeatAgeSeconds = 10L,
                healthCheckRunning = false,
            )

        assertEquals(10L, updated.heartbeatAgeSeconds)
        assertFalse(updated.healthCheckRunning)
    }

    @Test
    fun `heartbeatAgeSeconds extraction from debug info map`() {
        val debugInfoMap =
            mapOf(
                "heartbeat_age_seconds" to 7L,
            )

        val heartbeatAgeSeconds = debugInfoMap["heartbeat_age_seconds"] as? Long ?: -1L

        assertEquals(7L, heartbeatAgeSeconds)
    }

    @Test
    fun `heartbeatAgeSeconds defaults to -1 when missing`() {
        val debugInfoMap = mapOf<String, Any>()

        val heartbeatAgeSeconds = debugInfoMap["heartbeat_age_seconds"] as? Long ?: -1L

        assertEquals(-1L, heartbeatAgeSeconds)
    }

    @Test
    fun `healthCheckRunning extraction from debug info map`() {
        val debugInfoMap =
            mapOf(
                "health_check_running" to true,
            )

        val healthCheckRunning = debugInfoMap["health_check_running"] as? Boolean ?: false

        assertTrue(healthCheckRunning)
    }

    @Test
    fun `healthCheckRunning defaults to false when missing`() {
        val debugInfoMap = mapOf<String, Any>()

        val healthCheckRunning = debugInfoMap["health_check_running"] as? Boolean ?: false

        assertFalse(healthCheckRunning)
    }

    @Test
    fun `networkMonitorRunning extraction from debug info map`() {
        val debugInfoMap =
            mapOf(
                "network_monitor_running" to true,
            )

        val networkMonitorRunning = debugInfoMap["network_monitor_running"] as? Boolean ?: false

        assertTrue(networkMonitorRunning)
    }

    @Test
    fun `maintenanceRunning extraction from debug info map`() {
        val debugInfoMap =
            mapOf(
                "maintenance_running" to true,
            )

        val maintenanceRunning = debugInfoMap["maintenance_running"] as? Boolean ?: false

        assertTrue(maintenanceRunning)
    }

    @Test
    fun `lastLockRefreshAgeSeconds extraction from debug info map`() {
        val debugInfoMap =
            mapOf(
                "last_lock_refresh_age_seconds" to 300L,
            )

        val lastLockRefreshAgeSeconds = debugInfoMap["last_lock_refresh_age_seconds"] as? Long ?: -1L

        assertEquals(300L, lastLockRefreshAgeSeconds)
    }

    @Test
    fun `failedInterfaceCount extraction from debug info map`() {
        val debugInfoMap =
            mapOf(
                "failed_interface_count" to 3,
            )

        val failedInterfaceCount = debugInfoMap["failed_interface_count"] as? Int ?: 0

        assertEquals(3, failedInterfaceCount)
    }

    @Test
    fun `full persistence debug info extraction`() {
        val debugInfoMap =
            mapOf(
                "initialized" to true,
                "reticulum_available" to true,
                "heartbeat_age_seconds" to 2L,
                "health_check_running" to true,
                "network_monitor_running" to true,
                "maintenance_running" to true,
                "last_lock_refresh_age_seconds" to 45L,
                "failed_interface_count" to 0,
            )

        val debugInfo =
            DebugInfo(
                initialized = debugInfoMap["initialized"] as? Boolean ?: false,
                reticulumAvailable = debugInfoMap["reticulum_available"] as? Boolean ?: false,
                heartbeatAgeSeconds = debugInfoMap["heartbeat_age_seconds"] as? Long ?: -1L,
                healthCheckRunning = debugInfoMap["health_check_running"] as? Boolean ?: false,
                networkMonitorRunning = debugInfoMap["network_monitor_running"] as? Boolean ?: false,
                maintenanceRunning = debugInfoMap["maintenance_running"] as? Boolean ?: false,
                lastLockRefreshAgeSeconds = debugInfoMap["last_lock_refresh_age_seconds"] as? Long ?: -1L,
                failedInterfaceCount = debugInfoMap["failed_interface_count"] as? Int ?: 0,
            )

        assertTrue(debugInfo.initialized)
        assertTrue(debugInfo.reticulumAvailable)
        assertEquals(2L, debugInfo.heartbeatAgeSeconds)
        assertTrue(debugInfo.healthCheckRunning)
        assertTrue(debugInfo.networkMonitorRunning)
        assertTrue(debugInfo.maintenanceRunning)
        assertEquals(45L, debugInfo.lastLockRefreshAgeSeconds)
        assertEquals(0, debugInfo.failedInterfaceCount)
    }

    @Test
    fun `persistence fields work with all other DebugInfo fields`() {
        val debugInfo =
            DebugInfo(
                initialized = true,
                reticulumAvailable = true,
                storagePath = "/data/app",
                interfaceCount = 2,
                interfaces =
                    listOf(
                        InterfaceInfo("RNode", "ColumbaRNodeInterface", true, null),
                    ),
                transportEnabled = true,
                multicastLockHeld = true,
                wakeLockHeld = true,
                error = null,
                heartbeatAgeSeconds = 1L,
                healthCheckRunning = true,
                networkMonitorRunning = true,
                maintenanceRunning = true,
                lastLockRefreshAgeSeconds = 30L,
                failedInterfaceCount = 0,
            )

        // Verify all fields work together
        assertTrue(debugInfo.initialized)
        assertTrue(debugInfo.reticulumAvailable)
        assertEquals("/data/app", debugInfo.storagePath)
        assertEquals(2, debugInfo.interfaceCount)
        assertEquals(1, debugInfo.interfaces.size)
        assertTrue(debugInfo.transportEnabled)
        assertTrue(debugInfo.multicastLockHeld)
        assertTrue(debugInfo.wakeLockHeld)
        assertEquals(1L, debugInfo.heartbeatAgeSeconds)
        assertTrue(debugInfo.healthCheckRunning)
        assertTrue(debugInfo.networkMonitorRunning)
        assertTrue(debugInfo.maintenanceRunning)
        assertEquals(30L, debugInfo.lastLockRefreshAgeSeconds)
        assertEquals(0, debugInfo.failedInterfaceCount)
    }

    @Test
    fun `heartbeat age -1 indicates no heartbeat received`() {
        val debugInfo = DebugInfo(heartbeatAgeSeconds = -1L)

        // -1 is used to indicate the heartbeat thread hasn't started
        assertEquals(-1L, debugInfo.heartbeatAgeSeconds)
    }

    @Test
    fun `heartbeat age 0 indicates very fresh heartbeat`() {
        val debugInfo = DebugInfo(heartbeatAgeSeconds = 0L)

        // 0 means heartbeat was received within the last second
        assertEquals(0L, debugInfo.heartbeatAgeSeconds)
    }

    @Test
    fun `last lock refresh age -1 indicates never refreshed`() {
        val debugInfo = DebugInfo(lastLockRefreshAgeSeconds = -1L)

        // -1 indicates maintenance manager hasn't refreshed locks yet
        assertEquals(-1L, debugInfo.lastLockRefreshAgeSeconds)
    }
}
