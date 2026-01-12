package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing received location telemetry from contacts.
 *
 * Locations are stored to allow:
 * - Displaying on map even when offline
 * - History/trail visualization
 * - Cleanup of expired locations
 */
@Entity(
    tableName = "received_locations",
    indices = [
        Index("senderHash"), // For querying locations by contact
        Index("senderHash", "timestamp"), // For getting latest per contact
        Index("expiresAt"), // For cleanup of expired locations
    ],
)
data class ReceivedLocationEntity(
    @PrimaryKey
    val id: String, // UUID generated on receive
    val senderHash: String, // Destination hash of the sender
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float, // In meters
    val timestamp: Long, // When the location was captured (from sender)
    val expiresAt: Long?, // When sharing ends (null = indefinite)
    val receivedAt: Long, // When we received this update
    val approximateRadius: Int = 0, // Coarsening radius in meters (0 = precise)
    val appearanceJson: String? = null, // Icon appearance JSON: {"name":"icon", "fg":"#RRGGBB", "bg":"#RRGGBB"}
)
