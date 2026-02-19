package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.DebugInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for Process Persistence section in ReticulumInfoCard.
 * Tests the display of heartbeat, health check, network monitor, and maintenance status.
 * Uses assertExists() for nodes that may be off-screen in the composable tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityScreenPersistenceTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Process Persistence Section Header ==========

    @Test
    fun reticulumInfoCard_displaysPersistenceSectionHeader() {
        val debugInfo = DebugInfo(reticulumAvailable = true)

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Process Persistence").assertExists()
    }

    // ========== Heartbeat Display Tests ==========

    @Test
    fun reticulumInfoCard_heartbeatNotStarted_displaysNotStarted() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                heartbeatAgeSeconds = -1L,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Heartbeat").assertExists()
        composeTestRule.onNodeWithText("Not started").assertExists()
    }

    @Test
    fun reticulumInfoCard_heartbeatZeroSeconds_displaysZeroSecondsAgo() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                heartbeatAgeSeconds = 0L,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Heartbeat").assertExists()
        composeTestRule.onNodeWithText("0s ago").assertExists()
    }

    @Test
    fun reticulumInfoCard_heartbeatPositive_displaysSecondsAgo() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                heartbeatAgeSeconds = 5L,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Heartbeat").assertExists()
        composeTestRule.onNodeWithText("5s ago").assertExists()
    }

    // ========== Health Check Status Tests ==========

    @Test
    fun reticulumInfoCard_healthCheckRunning_displaysRunning() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                healthCheckRunning = true,
                networkMonitorRunning = true,
                maintenanceRunning = true,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Health Check").assertExists()
        composeTestRule.onAllNodesWithText("✓ Running").assertCountEquals(3)
    }

    @Test
    fun reticulumInfoCard_healthCheckStopped_displaysStopped() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                healthCheckRunning = false,
                networkMonitorRunning = false,
                maintenanceRunning = false,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Health Check").assertExists()
        composeTestRule.onAllNodesWithText("✗ Stopped").assertCountEquals(3)
    }

    // ========== Network Monitor Status Tests ==========

    @Test
    fun reticulumInfoCard_networkMonitorRunning_displaysLabel() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                networkMonitorRunning = true,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Network Monitor").assertExists()
    }

    // ========== Lock Maintenance Status Tests ==========

    @Test
    fun reticulumInfoCard_maintenanceRunning_displaysLabel() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                maintenanceRunning = true,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Lock Maintenance").assertExists()
    }

    // ========== Last Lock Refresh Tests ==========

    @Test
    fun reticulumInfoCard_lastLockRefreshNotYet_displaysNotYet() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                lastLockRefreshAgeSeconds = -1L,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Last Lock Refresh").assertExists()
        composeTestRule.onNodeWithText("Not yet").assertExists()
    }

    @Test
    fun reticulumInfoCard_lastLockRefreshPositive_displaysSecondsAgo() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                lastLockRefreshAgeSeconds = 120L,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Last Lock Refresh").assertExists()
        composeTestRule.onNodeWithText("120s ago").assertExists()
    }

    // ========== Failed Interfaces Tests ==========

    @Test
    fun reticulumInfoCard_noFailedInterfaces_doesNotDisplayRow() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                failedInterfaceCount = 0,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Failed Interfaces").assertDoesNotExist()
    }

    @Test
    fun reticulumInfoCard_hasFailedInterfaces_displaysCount() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                failedInterfaceCount = 2,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Failed Interfaces").assertExists()
        composeTestRule.onNodeWithText("2 (auto-retrying)").assertExists()
    }

    // ========== Full Persistence Section Tests ==========

    @Test
    fun reticulumInfoCard_allPersistenceFieldsRunning_displaysAllLabels() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                heartbeatAgeSeconds = 1L,
                healthCheckRunning = true,
                networkMonitorRunning = true,
                maintenanceRunning = true,
                lastLockRefreshAgeSeconds = 30L,
                failedInterfaceCount = 0,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        // Verify key labels and values exist in the tree
        composeTestRule.onNodeWithText("Process Persistence").assertExists()
        composeTestRule.onNodeWithText("Heartbeat").assertExists()
        composeTestRule.onNodeWithText("1s ago").assertExists()
        composeTestRule.onNodeWithText("Health Check").assertExists()
        composeTestRule.onNodeWithText("Network Monitor").assertExists()
        composeTestRule.onNodeWithText("Lock Maintenance").assertExists()
        composeTestRule.onNodeWithText("Last Lock Refresh").assertExists()
        composeTestRule.onNodeWithText("30s ago").assertExists()
        composeTestRule.onAllNodesWithText("✓ Running").assertCountEquals(3)
    }

    @Test
    fun reticulumInfoCard_allPersistenceFieldsStopped_displaysStoppedStates() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                heartbeatAgeSeconds = -1L,
                healthCheckRunning = false,
                networkMonitorRunning = false,
                maintenanceRunning = false,
                lastLockRefreshAgeSeconds = -1L,
                failedInterfaceCount = 3,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Not started").assertExists()
        composeTestRule.onNodeWithText("Not yet").assertExists()
        composeTestRule.onNodeWithText("Failed Interfaces").assertExists()
        composeTestRule.onNodeWithText("3 (auto-retrying)").assertExists()
        composeTestRule.onAllNodesWithText("✗ Stopped").assertCountEquals(3)
    }

    // ========== Existing Fields Still Work ==========

    @Test
    fun reticulumInfoCard_displaysCardTitle() {
        val debugInfo = DebugInfo(reticulumAvailable = true)

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reticulum Information").assertExists()
    }

    @Test
    fun reticulumInfoCard_displaysRnsAvailable() {
        val debugInfo = DebugInfo(reticulumAvailable = true)

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("RNS Available").assertExists()
        composeTestRule.onNodeWithText("Yes").assertExists()
    }

    @Test
    fun reticulumInfoCard_displaysLockStates() {
        val debugInfo =
            DebugInfo(
                reticulumAvailable = true,
                multicastLockHeld = true,
                wakeLockHeld = true,
            )

        composeTestRule.setContent {
            ReticulumInfoCard(debugInfo = debugInfo)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Multicast Lock").assertExists()
        composeTestRule.onNodeWithText("Wake Lock").assertExists()
        composeTestRule.onAllNodesWithText("✓ Held").assertCountEquals(2)
    }
}
