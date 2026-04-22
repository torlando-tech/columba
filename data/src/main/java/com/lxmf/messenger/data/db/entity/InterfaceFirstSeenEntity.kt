package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks the first time a discovered network interface was seen.
 * Uses INSERT OR IGNORE to preserve the original timestamp across re-discoveries.
 *
 * This is the only interface discovery field stored in Room. All other fields
 * (last heard, hops, coordinates, radio params, etc.) are persisted by RNS itself
 * in ~/.reticulum/storage/discovery/interfaces/ as msgpack files and survive app
 * restarts. First-seen is not tracked by RNS, so we persist it here.
 *
 * When migrating to reticulum-kt, this table should be retained unless the Kotlin
 * implementation adds first-seen tracking natively.
 */
@Entity(tableName = "interface_first_seen")
data class InterfaceFirstSeenEntity(
    @PrimaryKey val interfaceId: String,
    val firstSeenTimestamp: Long,
)
