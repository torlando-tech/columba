package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores information about children paired with this device (parent side).
 * When this device acts as a guardian, it tracks which children have paired.
 */
@Entity(tableName = "paired_children")
data class PairedChildEntity(
    @PrimaryKey
    val childDestinationHash: String,

    /** Display name of the child (from their announce or manual entry) */
    val displayName: String? = null,

    /** Whether this child is currently locked */
    val isLocked: Boolean = false,

    /** Timestamp when lock was last changed */
    val lockChangedTimestamp: Long = 0,

    /** Timestamp when this child paired with us */
    val pairedTimestamp: Long = System.currentTimeMillis(),

    /** Last time we received any message from this child */
    val lastSeenTimestamp: Long = 0,

    /** The identity hash this child is associated with (our identity as guardian) */
    val guardianIdentityHash: String,
)
