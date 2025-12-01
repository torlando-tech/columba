package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing known peer identities (public keys) separately from conversations.
 * This allows us to restore peer identities on app startup even for peers we haven't
 * chatted with yet. Peer identities are global (not identity-scoped) since they
 * represent other nodes on the network.
 */
@Entity(tableName = "peer_identities")
data class PeerIdentityEntity(
    @PrimaryKey
    val peerHash: String, // Identity hash (SHA256 of public key) - NOT destination hash
    val publicKey: ByteArray, // Public key for identity restoration
    val lastSeenTimestamp: Long, // When we last saw an announce from this peer
)
