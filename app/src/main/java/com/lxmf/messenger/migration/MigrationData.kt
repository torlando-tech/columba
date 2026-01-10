package com.lxmf.messenger.migration

import com.lxmf.messenger.data.db.entity.CustomThemeEntity
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
        const val CURRENT_VERSION = 6

        // Minimum version we can import - older files may have incompatible structure
        const val MINIMUM_VERSION = 1
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
    /** Base64 encoded 64-byte private key */
    val keyData: String,
    val createdTimestamp: Long,
    val lastUsedTimestamp: Long,
    val isActive: Boolean,
    // Profile icon data (nullable for backward compatibility with older exports)
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
)

/**
 * Exported conversation metadata.
 */
@Serializable
data class ConversationExport(
    val peerHash: String,
    val identityHash: String,
    val peerName: String,
    /** Base64 encoded public key */
    val peerPublicKey: String?,
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
    /** Base64 encoded public key */
    val publicKey: String,
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
    /** Base64 encoded 64-byte public key */
    val publicKey: String,
    /** Base64 encoded */
    val appData: String?,
    val hops: Int,
    val lastSeenTimestamp: Long,
    val nodeType: String,
    val receivingInterface: String?,
    val receivingInterfaceType: String? = null,
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
    /** Identity hash (SHA256 of public key) */
    val peerHash: String,
    /** Base64 encoded public key */
    val publicKey: String,
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
 * A single preference entry for serialization.
 * Stores the key name, type identifier, and string-encoded value.
 */
@Serializable
data class PreferenceEntry(
    val key: String,
    /** "boolean", "int", "long", "float", "string", "string_set" */
    val type: String,
    /** String representation of the value */
    val value: String,
)

/**
 * Exported user settings.
 * Uses automatic DataStore export - all preferences are captured automatically.
 * New settings added to SettingsRepository will be included without code changes.
 * Unknown preferences are safely ignored during import for backward/forward compatibility.
 */
@Serializable
data class SettingsExport(
    val preferences: List<PreferenceEntry> = emptyList(),
    // Legacy fields for backward compatibility with v6 exports (will be deprecated)
    @Deprecated("Use preferences list instead")
    val notificationsEnabled: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val notificationReceivedMessage: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val notificationReceivedMessageFavorite: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val notificationHeardAnnounce: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val notificationBleConnected: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val notificationBleDisconnected: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val hasRequestedNotificationPermission: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val autoAnnounceEnabled: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val autoAnnounceIntervalMinutes: Int? = null,
    @Deprecated("Use preferences list instead")
    val autoAnnounceIntervalHours: Int? = null,
    @Deprecated("Use preferences list instead")
    val lastAutoAnnounceTime: Long? = null,
    @Deprecated("Use preferences list instead")
    val themePreference: String? = null,
    @Deprecated("Use preferences list instead")
    val preferOwnInstance: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val rpcKey: String? = null,
    @Deprecated("Use preferences list instead")
    val defaultDeliveryMethod: String? = null,
    @Deprecated("Use preferences list instead")
    val tryPropagationOnFail: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val manualPropagationNode: String? = null,
    @Deprecated("Use preferences list instead")
    val lastPropagationNode: String? = null,
    @Deprecated("Use preferences list instead")
    val autoSelectPropagationNode: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val autoRetrieveEnabled: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val retrievalIntervalSeconds: Int? = null,
    @Deprecated("Use preferences list instead")
    val lastSyncTimestamp: Long? = null,
    @Deprecated("Use preferences list instead")
    val transportNodeEnabled: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val locationSharingEnabled: Boolean? = null,
    @Deprecated("Use preferences list instead")
    val defaultSharingDuration: String? = null,
    @Deprecated("Use preferences list instead")
    val locationPrecisionRadius: Int? = null,
)

/**
 * Reference to an attachment file in the migration archive.
 */
@Serializable
data class AttachmentRef(
    val messageId: String,
    val fieldKey: String,
    /** Path within attachments/ in the ZIP */
    val relativePath: String,
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

/**
 * Convert a CustomThemeEntity to CustomThemeExport for migration.
 */
fun CustomThemeEntity.toExport(): CustomThemeExport =
    CustomThemeExport(
        originalId = id,
        name = name,
        description = description,
        baseTheme = baseTheme,
        seedPrimary = seedPrimary,
        seedSecondary = seedSecondary,
        seedTertiary = seedTertiary,
        createdTimestamp = createdTimestamp,
        modifiedTimestamp = modifiedTimestamp,
        lightPrimary = lightPrimary,
        lightOnPrimary = lightOnPrimary,
        lightPrimaryContainer = lightPrimaryContainer,
        lightOnPrimaryContainer = lightOnPrimaryContainer,
        lightSecondary = lightSecondary,
        lightOnSecondary = lightOnSecondary,
        lightSecondaryContainer = lightSecondaryContainer,
        lightOnSecondaryContainer = lightOnSecondaryContainer,
        lightTertiary = lightTertiary,
        lightOnTertiary = lightOnTertiary,
        lightTertiaryContainer = lightTertiaryContainer,
        lightOnTertiaryContainer = lightOnTertiaryContainer,
        lightError = lightError,
        lightOnError = lightOnError,
        lightErrorContainer = lightErrorContainer,
        lightOnErrorContainer = lightOnErrorContainer,
        lightBackground = lightBackground,
        lightOnBackground = lightOnBackground,
        lightSurface = lightSurface,
        lightOnSurface = lightOnSurface,
        lightSurfaceVariant = lightSurfaceVariant,
        lightOnSurfaceVariant = lightOnSurfaceVariant,
        lightOutline = lightOutline,
        lightOutlineVariant = lightOutlineVariant,
        darkPrimary = darkPrimary,
        darkOnPrimary = darkOnPrimary,
        darkPrimaryContainer = darkPrimaryContainer,
        darkOnPrimaryContainer = darkOnPrimaryContainer,
        darkSecondary = darkSecondary,
        darkOnSecondary = darkOnSecondary,
        darkSecondaryContainer = darkSecondaryContainer,
        darkOnSecondaryContainer = darkOnSecondaryContainer,
        darkTertiary = darkTertiary,
        darkOnTertiary = darkOnTertiary,
        darkTertiaryContainer = darkTertiaryContainer,
        darkOnTertiaryContainer = darkOnTertiaryContainer,
        darkError = darkError,
        darkOnError = darkOnError,
        darkErrorContainer = darkErrorContainer,
        darkOnErrorContainer = darkOnErrorContainer,
        darkBackground = darkBackground,
        darkOnBackground = darkOnBackground,
        darkSurface = darkSurface,
        darkOnSurface = darkOnSurface,
        darkSurfaceVariant = darkSurfaceVariant,
        darkOnSurfaceVariant = darkOnSurfaceVariant,
        darkOutline = darkOutline,
        darkOutlineVariant = darkOutlineVariant,
    )

/**
 * Convert a CustomThemeExport to CustomThemeEntity for import.
 */
fun CustomThemeExport.toEntity(): CustomThemeEntity =
    CustomThemeEntity(
        // New entity, will be auto-generated
        id = 0,
        name = name,
        description = description,
        baseTheme = baseTheme,
        seedPrimary = seedPrimary,
        seedSecondary = seedSecondary,
        seedTertiary = seedTertiary,
        createdTimestamp = createdTimestamp,
        modifiedTimestamp = modifiedTimestamp,
        lightPrimary = lightPrimary,
        lightOnPrimary = lightOnPrimary,
        lightPrimaryContainer = lightPrimaryContainer,
        lightOnPrimaryContainer = lightOnPrimaryContainer,
        lightSecondary = lightSecondary,
        lightOnSecondary = lightOnSecondary,
        lightSecondaryContainer = lightSecondaryContainer,
        lightOnSecondaryContainer = lightOnSecondaryContainer,
        lightTertiary = lightTertiary,
        lightOnTertiary = lightOnTertiary,
        lightTertiaryContainer = lightTertiaryContainer,
        lightOnTertiaryContainer = lightOnTertiaryContainer,
        lightError = lightError,
        lightOnError = lightOnError,
        lightErrorContainer = lightErrorContainer,
        lightOnErrorContainer = lightOnErrorContainer,
        lightBackground = lightBackground,
        lightOnBackground = lightOnBackground,
        lightSurface = lightSurface,
        lightOnSurface = lightOnSurface,
        lightSurfaceVariant = lightSurfaceVariant,
        lightOnSurfaceVariant = lightOnSurfaceVariant,
        lightOutline = lightOutline,
        lightOutlineVariant = lightOutlineVariant,
        darkPrimary = darkPrimary,
        darkOnPrimary = darkOnPrimary,
        darkPrimaryContainer = darkPrimaryContainer,
        darkOnPrimaryContainer = darkOnPrimaryContainer,
        darkSecondary = darkSecondary,
        darkOnSecondary = darkOnSecondary,
        darkSecondaryContainer = darkSecondaryContainer,
        darkOnSecondaryContainer = darkOnSecondaryContainer,
        darkTertiary = darkTertiary,
        darkOnTertiary = darkOnTertiary,
        darkTertiaryContainer = darkTertiaryContainer,
        darkOnTertiaryContainer = darkOnTertiaryContainer,
        darkError = darkError,
        darkOnError = darkOnError,
        darkErrorContainer = darkErrorContainer,
        darkOnErrorContainer = darkOnErrorContainer,
        darkBackground = darkBackground,
        darkOnBackground = darkOnBackground,
        darkSurface = darkSurface,
        darkOnSurface = darkOnSurface,
        darkSurfaceVariant = darkSurfaceVariant,
        darkOnSurfaceVariant = darkOnSurfaceVariant,
        darkOutline = darkOutline,
        darkOutlineVariant = darkOutlineVariant,
    )
