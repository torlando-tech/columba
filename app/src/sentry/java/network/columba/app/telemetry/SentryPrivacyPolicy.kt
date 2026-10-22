package network.columba.app.telemetry

import io.sentry.ISerializer
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.SentryLevel
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace

/** An output allowlist, not a best-effort redactor. Never serialize the input object. */
internal class SentryPrivacyPolicy(
    private val release: String,
    private val environment: String,
    private val proguardUuid: String? = null,
) {
    fun event(input: SentryEvent): SentryEvent? {
        val exceptions =
            input.exceptions.orEmpty().takeLast(8).mapNotNull { exception ->
                val safeType = symbol(exception.type) ?: return@mapNotNull null
                SentryException().apply {
                    type = safeType
                    module = symbol(exception.module)
                    exception.mechanism?.let { original ->
                        mechanism =
                            Mechanism().apply {
                                type = "generic"
                                isHandled = original.isHandled
                            }
                    }
                    exception.stacktrace?.frames?.let { frames ->
                        stacktrace =
                            SentryStackTrace(
                                frames.takeLast(128).map { frame ->
                                    SentryStackFrame().apply {
                                        module = symbol(frame.module)
                                        function = symbol(frame.function)
                                        lineno = frame.lineno?.takeIf { it in 1..1_000_000 }
                                        isInApp = frame.isInApp
                                    }
                                },
                            )
                    }
                }
            }
        val memoryWarning = input.message?.formatted == HIGH_MEMORY_MESSAGE && input.level == SentryLevel.WARNING
        if (exceptions.isEmpty() && !memoryWarning) return null
        return SentryEvent(input.timestamp).apply {
            eventId = input.eventId
            platform = "java"
            release = this@SentryPrivacyPolicy.release
            environment = this@SentryPrivacyPolicy.environment
            level = if (memoryWarning) SentryLevel.WARNING else input.level ?: SentryLevel.ERROR
            if (exceptions.isNotEmpty()) this.exceptions = exceptions
            if (memoryWarning) message = Message().apply { formatted = HIGH_MEMORY_MESSAGE }
            proguardUuid?.let { buildUuid ->
                debugMeta =
                    DebugMeta().apply {
                        images =
                            listOf(
                                DebugImage().apply {
                                    type = DebugImage.PROGUARD
                                    uuid = buildUuid
                                },
                            )
                    }
            }
            memorySnapshot(input.contexts[MEMORY_CONTEXT])?.let { contexts[MEMORY_CONTEXT] = it }
        }
    }

    /** This is also applied on cache write and replay, including SDK captureEnvelope paths. */
    fun envelope(
        input: SentryEnvelope,
        serializer: ISerializer,
    ): SentryEnvelope? {
        val items = input.items.iterator()
        var inspected = 0
        while (items.hasNext() && inspected++ < 16) {
            val item = items.next()
            if (item.header.type != SentryItemType.Event) continue
            val safe =
                try {
                    if (item.data.size > 256 * 1024) return null
                    val event = item.getEvent(serializer) ?: return null
                    // Never relabel a cached error from a different build with this build's mapping.
                    if (event.release != release) return null
                    if (event.debugMeta?.images.orEmpty().any {
                            it.type == DebugImage.PROGUARD && it.uuid != proguardUuid
                        }
                    ) {
                        return null
                    }
                    this.event(event)
                } catch (_: Exception) {
                    null
                } ?: return null
            return SentryEnvelope(
                SentryEnvelopeHeader(safe.eventId),
                listOf(SentryEnvelopeItem.fromEvent(serializer, safe)),
            )
        }
        return null
    }

    private fun symbol(value: String?): String? = value?.takeIf { it.length <= 256 && SYMBOL.matches(it) }

    private fun memorySnapshot(value: Any?): Map<String, Any>? {
        val input = value as? Map<*, *> ?: return null
        if (input["schema_version"] != 1 || input["sample_interval_seconds"] != 60) return null
        val role = input["process_role"] as? String ?: return null
        if (role !in setOf("app", "service", "unknown")) return null
        val samples = input["samples"] as? List<*> ?: return null
        val safeSamples =
            samples.takeLast(16).mapNotNull { raw ->
                val sample = raw as? Map<*, *> ?: return@mapNotNull null
                val safe = linkedMapOf<String, Any>()
                for ((key, maximum) in BUCKETS) {
                    if (!sample.containsKey(key) && key in OPTIONAL_COUNTERS) continue
                    val bucket = sample[key] as? Int ?: return@mapNotNull null
                    if (bucket !in 0..maximum || (bucket != 0 && bucket and (bucket - 1) != 0)) return@mapNotNull null
                    safe[key] = bucket
                }
                val decile = sample["heap_usage_decile"] as? Int ?: return@mapNotNull null
                if (decile !in 0..10) return@mapNotNull null
                safe["heap_usage_decile"] = decile
                val foreground = sample["foreground"] as? String ?: return@mapNotNull null
                val trim = sample["trim"] as? String ?: return@mapNotNull null
                if (foreground !in FOREGROUND || trim !in TRIM) return@mapNotNull null
                safe["foreground"] = foreground
                safe["trim"] = trim
                safe
            }
        return mapOf(
            "schema_version" to 1,
            "process_role" to role,
            "sample_interval_seconds" to 60,
            "samples" to safeSamples,
        )
    }

    companion object {
        const val HIGH_MEMORY_MESSAGE = "High memory pressure"
        const val MEMORY_CONTEXT = "memory_diagnostics"

        // JVM symbols only: no paths, values, generics, source text or native addresses.
        private val SYMBOL = Regex("[A-Za-z_$][A-Za-z0-9_$.<>-]*")
        private val FOREGROUND = setOf("foreground", "background", "unknown")
        private val TRIM =
            setOf(
                "none",
                "running_moderate",
                "running_low",
                "running_critical",
                "ui_hidden",
                "background",
                "moderate",
                "complete",
                "unknown",
            )
        private val BUCKETS =
            mapOf(
                "uptime_minutes_bucket" to 262144,
                "heap_used_mib_bucket" to 65536,
                "heap_limit_mib_bucket" to 65536,
                "heap_committed_mib_bucket" to 65536,
                "processed_message_ids_bucket" to 1048576,
                "peer_names_bucket" to 1048576,
                "pending_relay_retries_bucket" to 1048576,
                "exports_bucket" to 1048576,
                "imports_bucket" to 1048576,
            )
        private val OPTIONAL_COUNTERS =
            setOf(
                "processed_message_ids_bucket",
                "peer_names_bucket",
                "pending_relay_retries_bucket",
            )
    }
}
