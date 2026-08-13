package network.columba.app.data.model

/** Identity-oriented projection over destination-scoped block enforcement rows. */
data class BlockedIdentity(
    val remoteIdentityHash: String,
    val localIdentityHash: String,
    val displayName: String?,
    val destinationHashes: List<String>,
    val blockedTimestamp: Long,
    val isBlackholeEnabled: Boolean,
    val identityAuthoritative: Boolean,
    val destinationAspects: Map<String, String?> = emptyMap(),
)
