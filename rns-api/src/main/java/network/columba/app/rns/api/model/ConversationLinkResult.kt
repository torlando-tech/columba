package network.columba.app.rns.api.model

/**
 * Result of conversation link operations.
 *
 * Contains all metrics needed for connection quality assessment and
 * image transfer time estimation.
 */
data class ConversationLinkResult(
    /** Whether link is currently active. */
    val isActive: Boolean,
    /** Link establishment rate in bits/sec (if active). */
    val establishmentRateBps: Long? = null,
    /** Actual measured throughput from prior transfers (most accurate). */
    val expectedRateBps: Long? = null,
    /** First hop interface bitrate (for fast links like WiFi). */
    val nextHopBitrateBps: Long? = null,
    /** Round-trip time in seconds. */
    val rttSeconds: Double? = null,
    /** Number of hops to destination. */
    val hops: Int? = null,
    /** Link MTU in bytes (higher = faster connection). */
    val linkMtu: Int? = null,
    /** Whether the link already existed (for establish operations). */
    val alreadyExisted: Boolean = false,
    /** Error message if operation failed. */
    val error: String? = null,
)
