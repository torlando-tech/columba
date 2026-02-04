// Performance Metrics Template

package com.lxmf.messenger.metrics

import android.util.Log
import kotlin.system.measureTimeMillis

data class PerformanceMetric(
    val name: String,
    val targetMs: Long
) {
    private val measurements = mutableListOf<Long>()
    
    fun record(durationMs: Long) {
        measurements.add(durationMs)
        
        if (durationMs > targetMs) {
            Log.w("Metrics", "⚠️ $name exceeded target: ${durationMs}ms > ${targetMs}ms")
        }
    }
    
    fun getAverage(): Double = measurements.average()
    fun getMax(): Long = measurements.maxOrNull() ?: 0
    fun getCount(): Int = measurements.size
}

inline fun <T> measureAndRecord(metric: PerformanceMetric, block: () -> T): T {
    var result: T
    val duration = measureTimeMillis {
        result = block()
    }
    metric.record(duration)
    return result
}

// Usage example:
// val initMetric = PerformanceMetric("initialization", targetMs = 3000)
// measureAndRecord(initMetric) {
//     initialize()
// }
