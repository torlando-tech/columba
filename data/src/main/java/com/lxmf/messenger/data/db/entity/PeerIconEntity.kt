package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing peer icon appearances received via LXMF messages.
 *
 * Icons are transmitted in LXMF Field 4 (FIELD_ICON_APPEARANCE) and represent
 * the user's chosen visual identity. This is separate from announces (Reticulum
 * network discovery) - icons are an LXMF-layer concept.
 *
 * This table is the single source of truth for peer icons and is joined
 * by conversations, announces, and contacts queries to display icons consistently.
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
