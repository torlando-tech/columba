package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing peer icon appearances from LXMF messages (Field 4).
 *
 * Icons are an LXMF concept (transmitted in messages), separate from announces
 * (which are a Reticulum concept for network peer discovery).
 *
 * This table is joined by UI queries (conversations, announces, contacts) to
 * display icons wherever a peer is rendered.
 */
@Entity(tableName = "peer_icons")
data class PeerIconEntity(
    @PrimaryKey
    val destinationHash: String,
    val iconName: String,
    val foregroundColor: String, // Hex RGB e.g., "FFFFFF"
    val backgroundColor: String, // Hex RGB e.g., "1E88E5"
    val updatedTimestamp: Long,
)
