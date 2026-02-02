package com.lxmf.messenger.service.util

import android.util.Log

/**
 * Centralized peer name resolution logic.
 *
 * This utility provides a single source of truth for resolving peer display names,
 * used by both MessageCollector (app process) and ServicePersistenceManager (service process).
 *
 * The resolution priority is:
 * 1. Contact custom nickname (user-set, highest priority)
 * 2. Announce peer name (from network)
 * 3. Conversation peer name (from existing conversation)
 * 4. Formatted hash fallback (e.g., "Peer ABCD1234")
 */
object PeerNameResolver {
    private const val TAG = "PeerNameResolver"

    /**
     * Resolve peer display name using the standard lookup chain.
     *
     * @param peerHash The destination hash of the peer
     * @param cachedName Optional cached name (checked first for performance)
     * @param contactNicknameLookup Lambda to look up contact's custom nickname
     * @param announcePeerNameLookup Lambda to look up peer name from announce
     * @param conversationPeerNameLookup Lambda to look up peer name from conversation
     * @return The resolved peer name, or formatted hash if not found
     */
    suspend fun resolve(
        peerHash: String,
        cachedName: String? = null,
        contactNicknameLookup: (suspend () -> String?)? = null,
        announcePeerNameLookup: (suspend () -> String?)? = null,
        conversationPeerNameLookup: (suspend () -> String?)? = null,
    ): String {
        // Check cache first (fastest)
        cachedName?.let {
            if (isValidPeerName(it)) {
                Log.d(TAG, "Found peer name in cache: $it")
                return it
            }
        }

        // Check contact custom nickname (highest priority for user-set names)
        contactNicknameLookup?.let { lookup ->
            try {
                lookup()?.let { nickname ->
                    if (isValidPeerName(nickname)) {
                        Log.d(TAG, "Found peer name in contact nickname: $nickname")
                        return nickname
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error looking up contact nickname", e)
            }
        }

        // Check announce peer name (from network)
        announcePeerNameLookup?.let { lookup ->
            try {
                lookup()?.let { name ->
                    if (isValidPeerName(name)) {
                        Log.d(TAG, "Found peer name in announce: $name")
                        return name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error looking up announce peer name", e)
            }
        }

        // Check conversation peer name
        conversationPeerNameLookup?.let { lookup ->
            try {
                lookup()?.let { name ->
                    if (isValidPeerName(name)) {
                        Log.d(TAG, "Found peer name in conversation: $name")
                        return name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error looking up conversation peer name", e)
            }
        }

        // Fall back to formatted hash
        val fallbackName = formatHashAsFallback(peerHash)
        Log.d(TAG, "Using fallback name for peer: $fallbackName")
        return fallbackName
    }

    /**
     * Check if a peer name is valid (not a placeholder or fallback).
     */
    fun isValidPeerName(name: String?): Boolean =
        !name.isNullOrBlank() &&
            name != "Unknown" &&
            !name.startsWith("Peer ")

    /**
     * Format a peer hash as a fallback display name.
     */
    fun formatHashAsFallback(peerHash: String): String =
        if (peerHash.length >= 8) {
            "Peer ${peerHash.take(8).uppercase()}"
        } else {
            "Unknown Peer"
        }
}
