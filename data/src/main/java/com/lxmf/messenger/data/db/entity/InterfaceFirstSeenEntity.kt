package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks the first time a discovered network interface was seen.
 * Uses INSERT OR IGNORE to preserve the original timestamp across re-discoveries.
 */
@Entity(tableName = "interface_first_seen")
data class InterfaceFirstSeenEntity(
    @PrimaryKey val interfaceId: String,
    val firstSeenTimestamp: Long,
)
