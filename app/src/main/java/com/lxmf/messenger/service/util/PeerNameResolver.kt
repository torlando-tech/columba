package com.lxmf.messenger.service.util

import android.util.Log

/**
 * Centralized peer name resolution logic.
 *
 * This utility provides a single source of truth for resolving peer display names,
 * used by both MessageCollector (app process) and ServicePersistenceManager (service process).
 *
 * The resolution priority is:
 * 1. Contact custom nickname (user-set, always wins)
 * 2. In-memory cache (fast path for repeated lookups)
 * 3. Announce peer name (from network)
 * 4. Conversation peer name (from existing conversation)
 * 5. Formatted hash fallback (e.g., "Peer ABCD1234")
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
    ): String =
        tryLookup("contact", contactNicknameLookup)
            ?: tryLookup("cache") { cachedName }
            ?: tryLookup("announce", announcePeerNameLookup)
            ?: tryLookup("conversation", conversationPeerNameLookup)
            ?: formatHashAsFallback(peerHash).also {
                Log.d(TAG, "Using fallback name for peer")
            }

    /**
     * Attempt a single lookup, returning the name only if valid.
     * Handles null lookups and exceptions gracefully.
     */
    private suspend fun tryLookup(
        source: String,
        lookup: (suspend () -> String?)?,
    ): String? {
        if (lookup == null) return null
        return try {
            lookup()?.takeIf { isValidPeerName(it) }?.also {
                Log.d(TAG, "Found peer name in $source")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking up $source peer name", e)
            null
        }
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
