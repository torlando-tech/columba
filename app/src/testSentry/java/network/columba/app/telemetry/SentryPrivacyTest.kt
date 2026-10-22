package network.columba.app.telemetry

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.cache.EnvelopeCache
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.columba.app.util.CrashReportManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SentryPrivacyTest {
    private val options = SentryOptions()
    private val policy = SentryPrivacyPolicy("network.columba.app@1.2.3+123", "production", BUILD_UUID)
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private var reporter: SentryCrashReporter? = null

    @After
    fun tearDown() {
        reporter?.setEnabled(false)
        MemoryDiagnostics.stop()
        Sentry.close()
        SentryCrashReporter.purgeSdkDirectory(File(context.cacheDir, SentryCrashReporter.PRIVATE_CACHE))
    }

    @Test
    fun `final transport reconstructs actual SDK envelope and rejects all other items`() {
        val recording = RecordingTransport(options)
        val transport = SentryPrivacyTransport(SentryConsent(), policy, options.serializer, recording)
        val event = hostileEvent()
        val attachment =
            SentryEnvelopeItem.fromAttachment(
                options.serializer,
                options.logger,
                Attachment(SECRET.toByteArray(), "$SECRET.txt", "text/plain"),
                100_000,
            )
        val envelope =
            SentryEnvelope(
                SentryEnvelopeHeader(event.eventId).apply { unknown = mapOf(SECRET to SECRET) },
                listOf(attachment, SentryEnvelopeItem.fromEvent(options.serializer, event), attachment),
            )
        val wire = ByteArrayOutputStream().also { options.serializer.serialize(envelope, it) }.toString("UTF-8")
        val headerEnd = wire.indexOf('\n')
        val hostileHeader =
            JSONObject(wire.substring(0, headerEnd)).put(
                "trace",
                JSONObject()
                    .put("trace_id", "f04d790078624afda18e24dab527bafd")
                    .put("public_key", SECRET)
                    .put("user_id", SECRET)
                    .put("transaction", SECRET)
                    .put("release", SECRET)
                    .put("environment", SECRET),
            )
        val replay =
            options.serializer.deserializeEnvelope(
                ByteArrayInputStream((hostileHeader.toString() + wire.substring(headerEnd)).toByteArray()),
            )!!
        assertNotNull(replay.header.traceContext)
        transport.send(replay, Hint.withAttachment(Attachment(SECRET.toByteArray(), SECRET)))
        val bytes = recording.payloads.single()
        assertPrivate(bytes)
        val decoded = options.serializer.deserializeEnvelope(ByteArrayInputStream(bytes))!!
        assertEquals(1, decoded.items.count())
        val clean = decoded.items.single().getEvent(options.serializer)!!
        assertEquals("IllegalStateException", clean.exceptions!!.single().type)
        assertEquals(
            "network.columba.app.SafeClass",
            clean.exceptions!!
                .single()
                .stacktrace!!
                .frames!!
                .single()
                .module,
        )
        assertEquals(
            "performOperation",
            clean.exceptions!!
                .single()
                .stacktrace!!
                .frames!!
                .single()
                .function,
        )
        assertEquals(
            42,
            clean.exceptions!!
                .single()
                .stacktrace!!
                .frames!!
                .single()
                .lineno,
        )
        assertEquals(
            false,
            clean.exceptions!!
                .single()
                .mechanism!!
                .isHandled,
        )
        assertEquals("network.columba.app@1.2.3+123", clean.release)
        assertEquals(
            "proguard",
            clean.debugMeta!!
                .images!!
                .single()
                .type,
        )
        assertEquals(
            BUILD_UUID,
            clean.debugMeta!!
                .images!!
                .single()
                .uuid,
        )
        assertNull(
            clean.debugMeta!!
                .images!!
                .single()
                .debugFile,
        )
        assertNull(clean.request)
        assertNull(clean.user)
        assertNull(clean.threads)
        assertNull(clean.breadcrumbs)
        assertNull(clean.extras)
        assertNull(decoded.header.traceContext)
        assertNull(decoded.header.unknown)
        assertEquals(1, (clean.contexts[SentryPrivacyPolicy.MEMORY_CONTEXT] as Map<*, *>)["schema_version"])
    }

    @Test
    fun `unknown and non-event envelope items cannot bypass beforeSend`() {
        val recording = RecordingTransport(options)
        val transport = SentryPrivacyTransport(SentryConsent(), policy, options.serializer, recording)
        for (type in listOf(
            "session",
            "sessions",
            "transaction",
            "attachment",
            "client_report",
            "profile",
            "profile_chunk",
            "replay_event",
            "replay_recording",
            "check_in",
            "user_report",
            "feedback",
            "log",
            "trace_metric",
            "future_type",
        )) {
            val body = "{\"private\":\"$SECRET\"}"
            val raw = "{}\n{\"type\":\"$type\",\"length\":${body.toByteArray().size}}\n$body\n"
            val envelope = options.serializer.deserializeEnvelope(ByteArrayInputStream(raw.toByteArray()))!!
            transport.send(envelope, Hint())
        }
        assertTrue(recording.payloads.isEmpty())
        val plainMessage =
            SentryEvent().apply {
                message =
                    io.sentry.protocol
                        .Message()
                        .apply { formatted = SECRET }
            }
        transport.send(SentryEnvelope.from(options.serializer, plainMessage, null), Hint())
        assertTrue(recording.payloads.isEmpty())
    }

    @Test
    fun `cache replay from another release or mapping is dropped rather than mislabeled`() {
        val recording = RecordingTransport(options)
        val transport = SentryPrivacyTransport(SentryConsent(), policy, options.serializer, recording)
        val differentRelease = hostileEvent().apply { release = "network.columba.app@0.1.0+1" }
        transport.send(SentryEnvelope.from(options.serializer, differentRelease, null), Hint())
        val differentMapping =
            policy.event(hostileEvent())!!.apply {
                debugMeta!!.images!!.single().uuid = "235453c7-d883-425a-b910-686aab57cfc0"
            }
        transport.send(SentryEnvelope.from(options.serializer, differentMapping, null), Hint())
        assertTrue(recording.payloads.isEmpty())
        transport.send(SentryEnvelope.from(options.serializer, hostileEvent(), null), Hint())
        assertEquals(1, recording.payloads.size)
        assertPrivate(recording.payloads.single())
    }

    @Test
    fun `SDK exception capture strips nested scope and throwable values before serialization`() {
        val factory = RecordingFactory()
        val current = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
        current.configure(context, SECRET)
        current.setEnabled(true)
        assertTrue(Sentry.isEnabled())
        Sentry.setUser(
            User().apply {
                id = SECRET
                email = SECRET
                username = SECRET
                ipAddress = SECRET
            },
        )
        Sentry.setTag(SECRET, SECRET)
        Sentry.setExtra(SECRET, SECRET)
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                message = SECRET
                setData(SECRET, listOf(SECRET))
            },
        )
        val error =
            IllegalStateException(SECRET, IllegalArgumentException(SECRET)).apply {
                stackTrace = arrayOf(StackTraceElement("network.columba.app.SafeClass", "performOperation", SECRET, 42))
            }
        current.captureException(error)
        val payload =
            factory.transports
                .single()
                .payloads
                .single()
        assertPrivate(payload)
        val decoded =
            factory.options
                .single()
                .serializer
                .deserializeEnvelope(ByteArrayInputStream(payload))!!
        val clean = decoded.items.single().getEvent(factory.options.single().serializer)!!
        assertEquals(2, clean.exceptions!!.size)
        assertTrue(clean.exceptions!!.any { it.type == "IllegalStateException" })
        assertTrue(clean.exceptions!!.all { it.value == null })
        assertEquals("development", clean.environment)
        assertNotNull(clean.release)
    }

    @Test
    fun `only bounded fixed memory schema survives actual serialized roundtrip`() {
        val recording = RecordingTransport(options)
        val transport = SentryPrivacyTransport(SentryConsent(), policy, options.serializer, recording)
        val event =
            hostileEvent().apply {
                exceptions = null
                level = io.sentry.SentryLevel.WARNING
                message =
                    io.sentry.protocol
                        .Message()
                        .apply { formatted = SentryPrivacyPolicy.HIGH_MEMORY_MESSAGE }
            }
        transport.send(SentryEnvelope.from(options.serializer, event, null), Hint())
        assertPrivate(recording.payloads.single())
        val decoded = options.serializer.deserializeEnvelope(ByteArrayInputStream(recording.payloads.single()))!!
        val memory =
            decoded.items
                .single()
                .getEvent(options.serializer)!!
                .contexts[SentryPrivacyPolicy.MEMORY_CONTEXT] as Map<*, *>
        val samples = memory["samples"] as List<*>
        assertEquals(16, samples.size)
        val sample = samples.first() as Map<*, *>
        assertEquals(128, sample["heap_used_mib_bucket"])
        assertFalse(sample.containsKey("processed_message_ids_bucket"))
        assertFalse(sample.containsKey(SECRET))
    }

    @Test
    fun `disk cache sanitizes independently and replay passes final transport policy`() {
        val directory = File(context.cacheDir, SentryCrashReporter.PRIVATE_CACHE).apply { mkdirs() }
        val epoch = SentryConsent()
        val disk = EnvelopeCache(options, directory.absolutePath, 10)
        val cache = SentryPrivacyCache(epoch, policy, options.serializer, disk)
        val hostile = SentryEnvelope.from(options.serializer, hostileEvent(), null)
        assertTrue(cache.storeEnvelope(hostile, Hint()))
        val file = directory.listFiles()!!.single { it.name.endsWith(".envelope") }
        assertPrivate(file.readBytes())
        val recording = RecordingTransport(options)
        val transport = SentryPrivacyTransport(epoch, policy, options.serializer, recording)
        val cached = cache.iterator().next()
        transport.send(cached, Hint())
        assertPrivate(recording.payloads.single())
        epoch.revoke()
        assertFalse(cache.storeEnvelope(hostile, Hint()))
        transport.send(cached, Hint())
        assertEquals(1, recording.payloads.size)
        cache.discard(hostile)
        assertFalse(file.exists())
    }

    @Test
    fun `consent gates init collection cache purge and old epochs after re-enable`() {
        val factory = RecordingFactory()
        val current = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
        val legacy = File(context.cacheDir, "sentry").apply { mkdirs() }
        File(legacy, "old.envelope").writeText(SECRET)
        val unrelated = File(context.cacheDir, "unrelated-private-data").apply { writeText(SECRET) }
        current.configure(context, "production")
        assertTrue("Configuration alone must not consume pending telemetry", legacy.exists())
        current.setEnabled(false)
        current.captureException(IllegalStateException(SECRET))
        assertTrue(factory.options.isEmpty())
        assertTrue(MemoryDiagnostics.snapshot().isEmpty())
        assertFalse(legacy.exists())
        current.setEnabled(true)
        assertEquals(1, factory.options.size)
        val oldOptions = factory.options.single()
        val cachedEnvelope =
            SentryEnvelope.from(
                oldOptions.serializer,
                hostileEvent().apply { release = oldOptions.release },
                null,
            )
        assertTrue(oldOptions.envelopeDiskCache.storeEnvelope(cachedEnvelope, Hint()))
        assertTrue(File(context.cacheDir, SentryCrashReporter.PRIVATE_CACHE).walkTopDown().any { it.name.endsWith(".envelope") })
        assertFalse(MemoryDiagnostics.snapshot().isEmpty())
        current.setEnabled(false)
        assertFalse(Sentry.isEnabled())
        assertFalse(oldOptions.transportGate.isConnected)
        assertTrue(MemoryDiagnostics.snapshot().isEmpty())
        assertFalse(File(context.cacheDir, SentryCrashReporter.PRIVATE_CACHE).exists())
        assertTrue(unrelated.exists())
        assertFalse(oldOptions.envelopeDiskCache.storeEnvelope(cachedEnvelope, Hint()))
        current.setEnabled(true)
        assertEquals(2, factory.options.size)
        assertFalse(oldOptions.transportGate.isConnected)
        assertTrue(
            factory.options
                .last()
                .transportGate.isConnected,
        )
        current.captureException(IllegalStateException(SECRET))
        assertEquals(
            1,
            factory.transports
                .last()
                .payloads.size,
        )
        assertPrivate(
            factory.transports
                .last()
                .payloads
                .single(),
        )
        unrelated.delete()
    }

    @Test
    fun `production asynchronous HTTP transport sends only sanitized payloads and removes delivered cache`() {
        val received = ArrayBlockingQueue<ByteArray>(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/1/envelope/") { exchange ->
            exchange.use {
                val input =
                    if (exchange.requestHeaders.getFirst("Content-Encoding") == "gzip") {
                        GZIPInputStream(exchange.requestBody)
                    } else {
                        exchange.requestBody
                    }
                received.add(input.use { it.readBytes() })
                exchange.sendResponseHeaders(200, -1)
            }
        }
        server.start()
        try {
            val current = SentryCrashReporter("http://public@127.0.0.1:${server.address.port}/1").also { reporter = it }
            current.configure(context, "production")
            current.setEnabled(true)
            Sentry.setUser(
                User().apply {
                    email = SECRET
                    id = SECRET
                },
            )
            current.captureException(IllegalStateException(SECRET))
            Sentry.flush(10_000)
            val payload = received.poll(10, TimeUnit.SECONDS)
            assertNotNull("Expected an actual loopback HTTP envelope", payload)
            assertPrivate(payload!!)
            val decoded = options.serializer.deserializeEnvelope(ByteArrayInputStream(payload))!!
            assertEquals(
                "IllegalStateException",
                decoded.items
                    .single()
                    .getEvent(options.serializer)!!
                    .exceptions!!
                    .single()
                    .type,
            )
            val remaining =
                File(context.cacheDir, SentryCrashReporter.PRIVATE_CACHE)
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "envelope" }
                    .toList()
            assertTrue("Delivered envelopes must not be replayed next launch", remaining.isEmpty())
            current.setEnabled(false)
            current.captureException(IllegalStateException(SECRET))
            assertTrue(received.isEmpty())
        } finally {
            reporter?.setEnabled(false)
            server.stop(0)
        }
    }

    @Test
    fun `interrupted optout cannot authorize reporting after process restart`() =
        runBlocking {
            val factory = RecordingFactory()
            val current = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            current.configure(context, "production")
            val manager = CrashReportManager(context)
            manager.updateCrashReportingConsent(true, current) { }
            assertTrue(Sentry.isEnabled())
            assertTrue(manager.isCrashReportingConsentGranted())

            val pendingWrite =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.updateCrashReportingConsent(false, current) { awaitCancellation() }
                }
            try {
                // The DataStore write is still suspended: both the live epoch and durable
                // startup authorization must already be off, including for a new manager.
                assertFalse(Sentry.isEnabled())
                assertTrue(MemoryDiagnostics.snapshot().isEmpty())
                assertFalse(CrashReportManager(context).isCrashReportingConsentGranted())
            } finally {
                pendingWrite.cancelAndJoin()
            }
            val restarted = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            restarted.configure(context, "production")
            CrashReportManager(context).initializeCrashReportingConsent(restarted) { false }
            assertFalse(Sentry.isEnabled())
            assertEquals(1, factory.options.size)
        }

    @Test
    fun `stale asynchronous grant cannot undo a newer optout`() =
        runBlocking {
            val factory = RecordingFactory()
            val current = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            current.configure(context, "production")
            val manager = CrashReportManager(context)
            val allowGrantToFinish = CompletableDeferred<Unit>()
            var persistedConsent = false
            val grant =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.updateCrashReportingConsent(true, current) {
                        allowGrantToFinish.await()
                        persistedConsent = it
                    }
                }
            val revoke =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.updateCrashReportingConsent(false, current) { persistedConsent = it }
                }
            assertFalse(Sentry.isEnabled())
            allowGrantToFinish.complete(Unit)
            grant.join()
            revoke.join()
            assertFalse(persistedConsent)
            assertFalse(manager.isCrashReportingConsentGranted())
            assertTrue(factory.options.isEmpty())
            assertTrue(MemoryDiagnostics.snapshot().isEmpty())
        }

    @Test
    fun `legacy startup permission cannot override a persisted optout`() =
        runBlocking {
            val factory = RecordingFactory()
            val original = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            original.configure(context, "production")
            val manager = CrashReportManager(context)
            manager.updateCrashReportingConsent(true, original) { }
            original.setEnabled(false) // A process shutdown does not itself update startup permission.
            assertTrue(manager.isCrashReportingConsentGranted())
            val restarted = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            restarted.configure(context, "production")
            manager.initializeCrashReportingConsent(restarted) { false }
            assertFalse(manager.isCrashReportingConsentGranted())
            assertFalse(Sentry.isEnabled())
            assertTrue(MemoryDiagnostics.snapshot().isEmpty())
            assertEquals(1, factory.options.size)
        }

    @Test
    fun `delayed startup read cannot override a newer optout`() =
        runBlocking {
            val factory = RecordingFactory()
            val current = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            current.configure(context, "production")
            val manager = CrashReportManager(context)
            manager.updateCrashReportingConsent(true, current) { }
            current.setEnabled(false)
            val readPermission = CompletableDeferred<Boolean>()
            val startup =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.initializeCrashReportingConsent(current) { readPermission.await() }
                }
            val revoke =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.updateCrashReportingConsent(false, current) { }
                }
            readPermission.complete(true)
            startup.join()
            revoke.join()
            assertFalse(Sentry.isEnabled())
            assertFalse(manager.isCrashReportingConsentGranted())
            assertEquals(1, factory.options.size)
        }

    @Test
    fun `interruption during SDK shutdown leaves durable startup denial`() =
        runBlocking {
            val factory = RecordingFactory()
            val current = SentryCrashReporter(TEST_DSN, factory).also { reporter = it }
            current.configure(context, "production")
            val manager = CrashReportManager(context)
            manager.updateCrashReportingConsent(true, current) { }
            val interruptedShutdown = mockk<CrashReporter>()
            every { interruptedShutdown.setEnabled(false) } throws IllegalStateException("simulated shutdown interruption")
            val failure =
                runCatching {
                    manager.updateCrashReportingConsent(false, interruptedShutdown) {
                        error("Preference persistence must not be reached after shutdown interruption")
                    }
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(CrashReportManager(context).isCrashReportingConsentGranted())
        }

    @Test
    fun `SDK cache cleanup accepts aliased parents without following selected links`() {
        val root = Files.createTempDirectory("sentry-cache-alias").toFile()
        val realCache = File(root, "real-cache").apply { mkdirs() }
        val protected = File(realCache, "protected").apply { mkdirs() }
        val canary = File(protected, "identity-material").apply { writeText(SECRET) }
        val alias = File(root, "cache-alias")
        Files.createSymbolicLink(alias.toPath(), realCache.toPath())
        try {
            val sdk = File(realCache, SentryCrashReporter.PRIVATE_CACHE).apply { mkdirs() }
            File(sdk, "pending.envelope").writeText("sanitized pending report")
            Files.createSymbolicLink(File(sdk, "escape").toPath(), protected.toPath())
            assertTrue(SentryCrashReporter.purgeSdkDirectory(File(alias, SentryCrashReporter.PRIVATE_CACHE)))
            assertFalse(sdk.exists())
            assertTrue(alias.isDirectory)
            assertEquals(SECRET, canary.readText())

            Files.createSymbolicLink(sdk.toPath(), protected.toPath())
            assertTrue(SentryCrashReporter.purgeSdkDirectory(File(alias, SentryCrashReporter.PRIVATE_CACHE)))
            assertFalse(Files.isSymbolicLink(sdk.toPath()))
            assertTrue(protected.isDirectory)
            assertEquals(SECRET, canary.readText())
        } finally {
            Files.deleteIfExists(alias.toPath())
            root.deleteRecursively()
        }
    }

    private fun hostileEvent(): SentryEvent {
        val sample =
            JSONObject().apply {
                put("uptime_minutes_bucket", 8)
                put("heap_used_mib_bucket", 128)
                put("heap_limit_mib_bucket", 256)
                put("heap_committed_mib_bucket", 256)
                put("heap_usage_decile", 8)
                put("exports_bucket", 1)
                put("imports_bucket", 0)
                put("foreground", "foreground")
                put("trim", "running_low")
                put(SECRET, SECRET)
            }
        val memory =
            JSONObject().apply {
                put("schema_version", 1)
                put("process_role", "app")
                put("sample_interval_seconds", 60)
                put("samples", org.json.JSONArray(List(20) { sample }))
                put(SECRET, SECRET)
            }
        val json =
            """
            {
              "message":{"formatted":"$SECRET","message":"$SECRET","params":["$SECRET"]},
              "logger":"$SECRET","transaction":"$SECRET","server_name":"$SECRET",
              "release":"$SECRET","environment":"$SECRET","dist":"$SECRET",
              "user":{"id":"$SECRET","email":"$SECRET","ip_address":"$SECRET","data":{"nested":"$SECRET"}},
              "request":{"url":"$SECRET","method":"$SECRET","cookies":"$SECRET","headers":{"Authorization":"$SECRET"},"data":{"nested":"$SECRET"}},
              "tags":{"private":"$SECRET"},"extra":{"nested":{"secret":"$SECRET"}},
              "fingerprint":["$SECRET"],"modules":{"private":"$SECRET"},
              "contexts":{"unknown":{"nested":"$SECRET"},"memory_diagnostics":$memory,"device":{"id":"$SECRET","name":"$SECRET"},"app":{"device_app_hash":"$SECRET"}},
              "breadcrumbs":[{"message":"$SECRET","category":"$SECRET","data":{"nested":"$SECRET"}}],
              "threads":{"values":[{"id":123,"name":"$SECRET","stacktrace":{"registers":{"secret":"$SECRET"}}}]},
              "exception":{"values":[{"type":"IllegalStateException","module":"java.lang","value":"$SECRET","thread_id":123,
                "mechanism":{"type":"$SECRET","handled":false,"data":{"secret":"$SECRET"},"meta":{"secret":"$SECRET"}},
                "stacktrace":{"registers":{"private":"$SECRET"},"frames":[{"module":"network.columba.app.SafeClass","function":"performOperation","lineno":42,"filename":"$SECRET","abs_path":"$SECRET","context_line":"$SECRET","pre_context":["$SECRET"],"post_context":["$SECRET"],"vars":{"secret":"$SECRET"},"package":"$SECRET","raw_function":"$SECRET","unknown":"$SECRET"}]},"unknown":"$SECRET"}]},
              "unknown":{"nested":"$SECRET"}
            }
            """.trimIndent()
        return options.serializer.deserialize(StringReader(json), SentryEvent::class.java)!!.apply {
            release = "network.columba.app@1.2.3+123"
        }
    }

    private fun assertPrivate(bytes: ByteArray) {
        val serialized = bytes.toString(Charsets.UTF_8)
        assertFalse(serialized, serialized.contains(SECRET))
        assertFalse(serialized, serialized.contains("attachment"))
        assertFalse(serialized, serialized.contains("thread_id"))
        assertFalse(serialized, serialized.contains("abs_path"))
        assertFalse(serialized, serialized.contains("registers"))
    }

    private class RecordingFactory : ITransportFactory {
        val options = CopyOnWriteArrayList<SentryOptions>()
        val transports = CopyOnWriteArrayList<RecordingTransport>()

        override fun create(
            options: SentryOptions,
            requestDetails: io.sentry.RequestDetails,
        ): ITransport {
            this.options.add(options)
            return RecordingTransport(options).also { transports.add(it) }
        }
    }

    private class RecordingTransport(
        private val options: SentryOptions,
    ) : ITransport {
        val payloads = CopyOnWriteArrayList<ByteArray>()

        override fun send(
            envelope: SentryEnvelope,
            hint: Hint,
        ) {
            val output = ByteArrayOutputStream()
            options.serializer.serialize(envelope, output)
            payloads.add(output.toByteArray())
        }

        override fun flush(timeoutMillis: Long) = Unit

        override fun getRateLimiter(): RateLimiter? = null

        override fun close() = Unit

        override fun close(isRestarting: Boolean) = Unit
    }

    companion object {
        private const val BUILD_UUID = "bba4455b-08d6-4c2c-9311-4c830019cbbc"
        private const val TEST_DSN = "https://public@example.invalid/1"
        private const val SECRET = "CANARY_private_identity-7f33@private.invalid/path"
    }
}
