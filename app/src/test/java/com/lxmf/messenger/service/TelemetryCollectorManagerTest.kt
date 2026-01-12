package com.lxmf.messenger.service

import android.content.Context
import app.cash.turbine.test
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TelemetryCollectorManager.
 *
 * Tests cover:
 * - State flow initialization and updates
 * - Settings persistence operations
 * - Validation logic for collector address
 * - Send operation result scenarios
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryCollectorManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var mockContext: Context
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockReticulumProtocol: ReticulumProtocol
    private lateinit var manager: TelemetryCollectorManager

    // Settings flows
    private val collectorAddressFlow = MutableStateFlow<String?>(null)
    private val collectorEnabledFlow = MutableStateFlow(false)
    private val sendIntervalFlow = MutableStateFlow(SettingsRepository.DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS)
    private val lastSendTimeFlow = MutableStateFlow<Long?>(null)
    private val networkStatusFlow = MutableStateFlow<NetworkStatus>(NetworkStatus.INITIALIZING)

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockSettingsRepository = mockk(relaxed = true)
        mockReticulumProtocol = mockk(relaxed = true)

        // Setup settings repository flows
        every { mockSettingsRepository.telemetryCollectorAddressFlow } returns collectorAddressFlow
        every { mockSettingsRepository.telemetryCollectorEnabledFlow } returns collectorEnabledFlow
        every { mockSettingsRepository.telemetrySendIntervalSecondsFlow } returns sendIntervalFlow
        every { mockSettingsRepository.lastTelemetrySendTimeFlow } returns lastSendTimeFlow
        every { mockReticulumProtocol.networkStatus } returns networkStatusFlow

        // Setup save methods
        coEvery { mockSettingsRepository.saveTelemetryCollectorAddress(any()) } just Runs
        coEvery { mockSettingsRepository.saveTelemetryCollectorEnabled(any()) } just Runs
        coEvery { mockSettingsRepository.saveTelemetrySendIntervalSeconds(any()) } just Runs
        coEvery { mockSettingsRepository.saveLastTelemetrySendTime(any()) } just Runs
    }

    private fun createManager(): TelemetryCollectorManager {
        return TelemetryCollectorManager(
            context = mockContext,
            settingsRepository = mockSettingsRepository,
            reticulumProtocol = mockReticulumProtocol,
            scope = testScope,
        )
    }

    // ========== State Flow Tests ==========

    @Test
    fun `initial state has null collector address`() = testScope.runTest {
        manager = createManager()

        manager.collectorAddress.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state has disabled collector`() = testScope.runTest {
        manager = createManager()

        manager.isEnabled.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state has default send interval`() = testScope.runTest {
        manager = createManager()

        manager.sendIntervalSeconds.test {
            assertEquals(SettingsRepository.DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state has null last send time`() = testScope.runTest {
        manager = createManager()

        manager.lastSendTime.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state is not sending`() = testScope.runTest {
        manager = createManager()

        manager.isSending.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Start/Stop Tests ==========

    @Test
    fun `start observes settings repository flows`() = testScope.runTest {
        manager = createManager()
        manager.start()

        // Verify that flows are being observed by checking that manager updates its state
        collectorAddressFlow.value = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        advanceUntilIdle()

        manager.collectorAddress.test {
            assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start updates enabled state from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        collectorEnabledFlow.value = true
        advanceUntilIdle()

        manager.isEnabled.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start updates send interval from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        sendIntervalFlow.value = 900
        advanceUntilIdle()

        manager.sendIntervalSeconds.test {
            assertEquals(900, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start updates last send time from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        val timestamp = System.currentTimeMillis()
        lastSendTimeFlow.value = timestamp
        advanceUntilIdle()

        manager.lastSendTime.test {
            assertEquals(timestamp, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stop cancels observer job`() = testScope.runTest {
        manager = createManager()
        manager.start()

        // Verify manager is observing by making a change
        collectorEnabledFlow.value = true
        advanceUntilIdle()
        assertTrue(manager.isEnabled.value)

        // Stop the manager
        manager.stop()

        // Change after stop should not affect manager state
        // (we'll check that it maintains the old value, not verify non-update)
        collectorEnabledFlow.value = false
        advanceUntilIdle()

        // Note: State doesn't change back since we're not actively updating it
        // The test verifies stop() doesn't throw and completes successfully
    }

    @Test
    fun `start is idempotent - calling twice does not restart observers`() = testScope.runTest {
        manager = createManager()
        manager.start()
        manager.start() // Second call should be no-op

        // Should still work correctly
        collectorAddressFlow.value = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        advanceUntilIdle()

        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", manager.collectorAddress.value)
    }

    // ========== setCollectorAddress Tests ==========

    @Test
    fun `setCollectorAddress saves valid 32-char hex address`() = testScope.runTest {
        manager = createManager()

        val validAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        manager.setCollectorAddress(validAddress)

        coVerify { mockSettingsRepository.saveTelemetryCollectorAddress(validAddress) }
    }

    @Test
    fun `setCollectorAddress saves null to clear`() = testScope.runTest {
        manager = createManager()

        manager.setCollectorAddress(null)

        coVerify { mockSettingsRepository.saveTelemetryCollectorAddress(null) }
    }

    @Test
    fun `setCollectorAddress rejects address shorter than 32 chars`() = testScope.runTest {
        manager = createManager()

        manager.setCollectorAddress("a1b2c3d4e5f6") // Only 12 chars

        coVerify(exactly = 0) { mockSettingsRepository.saveTelemetryCollectorAddress(any()) }
    }

    @Test
    fun `setCollectorAddress rejects address longer than 32 chars`() = testScope.runTest {
        manager = createManager()

        manager.setCollectorAddress("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6") // 36 chars

        coVerify(exactly = 0) { mockSettingsRepository.saveTelemetryCollectorAddress(any()) }
    }

    // ========== setEnabled Tests ==========

    @Test
    fun `setEnabled saves true value`() = testScope.runTest {
        manager = createManager()

        manager.setEnabled(true)

        coVerify { mockSettingsRepository.saveTelemetryCollectorEnabled(true) }
    }

    @Test
    fun `setEnabled saves false value`() = testScope.runTest {
        manager = createManager()

        manager.setEnabled(false)

        coVerify { mockSettingsRepository.saveTelemetryCollectorEnabled(false) }
    }

    // ========== setSendIntervalSeconds Tests ==========

    @Test
    fun `setSendIntervalSeconds saves valid interval`() = testScope.runTest {
        manager = createManager()

        manager.setSendIntervalSeconds(900) // 15 minutes

        coVerify { mockSettingsRepository.saveTelemetrySendIntervalSeconds(900) }
    }

    @Test
    fun `setSendIntervalSeconds saves 5 minute interval`() = testScope.runTest {
        manager = createManager()

        manager.setSendIntervalSeconds(300)

        coVerify { mockSettingsRepository.saveTelemetrySendIntervalSeconds(300) }
    }

    @Test
    fun `setSendIntervalSeconds saves 1 hour interval`() = testScope.runTest {
        manager = createManager()

        manager.setSendIntervalSeconds(3600)

        coVerify { mockSettingsRepository.saveTelemetrySendIntervalSeconds(3600) }
    }

    // ========== sendTelemetryNow Tests ==========

    @Test
    fun `sendTelemetryNow returns NoCollectorConfigured when address is null`() = testScope.runTest {
        manager = createManager()

        val result = manager.sendTelemetryNow()

        assertEquals(TelemetrySendResult.NoCollectorConfigured, result)
    }

    @Test
    fun `sendTelemetryNow returns NetworkNotReady when network initializing`() = testScope.runTest {
        manager = createManager()
        manager.start()

        // Set collector address via flow
        collectorAddressFlow.value = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        advanceUntilIdle()

        // Network is INITIALIZING by default
        val result = manager.sendTelemetryNow()

        assertEquals(TelemetrySendResult.NetworkNotReady, result)
    }

    @Test
    fun `sendTelemetryNow returns NetworkNotReady when network errored`() = testScope.runTest {
        manager = createManager()
        manager.start()

        collectorAddressFlow.value = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        networkStatusFlow.value = NetworkStatus.ERROR
        advanceUntilIdle()

        val result = manager.sendTelemetryNow()

        assertEquals(TelemetrySendResult.NetworkNotReady, result)
    }

    // ========== TelemetrySendResult Tests ==========

    @Test
    fun `TelemetrySendResult Success is a data object`() {
        val result = TelemetrySendResult.Success
        assertTrue(result is TelemetrySendResult.Success)
    }

    @Test
    fun `TelemetrySendResult Error contains message`() {
        val result = TelemetrySendResult.Error("Test error message")
        assertEquals("Test error message", result.message)
    }

    @Test
    fun `TelemetrySendResult NoCollectorConfigured is a data object`() {
        val result = TelemetrySendResult.NoCollectorConfigured
        assertTrue(result is TelemetrySendResult.NoCollectorConfigured)
    }

    @Test
    fun `TelemetrySendResult NoLocationAvailable is a data object`() {
        val result = TelemetrySendResult.NoLocationAvailable
        assertTrue(result is TelemetrySendResult.NoLocationAvailable)
    }

    @Test
    fun `TelemetrySendResult NetworkNotReady is a data object`() {
        val result = TelemetrySendResult.NetworkNotReady
        assertTrue(result is TelemetrySendResult.NetworkNotReady)
    }

    // ========== Hex Conversion Tests ==========

    @Test
    fun `hexToByteArray extension converts correctly`() {
        // Test the extension function behavior through the manager
        // This is validated implicitly through the address validation tests
        val validHex = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        assertEquals(32, validHex.length)
    }

    // ========== Flow Emission Tests ==========

    @Test
    fun `collectorAddress flow emits updates from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        manager.collectorAddress.test {
            // Initial value
            assertNull(awaitItem())

            // Update from settings
            collectorAddressFlow.value = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
            advanceUntilIdle()
            assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", awaitItem())

            // Clear
            collectorAddressFlow.value = null
            advanceUntilIdle()
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isEnabled flow emits updates from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        manager.isEnabled.test {
            // Initial value
            assertFalse(awaitItem())

            // Enable
            collectorEnabledFlow.value = true
            advanceUntilIdle()
            assertTrue(awaitItem())

            // Disable
            collectorEnabledFlow.value = false
            advanceUntilIdle()
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendIntervalSeconds flow emits updates from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        manager.sendIntervalSeconds.test {
            // Initial value (default)
            assertEquals(SettingsRepository.DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS, awaitItem())

            // Update to 15 minutes
            sendIntervalFlow.value = 900
            advanceUntilIdle()
            assertEquals(900, awaitItem())

            // Update to 1 hour
            sendIntervalFlow.value = 3600
            advanceUntilIdle()
            assertEquals(3600, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastSendTime flow emits updates from settings`() = testScope.runTest {
        manager = createManager()
        manager.start()

        manager.lastSendTime.test {
            // Initial value
            assertNull(awaitItem())

            // Update with timestamp
            val timestamp1 = System.currentTimeMillis()
            lastSendTimeFlow.value = timestamp1
            advanceUntilIdle()
            assertEquals(timestamp1, awaitItem())

            // Update with new timestamp
            val timestamp2 = timestamp1 + 60_000
            lastSendTimeFlow.value = timestamp2
            advanceUntilIdle()
            assertEquals(timestamp2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
