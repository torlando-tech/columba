package network.columba.app.telemetry

import android.content.ComponentCallbacks2
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryDiagnosticsTest {
    private val context = mockk<Context>()
    private lateinit var platform: FakePlatform

    @Before
    fun setUp() {
        MemoryDiagnostics.stop()
        platform = FakePlatform()
        every { context.applicationContext } returns context
        MemoryDiagnostics.setPlatformForTests(platform)
    }

    @After
    fun tearDown() {
        MemoryDiagnostics.stop()
        MemoryDiagnostics.setPlatformForTests(null)
    }

    @Test
    fun `stable providers read only during consent and reenable samples current counts with clean state`() {
        var peerCount = 99
        var providerReads = 0
        MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PEER_NAMES) {
            providerReads++
            peerCount
        }
        MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.EXPORT)
        assertEquals(emptyMap<String, Any>(), MemoryDiagnostics.snapshot())
        assertEquals(0, platform.reads)
        assertEquals(0, providerReads)

        MemoryDiagnostics.start(context) {}
        assertEquals(128, latest()["peer_names_bucket"])
        assertEquals(0, latest()["exports_bucket"])
        assertEquals(1, providerReads)
        peerCount = 200
        MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.EXPORT)
        platform.advance()
        assertEquals(256, latest()["peer_names_bucket"])
        assertEquals(1, latest()["exports_bucket"])
        assertEquals(2, providerReads)

        val cancelledSample = platform.pending!!
        val unregisteredTrim = platform.trimCallback!!
        MemoryDiagnostics.stop()
        assertEquals(1, platform.unregisters)
        assertEquals(null, platform.pending)
        assertEquals(emptyMap<String, Any>(), MemoryDiagnostics.snapshot())
        val readsAtStop = platform.reads
        peerCount = 10
        cancelledSample.run()
        unregisteredTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(readsAtStop, platform.reads)
        assertEquals(2, providerReads)

        // Registration survives stop; even a cache unchanged after reenable is measured immediately.
        MemoryDiagnostics.start(context) {}
        assertEquals(16, latest()["peer_names_bucket"])
        assertEquals(3, providerReads)
        assertEquals(0, latest()["exports_bucket"])
        cancelledSample.run()
        unregisteredTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals("none", latest()["trim"])
        assertEquals(1, samples().size)
        assertEquals(3, providerReads)
    }

    @Test
    fun `snapshot contains only bounded bucket and enum fields and detached chronological samples`() {
        var processedIds = 0
        var peerNames = 0
        var pendingRetries = 0
        MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PROCESSED_MESSAGE_IDS) { processedIds }
        MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PEER_NAMES) { peerNames }
        MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PENDING_RELAY_RETRIES) { pendingRetries }
        MemoryDiagnostics.start(context) {}
        val firstSnapshot = samples()
        repeat(20) { index ->
            platform.used = (index % 10) * 10L * 1024 * 1024
            processedIds = index + 1
            peerNames = Int.MAX_VALUE
            pendingRetries = -100
            platform.advance()
        }
        val snapshot = MemoryDiagnostics.snapshot()
        assertEquals(setOf("schema_version", "process_role", "sample_interval_seconds", "samples"), snapshot.keys)
        assertEquals(1, snapshot["schema_version"])
        assertEquals("app", snapshot["process_role"])
        assertEquals(60, snapshot["sample_interval_seconds"])
        assertEquals(16, samples().size)
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), samples().map { it["heap_usage_decile"] })
        assertEquals(1, firstSnapshot.size)
        assertEquals(3, firstSnapshot.single()["heap_usage_decile"])
        val keys =
            setOf(
                "uptime_minutes_bucket",
                "heap_used_mib_bucket",
                "heap_limit_mib_bucket",
                "heap_committed_mib_bucket",
                "heap_usage_decile",
                "processed_message_ids_bucket",
                "peer_names_bucket",
                "pending_relay_retries_bucket",
                "exports_bucket",
                "imports_bucket",
                "foreground",
                "trim",
            )
        samples().forEach { sample ->
            assertEquals(keys, sample.keys)
            assertEquals(1_048_576, sample["peer_names_bucket"])
            assertEquals(0, sample["pending_relay_retries_bucket"])
            assertEquals("foreground", sample["foreground"])
            assertEquals("none", sample["trim"])
            assertEquals(64, sample["heap_committed_mib_bucket"])
            sample.filterKeys { it.endsWith("_bucket") }.forEach { (key, value) ->
                val number = value as Int
                val cap =
                    when (key) {
                        "heap_used_mib_bucket", "heap_limit_mib_bucket", "heap_committed_mib_bucket" -> 65_536
                        "uptime_minutes_bucket" -> 262_144
                        else -> 1_048_576
                    }
                assertTrue(number in 0..cap)
                assertTrue(number == 0 || number and (number - 1) == 0)
            }
        }
    }

    @Test
    fun `sampling caps extreme heap and uptime inputs without leaking exact values`() {
        platform.used = Long.MAX_VALUE
        platform.maximum = Long.MAX_VALUE
        platform.committed = Long.MAX_VALUE
        MemoryDiagnostics.start(context) {}
        platform.advance(Long.MAX_VALUE)
        assertEquals(65_536, latest()["heap_used_mib_bucket"])
        assertEquals(65_536, latest()["heap_limit_mib_bucket"])
        assertEquals(65_536, latest()["heap_committed_mib_bucket"])
        assertEquals(10, latest()["heap_usage_decile"])
        assertEquals(262_144, latest()["uptime_minutes_bucket"])
        platform.used = -1
        platform.maximum = 0
        platform.committed = -1
        platform.advance(0)
        assertEquals(0, latest()["heap_used_mib_bucket"])
        assertEquals(0, latest()["heap_limit_mib_bucket"])
        assertEquals(0, latest()["heap_committed_mib_bucket"])
        assertEquals(0, latest()["heap_usage_decile"])
    }

    @Test
    fun `nested imports and exports remain active until all completions and never underflow`() {
        MemoryDiagnostics.start(context) {}
        val exportTickets = List(3) { MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.EXPORT) }
        val importTickets = List(2) { MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.IMPORT) }
        MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.EXPORT, exportTickets.first())
        MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.IMPORT, importTickets.first())
        platform.advance()
        assertEquals(2, latest()["exports_bucket"])
        assertEquals(1, latest()["imports_bucket"])
        repeat(5) {
            MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.EXPORT, exportTickets.last())
            MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.IMPORT, importTickets.last())
        }
        platform.advance()
        assertEquals(0, latest()["exports_bucket"])
        assertEquals(0, latest()["imports_bucket"])
    }

    @Test
    fun `old consent session completion cannot decrement newly enabled operations`() {
        val disabledTicket = MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.EXPORT)
        assertEquals(0L, disabledTicket)
        MemoryDiagnostics.start(context) {}
        val oldTicket = MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.EXPORT)
        assertTrue(oldTicket > 0L)
        MemoryDiagnostics.stop()
        MemoryDiagnostics.start(context) {}
        val newTicket = MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.EXPORT)
        assertTrue(newTicket > oldTicket)
        MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.EXPORT, oldTicket)
        MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.EXPORT, disabledTicket)
        platform.advance()
        assertEquals(1, latest()["exports_bucket"])
        MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.EXPORT, newTicket)
        platform.advance()
        assertEquals(0, latest()["exports_bucket"])
    }

    @Test
    fun `failed platform poll is omitted and subsequent sampling recovers`() {
        platform.failRead = true
        MemoryDiagnostics.start(context) {}
        assertEquals(emptyList<Map<String, Any>>(), samples())
        platform.failRead = false
        platform.advance()
        assertEquals(1, samples().size)
        assertEquals(32, latest()["heap_used_mib_bucket"])
        assertEquals(64, latest()["heap_committed_mib_bucket"])
    }

    @Test
    fun `warnings require crossing and recovery and are rate limited across recoveries`() {
        var warnings = 0
        platform.used = 80L * 1024 * 1024
        MemoryDiagnostics.start(context) { warnings++ }
        assertEquals(1, warnings)
        repeat(16) { platform.advance() }
        assertEquals(1, warnings)
        platform.used = 75L * 1024 * 1024
        platform.advance()
        platform.used = 85L * 1024 * 1024
        platform.advance()
        assertEquals(1, warnings)
        platform.used = 69L * 1024 * 1024
        platform.advance()
        platform.used = 80L * 1024 * 1024
        platform.advance()
        assertEquals(2, warnings)
        platform.used = 60L * 1024 * 1024
        platform.advance()
        platform.used = 90L * 1024 * 1024
        platform.advance()
        assertEquals(2, warnings)
        MemoryDiagnostics.stop()
        MemoryDiagnostics.start(context) { warnings++ }
        assertEquals(3, warnings)
    }

    @Test
    fun `trim notifications and foreground expose only constant classifications`() {
        MemoryDiagnostics.start(context) {}
        val cases =
            listOf(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE to "running_moderate",
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to "running_low",
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL to "running_critical",
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN to "ui_hidden",
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND to "background",
                ComponentCallbacks2.TRIM_MEMORY_MODERATE to "moderate",
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE to "complete",
                Int.MAX_VALUE to "unknown",
            )
        platform.foreground = MemoryDiagnosticForeground.BACKGROUND
        cases.forEach { (level, expected) ->
            platform.trimCallback!!.invoke(level)
            assertEquals(expected, latest()["trim"])
            platform.advance()
            assertEquals(expected, latest()["trim"])
            assertEquals("background", latest()["foreground"])
        }
        platform.foreground = MemoryDiagnosticForeground.UNKNOWN
        platform.advance()
        assertEquals("unknown", latest()["foreground"])
    }

    @Test
    fun `starting twice neither duplicates sampler nor replaces callback`() {
        var warnings = 0
        MemoryDiagnostics.start(context) { warnings++ }
        MemoryDiagnostics.start(context) { error("duplicate start replaced callback") }
        assertEquals(1, platform.reads)
        assertEquals(1, platform.registrations)
        platform.used = 90L * 1024 * 1024
        platform.advance()
        assertEquals(1, warnings)
        MemoryDiagnostics.stop()
        MemoryDiagnostics.stop()
        assertEquals(1, platform.unregisters)
    }

    @Test
    fun `missing and failing providers remain unknown independently and can recover`() {
        var processedIds = 0
        var fail = false
        MemoryDiagnostics.start(context) {}
        assertFalse(latest().containsKey("processed_message_ids_bucket"))
        MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PROCESSED_MESSAGE_IDS) {
            check(!fail) { "sensitive callback detail must not escape" }
            processedIds
        }
        platform.advance()
        assertEquals(0, latest()["processed_message_ids_bucket"])
        assertFalse(samples().first().containsKey("processed_message_ids_bucket"))
        assertFalse(latest().containsKey("peer_names_bucket"))
        assertFalse(latest().containsKey("pending_relay_retries_bucket"))
        var peerNames = 13
        MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PEER_NAMES) { peerNames }
        fail = true
        platform.advance()
        assertFalse(latest().containsKey("processed_message_ids_bucket"))
        assertEquals(16, latest()["peer_names_bucket"])
        assertEquals(3, latest()["heap_usage_decile"])
        fail = false
        processedIds = 5
        peerNames = 33
        platform.advance()
        assertEquals(8, latest()["processed_message_ids_bucket"])
        assertEquals(64, latest()["peer_names_bucket"])
    }

    @Test
    fun `concurrent operations do not lose starts or completions`() {
        MemoryDiagnostics.start(context) {}
        val started = java.util.concurrent.CountDownLatch(4)
        val finish = java.util.concurrent.CountDownLatch(1)
        val workers =
            List(4) {
                Thread {
                    var ticket = 0L
                    repeat(200) { ticket = MemoryDiagnostics.operationStarted(MemoryDiagnosticOperation.IMPORT) }
                    started.countDown()
                    finish.await()
                    repeat(200) { MemoryDiagnostics.operationFinished(MemoryDiagnosticOperation.IMPORT, ticket) }
                }.also { it.start() }
            }
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            platform.advance()
            assertEquals(1024, latest()["imports_bucket"])
        } finally {
            finish.countDown()
            workers.forEach { it.join(10_000) }
        }
        assertTrue(workers.none { it.isAlive })
        platform.advance()
        assertEquals(0, latest()["imports_bucket"])
    }

    @Test
    fun `warning callback can revoke consent without leaving scheduled work or data`() {
        platform.used = 90L * 1024 * 1024
        MemoryDiagnostics.start(context) {
            assertEquals(1, samples().size)
            MemoryDiagnostics.stop()
        }
        assertEquals(null, platform.pending)
        assertEquals(1, platform.unregisters)
        assertEquals(emptyMap<String, Any>(), MemoryDiagnostics.snapshot())
    }

    private fun samples(): List<Map<String, Any>> {
        @Suppress("UNCHECKED_CAST")
        return MemoryDiagnostics.snapshot()["samples"] as List<Map<String, Any>>
    }

    private fun latest(): Map<String, Any> = samples().last()

    private class FakePlatform : MemoryDiagnosticsPlatform {
        var now = 0L
        var used = 30L * 1024 * 1024
        var committed = 64L * 1024 * 1024
        var failRead = false
        var maximum = 100L * 1024 * 1024
        var foreground = MemoryDiagnosticForeground.FOREGROUND
        var pending: Runnable? = null
        var trimCallback: ((Int) -> Unit)? = null
        var reads = 0
        var unregisters = 0
        var registrations = 0
        override val role = MemoryDiagnosticRole.APP

        override fun elapsedRealtime(): Long = now

        override fun readMemory(reading: MemoryDiagnosticReading) {
            reads++
            check(!failRead) { "platform poll unavailable" }
            reading.usedBytes = used
            reading.committedBytes = committed
            reading.maxBytes = maximum
            reading.foreground = foreground
        }

        override fun registerTrimCallback(callback: (Int) -> Unit) {
            registrations++
            trimCallback = callback
        }

        override fun unregisterTrimCallback() {
            unregisters++
            trimCallback = null
        }

        override fun schedule(
            sample: Runnable,
            delayMillis: Long,
        ) {
            assertEquals(60_000L, delayMillis)
            check(pending == null)
            pending = sample
        }

        override fun cancel(sample: Runnable) {
            if (pending === sample) pending = null
        }

        fun advance(milliseconds: Long = 60_000L) {
            now += milliseconds
            val sample = checkNotNull(pending)
            pending = null
            sample.run()
        }
    }
}
