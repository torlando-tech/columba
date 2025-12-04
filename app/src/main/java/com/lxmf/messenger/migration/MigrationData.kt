package com.lxmf.messenger.migration

import kotlinx.serialization.Serializable

/**
 * Migration bundle containing all exportable app data.
 * This is the root object serialized to manifest.json in the migration ZIP.
 */
@Serializable
data class MigrationBundle(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val identities: List<IdentityExport>,
    val conversations: List<ConversationExport>,
    val messages: List<MessageExport>,
    val contacts: List<ContactExport>,
    val announces: List<AnnounceExport> = emptyList(),
    val peerIdentities: List<PeerIdentityExport> = emptyList(),
    val interfaces: List<InterfaceExport> = emptyList(),
    val customThemes: List<CustomThemeExport> = emptyList(),
    val settings: SettingsExport,
    val attachmentManifest: List<AttachmentRef> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 5
    }
}

/**
 * Exported identity data including the private key for restoration.
 */
@Serializable
data class IdentityExport(
    val identityHash: String,
    val displayName: String,
    val destinationHash: String,
    val keyData: String, // Base64 encoded 64-byte private key
    val createdTimestamp: Long,
    val lastUsedTimestamp: Long,
    val isActive: Boolean,
)

/**
 * Exported conversation metadata.
 */
@Serializable
data class ConversationExport(
    val peerHash: String,
    val identityHash: String,
    val peerName: String,
    val peerPublicKey: String?, // Base64 encoded public key
    val lastMessage: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val lastSeenTimestamp: Long,
)

/**
 * Exported message data.
 */
@Serializable
data class MessageExport(
    val id: String,
    val conversationHash: String,
    val identityHash: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val status: String,
    val isRead: Boolean,
    val fieldsJson: String?,
)

/**
 * Exported contact data.
 */
@Serializable
data class ContactExport(
    val destinationHash: String,
    val identityHash: String,
    val publicKey: String, // Base64 encoded public key
    val customNickname: String?,
    val notes: String?,
    val tags: String?,
    val addedTimestamp: Long,
    val addedVia: String,
    val lastInteractionTimestamp: Long,
    val isPinned: Boolean,
)

/**
 * Exported announce data for known peers.
 */
@Serializable
data class AnnounceExport(
    val destinationHash: String,
    val peerName: String,
    val publicKey: String, // Base64 encoded 64-byte public key
    val appData: String?, // Base64 encoded
    val hops: Int,
    val lastSeenTimestamp: Long,
    val nodeType: String,
    val receivingInterface: String?,
    val aspect: String?,
    val isFavorite: Boolean,
    val favoritedTimestamp: Long?,
)

/**
 * Exported peer identity data for identity restoration.
 * Peer identities store public keys indexed by identity hash (SHA256 of public key).
 */
@Serializable
data class PeerIdentityExport(
    val peerHash: String, // Identity hash (SHA256 of public key)
    val publicKey: String, // Base64 encoded public key
    val lastSeenTimestamp: Long,
)

/**
 * Exported interface configuration.
 */
@Serializable
data class InterfaceExport(
    val name: String,
    val type: String,
    val enabled: Boolean,
    val configJson: String,
    val displayOrder: Int,
)

/**
 * Exported custom theme data.
 */
@Serializable
data class CustomThemeExport(
    val originalId: Long,
    val name: String,
    val description: String,
    val baseTheme: String?,
    val seedPrimary: Int,
    val seedSecondary: Int,
    val seedTertiary: Int,
    val createdTimestamp: Long,
    val modifiedTimestamp: Long,
    // Light mode colors
    val lightPrimary: Int,
    val lightOnPrimary: Int,
    val lightPrimaryContainer: Int,
    val lightOnPrimaryContainer: Int,
    val lightSecondary: Int,
    val lightOnSecondary: Int,
    val lightSecondaryContainer: Int,
    val lightOnSecondaryContainer: Int,
    val lightTertiary: Int,
    val lightOnTertiary: Int,
    val lightTertiaryContainer: Int,
    val lightOnTertiaryContainer: Int,
    val lightError: Int,
    val lightOnError: Int,
    val lightErrorContainer: Int,
    val lightOnErrorContainer: Int,
    val lightBackground: Int,
    val lightOnBackground: Int,
    val lightSurface: Int,
    val lightOnSurface: Int,
    val lightSurfaceVariant: Int,
    val lightOnSurfaceVariant: Int,
    val lightOutline: Int,
    val lightOutlineVariant: Int,
    // Dark mode colors
    val darkPrimary: Int,
    val darkOnPrimary: Int,
    val darkPrimaryContainer: Int,
    val darkOnPrimaryContainer: Int,
    val darkSecondary: Int,
    val darkOnSecondary: Int,
    val darkSecondaryContainer: Int,
    val darkOnSecondaryContainer: Int,
    val darkTertiary: Int,
    val darkOnTertiary: Int,
    val darkTertiaryContainer: Int,
    val darkOnTertiaryContainer: Int,
    val darkError: Int,
    val darkOnError: Int,
    val darkErrorContainer: Int,
    val darkOnErrorContainer: Int,
    val darkBackground: Int,
    val darkOnBackground: Int,
    val darkSurface: Int,
    val darkOnSurface: Int,
    val darkSurfaceVariant: Int,
    val darkOnSurfaceVariant: Int,
    val darkOutline: Int,
    val darkOutlineVariant: Int,
)

/**
 * Exported user settings.
 */
@Serializable
data class SettingsExport(
    val notificationsEnabled: Boolean,
    val notificationReceivedMessage: Boolean,
    val notificationReceivedMessageFavorite: Boolean,
    val notificationHeardAnnounce: Boolean,
    val notificationBleConnected: Boolean,
    val notificationBleDisconnected: Boolean,
    val autoAnnounceEnabled: Boolean,
    val autoAnnounceIntervalMinutes: Int,
    val themePreference: String,
)

/**
 * Reference to an attachment file in the migration archive.
 */
@Serializable
data class AttachmentRef(
    val messageId: String,
    val fieldKey: String,
    val relativePath: String, // Path within attachments/ in the ZIP
    val sizeBytes: Long,
)

/**
 * Result of an export operation.
 */
sealed class ExportResult {
    data class Success(
        val identityCount: Int,
        val messageCount: Int,
        val contactCount: Int,
        val announceCount: Int,
        val peerIdentityCount: Int,
        val interfaceCount: Int,
        val customThemeCount: Int,
    ) : ExportResult()

    data class Error(val message: String, val cause: Throwable? = null) : ExportResult()
}

/**
 * Result of an import operation.
 */
sealed class ImportResult {
    data class Success(
        val identitiesImported: Int,
        val messagesImported: Int,
        val contactsImported: Int,
        val announcesImported: Int,
        val peerIdentitiesImported: Int,
        val interfacesImported: Int,
        val customThemesImported: Int,
    ) : ImportResult()

    data class Error(val message: String, val cause: Throwable? = null) : ImportResult()
}

/**
 * Preview of migration data before import.
 */
data class MigrationPreview(
    val version: Int,
    val exportedAt: Long,
    val identityCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val contactCount: Int,
    val announceCount: Int,
    val peerIdentityCount: Int,
    val interfaceCount: Int,
    val customThemeCount: Int,
    val identityNames: List<String>,
)
