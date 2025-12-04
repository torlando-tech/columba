package com.lxmf.messenger.migration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lxmf.messenger.data.database.InterfaceDatabase
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.CustomThemeEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles importing data from a migration bundle file.
 */
@Suppress("TooManyFunctions") // Helper methods extracted for readability
@Singleton
class MigrationImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: ColumbaDatabase,
        private val interfaceDatabase: InterfaceDatabase,
        private val reticulumProtocol: ReticulumProtocol,
        private val settingsRepository: SettingsRepository,
    ) {
        companion object {
            private const val TAG = "MigrationImporter"
            private const val MANIFEST_FILENAME = "manifest.json"
            private const val ATTACHMENTS_PREFIX = "attachments/"
        }

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Preview the contents of a migration file without importing.
         */
        suspend fun previewMigration(uri: Uri): Result<MigrationPreview> =
            withContext(Dispatchers.IO) {
                try {
                    val bundle = readMigrationBundle(uri)
                        ?: return@withContext Result.failure(
                            Exception("Failed to read migration file"),
                        )

                    Result.success(
                        MigrationPreview(
                            version = bundle.version,
                            exportedAt = bundle.exportedAt,
                            identityCount = bundle.identities.size,
                            conversationCount = bundle.conversations.size,
                            messageCount = bundle.messages.size,
                            contactCount = bundle.contacts.size,
                            announceCount = bundle.announces.size,
                            peerIdentityCount = bundle.peerIdentities.size,
                            interfaceCount = bundle.interfaces.size,
                            customThemeCount = bundle.customThemes.size,
                            identityNames = bundle.identities.map { it.displayName },
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to preview migration", e)
                    Result.failure(e)
                }
            }

        /**
         * Import data from a migration bundle file.
         *
         * @param uri URI to the .columba file
         * @param onProgress Callback for progress updates (0.0 to 1.0)
         * @return ImportResult indicating success or failure
         */
        suspend fun importData(
            uri: Uri,
            onProgress: (Float) -> Unit = {},
        ): ImportResult =
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Starting migration import...")
                    onProgress(0.05f)

                    val bundle = readMigrationBundle(uri)
                        ?: return@withContext ImportResult.Error("Failed to read migration file")

                    if (bundle.version > MigrationBundle.CURRENT_VERSION) {
                        return@withContext ImportResult.Error(
                            "Migration file is from a newer version (${bundle.version}). " +
                                "Please update the app first.",
                        )
                    }
                    onProgress(0.1f)

                    val identitiesImported = importIdentities(bundle.identities, onProgress)
                    onProgress(0.4f)

                    importConversations(bundle.conversations)
                    onProgress(0.5f)

                    val messagesImported = importMessages(bundle.messages, onProgress)
                    onProgress(0.7f)

                    val contactsImported = importContacts(bundle.contacts)
                    onProgress(0.75f)

                    val announcesImported = importAnnounces(bundle.announces)
                    onProgress(0.76f)

                    val peerIdentitiesImported = importPeerIdentities(bundle.peerIdentities)
                    onProgress(0.78f)

                    val interfacesImported = importInterfaces(bundle.interfaces)
                    onProgress(0.82f)

                    val (customThemesImported, themeIdMap) = importCustomThemes(bundle.customThemes)
                    onProgress(0.86f)

                    if (bundle.attachmentManifest.isNotEmpty()) {
                        importAttachments(uri)
                    }
                    onProgress(0.92f)

                    importSettings(bundle.settings, themeIdMap)
                    onProgress(1.0f)

                    Log.i(TAG, "Migration import complete")

                    ImportResult.Success(
                        identitiesImported = identitiesImported,
                        messagesImported = messagesImported,
                        contactsImported = contactsImported,
                        announcesImported = announcesImported,
                        peerIdentitiesImported = peerIdentitiesImported,
                        interfacesImported = interfacesImported,
                        customThemesImported = customThemesImported,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Migration import failed", e)
                    ImportResult.Error("Import failed: ${e.message}", e)
                }
            }

        private suspend fun importIdentities(
            identities: List<IdentityExport>,
            onProgress: (Float) -> Unit,
        ): Int {
            var imported = 0
            val activeIdentityFromExport = identities.find { it.isActive }?.identityHash

            identities.forEachIndexed { index, identityExport ->
                try {
                    if (importIdentity(identityExport)) imported++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import identity ${identityExport.identityHash}", e)
                }
                onProgress(0.1f + (0.3f * (index + 1) / identities.size))
            }

            if (activeIdentityFromExport != null) {
                Log.d(TAG, "Switching to active identity from export: $activeIdentityFromExport")
                database.localIdentityDao().setActive(activeIdentityFromExport)
            }
            Log.d(TAG, "Imported $imported identities")
            return imported
        }

        private suspend fun importConversations(conversations: List<ConversationExport>): Int {
            val entities = conversations.map { conv ->
                ConversationEntity(
                    peerHash = conv.peerHash,
                    identityHash = conv.identityHash,
                    peerName = conv.peerName,
                    peerPublicKey = conv.peerPublicKey?.let { Base64.decode(it, Base64.NO_WRAP) },
                    lastMessage = conv.lastMessage,
                    lastMessageTimestamp = conv.lastMessageTimestamp,
                    unreadCount = conv.unreadCount,
                    lastSeenTimestamp = conv.lastSeenTimestamp,
                )
            }
            database.conversationDao().insertConversations(entities)
            Log.d(TAG, "Imported ${entities.size} conversations")
            return entities.size
        }

        private suspend fun importMessages(
            messages: List<MessageExport>,
            onProgress: (Float) -> Unit,
        ): Int {
            val entities = messages.map { msg ->
                MessageEntity(
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
            val batches = entities.chunked(100)
            batches.forEachIndexed { batchIndex, batch ->
                database.messageDao().insertMessages(batch)
                onProgress(0.5f + (0.2f * (batchIndex + 1) / batches.size))
            }
            Log.d(TAG, "Imported ${entities.size} messages")
            return entities.size
        }

        private suspend fun importContacts(contacts: List<ContactExport>): Int {
            val entities = contacts.map { contact ->
                ContactEntity(
                    destinationHash = contact.destinationHash,
                    identityHash = contact.identityHash,
                    publicKey = Base64.decode(contact.publicKey, Base64.NO_WRAP),
                    customNickname = contact.customNickname,
                    notes = contact.notes,
                    tags = contact.tags,
                    addedTimestamp = contact.addedTimestamp,
                    addedVia = contact.addedVia,
                    lastInteractionTimestamp = contact.lastInteractionTimestamp,
                    isPinned = contact.isPinned,
                )
            }
            database.contactDao().insertContacts(entities)
            Log.d(TAG, "Imported ${entities.size} contacts")
            return entities.size
        }

        private suspend fun importAnnounces(announces: List<AnnounceExport>): Int {
            val entities = announces.map { announce ->
                AnnounceEntity(
                    destinationHash = announce.destinationHash,
                    peerName = announce.peerName,
                    publicKey = Base64.decode(announce.publicKey, Base64.NO_WRAP),
                    appData = announce.appData?.let { Base64.decode(it, Base64.NO_WRAP) },
                    hops = announce.hops,
                    lastSeenTimestamp = announce.lastSeenTimestamp,
                    nodeType = announce.nodeType,
                    receivingInterface = announce.receivingInterface,
                    aspect = announce.aspect,
                    isFavorite = announce.isFavorite,
                    favoritedTimestamp = announce.favoritedTimestamp,
                )
            }
            database.announceDao().insertAnnounces(entities)
            Log.d(TAG, "Imported ${entities.size} announces")
            return entities.size
        }

        private suspend fun importPeerIdentities(peerIdentities: List<PeerIdentityExport>): Int {
            val entities = peerIdentities.map { peer ->
                PeerIdentityEntity(
                    peerHash = peer.peerHash,
                    publicKey = Base64.decode(peer.publicKey, Base64.NO_WRAP),
                    lastSeenTimestamp = peer.lastSeenTimestamp,
                )
            }
            database.peerIdentityDao().insertPeerIdentities(entities)
            Log.d(TAG, "Imported ${entities.size} peer identities")
            return entities.size
        }

        private suspend fun importInterfaces(interfaces: List<InterfaceExport>): Int {
            var imported = 0
            val existingKeys = interfaceDatabase.interfaceDao().getAllInterfaces().first()
                .map { "${it.name}|${it.type}" }.toSet()

            interfaces.forEach { iface ->
                val key = "${iface.name}|${iface.type}"
                if (key !in existingKeys) {
                    interfaceDatabase.interfaceDao().insertInterface(
                        InterfaceEntity(
                            name = iface.name,
                            type = iface.type,
                            enabled = iface.enabled,
                            configJson = iface.configJson,
                            displayOrder = iface.displayOrder,
                        ),
                    )
                    imported++
                } else {
                    Log.d(TAG, "Interface '${iface.name}' (${iface.type}) already exists, skipping")
                }
            }
            Log.d(TAG, "Imported $imported interfaces")
            return imported
        }

        private data class ThemeImportResult(val imported: Int, val idMap: Map<Long, Long>)

        private suspend fun importCustomThemes(themes: List<CustomThemeExport>): ThemeImportResult {
            var imported = 0
            val themeIdMap = mutableMapOf<Long, Long>()
            val existingNames = database.customThemeDao().getAllThemes().first().map { it.name }.toSet()

            themes.forEach { theme ->
                if (theme.name !in existingNames) {
                    val newId = database.customThemeDao().insertTheme(createThemeEntity(theme))
                    themeIdMap[theme.originalId] = newId
                    imported++
                } else {
                    Log.d(TAG, "Custom theme '${theme.name}' already exists, skipping")
                    database.customThemeDao().getThemeByName(theme.name)?.let {
                        themeIdMap[theme.originalId] = it.id
                    }
                }
            }
            Log.d(TAG, "Imported $imported custom themes")
            return ThemeImportResult(imported, themeIdMap)
        }

        private fun createThemeEntity(theme: CustomThemeExport) = CustomThemeEntity(
            id = 0,
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

        /**
         * Read and parse the MigrationBundle from a ZIP file.
         */
        private fun readMigrationBundle(uri: Uri): MigrationBundle? {
            return try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                inputStream.use { stream ->
                    val manifestJson = extractManifestFromZip(stream)
                    manifestJson?.let { json.decodeFromString<MigrationBundle>(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read migration bundle", e)
                null
            }
        }

        private fun extractManifestFromZip(inputStream: java.io.InputStream): String? {
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_FILENAME) {
                        return zipIn.bufferedReader().readText()
                    }
                    entry = zipIn.nextEntry
                }
            }
            return null
        }

        /**
         * Import a single identity using the Reticulum protocol.
         */
        private suspend fun importIdentity(identityExport: IdentityExport): Boolean {
            // Check if identity already exists
            if (database.localIdentityDao().identityExists(identityExport.identityHash)) {
                Log.d(TAG, "Identity ${identityExport.identityHash} already exists, skipping")
                return false
            }

            // Decode the key data
            val keyData = if (identityExport.keyData.isNotEmpty()) {
                Base64.decode(identityExport.keyData, Base64.NO_WRAP)
            } else {
                Log.w(TAG, "No key data for identity ${identityExport.identityHash}")
                return false
            }

            // Create the identity file path
            val identityDir = File(context.filesDir, "reticulum")
            identityDir.mkdirs()
            val filePath = File(identityDir, "identity_${identityExport.identityHash}").absolutePath

            // Try to recover/import the identity via Reticulum
            try {
                val result = reticulumProtocol.recoverIdentityFile(
                    identityExport.identityHash,
                    keyData,
                    filePath,
                )
                val success = result["success"] as? Boolean ?: false
                if (!success) {
                    Log.w(
                        TAG,
                        "Reticulum failed to recover identity: ${result["error"]}",
                    )
                    // Fall back to direct database insert
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reticulum recovery failed, using direct insert", e)
            }

            // Insert into database
            val entity = LocalIdentityEntity(
                identityHash = identityExport.identityHash,
                displayName = identityExport.displayName,
                destinationHash = identityExport.destinationHash,
                filePath = filePath,
                keyData = keyData,
                createdTimestamp = identityExport.createdTimestamp,
                lastUsedTimestamp = identityExport.lastUsedTimestamp,
                isActive = identityExport.isActive,
            )
            database.localIdentityDao().insert(entity)

            // Write key file directly as backup
            try {
                File(filePath).writeBytes(keyData)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write identity file to $filePath", e)
            }

            return true
        }

        /**
         * Import attachments from the ZIP file.
         */
        private fun importAttachments(uri: Uri): Int {
            val attachmentsDir = File(context.filesDir, "attachments")
            attachmentsDir.mkdirs()

            return try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return 0
                inputStream.use { extractAttachmentsFromZip(it, attachmentsDir) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import attachments", e)
                0
            }
        }

        private fun extractAttachmentsFromZip(inputStream: java.io.InputStream, destDir: File): Int {
            var imported = 0
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith(ATTACHMENTS_PREFIX) && !entry.isDirectory) {
                        val relativePath = entry.name.removePrefix(ATTACHMENTS_PREFIX)
                        val destFile = File(destDir, relativePath)
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { output -> zipIn.copyTo(output) }
                        imported++
                    }
                    entry = zipIn.nextEntry
                }
            }
            return imported
        }

        /**
         * Import settings using SettingsRepository.
         * @param themeIdMap Maps old custom theme IDs to new IDs for theme preference restoration
         */
        private suspend fun importSettings(
            settings: SettingsExport,
            themeIdMap: Map<Long, Long>,
        ) {
            settingsRepository.saveNotificationsEnabled(settings.notificationsEnabled)
            settingsRepository.saveNotificationReceivedMessage(settings.notificationReceivedMessage)
            settingsRepository.saveNotificationReceivedMessageFavorite(settings.notificationReceivedMessageFavorite)
            settingsRepository.saveNotificationHeardAnnounce(settings.notificationHeardAnnounce)
            settingsRepository.saveNotificationBleConnected(settings.notificationBleConnected)
            settingsRepository.saveNotificationBleDisconnected(settings.notificationBleDisconnected)
            settingsRepository.saveAutoAnnounceEnabled(settings.autoAnnounceEnabled)
            settingsRepository.saveAutoAnnounceIntervalMinutes(settings.autoAnnounceIntervalMinutes)

            // Restore theme preference with ID remapping for custom themes
            try {
                val themePreference = settings.themePreference
                val remappedThemePref = if (themePreference.startsWith("custom:")) {
                    // Extract old ID and map to new ID
                    val oldId = themePreference.removePrefix("custom:").toLongOrNull()
                    if (oldId != null && themeIdMap.containsKey(oldId)) {
                        "custom:${themeIdMap[oldId]}"
                    } else {
                        Log.w(TAG, "Custom theme ID $oldId not found in mapping, using default")
                        null // Will use default theme
                    }
                } else {
                    // Preset theme (e.g., "preset:VIBRANT") - use as-is
                    themePreference
                }

                if (remappedThemePref != null) {
                    settingsRepository.saveThemePreferenceByIdentifier(remappedThemePref)
                    Log.d(TAG, "Restored theme preference: $remappedThemePref")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore theme preference: ${e.message}")
                // Non-fatal - continue with default theme
            }

            // Mark onboarding as completed since we're importing from another device
            settingsRepository.markOnboardingCompleted()
        }
    }
