package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Represents a blocked peer for a specific local identity.
 *
 * Block (LXMF-level): Silently drops incoming messages from this peer.
 * Blackhole (Reticulum Transport-level): Drops path entries and invalidates announces
 * for this peer's identity at the transport layer, preventing relay.
 *
 * These are separate toggles — a user might block someone (no messages) but still
 * relay their announces, or blackhole them (no relay) without blocking messages.
 *
 * Note: peerHash is the LXMF destination hash; peerIdentityHash is the Reticulum
 * identity hash (public key hash). A single identity can own multiple destinations.
 */
@Entity(
    tableName = "blocked_peers",
    primaryKeys = ["peerHash", "identityHash"],
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("identityHash"),
    ],
)
data class BlockedPeerEntity(
    val peerHash: String,
    val identityHash: String,
    val peerIdentityHash: String?,
    val displayName: String?,
    val blockedTimestamp: Long,
    val isBlackholeEnabled: Boolean = false,
)
