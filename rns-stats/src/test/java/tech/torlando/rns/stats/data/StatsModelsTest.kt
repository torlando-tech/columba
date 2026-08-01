package tech.torlando.rns.stats.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsModelsTest {
    @Test
    fun `converts cumulative RNS counters to bytes per second`() {
        val samples =
            listOf(
                InterfaceHistoryPoint(timestamp = 1_000L, rxBytes = 100L, txBytes = 200L),
                InterfaceHistoryPoint(timestamp = 3_000L, rxBytes = 500L, txBytes = 300L),
            ).toSpeedSamples()

        assertEquals(1, samples.size)
        assertEquals(200f, samples.single().rxBytesPerSec)
        assertEquals(50f, samples.single().txBytesPerSec)
    }

    @Test
    fun `counter reset never produces negative chart speed`() {
        val sample =
            listOf(
                InterfaceHistoryPoint(timestamp = 1_000L, rxBytes = 500L, txBytes = 300L),
                InterfaceHistoryPoint(timestamp = 2_000L, rxBytes = 10L, txBytes = 20L),
            ).toSpeedSamples().single()

        assertEquals(0f, sample.rxBytesPerSec)
        assertEquals(0f, sample.txBytesPerSec)
    }
}
