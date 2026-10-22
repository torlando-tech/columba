package network.columba.app.telemetry

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Only aggregate cardinalities; callers must never pass identifiers or collection contents. */
enum class MemoryDiagnosticCounter { PROCESSED_MESSAGE_IDS, PEER_NAMES, PENDING_RELAY_RETRIES }

enum class MemoryDiagnosticOperation { EXPORT, IMPORT }

/**
 * Consent-scoped, in-memory aggregates, not a heap profiler. Sampling writes into a fixed numeric
 * ring; best-effort snapshot allocation is bounded to 16 maps of at most 12 scalar fields, plus
 * their list and envelope. Uptime is consent-session-relative. Nothing is persisted.
 */
object MemoryDiagnostics {
    private const val SAMPLE_INTERVAL_MILLIS = 60_000L
    private const val WARNING_INTERVAL_MILLIS = 15 * 60_000L
    private const val CAPACITY = 16
    private const val FIELDS = 13
    private const val COUNTER_CAP = 1_048_576
    private const val MIB = 1024L * 1024
    private val lock = Any()
    private val ring = IntArray(CAPACITY * FIELDS)
    private val counterProviders = arrayOfNulls<() -> Int>(MemoryDiagnosticCounter.entries.size)
    private val operations = IntArray(MemoryDiagnosticOperation.entries.size)
    private var nextSample = 0
    private var sampleCount = 0
    private var lastSessionTicket = 0L
    private var testPlatform: MemoryDiagnosticsPlatform? = null

    @Volatile
    private var session: Session? = null

    fun start(
        context: Context,
        onHighMemory: () -> Unit,
    ) {
        val started =
            synchronized(lock) {
                if (session != null) return
                // Never retain an Activity, even when the caller accidentally supplies one.
                val application = context.applicationContext ?: return
                if (lastSessionTicket == Long.MAX_VALUE) return
                val platform = testPlatform ?: AndroidMemoryDiagnosticsPlatform(application)
                Session(platform, onHighMemory, ++lastSessionTicket).also {
                    session = it
                    platform.registerTrimCallback(it.trimCallback)
                }
            }
        sample(started)
    }

    fun stop() {
        synchronized(lock) {
            val previous = session
            session = null
            if (previous != null) {
                previous.onHighMemory = null
                previous.platform.cancel(previous)
                previous.platform.unregisterTrimCallback()
            }
            ring.fill(0)
            operations.fill(0)
            nextSample = 0
            sampleCount = 0
        }
    }

    /**
     * Registers at most one size-only provider per fixed counter, without reading it. Providers
     * belong to application singletons and survive consent changes; numeric samples do not.
     * Reads run under our lock: providers must not iterate contents, and their owners must never
     * call diagnostics while holding a lock that a provider also acquires.
     */
    fun registerCounter(
        counter: MemoryDiagnosticCounter,
        provider: () -> Int,
    ) {
        synchronized(lock) {
            counterProviders[counter.ordinal] = provider
        }
    }

    /** Returns a consent-session ticket; pass it to completion even if consent changes meanwhile. */
    fun operationStarted(operation: MemoryDiagnosticOperation): Long {
        val active = session ?: return 0L
        return synchronized(lock) {
            if (session !== active || operations[operation.ordinal] == Int.MAX_VALUE) {
                0L
            } else {
                operations[operation.ordinal]++
                active.ticket
            }
        }
    }

    fun operationFinished(
        operation: MemoryDiagnosticOperation,
        ticket: Long,
    ) {
        if (ticket <= 0L) return
        val active = session ?: return
        synchronized(lock) {
            if (session === active && active.ticket == ticket && operations[operation.ordinal] > 0) {
                operations[operation.ordinal]--
            }
        }
    }

    fun snapshot(): Map<String, Any> =
        synchronized(lock) {
            val active = session ?: return emptyMap()
            val samples = ArrayList<Map<String, Any>>(sampleCount)
            val oldest = (nextSample - sampleCount + CAPACITY) % CAPACITY
            repeat(sampleCount) { index ->
                val offset = ((oldest + index) % CAPACITY) * FIELDS
                val sample =
                    linkedMapOf<String, Any>(
                        "uptime_minutes_bucket" to ring[offset],
                        "heap_used_mib_bucket" to ring[offset + 1],
                        "heap_limit_mib_bucket" to ring[offset + 2],
                        "heap_committed_mib_bucket" to ring[offset + 12],
                        "heap_usage_decile" to ring[offset + 3],
                        "exports_bucket" to ring[offset + 7],
                        "imports_bucket" to ring[offset + 8],
                        "foreground" to MemoryDiagnosticForeground.entries[ring[offset + 9]].wireValue,
                        "trim" to MemoryDiagnosticTrim.entries[ring[offset + 10]].wireValue,
                    )
                val known = ring[offset + 11]
                if (known and 1 != 0) sample["processed_message_ids_bucket"] = ring[offset + 4]
                if (known and 2 != 0) sample["peer_names_bucket"] = ring[offset + 5]
                if (known and 4 != 0) sample["pending_relay_retries_bucket"] = ring[offset + 6]
                samples.add(sample)
            }
            mapOf(
                "schema_version" to 1,
                "process_role" to active.platform.role.wireValue,
                "sample_interval_seconds" to 60,
                "samples" to samples,
            )
        }

    internal fun setPlatformForTests(platform: MemoryDiagnosticsPlatform?) {
        synchronized(lock) {
            check(session == null)
            testPlatform = platform
            counterProviders.fill(null)
        }
    }

    private fun sample(active: Session) {
        val warning =
            synchronized(lock) {
                if (session !== active) return
                val platform = active.platform
                val now = platform.elapsedRealtime()
                try {
                    platform.readMemory(active.reading)
                } catch (_: RuntimeException) {
                    // Best effort: omit a failed poll rather than inventing a zero-memory sample.
                    platform.schedule(active, SAMPLE_INTERVAL_MILLIS)
                    return
                }
                val reading = active.reading
                val used = reading.usedBytes.coerceAtLeast(0)
                val maximum = reading.maxBytes.coerceAtLeast(0)
                val percent =
                    if (maximum > 0) {
                        (used.toDouble() / maximum.toDouble() * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                val offset = nextSample * FIELDS
                ring[offset] = bucket(((now - active.startedAt).coerceAtLeast(0) / 60_000L), 262_144)
                ring[offset + 1] = bucket(mibCeiling(used), 65_536)
                ring[offset + 2] = bucket(mibCeiling(maximum), 65_536)
                ring[offset + 3] = percent / 10
                var knownCounters = 0
                for (index in counterProviders.indices) {
                    ring[offset + 4 + index] = 0
                    val provider = counterProviders[index] ?: continue
                    try {
                        ring[offset + 4 + index] = bucket(provider().coerceAtLeast(0).toLong(), COUNTER_CAP)
                        knownCounters = knownCounters or (1 shl index)
                    } catch (_: Exception) {
                        // A failed size read is unknown, not zero; other counters remain useful.
                    }
                }
                ring[offset + 7] = bucket(operations[0].toLong(), COUNTER_CAP)
                ring[offset + 8] = bucket(operations[1].toLong(), COUNTER_CAP)
                ring[offset + 9] = reading.foreground.ordinal
                ring[offset + 10] = active.trim.ordinal
                ring[offset + 11] = knownCounters
                ring[offset + 12] = bucket(mibCeiling(reading.committedBytes.coerceAtLeast(0)), 65_536)
                nextSample = (nextSample + 1) % CAPACITY
                sampleCount = (sampleCount + 1).coerceAtMost(CAPACITY)

                // Hysteresis: a high period ends only below 70%; sustained pressure never floods.
                if (maximum > 0 && percent < 70) active.high = false
                val crossing = maximum > 0 && percent >= 80 && !active.high
                if (crossing) active.high = true
                val mayWarn = !active.hasWarned || now - active.lastWarningAt >= WARNING_INTERVAL_MILLIS
                val callback =
                    if (crossing && mayWarn) {
                        active.hasWarned = true
                        active.lastWarningAt = now
                        active.onHighMemory
                    } else {
                        null
                    }
                platform.schedule(active, SAMPLE_INTERVAL_MILLIS)
                callback
            }
        // Reporter rechecks its consent token. Never hold our lock while entering the SDK/reporter.
        if (session === active) warning?.invoke()
    }

    private fun mibCeiling(bytes: Long): Long = bytes / MIB + if (bytes % MIB == 0L) 0 else 1

    private fun bucket(
        value: Long,
        cap: Int,
    ): Int {
        if (value <= 0) return 0
        var bucket = 1
        while (bucket < value && bucket < cap) bucket *= 2
        return bucket
    }

    private class Session(
        val platform: MemoryDiagnosticsPlatform,
        var onHighMemory: (() -> Unit)?,
        val ticket: Long,
    ) : Runnable {
        val startedAt = platform.elapsedRealtime()
        val reading = MemoryDiagnosticReading()
        var trim = MemoryDiagnosticTrim.NONE
        var high = false
        var hasWarned = false
        var lastWarningAt = 0L
        val trimCallback: (Int) -> Unit = { level ->
            synchronized(lock) {
                if (session === this) {
                    trim = MemoryDiagnosticTrim.fromLevel(level)
                    // Preserve the latest pressure signal immediately, without another heap poll.
                    if (sampleCount > 0) {
                        val latest = (nextSample - 1 + CAPACITY) % CAPACITY
                        ring[latest * FIELDS + 10] = trim.ordinal
                    }
                }
            }
        }

        override fun run() = sample(this)
    }
}

internal enum class MemoryDiagnosticRole(
    val wireValue: String,
) {
    APP("app"),
    SERVICE("service"),
    UNKNOWN("unknown"),
}

internal enum class MemoryDiagnosticForeground(
    val wireValue: String,
) {
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    UNKNOWN("unknown"),
}

internal enum class MemoryDiagnosticTrim(
    val wireValue: String,
) {
    NONE("none"),
    RUNNING_MODERATE("running_moderate"),
    RUNNING_LOW("running_low"),
    RUNNING_CRITICAL("running_critical"),
    UI_HIDDEN("ui_hidden"),
    BACKGROUND("background"),
    MODERATE("moderate"),
    COMPLETE("complete"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromLevel(level: Int): MemoryDiagnosticTrim =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> RUNNING_MODERATE
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> RUNNING_LOW
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> RUNNING_CRITICAL
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> UI_HIDDEN
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> BACKGROUND
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MODERATE
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> COMPLETE
                else -> UNKNOWN
            }
    }
}

/** Reused destination, so the once-per-minute platform read does not allocate a sample object. */
internal class MemoryDiagnosticReading {
    var usedBytes: Long = 0
    var committedBytes: Long = 0
    var maxBytes: Long = 0
    var foreground: MemoryDiagnosticForeground = MemoryDiagnosticForeground.UNKNOWN
}

/** Clock/scheduling/platform seam; tests still exercise the public consent lifecycle and snapshots. */
internal interface MemoryDiagnosticsPlatform {
    val role: MemoryDiagnosticRole

    fun elapsedRealtime(): Long

    fun readMemory(reading: MemoryDiagnosticReading)

    fun registerTrimCallback(callback: (Int) -> Unit)

    fun unregisterTrimCallback()

    fun schedule(
        sample: Runnable,
        delayMillis: Long,
    )

    fun cancel(sample: Runnable)
}

private class AndroidMemoryDiagnosticsPlatform(
    private val application: Context,
) : MemoryDiagnosticsPlatform {
    private val handler = Handler(Looper.getMainLooper())
    private val runtime = Runtime.getRuntime()
    private val processInfo = ActivityManager.RunningAppProcessInfo()
    private var trimCallback: ((Int) -> Unit)? = null
    private val callbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                trimCallback?.invoke(level)
            }

            override fun onLowMemory() {
                trimCallback?.invoke(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }

    override val role: MemoryDiagnosticRole =
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val processName = Application.getProcessName()
                when {
                    processName == null -> MemoryDiagnosticRole.UNKNOWN
                    processName == application.applicationInfo.processName -> MemoryDiagnosticRole.APP
                    else -> MemoryDiagnosticRole.SERVICE
                }
            } else {
                MemoryDiagnosticRole.UNKNOWN
            }
        } catch (_: RuntimeException) {
            MemoryDiagnosticRole.UNKNOWN
        }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun readMemory(reading: MemoryDiagnosticReading) {
        reading.committedBytes = runtime.totalMemory()
        reading.usedBytes = (reading.committedBytes - runtime.freeMemory()).coerceAtLeast(0)
        reading.maxBytes = runtime.maxMemory()
        reading.foreground =
            try {
                ActivityManager.getMyMemoryState(processInfo)
                if (processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    MemoryDiagnosticForeground.FOREGROUND
                } else {
                    MemoryDiagnosticForeground.BACKGROUND
                }
            } catch (_: RuntimeException) {
                MemoryDiagnosticForeground.UNKNOWN
            }
    }

    override fun registerTrimCallback(callback: (Int) -> Unit) {
        trimCallback = callback
        application.registerComponentCallbacks(callbacks)
    }

    override fun unregisterTrimCallback() {
        trimCallback = null
        application.unregisterComponentCallbacks(callbacks)
    }

    override fun schedule(
        sample: Runnable,
        delayMillis: Long,
    ) {
        handler.postDelayed(sample, delayMillis)
    }

    override fun cancel(sample: Runnable) {
        handler.removeCallbacks(sample)
    }
}
