package com.lxmf.messenger.data.model

import com.lxmf.messenger.data.db.entity.ContactStatus

/**
 * Enriched contact data combining contacts table with announces and conversations.
 *
 * This model represents the result of a join query that combines:
 * - Contact data (from contacts table)
 * - Network status (from announces table)
 * - Conversation status (from conversations table)
 */
data class EnrichedContact(
    val destinationHash: String,
    val publicKey: ByteArray?, // Nullable for pending contacts
    // Display name with priority: customNickname > announceName > destinationHash
    val displayName: String,
    val customNickname: String?,
    val announceName: String?,
    // Network status (from announces table, null if never announced)
    val lastSeenTimestamp: Long?,
    val hops: Int?,
    val isOnline: Boolean,
    // Conversation status (from conversations table)
    val hasConversation: Boolean,
    val unreadCount: Int,
    val lastMessageTimestamp: Long?,
    // Contact metadata
    val notes: String?,
    val tags: String?, // JSON array
    val addedTimestamp: Long,
    val addedVia: String, // "ANNOUNCE", "QR_CODE", "MANUAL", "CONVERSATION", or "MANUAL_PENDING"
    val isPinned: Boolean,
    // Identity resolution status
    val status: ContactStatus = ContactStatus.ACTIVE,
) {
    /**
     * Parse tags from JSON string to list
     */
    fun getTagsList(): List<String> {
        if (tags.isNullOrBlank()) return emptyList()
        return try {
            // Simple JSON array parsing: ["tag1", "tag2"]
            tags.trim()
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EnrichedContact

        if (destinationHash != other.destinationHash) return false
        if (publicKey != null) {
            if (other.publicKey == null) return false
            if (!publicKey.contentEquals(other.publicKey)) return false
        } else if (other.publicKey != null) {
            return false
        }
        if (displayName != other.displayName) return false
        if (customNickname != other.customNickname) return false
        if (announceName != other.announceName) return false
        if (lastSeenTimestamp != other.lastSeenTimestamp) return false
        if (hops != other.hops) return false
        if (isOnline != other.isOnline) return false
        if (hasConversation != other.hasConversation) return false
        if (unreadCount != other.unreadCount) return false
        if (lastMessageTimestamp != other.lastMessageTimestamp) return false
        if (notes != other.notes) return false
        if (tags != other.tags) return false
        if (addedTimestamp != other.addedTimestamp) return false
        if (addedVia != other.addedVia) return false
        if (isPinned != other.isPinned) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = destinationHash.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (customNickname?.hashCode() ?: 0)
        result = 31 * result + (announceName?.hashCode() ?: 0)
        result = 31 * result + (lastSeenTimestamp?.hashCode() ?: 0)
        result = 31 * result + (hops ?: 0)
        result = 31 * result + isOnline.hashCode()
        result = 31 * result + hasConversation.hashCode()
        result = 31 * result + unreadCount
        result = 31 * result + (lastMessageTimestamp?.hashCode() ?: 0)
        result = 31 * result + (notes?.hashCode() ?: 0)
        result = 31 * result + (tags?.hashCode() ?: 0)
        result = 31 * result + addedTimestamp.hashCode()
        result = 31 * result + addedVia.hashCode()
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}
