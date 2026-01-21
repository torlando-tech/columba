package com.lxmf.messenger.data.model

/**
 * Enriched conversation data combining conversations table with announces and contacts.
 *
 * This model represents the result of a join query that combines:
 * - Conversation data (from conversations table)
 * - Profile icon (from announces table)
 * - Display name with priority: nickname > announce name > peer name > hash (from contacts + announces)
 */
data class EnrichedConversation(
    val peerHash: String,
    val peerName: String,
    // Display name with priority: customNickname > announceName > peerName > peerHash
    val displayName: String,
    val peerPublicKey: ByteArray?,
    val lastMessage: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    // Profile icon (from announces table)
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EnrichedConversation

        if (peerHash != other.peerHash) return false
        if (peerName != other.peerName) return false
        if (displayName != other.displayName) return false
        if (peerPublicKey != null) {
            if (other.peerPublicKey == null) return false
            if (!peerPublicKey.contentEquals(other.peerPublicKey)) return false
        } else if (other.peerPublicKey != null) {
            return false
        }
        if (lastMessage != other.lastMessage) return false
        if (lastMessageTimestamp != other.lastMessageTimestamp) return false
        if (unreadCount != other.unreadCount) return false
        if (iconName != other.iconName) return false
        if (iconForegroundColor != other.iconForegroundColor) return false
        if (iconBackgroundColor != other.iconBackgroundColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerHash.hashCode()
        result = 31 * result + peerName.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (peerPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + lastMessage.hashCode()
        result = 31 * result + lastMessageTimestamp.hashCode()
        result = 31 * result + unreadCount
        result = 31 * result + (iconName?.hashCode() ?: 0)
        result = 31 * result + (iconForegroundColor?.hashCode() ?: 0)
        result = 31 * result + (iconBackgroundColor?.hashCode() ?: 0)
        return result
    }
}
