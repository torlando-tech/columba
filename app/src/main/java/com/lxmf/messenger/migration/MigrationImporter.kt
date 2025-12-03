package com.lxmf.messenger.migration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lxmf.messenger.data.database.InterfaceDatabase
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
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
                            interfaceCount = bundle.interfaces.size,
                            attachmentCount = bundle.attachmentManifest.size,
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

                    // 1. Read and parse the migration bundle
                    val bundle = readMigrationBundle(uri)
                        ?: return@withContext ImportResult.Error("Failed to read migration file")

                    // Validate version
                    if (bundle.version > MigrationBundle.CURRENT_VERSION) {
                        return@withContext ImportResult.Error(
                            "Migration file is from a newer version (${bundle.version}). " +
                                "Please update the app first.",
                        )
                    }

                    onProgress(0.1f)

                    // 2. Import identities
                    var identitiesImported = 0
                    bundle.identities.forEachIndexed { index, identityExport ->
                        try {
                            val imported = importIdentity(identityExport)
                            if (imported) identitiesImported++
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Failed to import identity ${identityExport.identityHash}",
                                e,
                            )
                        }
                        val progress = 0.1f + (0.3f * (index + 1) / bundle.identities.size)
                        onProgress(progress)
                    }
                    Log.d(TAG, "Imported $identitiesImported identities")
                    onProgress(0.4f)

                    // 3. Import conversations
                    val conversationEntities = bundle.conversations.map { conv ->
                        ConversationEntity(
                            peerHash = conv.peerHash,
                            identityHash = conv.identityHash,
                            peerName = conv.peerName,
                            peerPublicKey = conv.peerPublicKey?.let {
                                Base64.decode(it, Base64.NO_WRAP)
                            },
                            lastMessage = conv.lastMessage,
                            lastMessageTimestamp = conv.lastMessageTimestamp,
                            unreadCount = conv.unreadCount,
                            lastSeenTimestamp = conv.lastSeenTimestamp,
                        )
                    }
                    database.conversationDao().insertConversations(conversationEntities)
                    Log.d(TAG, "Imported ${conversationEntities.size} conversations")
                    onProgress(0.5f)

                    // 4. Import messages in batches
                    val messageEntities = bundle.messages.map { msg ->
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
                    // Insert in batches to avoid memory issues
                    messageEntities.chunked(100).forEachIndexed { batchIndex, batch ->
                        database.messageDao().insertMessages(batch)
                        val progress = 0.5f + (0.2f * (batchIndex + 1) /
                            ((messageEntities.size / 100) + 1))
                        onProgress(progress)
                    }
                    Log.d(TAG, "Imported ${messageEntities.size} messages")
                    onProgress(0.7f)

                    // 5. Import contacts
                    val contactEntities = bundle.contacts.map { contact ->
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
                    database.contactDao().insertContacts(contactEntities)
                    Log.d(TAG, "Imported ${contactEntities.size} contacts")
                    onProgress(0.75f)

                    // 6. Import announces (known peers)
                    val announceEntities = bundle.announces.map { announce ->
                        AnnounceEntity(
                            destinationHash = announce.destinationHash,
                            peerName = announce.peerName,
                            publicKey = Base64.decode(announce.publicKey, Base64.NO_WRAP),
                            appData = announce.appData?.let {
                                Base64.decode(it, Base64.NO_WRAP)
                            },
                            hops = announce.hops,
                            lastSeenTimestamp = announce.lastSeenTimestamp,
                            nodeType = announce.nodeType,
                            receivingInterface = announce.receivingInterface,
                            aspect = announce.aspect,
                            isFavorite = announce.isFavorite,
                            favoritedTimestamp = announce.favoritedTimestamp,
                        )
                    }
                    database.announceDao().insertAnnounces(announceEntities)
                    Log.d(TAG, "Imported ${announceEntities.size} announces")
                    onProgress(0.78f)

                    // 7. Import interfaces (checking for duplicates by name+type)
                    var interfacesImported = 0
                    val existingInterfaces = interfaceDatabase.interfaceDao()
                        .getAllInterfaces().first()
                    val existingKeys = existingInterfaces.map { "${it.name}|${it.type}" }.toSet()

                    bundle.interfaces.forEach { iface ->
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
                            interfacesImported++
                        } else {
                            Log.d(TAG, "Interface '${iface.name}' (${iface.type}) already exists, skipping")
                        }
                    }
                    Log.d(TAG, "Imported $interfacesImported interfaces")
                    onProgress(0.82f)

                    // 8. Import attachments
                    var attachmentsImported = 0
                    if (bundle.attachmentManifest.isNotEmpty()) {
                        attachmentsImported = importAttachments(uri, bundle.attachmentManifest)
                    }
                    Log.d(TAG, "Imported $attachmentsImported attachments")
                    onProgress(0.92f)

                    // 9. Import settings
                    importSettings(bundle.settings)
                    Log.d(TAG, "Imported settings")
                    onProgress(1.0f)

                    Log.i(TAG, "Migration import complete")

                    ImportResult.Success(
                        identitiesImported = identitiesImported,
                        messagesImported = messageEntities.size,
                        contactsImported = contactEntities.size,
                        announcesImported = announceEntities.size,
                        interfacesImported = interfacesImported,
                        attachmentsImported = attachmentsImported,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Migration import failed", e)
                    ImportResult.Error("Import failed: ${e.message}", e)
                }
            }

        /**
         * Read and parse the MigrationBundle from a ZIP file.
         */
        private fun readMigrationBundle(uri: Uri): MigrationBundle? {
            return try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipIn ->
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            if (entry.name == MANIFEST_FILENAME) {
                                val manifestJson = zipIn.bufferedReader().readText()
                                return json.decodeFromString<MigrationBundle>(manifestJson)
                            }
                            entry = zipIn.nextEntry
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read migration bundle", e)
                null
            }
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
        private fun importAttachments(
            uri: Uri,
            manifest: List<AttachmentRef>,
        ): Int {
            var imported = 0
            val attachmentsDir = File(context.filesDir, "attachments")
            attachmentsDir.mkdirs()

            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipIn ->
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            if (entry.name.startsWith(ATTACHMENTS_PREFIX) && !entry.isDirectory) {
                                val relativePath = entry.name.removePrefix(ATTACHMENTS_PREFIX)
                                val destFile = File(attachmentsDir, relativePath)
                                destFile.parentFile?.mkdirs()

                                FileOutputStream(destFile).use { output ->
                                    zipIn.copyTo(output)
                                }
                                imported++
                            }
                            entry = zipIn.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import attachments", e)
            }

            return imported
        }

        /**
         * Import settings using SettingsRepository.
         */
        private suspend fun importSettings(settings: SettingsExport) {
            settingsRepository.saveNotificationsEnabled(settings.notificationsEnabled)
            settingsRepository.saveNotificationReceivedMessage(settings.notificationReceivedMessage)
            settingsRepository.saveNotificationReceivedMessageFavorite(settings.notificationReceivedMessageFavorite)
            settingsRepository.saveNotificationHeardAnnounce(settings.notificationHeardAnnounce)
            settingsRepository.saveNotificationBleConnected(settings.notificationBleConnected)
            settingsRepository.saveNotificationBleDisconnected(settings.notificationBleDisconnected)
            settingsRepository.saveAutoAnnounceEnabled(settings.autoAnnounceEnabled)
            settingsRepository.saveAutoAnnounceIntervalMinutes(settings.autoAnnounceIntervalMinutes)
            // Mark onboarding as completed since we're importing from another device
            settingsRepository.markOnboardingCompleted()
        }
    }
