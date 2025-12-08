package com.lxmf.messenger.reticulum.util

import android.util.Log

/**
 * Utility for extracting peer names from announce data.
 *
 * Name parsing is handled by Python's canonical LXMF functions:
 * - LXMF.display_name_from_app_data() for lxmf.delivery and nomadnetwork.node
 * - LXMF.pn_name_from_app_data() for lxmf.propagation
 *
 * This class just uses the pre-parsed displayName or generates a fallback.
 *
 * See: https://github.com/torlando-tech/columba/issues/41
 */
object AppDataParser {
    private const val TAG = "Columba:Kotlin:AppDataParser"

    /**
     * Extract a peer name from announce data.
     *
     * @param appData The app_data bytes (unused, kept for API compatibility)
     * @param destinationHash The destination hash hex string (used for fallback name)
     * @param displayName Pre-parsed display name from Python's LXMF functions
     * @return A human-readable peer name
     */
    @Suppress("UNUSED_PARAMETER")
    fun extractPeerName(
        appData: ByteArray?,
        destinationHash: String,
        displayName: String? = null,
    ): String {
        // Use Python-provided display name if valid
        if (!displayName.isNullOrBlank()) {
            Log.d(TAG, "Using display name from Python: $displayName")
            return displayName
        }

        // Fallback to hash-based name
        Log.d(TAG, "No display name, using fallback")
        return generateFallbackName(destinationHash)
    }

    /**
     * Generate a fallback name based on the destination hash.
     *
     * @param destinationHash The hex string of the destination hash
     * @return A formatted name like "Peer 970A60FC"
     */
    private fun generateFallbackName(destinationHash: String): String =
        if (destinationHash.length >= 8) {
            "Peer ${destinationHash.take(8).uppercase()}"
        } else {
            "Unknown Peer"
        }
}
