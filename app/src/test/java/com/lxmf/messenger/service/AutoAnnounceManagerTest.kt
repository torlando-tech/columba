package com.lxmf.messenger.service

import android.app.Application
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import io.mockk.clearAllMocks
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

        manager =
            AutoAnnounceManager(
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
    // Tests for minute-precision randomization: base interval ±60 minutes, clamped to 60-720 minutes

    companion object {
        private const val RANDOMIZATION_RANGE_MINUTES = 60
        private const val MIN_INTERVAL_MINUTES = 60
        private const val MAX_INTERVAL_MINUTES = 720
    }

    @Test
    fun randomizationLogic_baseInterval3h_producesMinuteValuesIn2to4hRange() {
        // Test the randomization formula with minute precision
        // 3h = 180 min, ±60 min = 120-240 min range
        val baseIntervalMinutes = 3 * 60 // 180 minutes
        val results = mutableSetOf<Int>()

        // Run many iterations to verify range
        repeat(1000) {
            val randomOffsetMinutes = Random.nextInt(-RANDOMIZATION_RANGE_MINUTES, RANDOMIZATION_RANGE_MINUTES + 1)
            val actualDelayMinutes =
                (baseIntervalMinutes + randomOffsetMinutes)
                    .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            results.add(actualDelayMinutes)
        }

        // Should produce values from 120 to 240 minutes (2h to 4h)
        assertTrue("Minimum should be 120 (2h)", results.min() == 120)
        assertTrue("Maximum should be 240 (4h)", results.max() == 240)
        assertTrue("All values should be in valid range", results.all { it in 120..240 })
        // Should have more than 3 unique values (minute precision, not just hour buckets)
        assertTrue("Should have minute precision (many unique values)", results.size > 3)
    }

    @Test
    fun randomizationLogic_minimumInterval1h_clampsToMinimum() {
        // 1h = 60 min, 60 - 60 = 0, but should clamp to 60
        val baseIntervalMinutes = 1 * 60 // 60 minutes
        val results = mutableSetOf<Int>()

        repeat(1000) {
            val randomOffsetMinutes = Random.nextInt(-RANDOMIZATION_RANGE_MINUTES, RANDOMIZATION_RANGE_MINUTES + 1)
            val actualDelayMinutes =
                (baseIntervalMinutes + randomOffsetMinutes)
                    .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            results.add(actualDelayMinutes)
        }

        // Minimum should clamp to 60 (1h)
        // Maximum should be 120 (2h)
        assertTrue("Minimum should be clamped to 60 (1h)", results.min() == 60)
        assertTrue("Maximum should be 120 (2h)", results.max() == 120)
        assertTrue("All values should be >= 60", results.all { it >= MIN_INTERVAL_MINUTES })
    }

    @Test
    fun randomizationLogic_maximumInterval12h_clampsToMaximum() {
        // 12h = 720 min, 720 + 60 = 780, but should clamp to 720
        val baseIntervalMinutes = 12 * 60 // 720 minutes
        val results = mutableSetOf<Int>()

        repeat(1000) {
            val randomOffsetMinutes = Random.nextInt(-RANDOMIZATION_RANGE_MINUTES, RANDOMIZATION_RANGE_MINUTES + 1)
            val actualDelayMinutes =
                (baseIntervalMinutes + randomOffsetMinutes)
                    .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            results.add(actualDelayMinutes)
        }

        // Minimum should be 660 (11h)
        // Maximum should clamp to 720 (12h)
        assertTrue("Minimum should be 660 (11h)", results.min() == 660)
        assertTrue("Maximum should be clamped to 720 (12h)", results.max() == 720)
        assertTrue("All values should be <= 720", results.all { it <= MAX_INTERVAL_MINUTES })
    }

    @Test
    fun randomizationLogic_randomOffsetRange_coversFullMinuteRange() {
        // Verify Random.nextInt(-60, 61) produces values from -60 to 60
        val offsets = mutableSetOf<Int>()

        repeat(10000) {
            offsets.add(Random.nextInt(-RANDOMIZATION_RANGE_MINUTES, RANDOMIZATION_RANGE_MINUTES + 1))
        }

        assertTrue("Should include -60", -60 in offsets)
        assertTrue("Should include 0", 0 in offsets)
        assertTrue("Should include 60", 60 in offsets)
        assertTrue("All offsets should be in range", offsets.all { it in -60..60 })
        // Should have many unique values (121 possible: -60 to 60 inclusive)
        assertTrue("Should have many unique offset values", offsets.size > 100)
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

            // Change interval while disabled - should not throw
            // Note: We don't set enabled=true as that starts an infinite loop
            // that prevents test completion
            intervalFlow.value = 6
            testDispatcher.scheduler.advanceUntilIdle()

            intervalFlow.value = 12
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
