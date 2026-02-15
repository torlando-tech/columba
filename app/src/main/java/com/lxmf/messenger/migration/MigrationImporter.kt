package com.lxmf.messenger.migration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.lxmf.messenger.data.database.InterfaceDatabase
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.CustomThemeEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import com.lxmf.messenger.data.model.InterfaceType
import com.lxmf.messenger.data.util.HashUtils
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.PropagationNodeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
@Suppress("TooManyFunctions", "LargeClass") // Helper methods extracted for readability
@Singleton
class MigrationImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: ColumbaDatabase,
        private val interfaceDatabase: InterfaceDatabase,
        private val reticulumProtocol: ReticulumProtocol,
        private val settingsRepository: SettingsRepository,
        private val propagationNodeManager: PropagationNodeManager,
    ) {
        companion object {
            private const val TAG = "MigrationImporter"
            private const val MANIFEST_FILENAME = "manifest.json"
            private const val ATTACHMENTS_PREFIX = "attachments/"
        }

        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /**
         * Check whether a migration file is encrypted (requires a password to import).
         */
        suspend fun isEncryptedExport(uri: Uri): Result<Boolean> =
            withContext(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: return@withContext Result.failure(Exception("Cannot open file"))
                    inputStream.use { stream ->
                        val header = ByteArray(2)
                        val bytesRead = stream.read(header)
                        if (bytesRead < 1) {
                            return@withContext Result.failure(
                                InvalidExportFileException("Export file is empty"),
                            )
                        }
                        Result.success(MigrationCrypto.isEncrypted(header))
                    }
                } catch (e: InvalidExportFileException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check export format", e)
                    Result.failure(e)
                }
            }

        suspend fun previewMigration(
            uri: Uri,
            password: String? = null,
        ): Result<MigrationPreview> =
            withContext(Dispatchers.IO) {
                try {
                    val (bundle, _) =
                        readMigrationBundle(uri, password)
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
            password: String? = null,
            onProgress: (Float) -> Unit = {},
        ): ImportResult =
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Starting migration import...")
                    onProgress(0.05f)

                    val (bundle, zipBytes) =
                        readMigrationBundle(uri, password)
                            ?: return@withContext ImportResult.Error("Failed to read migration file")

                    if (bundle.version > MigrationBundle.CURRENT_VERSION) {
                        return@withContext ImportResult.Error(
                            "Migration file is from a newer version (${bundle.version}). " +
                                "Please update the app first.",
                        )
                    }

                    // Check minimum supported version for backwards compatibility
                    if (bundle.version < MigrationBundle.MINIMUM_VERSION) {
                        return@withContext ImportResult.Error(
                            "Migration file is from an old version (${bundle.version}). " +
                                "Minimum supported version is ${MigrationBundle.MINIMUM_VERSION}.",
                        )
                    }
                    onProgress(0.1f)

                    // Track successfully imported identities to filter dependent data
                    val importedIdentityHashes = mutableSetOf<String>()

                    // Wrap main database operations in a transaction for atomicity
                    val txResult =
                        database.withTransaction {
                            importDatabaseData(bundle, importedIdentityHashes, onProgress)
                        }

                    // Interface database is separate, import outside main transaction
                    val interfacesImported = importInterfaces(bundle.interfaces)
                    onProgress(0.86f)

                    if (bundle.attachmentManifest.isNotEmpty()) importAttachments(zipBytes)
                    onProgress(0.90f)

                    importRatchets(bundle.ratchetFiles)
                    onProgress(0.93f)

                    importSettings(bundle.settings, txResult.themeIdMap)
                    onProgress(0.95f)

                    // Restore relay settings after both the DB transaction and settings import
                    // so DataStore writes are never inside a Room transaction scope.
                    restoreRelaySettings(txResult.restoredRelayHash)
                    onProgress(1.0f)

                    Log.i(TAG, "Migration import complete")
                    ImportResult.Success(
                        identitiesImported = txResult.identitiesImported,
                        messagesImported = txResult.messagesImported,
                        contactsImported = txResult.contactsImported,
                        announcesImported = txResult.announcesImported,
                        peerIdentitiesImported = txResult.peerIdentitiesImported,
                        interfacesImported = interfacesImported,
                        customThemesImported = txResult.customThemesImported,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Migration import failed", e)
                    ImportResult.Error("Import failed: ${e.message}", e)
                }
            }

        /**
         * Helper data class to return multiple values from transaction block.
         */
        private data class TransactionResult(
            val identitiesImported: Int,
            val messagesImported: Int,
            val contactsImported: Int,
            val announcesImported: Int,
            val peerIdentitiesImported: Int,
            val customThemesImported: Int,
            val themeIdMap: Map<Long, Long>,
            /** Destination hash of the relay contact restored from backup, if any. */
            val restoredRelayHash: String?,
        )

        /**
         * Import all database data within a transaction.
         * Extracted to keep importData under line limit.
         */
        private suspend fun importDatabaseData(
            bundle: MigrationBundle,
            importedIdentityHashes: MutableSet<String>,
            onProgress: (Float) -> Unit,
        ): TransactionResult {
            val identities = importIdentities(bundle.identities, importedIdentityHashes, onProgress)
            onProgress(0.4f)

            // Filter to only import data for valid identities
            val validConversations =
                bundle.conversations.filter {
                    it.identityHash in importedIdentityHashes ||
                        database.localIdentityDao().identityExists(it.identityHash)
                }
            importConversations(validConversations)
            onProgress(0.5f)

            val validMessages =
                bundle.messages.filter {
                    it.identityHash in importedIdentityHashes ||
                        database.localIdentityDao().identityExists(it.identityHash)
                }
            val messages = importMessages(validMessages, onProgress)
            onProgress(0.7f)

            val validContacts =
                bundle.contacts.filter {
                    it.identityHash in importedIdentityHashes ||
                        database.localIdentityDao().identityExists(it.identityHash)
                }
            val contactResult = importContacts(validContacts)
            onProgress(0.75f)

            val announces = importAnnounces(bundle.announces)
            onProgress(0.76f)

            val peerIdentities = importPeerIdentities(bundle.peerIdentities)
            onProgress(0.78f)

            val (themes, idMap) = importCustomThemes(bundle.customThemes)
            onProgress(0.82f)

            return TransactionResult(
                identities,
                messages,
                contactResult.imported,
                announces,
                peerIdentities,
                themes,
                idMap,
                contactResult.relayHash,
            )
        }

        private suspend fun importIdentities(
            identities: List<IdentityExport>,
            importedIdentityHashes: MutableSet<String>,
            onProgress: (Float) -> Unit,
        ): Int {
            var imported = 0
            val activeIdentityFromExport = identities.find { it.isActive }?.identityHash

            identities.forEachIndexed { index, identityExport ->
                try {
                    if (importIdentity(identityExport)) {
                        imported++
                        importedIdentityHashes.add(identityExport.identityHash)
                    } else if (database.localIdentityDao().identityExists(identityExport.identityHash)) {
                        // Identity already exists, still counts as valid for dependent data
                        importedIdentityHashes.add(identityExport.identityHash)
                    }
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
            val entities =
                conversations.map { conv ->
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
            val entities =
                messages.map { msg ->
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
                // Use IGNORE strategy to preserve existing messages
                // This prevents LXMF replay from overwriting imported message timestamps
                database.messageDao().insertMessagesIgnoreDuplicates(batch)
                onProgress(0.5f + (0.2f * (batchIndex + 1) / batches.size))
            }
            Log.d(TAG, "Imported ${entities.size} messages")
            return entities.size
        }

        private suspend fun importContacts(contacts: List<ContactExport>): ContactImportResult {
            // Track relay restoration — written to DataStore after the transaction completes
            var restoredRelayHash: String? = null

            val entities =
                contacts.map { contact ->
                    // Determine status: use exported value, or infer from publicKey for backward compatibility
                    val status =
                        contact.status?.let {
                            try {
                                ContactStatus.valueOf(it)
                            } catch (_: Exception) {
                                null
                            }
                        } ?: if (contact.publicKey == null) {
                            ContactStatus.PENDING_IDENTITY
                        } else {
                            ContactStatus.ACTIVE
                        }

                    // Track which contact was the relay for settings restoration
                    val isMyRelay = contact.isMyRelay == true
                    if (isMyRelay) {
                        if (restoredRelayHash != null) {
                            Log.w(TAG, "Multiple relay contacts found in backup, using latest")
                        }
                        restoredRelayHash = contact.destinationHash
                        Log.d(TAG, "Found relay contact: ${contact.customNickname ?: contact.destinationHash.take(12)}")
                    }

                    ContactEntity(
                        destinationHash = contact.destinationHash,
                        identityHash = contact.identityHash,
                        publicKey = contact.publicKey?.let { Base64.decode(it, Base64.NO_WRAP) },
                        customNickname = contact.customNickname,
                        notes = contact.notes,
                        tags = contact.tags,
                        addedTimestamp = contact.addedTimestamp,
                        addedVia = contact.addedVia,
                        lastInteractionTimestamp = contact.lastInteractionTimestamp,
                        isPinned = contact.isPinned,
                        status = status,
                        isMyRelay = isMyRelay,
                    )
                }
            database.contactDao().insertContacts(entities)
            Log.d(TAG, "Imported ${entities.size} contacts")

            return ContactImportResult(entities.size, restoredRelayHash)
        }

        private suspend fun importAnnounces(announces: List<AnnounceExport>): Int {
            val entities =
                announces.map { announce ->
                    // Derive interface type from receivingInterface if not present (backward compatibility)
                    val interfaceType =
                        announce.receivingInterfaceType
                            ?: InterfaceType.fromInterfaceName(announce.receivingInterface).name

                    val decodedPublicKey = Base64.decode(announce.publicKey, Base64.NO_WRAP)
                    AnnounceEntity(
                        destinationHash = announce.destinationHash,
                        peerName = announce.peerName,
                        publicKey = decodedPublicKey,
                        appData = announce.appData?.let { Base64.decode(it, Base64.NO_WRAP) },
                        hops = announce.hops,
                        lastSeenTimestamp = announce.lastSeenTimestamp,
                        nodeType = announce.nodeType,
                        receivingInterface = announce.receivingInterface,
                        receivingInterfaceType = interfaceType,
                        aspect = announce.aspect,
                        isFavorite = announce.isFavorite,
                        favoritedTimestamp = announce.favoritedTimestamp,
                        computedIdentityHash = HashUtils.computeIdentityHash(decodedPublicKey),
                    )
                }
            database.announceDao().insertAnnounces(entities)
            Log.d(TAG, "Imported ${entities.size} announces")
            return entities.size
        }

        private suspend fun importPeerIdentities(peerIdentities: List<PeerIdentityExport>): Int {
            val entities =
                peerIdentities.map { peer ->
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
            val existingKeys =
                interfaceDatabase
                    .interfaceDao()
                    .getAllInterfaces()
                    .first()
                    .map { "${it.name}|${it.type}" }
                    .toSet()

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

        private data class ContactImportResult(
            val imported: Int,
            val relayHash: String?,
        )

        private data class ThemeImportResult(
            val imported: Int,
            val idMap: Map<Long, Long>,
        )

        private suspend fun importCustomThemes(themes: List<CustomThemeExport>): ThemeImportResult {
            var imported = 0
            val themeIdMap = mutableMapOf<Long, Long>()
            val existingNames =
                database
                    .customThemeDao()
                    .getAllThemes()
                    .first()
                    .map { it.name }
                    .toSet()

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

        private fun createThemeEntity(theme: CustomThemeExport) =
            CustomThemeEntity(
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
        @Suppress("ThrowsCount")
        private fun readMigrationBundle(
            uri: Uri,
            password: String? = null,
        ): Pair<MigrationBundle, ByteArray>? {
            return try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                inputStream.use { stream ->
                    val rawBytes = stream.readBytes()
                    val zipBytes =
                        if (MigrationCrypto.isEncrypted(rawBytes)) {
                            if (password == null) {
                                throw PasswordRequiredException("This export file is encrypted")
                            }
                            MigrationCrypto.decrypt(rawBytes, password)
                        } else {
                            rawBytes
                        }
                    val manifestJson = extractManifestFromZip(java.io.ByteArrayInputStream(zipBytes))
                    manifestJson?.let { json.decodeFromString<MigrationBundle>(it) to zipBytes }
                }
            } catch (e: WrongPasswordException) {
                Log.e(TAG, "Wrong password for encrypted export", e)
                throw e
            } catch (e: PasswordRequiredException) {
                Log.e(TAG, "Password required for encrypted export", e)
                throw e
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
                Log.d(TAG, "Identity ${identityExport.identityHash} already exists, updating icon if present")
                // Update icon fields for existing identity if they're present in the export
                if (identityExport.iconName != null ||
                    identityExport.iconForegroundColor != null ||
                    identityExport.iconBackgroundColor != null
                ) {
                    database.localIdentityDao().updateIconAppearance(
                        identityHash = identityExport.identityHash,
                        iconName = identityExport.iconName,
                        foregroundColor = identityExport.iconForegroundColor,
                        backgroundColor = identityExport.iconBackgroundColor,
                    )
                    Log.d(TAG, "Updated icon for existing identity ${identityExport.identityHash}")
                }
                return false
            }

            // Decode the key data
            val keyData =
                if (identityExport.keyData.isNotEmpty()) {
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
                val result =
                    reticulumProtocol.recoverIdentityFile(
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

            // Insert into database with profile icon data
            val entity =
                LocalIdentityEntity(
                    identityHash = identityExport.identityHash,
                    displayName = identityExport.displayName,
                    destinationHash = identityExport.destinationHash,
                    filePath = filePath,
                    keyData = keyData,
                    createdTimestamp = identityExport.createdTimestamp,
                    lastUsedTimestamp = identityExport.lastUsedTimestamp,
                    isActive = identityExport.isActive,
                    // Restore profile icon data (nullable for backward compatibility)
                    iconName = identityExport.iconName,
                    iconForegroundColor = identityExport.iconForegroundColor,
                    iconBackgroundColor = identityExport.iconBackgroundColor,
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
         * Import ratchet key files from the migration bundle.
         * Restores forward secrecy keys so messages encrypted with ratchets
         * can still be decrypted after migration.
         */
        private fun importRatchets(ratchetFiles: List<RatchetRef>): Int {
            if (ratchetFiles.isEmpty()) return 0

            val reticulumDir = File(context.filesDir, "reticulum")
            val imported = ratchetFiles.count { importSingleRatchet(it, reticulumDir) }

            Log.d(TAG, "Imported $imported ratchet files")
            return imported
        }

        private fun importSingleRatchet(
            ref: RatchetRef,
            reticulumDir: File,
        ): Boolean {
            val subPath =
                when (ref.type) {
                    "own" -> "lxmf/ratchets"
                    "peer" -> "storage/ratchets"
                    else -> {
                        Log.w(TAG, "Unknown ratchet type: ${ref.type}")
                        return false
                    }
                }
            val targetDir = File(reticulumDir, subPath).also { it.mkdirs() }

            // Security: prevent path traversal
            val destFile = File(targetDir, ref.filename)
            if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                Log.w(TAG, "Skipping suspicious ratchet path: ${ref.filename}")
                return false
            }

            return try {
                destFile.writeBytes(Base64.decode(ref.data, Base64.NO_WRAP))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import ratchet ${ref.filename}", e)
                false
            }
        }

        /**
         * Import attachments from the ZIP file.
         */
        private fun importAttachments(zipBytes: ByteArray): Int {
            val attachmentsDir = File(context.filesDir, "attachments")
            attachmentsDir.mkdirs()

            return try {
                extractAttachmentsFromZip(java.io.ByteArrayInputStream(zipBytes), attachmentsDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import attachments", e)
                0
            }
        }

        private fun extractAttachmentsFromZip(
            inputStream: java.io.InputStream,
            destDir: File,
        ): Int {
            var imported = 0
            val destDirCanonical = destDir.canonicalPath
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith(ATTACHMENTS_PREFIX) && !entry.isDirectory) {
                        val relativePath = entry.name.removePrefix(ATTACHMENTS_PREFIX)
                        val destFile = File(destDir, relativePath)

                        // Security: Prevent path traversal attacks (e.g., "../../../sensitive_file")
                        if (!destFile.canonicalPath.startsWith(destDirCanonical + File.separator)) {
                            Log.w(TAG, "Skipping suspicious path (path traversal attempt): ${entry.name}")
                            entry = zipIn.nextEntry
                            continue
                        }

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
         * Handles both new automatic format and legacy format for backward compatibility.
         * @param themeIdMap Maps old custom theme IDs to new IDs for theme preference restoration
         */
        private suspend fun importSettings(
            settings: SettingsExport,
            themeIdMap: Map<Long, Long>,
        ) {
            // New format: Import all preferences automatically
            if (settings.preferences.isNotEmpty()) {
                Log.d(TAG, "Importing settings using automatic format (${settings.preferences.size} entries)")
                settingsRepository.importAllPreferences(settings.preferences)

                // Handle theme preference remapping for custom themes
                val themePreferenceEntry = settings.preferences.find { it.key == "app_theme" }
                if (themePreferenceEntry != null) {
                    remapThemePreference(themePreferenceEntry.value, themeIdMap)
                }
            } else {
                // Legacy format: Import manually from individual fields
                Log.d(TAG, "Importing settings using legacy format (backward compatibility)")
                importLegacySettings(settings, themeIdMap)
            }

            // Mark onboarding as completed since we're importing from another device
            settingsRepository.markOnboardingCompleted()
        }

        /**
         * Remap theme preference for custom themes after import.
         * Custom theme IDs may have changed during import, so we need to update the preference.
         */
        private suspend fun remapThemePreference(
            themePreference: String,
            themeIdMap: Map<Long, Long>,
        ) {
            try {
                val remappedThemePref =
                    if (themePreference.startsWith("custom:")) {
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

                if (remappedThemePref != null && remappedThemePref != themePreference) {
                    settingsRepository.saveThemePreferenceByIdentifier(remappedThemePref)
                    Log.d(TAG, "Remapped theme preference: $themePreference -> $remappedThemePref")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remap theme preference: ${e.message}")
                // Non-fatal - continue with imported theme
            }
        }

        /**
         * Import settings from legacy format (pre-automatic export).
         * Delegates to LegacySettingsImporter for backward compatibility with older exports.
         */
        private suspend fun importLegacySettings(
            settings: SettingsExport,
            themeIdMap: Map<Long, Long>,
        ) {
            LegacySettingsImporter(settingsRepository).importAll(settings, themeIdMap)
        }

        /**
         * Restore relay (propagation node) settings after the DB transaction and settings import.
         *
         * This runs AFTER importSettings, which may have already restored the relay preference
         * from the backup's DataStore preferences. We only write from the contact's isMyRelay
         * flag if importSettings didn't already restore a manual relay — this covers the case
         * where an old backup has the contact flag but not the DataStore preference.
         *
         * If no relay was restored from either source and auto-select is enabled,
         * trigger auto-selection so the user doesn't end up with no relay at all.
         */
        private suspend fun restoreRelaySettings(restoredRelayHash: String?) {
            try {
                val manualRelay = settingsRepository.getManualPropagationNode()

                if (manualRelay != null) {
                    // importSettings already restored the relay preference — nothing to do
                    Log.d(TAG, "Relay already restored from settings: $manualRelay")
                    return
                }

                if (restoredRelayHash != null) {
                    // Contact had isMyRelay=true but settings didn't include the preference
                    // (e.g., older backup format). Write it now.
                    settingsRepository.saveManualPropagationNode(restoredRelayHash)
                    settingsRepository.saveAutoSelectPropagationNode(false)
                    Log.d(TAG, "Restored manual propagation node from contact flag: $restoredRelayHash")
                    return
                }

                // No relay from either source — trigger auto-select if enabled
                val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
                if (isAutoSelect) {
                    Log.d(TAG, "No relay restored, auto-select enabled — triggering auto-selection")
                    propagationNodeManager.enableAutoSelect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore relay settings after import", e)
                // Non-fatal — user can manually select a relay
            }
        }
    }
