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

    /** Max chars of a user-entered thread title (automatic excerpts keep the 120-char cap). */
    const val MAX_THREAD_TITLE_LENGTH = 64

    private val WHITESPACE_RUN = Regex("\\s+")

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

    /**
     * Normalize a user-entered thread title before it is stored:
     * whitespace runs collapse to one space, control characters (newlines,
     * tabs, format marks) are dropped, the result is trimmed and capped at
     * [MAX_THREAD_TITLE_LENGTH].
     *
     * Null stays null and a blank/control-only input also becomes null —
     * NULL is the "automatic title" state (threads-spec D8), so an empty
     * custom title is the same thing as resetting it.
     */
    fun sanitizeThreadTitle(raw: String?): String? {
        if (raw == null) return null
        // Control characters become spaces before collapsing: dropping them
        // would glue the words they separated ("Build\nnotes" -> "Buildnotes").
        val normalized = raw.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        val flattened = normalized.replace(WHITESPACE_RUN, " ").trim()
        val capped = flattened.take(MAX_THREAD_TITLE_LENGTH).trim()
        return capped.ifBlank { null }
    }

    /** True when a thread's [lastActivityAt] puts it outside the dormancy window at [now]. */
    fun isDormant(
        lastActivityAt: Long,
        now: Long,
    ): Boolean = now - lastActivityAt > DORMANCY_WINDOW_MS
}
