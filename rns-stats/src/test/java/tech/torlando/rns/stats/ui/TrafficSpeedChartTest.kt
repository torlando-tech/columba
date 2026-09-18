package tech.torlando.rns.stats.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.torlando.rns.stats.data.SpeedSample

class TrafficSpeedChartTest {
    @Test
    fun `rolling window animation aligns retained samples by timestamp`() {
        val previous =
            listOf(
                SpeedSample(timestamp = 1L, rxBytesPerSec = 10f, txBytesPerSec = 20f),
                SpeedSample(timestamp = 2L, rxBytesPerSec = 30f, txBytesPerSec = 40f),
                SpeedSample(timestamp = 3L, rxBytesPerSec = 50f, txBytesPerSec = 60f),
            )
        val shifted =
            listOf(
                SpeedSample(timestamp = 2L, rxBytesPerSec = 34f, txBytesPerSec = 44f),
                SpeedSample(timestamp = 3L, rxBytesPerSec = 54f, txBytesPerSec = 64f),
                SpeedSample(timestamp = 4L, rxBytesPerSec = 70f, txBytesPerSec = 80f),
            )

        val halfway = interpolateSpeedSamples(previous, shifted, 0.5f)

        assertEquals(listOf(2L, 3L, 4L), halfway.map { it.timestamp })
        assertEquals(listOf(32f, 52f, 70f), halfway.map { it.rxBytesPerSec })
        assertEquals(listOf(42f, 62f, 80f), halfway.map { it.txBytesPerSec })
    }
}
