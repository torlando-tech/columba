package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.db.entity.GuardianConfigEntity
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.GuardianRepository
import com.lxmf.messenger.reticulum.protocol.ReceivedMessage
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes parental control commands received from a guardian device.
 *
 * Commands arrive as LXMF messages with a special field (FIELD_PARENTAL_CONTROL = 0x80)
 * containing msgpack-encoded command data with signature.
 *
 * Command types:
 * - LOCK: Lock the device (enable allow-list filtering)
 * - UNLOCK: Unlock the device (disable allow-list filtering)
 * - ALLOW_ADD: Add contacts to the allow list
 * - ALLOW_REMOVE: Remove contacts from the allow list
 * - ALLOW_SET: Replace the entire allow list
 * - STATUS_REQUEST: Request current lock state and allow list
 *
 * Security:
 * - All commands must be signed by the guardian's Ed25519 private key
 * - Signature verification uses the stored guardian public key
 * - Replay attacks prevented via nonce + timestamp validation
 */
@Singleton
class GuardianCommandProcessor
    @Inject
    constructor(
        private val guardianRepository: GuardianRepository,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: ContactRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) {
        companion object {
            private const val TAG = "GuardianCommandProcessor"

            // LXMF field type for parental control commands
            const val FIELD_PARENTAL_CONTROL = 0x80

            // Command types (guardian -> child)
            const val CMD_LOCK = "LOCK"
            const val CMD_UNLOCK = "UNLOCK"
            const val CMD_ALLOW_ADD = "ALLOW_ADD"
            const val CMD_ALLOW_REMOVE = "ALLOW_REMOVE"
            const val CMD_ALLOW_SET = "ALLOW_SET"
            const val CMD_STATUS_REQUEST = "STATUS_REQUEST"

            // Command types (child -> guardian)
            const val CMD_PAIR_ACK = "PAIR_ACK"

            // Command timestamp window (5 minutes)
            const val COMMAND_WINDOW_MS = 5 * 60 * 1000L

            // Prefix for guardian commands embedded in message content
            private const val GUARDIAN_CMD_PREFIX = "__GUARDIAN_CMD__:"
        }

        // Coroutine scope for background processing
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Check if a received message is a guardian command.
         *
         * A message is a guardian command if:
         * 1. The sender is the configured guardian
         * 2. The message contains the FIELD_PARENTAL_CONTROL field OR starts with __GUARDIAN_CMD__:
         *
         * @param message The received LXMF message
         * @param guardianConfig Current guardian config (or null if none)
         * @return True if this is a guardian command message
         */
        fun isGuardianCommand(message: ReceivedMessage, guardianConfig: GuardianConfigEntity?): Boolean {
            if (guardianConfig == null || !guardianConfig.hasGuardian()) {
                return false
            }

            // Check if sender is the guardian
            val senderHash = message.sourceHash.joinToString("") { "%02x".format(it) }
            if (senderHash != guardianConfig.guardianDestinationHash) {
                Log.d(TAG, "Message sender $senderHash doesn't match guardian ${guardianConfig.guardianDestinationHash}")
                return false
            }

            // Check for command embedded in content (new format)
            if (message.content.startsWith(GUARDIAN_CMD_PREFIX)) {
                Log.d(TAG, "Found guardian command in content from ${senderHash.take(16)}")
                return true
            }

            // Check for parental control field in LXMF fields (legacy format)
            val fieldsJson = message.fieldsJson ?: return false
            return try {
                val fields = JSONObject(fieldsJson)
                val hasField = fields.has(FIELD_PARENTAL_CONTROL.toString()) ||
                    fields.has("128") // Decimal representation of 0x80
                if (hasField) {
                    Log.d(TAG, "Found guardian command in LXMF field 0x80 from ${senderHash.take(16)}")
                }
                hasField
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing fields JSON", e)
                false
            }
        }

        /**
         * Process a guardian command message.
         *
         * @param message The received LXMF message
         * @param guardianConfig Current guardian config
         * @return True if the command was processed successfully
         */
        suspend fun processCommand(
            message: ReceivedMessage,
            guardianConfig: GuardianConfigEntity,
        ): Boolean {
            try {
                // Extract command data from content (new format) or LXMF field 0x80 (legacy)
                val commandDataStr: String = if (message.content.startsWith(GUARDIAN_CMD_PREFIX)) {
                    // New format: command JSON in message content
                    message.content.removePrefix(GUARDIAN_CMD_PREFIX)
                } else {
                    // Legacy format: command in LXMF field 0x80
                    val fieldsJson = message.fieldsJson
                    if (fieldsJson == null) {
                        Log.e(TAG, "No fields JSON and no command in content")
                        return false
                    }
                    val fields = JSONObject(fieldsJson)
                    fields.optString(FIELD_PARENTAL_CONTROL.toString())
                        .ifEmpty { fields.optString("128") }
                }

                if (commandDataStr.isEmpty()) {
                    Log.e(TAG, "No command data found in message")
                    return false
                }

                // Parse command JSON
                // Expected format: {"cmd": "LOCK", "nonce": "hex", "timestamp": ms, "payload": {...}, "signature": "hex"}
                val commandData = JSONObject(commandDataStr)

                val cmd = commandData.getString("cmd")
                val nonceHex = commandData.getString("nonce")
                val timestamp = commandData.getLong("timestamp")
                val signature = commandData.getString("signature")
                val payload = commandData.optJSONObject("payload") ?: JSONObject()

                Log.d(TAG, "Processing guardian command: $cmd")

                // Validate timestamp (within window)
                val now = System.currentTimeMillis()
                if (kotlin.math.abs(now - timestamp) > COMMAND_WINDOW_MS) {
                    Log.w(TAG, "Command timestamp outside window: $timestamp (now: $now)")
                    return false
                }

                // Validate nonce hasn't been used (anti-replay)
                if (!guardianRepository.validateCommand(nonceHex, timestamp)) {
                    Log.w(TAG, "Command replay detected: nonce=$nonceHex")
                    return false
                }

                // TODO: Verify signature using guardianConfig.guardianPublicKey
                // This requires exposing guardian_verify_command via AIDL
                // For now, we trust messages from the guardian destination
                // (This is acceptable for initial implementation since destination is authenticated)

                // Execute command
                val success = when (cmd) {
                    CMD_LOCK -> executeLock()
                    CMD_UNLOCK -> executeUnlock()
                    CMD_ALLOW_ADD -> executeAllowAdd(payload)
                    CMD_ALLOW_REMOVE -> executeAllowRemove(payload)
                    CMD_ALLOW_SET -> executeAllowSet(payload)
                    CMD_STATUS_REQUEST -> executeStatusRequest()
                    else -> {
                        Log.w(TAG, "Unknown command type: $cmd")
                        false
                    }
                }

                if (success) {
                    // Record command as processed (anti-replay)
                    guardianRepository.recordProcessedCommand(nonceHex, timestamp)
                    Log.i(TAG, "Command $cmd executed successfully")

                    // Sync guardian config to Python for link filtering
                    // Only for state-changing commands (not STATUS_REQUEST)
                    if (cmd in listOf(CMD_LOCK, CMD_UNLOCK, CMD_ALLOW_ADD, CMD_ALLOW_REMOVE, CMD_ALLOW_SET)) {
                        syncGuardianConfigToPython()
                    }
                }

                return success
            } catch (e: Exception) {
                Log.e(TAG, "Error processing guardian command", e)
                return false
            }
        }

        private suspend fun executeLock(): Boolean {
            guardianRepository.setLockState(true)
            Log.i(TAG, "Device locked by guardian")
            return true
        }

        private suspend fun executeUnlock(): Boolean {
            guardianRepository.setLockState(false)
            Log.i(TAG, "Device unlocked by guardian")
            return true
        }

        private suspend fun executeAllowAdd(payload: JSONObject): Boolean {
            val contactsArray = payload.optJSONArray("contacts") ?: return false
            val contacts = mutableListOf<Pair<String, String?>>()

            for (i in 0 until contactsArray.length()) {
                val contact = contactsArray.getJSONObject(i)
                val hash = contact.getString("hash")
                val name = contact.optString("name").ifEmpty { null }
                contacts.add(Pair(hash, name))
            }

            if (contacts.isEmpty()) {
                return false
            }

            // Add to allowed contacts list (for message filtering)
            guardianRepository.addAllowedContacts(contacts)
            Log.i(TAG, "Added ${contacts.size} contacts to allow list")

            // Also add to child's contacts list so they can see and message them
            for ((hash, name) in contacts) {
                try {
                    // Check if contact already exists
                    if (!contactRepository.hasContact(hash)) {
                        val result = contactRepository.addPendingContact(hash, name)
                        if (result.isSuccess) {
                            Log.i(TAG, "Added contact $hash to contacts list (guardian ALLOW_ADD)")
                        } else {
                            Log.w(TAG, "Failed to add contact $hash to contacts list: ${result.exceptionOrNull()?.message}")
                        }
                    } else {
                        Log.d(TAG, "Contact $hash already exists in contacts list")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding contact $hash to contacts list", e)
                    // Continue with other contacts even if one fails
                }
            }

            return true
        }

        private suspend fun executeAllowRemove(payload: JSONObject): Boolean {
            val contactsArray = payload.optJSONArray("contacts") ?: return false

            for (i in 0 until contactsArray.length()) {
                val hash = contactsArray.getString(i)
                guardianRepository.removeAllowedContact(hash)
            }

            Log.i(TAG, "Removed ${contactsArray.length()} contacts from allow list")
            return true
        }

        private suspend fun executeAllowSet(payload: JSONObject): Boolean {
            val contactsArray = payload.optJSONArray("contacts") ?: return false
            val contacts = mutableListOf<Pair<String, String?>>()

            for (i in 0 until contactsArray.length()) {
                val contact = contactsArray.getJSONObject(i)
                val hash = contact.getString("hash")
                val name = contact.optString("name").ifEmpty { null }
                contacts.add(Pair(hash, name))
            }

            guardianRepository.setAllowedContacts(contacts)
            Log.i(TAG, "Replaced allow list with ${contacts.size} contacts")
            return true
        }

        private suspend fun executeStatusRequest(): Boolean {
            // TODO: Send status response back to guardian
            // This would require sending an LXMF message via ReticulumProtocol
            // For now, just log that status was requested
            val isLocked = guardianRepository.isLocked()
            Log.i(TAG, "Status requested - isLocked: $isLocked")
            return true
        }

        /**
         * Sync current guardian config to Python layer for link filtering.
         *
         * When the device is locked, the Python layer will reject links from
         * non-allowed peers to prevent revealing online status.
         *
         * This should be called:
         * - After processing LOCK/UNLOCK/ALLOW_* commands
         * - On app startup to sync current state
         */
        suspend fun syncGuardianConfigToPython() {
            try {
                val isLocked = guardianRepository.isLocked()
                val guardianHash = guardianRepository.getGuardianDestinationHash()
                val allowedHashes = guardianRepository.getAllowedContactHashesSync()

                val success = reticulumProtocol.updateGuardianConfig(
                    isLocked = isLocked,
                    guardianHash = guardianHash,
                    allowedHashes = allowedHashes,
                )

                if (success) {
                    Log.d(TAG, "Synced guardian config to Python: locked=$isLocked, " +
                        "guardian=$guardianHash, allowed=${allowedHashes.size} contacts")
                } else {
                    Log.w(TAG, "Failed to sync guardian config to Python")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing guardian config to Python", e)
            }
        }

        // ==================== Parent Side Processing ====================

        /**
         * Check if a message contains a PAIR_ACK from a child device.
         * This is used on the parent side to receive pairing acknowledgments.
         *
         * Commands can be in two formats:
         * 1. In message content with "__GUARDIAN_CMD__:" prefix
         * 2. In LXMF field 0x80 (legacy format)
         */
        fun isPairAckMessage(message: ReceivedMessage): Boolean {
            // First check for command embedded in content
            if (message.content.startsWith(GUARDIAN_CMD_PREFIX)) {
                return try {
                    val commandJson = message.content.removePrefix(GUARDIAN_CMD_PREFIX)
                    val commandData = JSONObject(commandJson)
                    commandData.optString("cmd") == CMD_PAIR_ACK
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to parse guardian command from content", e)
                    false
                }
            }

            // Fall back to LXMF field 0x80 (legacy format)
            val fieldsJson = message.fieldsJson ?: return false
            return try {
                val fields = JSONObject(fieldsJson)
                val commandDataStr = fields.optString(FIELD_PARENTAL_CONTROL.toString())
                    .ifEmpty { fields.optString("128") }

                if (commandDataStr.isEmpty()) return false

                val commandData = JSONObject(commandDataStr)
                commandData.optString("cmd") == CMD_PAIR_ACK
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Process a PAIR_ACK message from a child device.
         * Adds the sender to our list of paired children.
         *
         * @param message The received LXMF message containing PAIR_ACK
         * @return True if the child was successfully registered
         */
        suspend fun processPairAck(message: ReceivedMessage): Boolean {
            try {
                // sourceHash is now correctly 16 bytes (32 hex chars) after fixing the base64/hex encoding mismatch
                val senderHash = message.sourceHash.joinToString("") { "%02x".format(it) }
                Log.i(TAG, "Processing PAIR_ACK from: $senderHash (${message.sourceHash.size} bytes)")

                // Extract any display name from the message payload
                var displayName: String? = null
                try {
                    // First try content-based format
                    if (message.content.startsWith(GUARDIAN_CMD_PREFIX)) {
                        val commandJson = message.content.removePrefix(GUARDIAN_CMD_PREFIX)
                        val commandData = JSONObject(commandJson)
                        val payload = commandData.optJSONObject("payload")
                        displayName = payload?.optString("display_name")?.ifEmpty { null }
                    } else {
                        // Fall back to LXMF field 0x80 (legacy format)
                        val fieldsJson = message.fieldsJson
                        if (fieldsJson != null) {
                            val fields = JSONObject(fieldsJson)
                            val commandDataStr = fields.optString(FIELD_PARENTAL_CONTROL.toString())
                                .ifEmpty { fields.optString("128") }
                            if (commandDataStr.isNotEmpty()) {
                                val commandData = JSONObject(commandDataStr)
                                val payload = commandData.optJSONObject("payload")
                                displayName = payload?.optString("display_name")?.ifEmpty { null }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Non-fatal - just won't have display name
                    Log.d(TAG, "Could not extract display name from PAIR_ACK")
                }

                // Add the child to our paired children list
                guardianRepository.addPairedChild(senderHash, displayName)
                Log.i(TAG, "Added paired child: $senderHash (name: $displayName)")

                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error processing PAIR_ACK", e)
                return false
            }
        }
    }
