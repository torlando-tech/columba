package network.columba.app.telemetry

import io.sentry.Hint
import io.sentry.ISerializer
import io.sentry.ITransportFactory
import io.sentry.RequestDetails
import io.sentry.SentryEnvelope
import io.sentry.SentryOptions
import io.sentry.cache.IEnvelopeCache
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.util.WeakHashMap

/** One token per opt-in epoch. An old queued send can never become enabled again. */
internal class SentryConsent {
    @Volatile
    var granted: Boolean = true
        private set

    @Synchronized
    fun revoke() {
        granted = false
    }

    @Synchronized
    fun <T> ifGranted(action: () -> T): T? = if (granted) action() else null
}

internal class SentryPrivacyTransportFactory(
    private val consent: SentryConsent,
    private val policy: SentryPrivacyPolicy,
    private val delegate: ITransportFactory,
) : ITransportFactory {
    override fun create(
        options: SentryOptions,
        requestDetails: RequestDetails,
    ): ITransport = SentryPrivacyTransport(consent, policy, options.serializer, delegate.create(options, requestDetails))
}

internal class SentryPrivacyTransport(
    private val consent: SentryConsent,
    private val policy: SentryPrivacyPolicy,
    private val serializer: ISerializer,
    private val delegate: ITransport,
) : ITransport {
    override fun send(
        envelope: SentryEnvelope,
        hint: Hint,
    ) {
        consent.ifGranted {
            val safe = policy.envelope(envelope, serializer) ?: return@ifGranted
            hint.clearAttachments()
            hint.setScreenshot(null)
            hint.setViewHierarchy(null)
            hint.setThreadDump(null)
            hint.replayRecording = null
            delegate.send(safe, hint)
        }
    }

    override fun flush(timeoutMillis: Long) = delegate.flush(timeoutMillis)

    override fun getRateLimiter(): RateLimiter? = delegate.rateLimiter

    override fun isHealthy(): Boolean = consent.granted && delegate.isHealthy

    override fun close() = close(false)

    override fun close(isRestarting: Boolean) {
        // The async transport's consent gate also checks immediately before HTTP.
        delegate.close(isRestarting)
    }
}

/** Even direct SDK cache writes are reconstructed before touching disk. */
internal class SentryPrivacyCache(
    private val consent: SentryConsent,
    private val policy: SentryPrivacyPolicy,
    private val serializer: ISerializer,
    private val delegate: IEnvelopeCache,
) : IEnvelopeCache {
    // SDK cache filenames are keyed by envelope object identity, not event ID.
    // Preserve the caller-to-sanitized identity so successful sends delete their cached file.
    private val stored = WeakHashMap<SentryEnvelope, SentryEnvelope>()

    override fun store(
        envelope: SentryEnvelope,
        hint: Hint,
    ) {
        storeEnvelope(envelope, hint)
    }

    override fun storeEnvelope(
        envelope: SentryEnvelope,
        hint: Hint,
    ): Boolean =
        consent.ifGranted {
            val safe = policy.envelope(envelope, serializer) ?: return@ifGranted false
            synchronized(stored) {
                stored.remove(envelope)?.let { delegate.discard(it) }
                if (delegate.storeEnvelope(safe, hint)) {
                    stored[envelope] = safe
                    true
                } else {
                    false
                }
            }
        } ?: false

    override fun discard(envelope: SentryEnvelope) {
        val safe = synchronized(stored) { stored.remove(envelope) }
        delegate.discard(safe ?: envelope)
    }

    override fun iterator(): MutableIterator<SentryEnvelope> = delegate.iterator()
}
