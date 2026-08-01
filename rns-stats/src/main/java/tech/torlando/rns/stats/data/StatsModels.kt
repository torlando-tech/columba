package tech.torlando.rns.stats.data

/**
 * A timestamped snapshot of cumulative byte counters for an interface.
 * Speed is computed from consecutive points: (rxBytes[i] - rxBytes[i-1]) / dt.
 */
data class InterfaceHistoryPoint(
    val timestamp: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

/**
 * A computed speed sample (bytes/sec) at a point in time.
 */
data class SpeedSample(
    val timestamp: Long,
    val rxBytesPerSec: Float,
    val txBytesPerSec: Float,
)

/**
 * Ring buffer for interface history. Thread-safe via synchronized access.
 * Each app feeds data from its own polling source.
 */
class HistoryBuffer(private val maxSize: Int = 120) {
    private val buffer = ArrayDeque<InterfaceHistoryPoint>()

    @Synchronized
    fun add(point: InterfaceHistoryPoint) {
        buffer.addLast(point)
        while (buffer.size > maxSize) buffer.removeFirst()
    }

    @Synchronized
    fun toList(): List<InterfaceHistoryPoint> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()

    val size: Int get() = buffer.size
}

/**
 * Compute speed samples from consecutive history points.
 */
fun List<InterfaceHistoryPoint>.toSpeedSamples(): List<SpeedSample> {
    if (size < 2) return emptyList()
    val result = mutableListOf<SpeedSample>()
    for (i in 1 until size) {
        val dt = (this[i].timestamp - this[i - 1].timestamp) / 1000f
        if (dt <= 0f) continue
        val rxSpeed = (this[i].rxBytes - this[i - 1].rxBytes) / dt
        val txSpeed = (this[i].txBytes - this[i - 1].txBytes) / dt
        result.add(
            SpeedSample(
                timestamp = this[i].timestamp,
                rxBytesPerSec = rxSpeed.coerceAtLeast(0f),
                txBytesPerSec = txSpeed.coerceAtLeast(0f),
            ),
        )
    }
    return result
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun formatSpeed(bytesPerSec: Float): String = when {
    bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "%.0f B/s".format(bytesPerSec)
}
