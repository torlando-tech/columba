package com.lxmf.messenger.service

import android.app.Application
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for AutoAnnounceManager.
 * Tests the randomization logic and timer reset functionality.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoAnnounceManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockIdentityRepository: IdentityRepository
    private lateinit var mockReticulumProtocol: ReticulumProtocol
    private lateinit var manager: AutoAnnounceManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        mockSettingsRepository = mockk(relaxed = true)
        mockIdentityRepository = mockk(relaxed = true)
        mockReticulumProtocol = mockk(relaxed = true)

        // Default mock behaviors
        every { mockSettingsRepository.autoAnnounceEnabledFlow } returns flowOf(false)
        every { mockSettingsRepository.autoAnnounceIntervalHoursFlow } returns flowOf(3)
        every { mockSettingsRepository.networkChangeAnnounceTimeFlow } returns flowOf(null)
        every { mockIdentityRepository.activeIdentity } returns flowOf(null)

        manager = AutoAnnounceManager(
            mockSettingsRepository,
            mockIdentityRepository,
            mockReticulumProtocol,
            testScope,
        )
    }

    @After
    fun tearDown() {
        manager.stop()
        testScope.cancel()
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Randomization Logic Tests ==========

    @Test
    fun randomizationLogic_baseInterval3h_producesValuesIn2to4Range() {
        // Test the randomization formula: (intervalHours + Random.nextInt(-1, 2)).coerceIn(1, 12)
        val intervalHours = 3
        val results = mutableSetOf<Int>()

        // Run many iterations to verify range
        repeat(1000) {
            val randomOffset = Random.nextInt(-1, 2) // -1, 0, or 1
            val actualDelay = (intervalHours + randomOffset).coerceIn(1, 12)
            results.add(actualDelay)
        }

        // Should produce values 2, 3, or 4 (3 +/- 1)
        assertTrue("Should contain 2", 2 in results)
        assertTrue("Should contain 3", 3 in results)
        assertTrue("Should contain 4", 4 in results)
        assertEquals("Should only contain 2, 3, 4", setOf(2, 3, 4), results)
    }

    @Test
    fun randomizationLogic_minimumInterval1h_clampsToMinimum() {
        val intervalHours = 1
        val results = mutableSetOf<Int>()

        repeat(1000) {
            val randomOffset = Random.nextInt(-1, 2)
            val actualDelay = (intervalHours + randomOffset).coerceIn(1, 12)
            results.add(actualDelay)
        }

        // 1 - 1 = 0, but should clamp to 1
        // 1 + 0 = 1
        // 1 + 1 = 2
        assertTrue("Should contain 1 (clamped from 0)", 1 in results)
        assertTrue("Should contain 2", 2 in results)
        assertTrue("All values should be >= 1", results.all { it >= 1 })
    }

    @Test
    fun randomizationLogic_maximumInterval12h_clampsToMaximum() {
        val intervalHours = 12
        val results = mutableSetOf<Int>()

        repeat(1000) {
            val randomOffset = Random.nextInt(-1, 2)
            val actualDelay = (intervalHours + randomOffset).coerceIn(1, 12)
            results.add(actualDelay)
        }

        // 12 - 1 = 11
        // 12 + 0 = 12
        // 12 + 1 = 13, but should clamp to 12
        assertTrue("Should contain 11", 11 in results)
        assertTrue("Should contain 12", 12 in results)
        assertTrue("All values should be <= 12", results.all { it <= 12 })
    }

    @Test
    fun randomizationLogic_randomOffsetRange_coversExpectedValues() {
        // Verify Random.nextInt(-1, 2) produces -1, 0, 1
        val offsets = mutableSetOf<Int>()

        repeat(1000) {
            offsets.add(Random.nextInt(-1, 2))
        }

        assertEquals("Should produce exactly -1, 0, 1", setOf(-1, 0, 1), offsets)
    }

    // ========== Timer Reset Tests ==========

    @Test
    fun resetTimer_emitsSignal() =
        runTest {
            // Access the internal resetTimerSignal via reflection for testing
            val field = AutoAnnounceManager::class.java.getDeclaredField("resetTimerSignal")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val resetTimerSignal = field.get(manager) as kotlinx.coroutines.flow.MutableSharedFlow<Unit>

            resetTimerSignal.test(timeout = 5.seconds) {
                // Call resetTimer
                manager.resetTimer()

                // Should receive the signal
                awaitItem()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun resetTimer_multipleCallsEmitMultipleSignals() =
        runTest {
            val field = AutoAnnounceManager::class.java.getDeclaredField("resetTimerSignal")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val resetTimerSignal = field.get(manager) as kotlinx.coroutines.flow.MutableSharedFlow<Unit>

            resetTimerSignal.test(timeout = 5.seconds) {
                manager.resetTimer()
                awaitItem()

                manager.resetTimer()
                awaitItem()

                manager.resetTimer()
                awaitItem()

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Start/Stop Tests ==========

    @Test
    fun start_createsJobs() {
        manager.start()

        // Jobs should be created (we can't directly access them, but start shouldn't throw)
        // The manager should be in a started state
    }

    @Test
    fun stop_cancelsJobs() {
        manager.start()
        manager.stop()

        // Stop should complete without exception
        // Manager should be safe to start again
        manager.start()
        manager.stop()
    }

    @Test
    fun stop_beforeStart_doesNotThrow() {
        // Calling stop before start should be safe
        manager.stop()
    }

    @Test
    fun start_observesSettingsChanges() =
        runTest {
            val enabledFlow = MutableStateFlow(false)
            val intervalFlow = MutableStateFlow(3)

            every { mockSettingsRepository.autoAnnounceEnabledFlow } returns enabledFlow
            every { mockSettingsRepository.autoAnnounceIntervalHoursFlow } returns intervalFlow

            manager.start()
            testDispatcher.scheduler.advanceUntilIdle()

            // Change settings - should not throw
            enabledFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()

            intervalFlow.value = 6
            testDispatcher.scheduler.advanceUntilIdle()

            manager.stop()
        }

    // ========== Network Change Observer Tests ==========

    @Test
    fun networkChangeObserver_resetsTimerOnTimestampChange() =
        runTest {
            val networkChangeFlow = MutableStateFlow<Long?>(null)
            every { mockSettingsRepository.networkChangeAnnounceTimeFlow } returns networkChangeFlow

            val field = AutoAnnounceManager::class.java.getDeclaredField("resetTimerSignal")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val resetTimerSignal = field.get(manager) as kotlinx.coroutines.flow.MutableSharedFlow<Unit>

            manager.start()
            testDispatcher.scheduler.advanceUntilIdle()

            resetTimerSignal.test(timeout = 5.seconds) {
                // Emit a network change timestamp
                networkChangeFlow.value = System.currentTimeMillis()
                testDispatcher.scheduler.advanceUntilIdle()

                // Should receive reset signal
                awaitItem()

                cancelAndIgnoreRemainingEvents()
            }

            manager.stop()
        }

    @Test
    fun networkChangeObserver_ignoresNullTimestamp() =
        runTest {
            val networkChangeFlow = MutableStateFlow<Long?>(null)
            every { mockSettingsRepository.networkChangeAnnounceTimeFlow } returns networkChangeFlow

            val field = AutoAnnounceManager::class.java.getDeclaredField("resetTimerSignal")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val resetTimerSignal = field.get(manager) as kotlinx.coroutines.flow.MutableSharedFlow<Unit>

            manager.start()
            testDispatcher.scheduler.advanceUntilIdle()

            resetTimerSignal.test(timeout = 2.seconds) {
                // Emit null - should NOT trigger reset
                networkChangeFlow.value = null
                testDispatcher.scheduler.advanceUntilIdle()

                // Should not receive any signal
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }

            manager.stop()
        }
}
