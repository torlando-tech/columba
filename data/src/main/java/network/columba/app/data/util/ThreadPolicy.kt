package network.columba.app.data.util

import java.security.SecureRandom

/**
 * Locked thread-policy constants from the threads spec (manager calls on the
 * spec's open questions, TOR-24):
 *  - dormancy window = 14 days,
 *  - quote excerpt cap = 120 chars,
 *  - thread IDs = 16 fresh CSPRNG bytes, reused for the thread's lifetime.
 */
object ThreadPolicy {
    /** Wire thread-ID length in bytes (threads-spec §4.1: `os.urandom(16)` equivalent). */
    const val THREAD_ID_SIZE_BYTES = 16

    /** Dormancy window: no activity for this long => DORMANT (derived, local-only). */
    const val DORMANCY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    /** Max chars of quoted/root content carried on the wire (0x31) or used as a local thread title. */
    const val QUOTE_EXCERPT_MAX_CHARS = 120

    private val secureRandom = SecureRandom()

    /**
     * Fresh thread ID: 16 random bytes from a CSPRNG. Random, not derived —
     * hashing a timestamp/identity/root would create correlation handles
     * (threads-spec §4.1). Call once per thread; reuse the value for the
     * thread's whole lifetime.
     */
    fun newThreadId(): ByteArray = ByteArray(THREAD_ID_SIZE_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * Local thread title for implicit creation: truncated excerpt of the
     * first message (threads-spec §4.3), capped at [QUOTE_EXCERPT_MAX_CHARS]
     * with an ellipsis when truncated. Titles never touch the wire.
     */
    fun threadTitleExcerpt(content: String): String {
        val flattened = content.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= QUOTE_EXCERPT_MAX_CHARS) {
            flattened
        } else {
            flattened.take(QUOTE_EXCERPT_MAX_CHARS - 1) + "…"
        }
    }

    /** Wire quote excerpt for `fields[0x31]` (cases 2/4), capped per the locked decision. */
    fun quoteExcerpt(content: String): String =
        if (content.length <= QUOTE_EXCERPT_MAX_CHARS) content else content.take(QUOTE_EXCERPT_MAX_CHARS)

    /** True when a thread's [lastActivityAt] puts it outside the dormancy window at [now]. */
    fun isDormant(
        lastActivityAt: Long,
        now: Long,
    ): Boolean = now - lastActivityAt > DORMANCY_WINDOW_MS
}
