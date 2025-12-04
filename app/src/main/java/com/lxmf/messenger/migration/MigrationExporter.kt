package com.lxmf.messenger.migration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.lxmf.messenger.data.database.InterfaceDatabase
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles exporting all app data to a migration bundle file.
 *
 * The export creates a .columba file (ZIP archive) containing:
 * - manifest.json: Serialized MigrationBundle with all data
 * - attachments/: Directory with message attachments
 */
@Suppress("TooManyFunctions") // Helper methods extracted for readability
@Singleton
class MigrationExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: ColumbaDatabase,
        private val interfaceDatabase: InterfaceDatabase,
        private val settingsRepository: SettingsRepository,
    ) {
        companion object {
            private const val TAG = "MigrationExporter"
            private const val MANIFEST_FILENAME = "manifest.json"
            private const val ATTACHMENTS_DIR = "attachments"
            private const val EXPORT_DIR = "migration_export"
        }

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        /**
         * Export all app data to a migration bundle file.
         *
         * @param onProgress Callback for progress updates (0.0 to 1.0)
         * @return URI to the exported file via FileProvider, or null on failure
         */
        suspend fun exportData(onProgress: (Float) -> Unit = {}): Result<Uri> =
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Starting migration export...")
                    onProgress(0.05f)

                    // Collect all data
                    val identities = database.localIdentityDao().getAllIdentitiesSync()
                    Log.d(TAG, "Found ${identities.size} identities to export")
                    onProgress(0.1f)

                    val (conversations, messages, contacts) = collectUserData(identities, onProgress)
                    onProgress(0.5f)

                    val identityExports = exportIdentities(identities)
                    onProgress(0.55f)

                    val announceExports = exportAnnounces()
                    onProgress(0.58f)

                    val peerIdentityExports = exportPeerIdentities()
                    onProgress(0.6f)

                    val interfaceExports = exportInterfaces()
                    onProgress(0.62f)

                    val customThemeExports = exportCustomThemes()
                    onProgress(0.64f)

                    val settingsExport = exportSettings()
                    onProgress(0.65f)

                    val attachmentRefs = collectAttachments()
                    onProgress(0.7f)

                    // Create migration bundle
                    val bundle = MigrationBundle(
                        identities = identityExports,
                        conversations = conversations,
                        messages = messages,
                        contacts = contacts,
                        announces = announceExports,
                        peerIdentities = peerIdentityExports,
                        interfaces = interfaceExports,
                        customThemes = customThemeExports,
                        settings = settingsExport,
                        attachmentManifest = attachmentRefs,
                    )

                    // Create and return ZIP file
                    val exportFile = createExportZip(bundle, attachmentRefs, onProgress)
                    Log.i(TAG, "Export complete: ${exportFile.absolutePath}")
                    onProgress(1.0f)

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        exportFile,
                    )
                    Result.success(uri)
                } catch (e: Exception) {
                    Log.e(TAG, "Export failed", e)
                    Result.failure(e)
                }
            }

        private data class UserData(
            val conversations: List<ConversationExport>,
            val messages: List<MessageExport>,
            val contacts: List<ContactExport>,
        )

        private suspend fun collectUserData(
            identities: List<com.lxmf.messenger.data.db.entity.LocalIdentityEntity>,
            onProgress: (Float) -> Unit,
        ): UserData {
            val allConversations = mutableListOf<ConversationExport>()
            val allMessages = mutableListOf<MessageExport>()
            val allContacts = mutableListOf<ContactExport>()

            for ((index, identity) in identities.withIndex()) {
                allConversations.addAll(exportConversationsForIdentity(identity.identityHash))
                allMessages.addAll(exportMessagesForIdentity(identity.identityHash))
                allContacts.addAll(exportContactsForIdentity(identity.identityHash))

                val progress = 0.1f + (0.4f * (index + 1) / identities.size)
                onProgress(progress)
            }

            Log.d(
                TAG,
                "Collected ${allConversations.size} conversations, " +
                    "${allMessages.size} messages, ${allContacts.size} contacts",
            )
            return UserData(allConversations, allMessages, allContacts)
        }

        private suspend fun exportConversationsForIdentity(identityHash: String): List<ConversationExport> {
            return database.conversationDao().getAllConversationsList(identityHash).map { conv ->
                ConversationExport(
                    peerHash = conv.peerHash,
                    identityHash = conv.identityHash,
                    peerName = conv.peerName,
                    peerPublicKey = conv.peerPublicKey?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                    lastMessage = conv.lastMessage,
                    lastMessageTimestamp = conv.lastMessageTimestamp,
                    unreadCount = conv.unreadCount,
                    lastSeenTimestamp = conv.lastSeenTimestamp,
                )
            }
        }

        private suspend fun exportMessagesForIdentity(identityHash: String): List<MessageExport> {
            return database.messageDao().getAllMessagesForIdentity(identityHash).map { msg ->
                MessageExport(
                    id = msg.id,
                    conversationHash = msg.conversationHash,
                    identityHash = msg.identityHash,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    isFromMe = msg.isFromMe,
                    status = msg.status,
                    isRead = msg.isRead,
                    fieldsJson = msg.fieldsJson,
                )
            }
        }

        private suspend fun exportContactsForIdentity(identityHash: String): List<ContactExport> {
            return database.contactDao().getAllContactsSync(identityHash).map { contact ->
                ContactExport(
                    destinationHash = contact.destinationHash,
                    identityHash = contact.identityHash,
                    publicKey = Base64.encodeToString(contact.publicKey, Base64.NO_WRAP),
                    customNickname = contact.customNickname,
                    notes = contact.notes,
                    tags = contact.tags,
                    addedTimestamp = contact.addedTimestamp,
                    addedVia = contact.addedVia,
                    lastInteractionTimestamp = contact.lastInteractionTimestamp,
                    isPinned = contact.isPinned,
                )
            }
        }

        private fun exportIdentities(
            identities: List<com.lxmf.messenger.data.db.entity.LocalIdentityEntity>,
        ): List<IdentityExport> {
            return identities.map { identity ->
                val keyData = identity.keyData ?: loadIdentityKeyFromFile(identity.filePath)
                IdentityExport(
                    identityHash = identity.identityHash,
                    displayName = identity.displayName,
                    destinationHash = identity.destinationHash,
                    keyData = keyData?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "",
                    createdTimestamp = identity.createdTimestamp,
                    lastUsedTimestamp = identity.lastUsedTimestamp,
                    isActive = identity.isActive,
                )
            }
        }

        private suspend fun exportAnnounces(): List<AnnounceExport> {
            val announces = database.announceDao().getAllAnnouncesSync()
            Log.d(TAG, "Collected ${announces.size} announces")
            return announces.map { announce ->
                AnnounceExport(
                    destinationHash = announce.destinationHash,
                    peerName = announce.peerName,
                    publicKey = Base64.encodeToString(announce.publicKey, Base64.NO_WRAP),
                    appData = announce.appData?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                    hops = announce.hops,
                    lastSeenTimestamp = announce.lastSeenTimestamp,
                    nodeType = announce.nodeType,
                    receivingInterface = announce.receivingInterface,
                    aspect = announce.aspect,
                    isFavorite = announce.isFavorite,
                    favoritedTimestamp = announce.favoritedTimestamp,
                )
            }
        }

        private suspend fun exportPeerIdentities(): List<PeerIdentityExport> {
            val peerIdentities = database.peerIdentityDao().getAllPeerIdentities()
            Log.d(TAG, "Collected ${peerIdentities.size} peer identities")
            return peerIdentities.map { peer ->
                PeerIdentityExport(
                    peerHash = peer.peerHash,
                    publicKey = Base64.encodeToString(peer.publicKey, Base64.NO_WRAP),
                    lastSeenTimestamp = peer.lastSeenTimestamp,
                )
            }
        }

        private suspend fun exportInterfaces(): List<InterfaceExport> {
            val interfaces = interfaceDatabase.interfaceDao().getAllInterfaces().first()
            Log.d(TAG, "Collected ${interfaces.size} interfaces")
            return interfaces.map { iface ->
                InterfaceExport(
                    name = iface.name,
                    type = iface.type,
                    enabled = iface.enabled,
                    configJson = iface.configJson,
                    displayOrder = iface.displayOrder,
                )
            }
        }

        private suspend fun exportCustomThemes(): List<CustomThemeExport> {
            val customThemes = database.customThemeDao().getAllThemes().first()
            Log.d(TAG, "Collected ${customThemes.size} custom themes")
            return customThemes.map { theme ->
                CustomThemeExport(
                    originalId = theme.id,
                    name = theme.name,
                    description = theme.description,
                    baseTheme = theme.baseTheme,
                    seedPrimary = theme.seedPrimary,
                    seedSecondary = theme.seedSecondary,
                    seedTertiary = theme.seedTertiary,
                    createdTimestamp = theme.createdTimestamp,
                    modifiedTimestamp = theme.modifiedTimestamp,
                    lightPrimary = theme.lightPrimary,
                    lightOnPrimary = theme.lightOnPrimary,
                    lightPrimaryContainer = theme.lightPrimaryContainer,
                    lightOnPrimaryContainer = theme.lightOnPrimaryContainer,
                    lightSecondary = theme.lightSecondary,
                    lightOnSecondary = theme.lightOnSecondary,
                    lightSecondaryContainer = theme.lightSecondaryContainer,
                    lightOnSecondaryContainer = theme.lightOnSecondaryContainer,
                    lightTertiary = theme.lightTertiary,
                    lightOnTertiary = theme.lightOnTertiary,
                    lightTertiaryContainer = theme.lightTertiaryContainer,
                    lightOnTertiaryContainer = theme.lightOnTertiaryContainer,
                    lightError = theme.lightError,
                    lightOnError = theme.lightOnError,
                    lightErrorContainer = theme.lightErrorContainer,
                    lightOnErrorContainer = theme.lightOnErrorContainer,
                    lightBackground = theme.lightBackground,
                    lightOnBackground = theme.lightOnBackground,
                    lightSurface = theme.lightSurface,
                    lightOnSurface = theme.lightOnSurface,
                    lightSurfaceVariant = theme.lightSurfaceVariant,
                    lightOnSurfaceVariant = theme.lightOnSurfaceVariant,
                    lightOutline = theme.lightOutline,
                    lightOutlineVariant = theme.lightOutlineVariant,
                    darkPrimary = theme.darkPrimary,
                    darkOnPrimary = theme.darkOnPrimary,
                    darkPrimaryContainer = theme.darkPrimaryContainer,
                    darkOnPrimaryContainer = theme.darkOnPrimaryContainer,
                    darkSecondary = theme.darkSecondary,
                    darkOnSecondary = theme.darkOnSecondary,
                    darkSecondaryContainer = theme.darkSecondaryContainer,
                    darkOnSecondaryContainer = theme.darkOnSecondaryContainer,
                    darkTertiary = theme.darkTertiary,
                    darkOnTertiary = theme.darkOnTertiary,
                    darkTertiaryContainer = theme.darkTertiaryContainer,
                    darkOnTertiaryContainer = theme.darkOnTertiaryContainer,
                    darkError = theme.darkError,
                    darkOnError = theme.darkOnError,
                    darkErrorContainer = theme.darkErrorContainer,
                    darkOnErrorContainer = theme.darkOnErrorContainer,
                    darkBackground = theme.darkBackground,
                    darkOnBackground = theme.darkOnBackground,
                    darkSurface = theme.darkSurface,
                    darkOnSurface = theme.darkOnSurface,
                    darkSurfaceVariant = theme.darkSurfaceVariant,
                    darkOnSurfaceVariant = theme.darkOnSurfaceVariant,
                    darkOutline = theme.darkOutline,
                    darkOutlineVariant = theme.darkOutlineVariant,
                )
            }
        }

        private suspend fun exportSettings(): SettingsExport {
            return SettingsExport(
                notificationsEnabled = settingsRepository.notificationsEnabledFlow.first(),
                notificationReceivedMessage = settingsRepository
                    .notificationReceivedMessageFlow.first(),
                notificationReceivedMessageFavorite = settingsRepository
                    .notificationReceivedMessageFavoriteFlow.first(),
                notificationHeardAnnounce = settingsRepository
                    .notificationHeardAnnounceFlow.first(),
                notificationBleConnected = settingsRepository
                    .notificationBleConnectedFlow.first(),
                notificationBleDisconnected = settingsRepository
                    .notificationBleDisconnectedFlow.first(),
                autoAnnounceEnabled = settingsRepository.autoAnnounceEnabledFlow.first(),
                autoAnnounceIntervalMinutes = settingsRepository
                    .autoAnnounceIntervalMinutesFlow.first(),
                themePreference = settingsRepository.themePreferenceFlow.first().getIdentifier(),
            )
        }

        private fun collectAttachments(): List<AttachmentRef> {
            val attachmentsDir = File(context.filesDir, "attachments")
            val attachmentRefs = mutableListOf<AttachmentRef>()

            if (attachmentsDir.exists()) {
                attachmentsDir.listFiles()?.forEach { messageDir ->
                    if (messageDir.isDirectory) {
                        messageDir.listFiles()?.forEach { fieldFile ->
                            attachmentRefs.add(
                                AttachmentRef(
                                    messageId = messageDir.name,
                                    fieldKey = fieldFile.name,
                                    relativePath = "${messageDir.name}/${fieldFile.name}",
                                    sizeBytes = fieldFile.length(),
                                ),
                            )
                        }
                    }
                }
            }
            Log.d(TAG, "Found ${attachmentRefs.size} attachments to export")
            return attachmentRefs
        }

        private fun createExportZip(
            bundle: MigrationBundle,
            attachmentRefs: List<AttachmentRef>,
            onProgress: (Float) -> Unit,
        ): File {
            val exportDir = File(context.cacheDir, EXPORT_DIR).also { it.mkdirs() }
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val exportFile = File(exportDir, "columba_export_$timestamp.columba")

            ZipOutputStream(FileOutputStream(exportFile)).use { zipOut ->
                zipOut.putNextEntry(ZipEntry(MANIFEST_FILENAME))
                zipOut.write(json.encodeToString(bundle).toByteArray())
                zipOut.closeEntry()
                onProgress(0.8f)

                val attachmentsDir = File(context.filesDir, "attachments")
                if (attachmentsDir.exists() && attachmentRefs.isNotEmpty()) {
                    attachmentRefs.forEachIndexed { index, ref ->
                        val sourceFile = File(attachmentsDir, ref.relativePath)
                        if (sourceFile.exists()) {
                            zipOut.putNextEntry(ZipEntry("$ATTACHMENTS_DIR/${ref.relativePath}"))
                            sourceFile.inputStream().use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                        val progress = 0.8f + (0.15f * (index + 1) / attachmentRefs.size)
                        onProgress(progress)
                    }
                }
            }
            return exportFile
        }

        /**
         * Load identity key data from a file path.
         */
        private fun loadIdentityKeyFromFile(filePath: String): ByteArray? {
            return try {
                val file = File(filePath)
                if (file.exists()) {
                    file.readBytes()
                } else {
                    Log.w(TAG, "Identity file not found: $filePath")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load identity key from $filePath", e)
                null
            }
        }

        /**
         * Get a preview of what would be exported.
         */
        suspend fun getExportPreview(): ExportResult =
            withContext(Dispatchers.IO) {
                try {
                    val identities = database.localIdentityDao().getAllIdentitiesSync()
                    var messageCount = 0
                    var contactCount = 0

                    identities.forEach { identity ->
                        messageCount += database.messageDao()
                            .getAllMessagesForIdentity(identity.identityHash).size
                        contactCount += database.contactDao()
                            .getAllContactsSync(identity.identityHash).size
                    }

                    val announceCount = database.announceDao().getAnnounceCount()
                    val peerIdentityCount = database.peerIdentityDao().getAllPeerIdentities().size
                    val interfaceCount = interfaceDatabase.interfaceDao()
                        .getAllInterfaces().first().size
                    val customThemeCount = database.customThemeDao().getThemeCount()

                    ExportResult.Success(
                        identityCount = identities.size,
                        messageCount = messageCount,
                        contactCount = contactCount,
                        announceCount = announceCount,
                        peerIdentityCount = peerIdentityCount,
                        interfaceCount = interfaceCount,
                        customThemeCount = customThemeCount,
                    )
                } catch (e: Exception) {
                    ExportResult.Error("Failed to get export preview: ${e.message}", e)
                }
            }

        /**
         * Clean up old export files from cache.
         */
        fun cleanupExportFiles() {
            try {
                val exportDir = File(context.cacheDir, EXPORT_DIR)
                if (exportDir.exists()) {
                    exportDir.deleteRecursively()
                    Log.d(TAG, "Cleaned up export cache")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup export files", e)
            }
        }
    }
