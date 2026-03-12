package com.lxmf.messenger.reticulum.bindings.lxmf

/**
 * LXMF message lifecycle states.
 *
 * Maps to Python LXMF.LXMessage state constants:
 * - DRAFT (0), OUTBOUND (1), SENDING (2), SENT (3), DELIVERED (4), FAILED (5)
 */
enum class LxmfMessageState {
    DRAFT,
    OUTBOUND,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
}
