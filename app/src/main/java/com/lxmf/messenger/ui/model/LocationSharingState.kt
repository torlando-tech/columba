package com.lxmf.messenger.ui.model

/**
 * Represents the location sharing state between the current user and a contact.
 *
 * This enum captures the bidirectional nature of location sharing:
 * - We can share our location with them (outgoing)
 * - They can share their location with us (incoming)
 * - Both directions can be active simultaneously (mutual)
 */
enum class LocationSharingState {
    /** No location sharing in either direction */
    NONE,

    /** We are sharing our location with them, but they are not sharing with us */
    SHARING_WITH_THEM,

    /** They are sharing their location with us, but we are not sharing with them */
    THEY_SHARE_WITH_ME,

    /** Both directions are active - mutual location sharing */
    MUTUAL,
}
