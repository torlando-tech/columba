package network.columba.app.data.model

/** Identity-scoped call evidence enriched with the peer's current presentation mapping. */
data class CallHistoryRecord(
    val callAttemptId: String,
    val localIdentityHash: String,
    val remoteIdentityHash: String,
    val direction: String,
    val peerDisplayNameSnapshot: String?,
    val codecProfileCode: Int?,
    val attemptedAt: Long,
    val ringingAt: Long?,
    val connectedAt: Long?,
    val endedAt: Long?,
    val outcome: String?,
    val inferredEnding: Boolean,
    val failureReason: String?,
    val displayName: String?,
    val currentDestinationHash: String?,
    val localIdentityName: String? = null,
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
)
