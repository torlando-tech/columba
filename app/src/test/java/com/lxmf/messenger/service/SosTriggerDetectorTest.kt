package com.lxmf.messenger.service

import android.content.Context
import com.lxmf.messenger.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SosTriggerDetector] spike-based tap detection and shake detection.
 *
 * Tests call [handleTap]/[handleShake] directly with controlled timestamps,
 * bypassing the sensor layer. This isolates the detection state machines from
 * Android sensor APIs.
 */
class SosTriggerDetectorTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sosManager: SosManager
    private lateinit var detector: SosTriggerDetector

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        settingsRepository = mockk()
        sosManager = mockk()

        every { sosManager.trigger() } just Runs
        every { sosManager.state } returns MutableStateFlow(SosState.Idle)

        detector = SosTriggerDetector(context, settingsRepository, sosManager)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Tap Detection — Spike-Based State Machine ==========

    /**
     * Simulate a single tap: rising edge (above threshold) → falling edge (below threshold).
     * @param startTime timestamp of the rising edge
     * @param spikeDurationMs how long the spike lasts (must be ≤100ms for a valid tap)
     * @param peakAcceleration net acceleration during the spike (must be > 4.0 m/s²)
     */
    private fun simulateTap(
        startTime: Long,
        spikeDurationMs: Long = 50,
        peakAcceleration: Float = 6.0f,
    ) {
        // Rising edge
        detector.handleTap(peakAcceleration, startTime)
        // Falling edge
        detector.handleTap(1.0f, startTime + spikeDurationMs)
    }

    /**
     * Simulate N taps spaced evenly apart.
     * @return the timestamp after the last tap's falling edge
     */
    private fun simulateNTaps(
        count: Int,
        startTime: Long = 10_000L,
        intervalMs: Long = 300L,
        spikeDurationMs: Long = 50,
    ): Long {
        var t = startTime
        repeat(count) {
            simulateTap(t, spikeDurationMs)
            t += intervalMs
        }
        return t
    }

    @Test
    fun `single tap does not trigger SOS`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        simulateTap(startTime = 10_000L)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `two taps do not trigger when three required`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        simulateNTaps(count = 2)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `three taps within window triggers SOS`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        simulateNTaps(count = 3)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `five taps triggers SOS when required count is 5`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 5

        simulateNTaps(count = 5)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `four taps do not trigger when five required`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 5

        simulateNTaps(count = 4)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `taps outside 2500ms window do not accumulate`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        // Two taps at the beginning
        simulateTap(startTime = 10_000L)
        simulateTap(startTime = 10_300L)

        // Third tap way outside the window (>2500ms later)
        simulateTap(startTime = 13_000L)

        // Only 1 tap in window (the third one), first two expired
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `long spike is rejected as walking step`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        val t = 10_000L
        // Three "taps" but each spike lasts 150ms (>MAX_TAP_SPIKE_MS of 100ms)
        repeat(3) { i ->
            val start = t + i * 300L
            // Rising edge
            detector.handleTap(6.0f, start)
            // Sustained above threshold for 110ms (> 100ms limit)
            detector.handleTap(6.0f, start + 110)
            // Now it's been too long — spike is aborted by the state machine
            // Falling edge after abort
            detector.handleTap(1.0f, start + 150)
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `taps too close together are deduplicated`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        val t = 10_000L
        // First tap
        simulateTap(t)
        // Second tap only 100ms later (< TAP_MIN_INTERVAL_MS of 150ms)
        simulateTap(t + 100)
        // Third tap at 200ms (still < 150ms from the second attempt, but the second
        // was rejected, so 200ms from first registered tap is fine)
        simulateTap(t + 200)
        // Fourth tap
        simulateTap(t + 400)

        // Only 3 registered: t+50 (first falling edge), t+250 (third falling edge, 200ms gap from first), t+450
        // That should be enough
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `cooldown prevents re-trigger after tap pattern`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        // First trigger
        val t1End = simulateNTaps(count = 3, startTime = 10_000L)
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }

        // Attempt to trigger again within cooldown (5000ms)
        simulateNTaps(count = 3, startTime = t1End + 1_000L)

        // Still only 1 trigger
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `tap works again after cooldown expires`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        // First trigger
        simulateNTaps(count = 3, startTime = 10_000L)
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }

        // After cooldown (>5000ms from last trigger)
        simulateNTaps(count = 3, startTime = 20_000L)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 2) { sosManager.trigger() }
    }

    @Test
    fun `below-threshold acceleration does not register as tap`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        val t = 10_000L
        // Acceleration below TAP_THRESHOLD (4.0 m/s²) — no spike starts
        repeat(5) { i ->
            detector.handleTap(3.0f, t + i * 300L)
            detector.handleTap(1.0f, t + i * 300L + 50)
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `resetTapState clears all tap state`() {
        detector.activeModes = setOf(SosTriggerMode.TAP_PATTERN)
        detector.requiredTapCount = 3

        // Register 2 taps
        simulateNTaps(count = 2, startTime = 10_000L)
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }

        // Reset mid-sequence
        detector.resetTapState()

        // Need 3 more taps from scratch
        simulateNTaps(count = 2, startTime = 11_000L)
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }

        simulateNTaps(count = 3, startTime = 12_000L)
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    // ========== Shake Detection ==========

    /** Mirrors the detector's threshold formula: (0.5 + sensitivity * 0.5) * g. */
    private fun shakeThreshold(sensitivity: Float) = (0.5f + sensitivity * 0.5f) * 9.81f

    @Test
    fun `brief shake below duration does not trigger`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // Above threshold for only 200ms (< SHAKE_DURATION_MS of 500ms)
        detector.handleShake(threshold + 5f, t)
        detector.handleShake(threshold + 5f, t + 100)
        detector.handleShake(threshold + 5f, t + 200)
        // Drop below threshold
        detector.handleShake(1.0f, t + 201)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `sustained shake triggers SOS`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // Sustained above threshold for 600ms (> SHAKE_DURATION_MS of 500ms)
        // Simulate sensor events every 100ms
        for (i in 0..6) {
            detector.handleShake(threshold + 5f, t + i * 100L)
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `shake below threshold does not trigger`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // Acceleration below threshold for a long time
        for (i in 0..20) {
            detector.handleShake(threshold - 5f, t + i * 100L)
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `shake outside 1000ms window resets`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // Above threshold but window exceeds SHAKE_WINDOW_MS (1000ms)
        detector.handleShake(threshold + 5f, t)
        detector.handleShake(threshold + 5f, t + 400)
        // Jump past the 1000ms window
        detector.handleShake(threshold + 5f, t + 1_100)

        // Window expired, state should have reset; continue shaking
        detector.handleShake(threshold + 5f, t + 1_200)
        detector.handleShake(threshold + 5f, t + 1_300)

        // Not enough accumulated time in new window
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `gap in shake resets state`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // Shake for 300ms
        detector.handleShake(threshold + 5f, t)
        detector.handleShake(threshold + 5f, t + 100)
        detector.handleShake(threshold + 5f, t + 200)
        detector.handleShake(threshold + 5f, t + 300)

        // Drop below threshold with gap > 200ms
        detector.handleShake(1.0f, t + 600)

        // Resume shaking — state was reset
        detector.handleShake(threshold + 5f, t + 700)
        detector.handleShake(threshold + 5f, t + 800)
        detector.handleShake(threshold + 5f, t + 900)

        // Not enough continuous time since reset
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `shake cooldown prevents re-trigger`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // First trigger
        for (i in 0..6) {
            detector.handleShake(threshold + 5f, t + i * 100L)
        }
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }

        // Try again within cooldown (5000ms)
        val t2 = t + 2_000L
        for (i in 0..6) {
            detector.handleShake(threshold + 5f, t2 + i * 100L)
        }

        // Still only 1 trigger
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `shake works again after cooldown expires`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // First trigger
        for (i in 0..6) {
            detector.handleShake(threshold + 5f, t + i * 100L)
        }
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }

        // After cooldown (>5000ms)
        val t2 = t + 20_000L
        for (i in 0..6) {
            detector.handleShake(threshold + 5f, t2 + i * 100L)
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 2) { sosManager.trigger() }
    }

    @Test
    fun `higher sensitivity requires less acceleration`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 1.0f // Low sensitivity → lower threshold

        val threshold = shakeThreshold(1.0f)
        val t = 10_000L

        // This acceleration is above low threshold but would be below 2.5x threshold
        for (i in 0..6) {
            detector.handleShake(threshold + 2f, t + i * 100L)
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `bursty spikes separated by short dips do not trigger shake`() {
        // Regression: previously, a dip below threshold ≤200ms did not reset
        // lastShakeEventTime, so the next above-threshold sample would credit
        // the ENTIRE dip duration into shakeAccumulatedMs, letting sparse
        // bumps (running / dropped bag) falsely trigger SOS even at 4.0x.
        //
        // This test simulates 5 brief spikes (30 ms above threshold) spaced
        // 150 ms apart — total above-threshold time ~150 ms, well under the
        // 500 ms required. It must NOT trigger.
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 4.0f

        val threshold = shakeThreshold(4.0f)
        val t = 10_000L
        val spikeDuration = 30L
        val interSpikeGap = 150L

        repeat(5) { i ->
            val spikeStart = t + i * (spikeDuration + interSpikeGap)
            // Above-threshold for `spikeDuration`, sampled every 10 ms
            var s = spikeStart
            while (s <= spikeStart + spikeDuration) {
                detector.handleShake(threshold + 5f, s)
                s += 10L
            }
            // Dip below threshold, sampled every 30 ms through the gap
            var d = spikeStart + spikeDuration + 30L
            while (d < spikeStart + spikeDuration + interSpikeGap) {
                detector.handleShake(1.0f, d)
                d += 30L
            }
        }

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `resetShakeState clears shake tracking`() {
        detector.activeModes = setOf(SosTriggerMode.SHAKE)
        detector.shakeSensitivity = 2.5f

        val threshold = shakeThreshold(2.5f)
        val t = 10_000L

        // Accumulate 400ms of shake
        for (i in 0..4) {
            detector.handleShake(threshold + 5f, t + i * 100L)
        }

        // Reset mid-shake
        detector.resetShakeState()

        // Continue shaking — but accumulated time was reset
        for (i in 5..7) {
            detector.handleShake(threshold + 5f, t + i * 100L)
        }

        // Only 300ms accumulated after reset, not enough
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    // ========== SosTriggerMode Enum Tests ==========

    @Test
    fun `fromKey returns null for unknown key`() {
        assertNull(SosTriggerMode.fromKey("unknown"))
    }

    @Test
    fun `fromKey returns correct mode for each key`() {
        assertEquals(SosTriggerMode.SHAKE, SosTriggerMode.fromKey("shake"))
        assertEquals(SosTriggerMode.TAP_PATTERN, SosTriggerMode.fromKey("tap_pattern"))
        assertEquals(SosTriggerMode.POWER_BUTTON, SosTriggerMode.fromKey("power_button"))
    }

    @Test
    fun `fromKeys converts set of key strings to modes`() {
        val modes = SosTriggerMode.fromKeys(setOf("shake", "power_button"))
        assertEquals(setOf(SosTriggerMode.SHAKE, SosTriggerMode.POWER_BUTTON), modes)
    }

    @Test
    fun `fromKeys ignores unknown keys`() {
        val modes = SosTriggerMode.fromKeys(setOf("shake", "unknown"))
        assertEquals(setOf(SosTriggerMode.SHAKE), modes)
    }

    // ========== Power Button Tests ==========

    @Test
    fun `handlePowerPress triggers SOS after 3 rapid presses`() {
        detector.activeModes = setOf(SosTriggerMode.POWER_BUTTON)
        val t = 10_000L

        detector.handlePowerPress(t)
        detector.handlePowerPress(t + 500L)
        detector.handlePowerPress(t + 1000L)

        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 1) { sosManager.trigger() }
    }

    @Test
    fun `handlePowerPress does not trigger with only 2 presses`() {
        detector.activeModes = setOf(SosTriggerMode.POWER_BUTTON)
        val t = 10_000L

        detector.handlePowerPress(t)
        detector.handlePowerPress(t + 500L)

        // SOS should not have been triggered — state stays Idle
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }

    @Test
    fun `handlePowerPress does not trigger if presses are too spread out`() {
        detector.activeModes = setOf(SosTriggerMode.POWER_BUTTON)
        val t = 10_000L

        detector.handlePowerPress(t)
        detector.handlePowerPress(t + 1500L)
        detector.handlePowerPress(t + 3000L) // first press expired from window

        // SOS should not have been triggered — state stays Idle
        assertEquals(SosState.Idle, sosManager.state.value)
        verify(exactly = 0) { sosManager.trigger() }
    }
}
