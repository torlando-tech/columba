package com.lxmf.messenger.service.manager

import android.util.Log
import com.chaquo.python.PyObject
import com.lxmf.messenger.data.model.InterfaceType
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.reticulum.protocol.NodeTypeDetector
import com.lxmf.messenger.reticulum.util.AppDataParser
import com.lxmf.messenger.service.manager.PythonWrapperManager.Companion.getDictValue
import com.lxmf.messenger.service.persistence.ServicePersistenceManager
import com.lxmf.messenger.service.state.ServiceState
import com.lxmf.messenger.service.util.PeerNameResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles event-driven announce and message delivery with service-side persistence.
 *
 * Both announces and messages are handled via Python callbacks and persisted
 * directly to the database in the service process. This ensures data survives
 * even when the app process is killed by Android.
 *
 * Message delivery is 100% event-driven via Python callbacks.
 * A one-time startup drain catches any messages that arrived before callback registration.
 */
@Suppress("TooManyFunctions") // Event handlers are grouped logically in this class
class EventHandler(
    private val state: ServiceState,
    private val wrapperManager: PythonWrapperManager,
    private val broadcaster: CallbackBroadcaster,
    private val scope: CoroutineScope,
    private val attachmentStorage: AttachmentStorageManager? = null,
    private val persistenceManager: ServicePersistenceManager? = null,
) {
    companion object {
        private const val TAG = "EventHandler"

        /**
         * Helper to convert ByteArray to Base64 string.
         */
        private fun ByteArray?.toBase64(): String? = this?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

        /**
         * Safely parse PyObject to Int, handling Python None and parse errors.
         * Returns null if the value is None, not a number, or cannot be parsed.
         */
        private fun PyObject?.toIntOrNull(): Int? =
            this?.let {
                val str = it.toString()
                if (str == "None") null else str.toIntOrNull()
            }

        /**
         * Safely parse PyObject to Float, handling Python None and parse errors.
         * Returns null if the value is None, not a number, or cannot be parsed.
         */
        private fun PyObject?.toFloatOrNull(): Float? =
            this?.let {
                val str = it.toString()
                if (str == "None") null else str.toFloatOrNull()
            }
    }

    /**
     * Start event handling.
     *
     * Announces are now 100% event-driven via Python callbacks and handleAnnounceEvent().
     * This method performs a one-time drain of any pending announces that arrived
     * before the callback was registered.
     */
    fun startEventHandling() {
        Log.d(TAG, "Announce handling started - using event-driven callbacks (polling disabled)")

        // One-time drain of pending announces from startup
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Draining pending announces from startup queue...")

                val announces =
                    wrapperManager.withWrapper { wrapper ->
                        wrapper.callAttr("get_pending_announces")?.asList()
                    }

                if (announces != null && announces.isNotEmpty()) {
                    Log.i(TAG, "Startup drain found ${announces.size} pending announce(s)")
                    for (announceObj in announces) {
                        handleAnnounceEvent(announceObj as PyObject)
                    }
                } else {
                    Log.d(TAG, "No pending announces in startup queue")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Announce drain cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error draining pending announces", e)
            }
        }
    }

    /**
     * Drain any pending messages from the queue.
     *
     * Called once at startup to catch messages that arrived before the
     * event-driven callback was registered. After this, all message delivery
     * is handled via handleMessageReceivedEvent().
     */
    fun drainPendingMessages() {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Draining pending messages from startup queue...")

                val messages =
                    wrapperManager.withWrapper { wrapper ->
                        wrapper.callAttr("poll_received_messages")?.asList()
                    }

                if (messages != null && messages.isNotEmpty()) {
                    Log.i(TAG, "Startup drain found ${messages.size} pending message(s)")
                    for (messageObj in messages) {
                        handleMessageEvent(messageObj as PyObject)
                    }
                } else {
                    Log.d(TAG, "No pending messages in startup queue")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error draining pending messages", e)
            }
        }
    }

    /**
     * Stop all polling operations.
     * Note: With event-driven architecture, there's no continuous polling to stop.
     */
    fun stopAll() {
        Log.d(TAG, "PollingManager stopped (event-driven mode)")
    }

    /**
     * Set conversation active state.
     *
     * Note: Message delivery is event-driven via Python callbacks.
     * This method now just tracks state for potential future use.
     *
     * @param active true if conversation screen is open
     */
    fun setConversationActive(active: Boolean) {
        Log.d(TAG, "Conversation active state changed: $active")
        state.isConversationActive.set(active)
        // Message delivery is event-driven; no special polling needed for active conversations
    }

    /**
     * Handle delivery status event from Python callback.
     */
    fun handleDeliveryStatusEvent(statusJson: String) {
        try {
            Log.d(TAG, "Delivery status update received: $statusJson")
            broadcaster.broadcastDeliveryStatus(statusJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling delivery status event", e)
        }
    }

    /**
     * Handle reaction received event from Python callback.
     * Broadcasts reaction to update target message in the database.
     *
     * @param reactionJson JSON with reaction data:
     *        {"reaction_to": "msg_id", "emoji": "👍", "sender": "sender_hash", "source_hash": "...", "timestamp": ...}
     */
    fun handleReactionReceivedEvent(reactionJson: String) {
        try {
            Log.d(TAG, "😀 Reaction received event: $reactionJson")
            broadcaster.broadcastReactionReceived(reactionJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reaction received event", e)
        }
    }

    /**
     * Handle message received event from Python callback.
     * Now truly event-driven: processes message data directly from callback JSON.
     */
    fun handleMessageReceivedEvent(messageJson: String) {
        try {
            Log.d(TAG, "Message received event")

            scope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Message coroutine started on ${Thread.currentThread().name}")
                    val json = JSONObject(messageJson)

                    // Check if this is a full message (truly event-driven) or just a notification
                    if (json.optBoolean("full_message", false)) {
                        // Process directly from callback data - no polling needed
                        processMessageFromJson(json)
                        Log.d(TAG, "Processed message directly from callback (event-driven)")
                    } else {
                        // Fallback to polling for backwards compatibility
                        Log.d(TAG, "Callback missing full_message flag, falling back to polling")
                        val messages =
                            wrapperManager.withWrapper { wrapper ->
                                wrapper.callAttr("poll_received_messages")?.asList()
                            }

                        if (messages != null && messages.isNotEmpty()) {
                            for (messageObj in messages) {
                                handleMessageEvent(messageObj as PyObject)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message event", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message received event", e)
        }
    }

    /**
     * Process a message directly from JSON callback data (truly event-driven).
     */
    private suspend fun processMessageFromJson(json: JSONObject) {
        try {
            val messageHash = json.optString("message_hash", "")
            val content = json.optString("content", "")
            val sourceHashHex = json.optString("source_hash", "")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val receivedHopCount = json.optInt("hops", -1).takeIf { it >= 0 }
            val receivedInterface = json.optString("receiving_interface", "").takeIf { it.isNotEmpty() }
            val receivedRssi = if (json.has("rssi") && !json.isNull("rssi")) json.optInt("rssi") else null
            val receivedSnr = if (json.has("snr") && !json.isNull("snr")) json.optDouble("snr").toFloat() else null

            // Parse public key from hex string
            val publicKeyHex = json.optString("public_key", "")
            val publicKey =
                if (publicKeyHex.isNotEmpty()) {
                    publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } else {
                    null
                }

            // Parse fields JSON if present
            val fieldsStr = json.optString("fields", "")
            var fieldsJson =
                if (fieldsStr.isNotEmpty()) {
                    try {
                        JSONObject(fieldsStr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse fields: ${e.message}")
                        null
                    }
                } else {
                    null
                }

            // Check if message has file attachments
            val hasFileAttachments = fieldsJson?.optJSONArray("5")?.let { it.length() > 0 } ?: false

            // Extract large attachments to disk if needed
            fieldsJson = extractLargeAttachments(messageHash, fieldsJson)

            // Extract reply_to_message_id from fields
            val replyToMessageId = fieldsJson?.optString("9")?.takeIf { it.isNotBlank() }

            // Persist to database and only broadcast if successful
            // This ensures blocked messages don't trigger notifications in the app process
            if (persistenceManager != null && messageHash.isNotBlank() && sourceHashHex.isNotBlank()) {
                val persisted =
                    persistenceManager.persistMessage(
                        messageHash = messageHash,
                        content = content,
                        sourceHash = sourceHashHex,
                        timestamp = timestamp,
                        fieldsJson = fieldsJson?.toString(),
                        publicKey = publicKey,
                        replyToMessageId = replyToMessageId,
                        deliveryMethod = null,
                        hasFileAttachments = hasFileAttachments,
                        receivedHopCount = receivedHopCount,
                        receivedInterface = receivedInterface,
                        receivedRssi = receivedRssi,
                        receivedSnr = receivedSnr,
                    )
                if (persisted) {
                    Log.d(TAG, "Message persisted from callback: $messageHash from $sourceHashHex")
                    // Broadcast to app process for UI updates (only if persisted)
                    broadcaster.broadcastMessage(messageJson = json.toString())
                } else {
                    Log.d(TAG, "Message blocked or failed to persist: $messageHash - not broadcasting")
                }
            } else {
                // No persistence manager - broadcast anyway for UI updates
                broadcaster.broadcastMessage(messageJson = json.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message from JSON", e)
        }
    }

    /**
     * Handle incoming announce event.
     *
     * Persists to database first (survives app process death), then broadcasts for UI updates.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun handleAnnounceEvent(event: PyObject) {
        try {
            Log.d(TAG, "handleAnnounceEvent() called - processing announce from Python")

            val destinationHash = event.getDictValue("destination_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val identityHash = event.getDictValue("identity_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val publicKey = event.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
            val appData = event.getDictValue("app_data")?.toJava(ByteArray::class.java) as? ByteArray
            val hops = event.getDictValue("hops")?.toInt() ?: 0
            val timestamp = event.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()
            val aspect = event.getDictValue("aspect")?.toString()?.takeIf { it != "None" }
            val receivingInterface = event.getDictValue("interface")?.toString()?.takeIf { it != "None" }
            val displayName = event.getDictValue("display_name")?.toString()?.takeIf { it != "None" }
            val stampCost = event.getDictValue("stamp_cost").toIntOrNull()
            val stampCostFlexibility = event.getDictValue("stamp_cost_flexibility").toIntOrNull()
            val peeringCost = event.getDictValue("peering_cost").toIntOrNull()

            val destinationHashHex = destinationHash?.joinToString("") { "%02x".format(it) } ?: return
            Log.i(TAG, "  Hash: ${destinationHashHex.take(16)}")
            Log.i(TAG, "  Hops: $hops, Interface: $receivingInterface, Aspect: $aspect")

            // Detect node type from aspect and app_data
            val nodeType = NodeTypeDetector.detectNodeType(appData, aspect)

            // Extract propagation node metadata once (reused for name and transfer limit)
            val propagationMetadata =
                if (nodeType == NodeType.PROPAGATION_NODE) {
                    AppDataParser.extractPropagationNodeMetadata(appData)
                } else {
                    null
                }

            // Determine display name (prefer parsed name from Python LXMF helpers)
            // For propagation nodes: DO NOT interpret msgpack binary as UTF-8 (causes garbled names)
            // For regular peers: app_data IS a UTF-8 display name string, so fallback is safe
            val peerName =
                when {
                    !displayName.isNullOrBlank() -> displayName
                    nodeType == NodeType.PROPAGATION_NODE -> {
                        propagationMetadata?.name ?: PeerNameResolver.formatHashAsFallback(destinationHashHex)
                    }
                    else -> {
                        appData?.let {
                            String(it, Charsets.UTF_8).takeIf { s -> s.isNotBlank() && s.length < 128 }
                        } ?: PeerNameResolver.formatHashAsFallback(destinationHashHex)
                    }
                }

            // Transfer size limit for propagation nodes (from cached metadata)
            val propagationTransferLimitKb = propagationMetadata?.transferLimitKb

            // Persist to database first (survives app process death)
            if (persistenceManager != null && publicKey != null) {
                persistenceManager.persistAnnounce(
                    destinationHash = destinationHashHex,
                    peerName = peerName,
                    publicKey = publicKey,
                    appData = appData,
                    hops = hops,
                    timestamp = timestamp,
                    nodeType = nodeType.name,
                    receivingInterface = receivingInterface,
                    receivingInterfaceType = InterfaceType.fromInterfaceName(receivingInterface).name,
                    aspect = aspect,
                    stampCost = stampCost,
                    stampCostFlexibility = stampCostFlexibility,
                    peeringCost = peeringCost,
                    propagationTransferLimitKb = propagationTransferLimitKb,
                )

                // Also persist peer identity (public key)
                persistenceManager.persistPeerIdentity(destinationHashHex, publicKey)
                Log.d(TAG, "Announce persisted to database: ${destinationHashHex.take(16)}")
            }

            // Broadcast to app process for UI updates (may be dead, that's OK)
            val announceJson = buildAnnounceJson(event)
            broadcaster.broadcastAnnounce(announceJson.toString())
            Log.d(TAG, "Announce broadcast complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling announce event", e)
        }
    }

    private fun buildAnnounceJson(event: PyObject): JSONObject {
        val destinationHash = event.getDictValue("destination_hash")?.toJava(ByteArray::class.java) as? ByteArray
        val identityHash = event.getDictValue("identity_hash")?.toJava(ByteArray::class.java) as? ByteArray
        val publicKey = event.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
        val appData = event.getDictValue("app_data")?.toJava(ByteArray::class.java) as? ByteArray
        val hops = event.getDictValue("hops")?.toInt() ?: 0
        val timestamp = event.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()
        val aspect = event.getDictValue("aspect")?.toString()
        val receivingInterface = event.getDictValue("interface")?.toString()
        val displayName = event.getDictValue("display_name")?.toString()?.takeIf { it != "None" }

        Log.i(TAG, "  Hash: ${destinationHash?.take(8)?.joinToString("") { "%02x".format(it) }}")
        Log.i(TAG, "  Hops: $hops, Interface: $receivingInterface, Aspect: $aspect")

        return JSONObject().apply {
            put("destination_hash", destinationHash.toBase64())
            put("identity_hash", identityHash.toBase64())
            put("public_key", publicKey.toBase64())
            put("app_data", appData.toBase64())
            put("hops", hops)
            put("timestamp", timestamp)
            addOptionalFields(event, aspect, receivingInterface, displayName)
            if (aspect == "rmsp.maps") {
                addRmspFields(event)
            }
        }
    }

    private fun JSONObject.addOptionalFields(
        event: PyObject,
        aspect: String?,
        receivingInterface: String?,
        displayName: String?,
    ) {
        val stampCost = event.getDictValue("stamp_cost").toIntOrNull()
        val stampCostFlexibility = event.getDictValue("stamp_cost_flexibility").toIntOrNull()
        val peeringCost = event.getDictValue("peering_cost").toIntOrNull()

        if (aspect != null) put("aspect", aspect)
        if (receivingInterface != null && receivingInterface != "None") put("interface", receivingInterface)
        if (displayName != null) put("display_name", displayName)
        if (stampCost != null) put("stamp_cost", stampCost)
        if (stampCostFlexibility != null) put("stamp_cost_flexibility", stampCostFlexibility)
        if (peeringCost != null) put("peering_cost", peeringCost)
    }

    private fun JSONObject.addRmspFields(event: PyObject) {
        val rmspServerName = event.getDictValue("rmsp_server_name")?.toString()
        val rmspVersion = event.getDictValue("rmsp_version")?.toString()
        val rmspCoverage = event.getDictValue("rmsp_coverage")?.asList()?.map { it.toString() }
        val rmspZoomRange = event.getDictValue("rmsp_zoom_range")?.asList()?.map { it.toInt() }
        val rmspFormats = event.getDictValue("rmsp_formats")?.asList()?.map { it.toString() }
        val rmspLayers = event.getDictValue("rmsp_layers")?.asList()?.map { it.toString() }
        val rmspUpdated = event.getDictValue("rmsp_updated")?.toLong()
        val rmspSize = event.getDictValue("rmsp_size")?.toLong()

        if (rmspServerName != null) put("rmsp_server_name", rmspServerName)
        if (rmspVersion != null) put("rmsp_version", rmspVersion)
        if (rmspCoverage != null) put("rmsp_coverage", org.json.JSONArray(rmspCoverage))
        if (rmspZoomRange != null) put("rmsp_zoom_range", org.json.JSONArray(rmspZoomRange))
        if (rmspFormats != null) put("rmsp_formats", org.json.JSONArray(rmspFormats))
        if (rmspLayers != null) put("rmsp_layers", org.json.JSONArray(rmspLayers))
        if (rmspUpdated != null) put("rmsp_updated", rmspUpdated)
        if (rmspSize != null) put("rmsp_size", rmspSize)

        Log.i(TAG, "  RMSP Server: $rmspServerName (coverage: ${rmspCoverage?.size ?: 0} areas)")
    }

    /**
     * Handle incoming message event.
     *
     * Persists to database first (survives app process death), then broadcasts for UI updates.
     * This is a suspend function to ensure message persistence completes before sync
     * completion is reported to the UI.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // Complex due to LXMF field extraction
    private suspend fun handleMessageEvent(event: PyObject) {
        try {
            val messageHash = event.getDictValue("message_hash")?.toString().orEmpty()
            val content = event.getDictValue("content")?.toString().orEmpty()
            val sourceHash = event.getDictValue("source_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val destHash = event.getDictValue("destination_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val timestamp = event.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()

            // Extract sender's public key if available (from RNS identity cache)
            val publicKey = event.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray

            // Extract LXMF fields (attachments, images, etc.) if present
            val fieldsObj = event.getDictValue("fields")
            var fieldsJson =
                fieldsObj?.let {
                    try {
                        JSONObject(it.toString()).also { json ->
                            Log.d(TAG, "Message has ${json.length()} field(s)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse fields: ${e.message}")
                        null
                    }
                }

            // Check if message has file attachments BEFORE extraction (for supersede matching)
            // Field 5 is the LXMF file attachments field
            val hasFileAttachments = fieldsJson?.optJSONArray("5")?.let { it.length() > 0 } ?: false

            // Check if fields contain large attachments that need to be saved to disk
            // to avoid AIDL TransactionTooLargeException (~1MB limit)
            fieldsJson = extractLargeAttachments(messageHash, fieldsJson)

            val sourceHashHex = sourceHash?.joinToString("") { "%02x".format(it) } ?: ""

            // Extract reply_to_message_id from fields (LXMF field 9)
            val replyToMessageId = fieldsJson?.optString("9")?.takeIf { it.isNotBlank() }

            // Extract delivery method if available
            val deliveryMethod = event.getDictValue("delivery_method")?.toString()?.takeIf { it != "None" }

            // Extract received message info (hop count and receiving interface)
            val receivedHopCount = event.getDictValue("hops").toIntOrNull()
            val receivedInterface = event.getDictValue("receiving_interface")?.toString()?.takeIf { it != "None" }
            val receivedRssi = event.getDictValue("rssi").toIntOrNull()
            val receivedSnr = event.getDictValue("snr").toFloatOrNull()

            // Build broadcast JSON (used whether or not we have persistence manager)
            val messageJson =
                buildMessageBroadcastJson(
                    messageHash,
                    content,
                    sourceHash,
                    destHash,
                    timestamp,
                    fieldsJson,
                    publicKey,
                )

            // Persist to database first (survives app process death)
            // Only broadcast if message was actually persisted (not blocked)
            if (persistenceManager != null && messageHash.isNotBlank() && sourceHashHex.isNotBlank()) {
                val persisted =
                    persistenceManager.persistMessage(
                        messageHash = messageHash,
                        content = content,
                        sourceHash = sourceHashHex,
                        timestamp = timestamp,
                        fieldsJson = fieldsJson?.toString(),
                        publicKey = publicKey,
                        replyToMessageId = replyToMessageId,
                        deliveryMethod = deliveryMethod,
                        hasFileAttachments = hasFileAttachments,
                        receivedHopCount = receivedHopCount,
                        receivedInterface = receivedInterface,
                        receivedRssi = receivedRssi,
                        receivedSnr = receivedSnr,
                    )

                if (persisted) {
                    Log.d(TAG, "Message persisted to database: $messageHash from $sourceHashHex")
                    broadcaster.broadcastMessage(messageJson.toString())
                } else {
                    Log.d(TAG, "Message blocked or failed to persist: $messageHash - not broadcasting")
                }
            } else {
                // No persistence manager - broadcast anyway for UI updates
                broadcaster.broadcastMessage(messageJson.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message event", e)
        }
    }

    /**
     * Build JSON object for broadcasting a message to the app process.
     */
    private fun buildMessageBroadcastJson(
        messageHash: String,
        content: String,
        sourceHash: ByteArray?,
        destHash: ByteArray?,
        timestamp: Long,
        fieldsJson: JSONObject?,
        publicKey: ByteArray?,
    ): JSONObject =
        JSONObject().apply {
            put("message_hash", messageHash)
            put("content", content)
            put("source_hash", sourceHash.toBase64())
            put("destination_hash", destHash.toBase64())
            put("timestamp", timestamp)
            fieldsJson?.let { put("fields", it) }
            publicKey?.let { put("public_key", it.toBase64()) }
        }

    /**
     * Extract large attachments from fields and save to disk.
     *
     * If any field value exceeds the size threshold, it's saved to disk and replaced
     * with a file reference. This prevents TransactionTooLargeException when
     * broadcasting messages through AIDL.
     *
     * @param messageHash Message identifier for storage path
     * @param fields Original fields JSON
     * @return Modified fields JSON with file references for large attachments
     */
    private fun extractLargeAttachments(
        messageHash: String,
        fields: JSONObject?,
    ): JSONObject? {
        if (fields == null || attachmentStorage == null) return fields

        val totalSize = fields.toString().length
        if (totalSize < AttachmentStorageManager.SIZE_THRESHOLD) {
            return fields // No extraction needed
        }

        Log.d(TAG, "Fields size ($totalSize chars) exceeds threshold, extracting large attachments")

        val modifiedFields = JSONObject()
        val keys = fields.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            extractField(messageHash, fields, key, modifiedFields)
        }

        val newSize = modifiedFields.toString().length
        Log.d(TAG, "Fields size reduced from $totalSize to $newSize chars")
        return modifiedFields
    }

    private fun extractField(
        messageHash: String,
        fields: JSONObject,
        key: String,
        modifiedFields: JSONObject,
    ) {
        // Field 5: file attachments array
        if (key == "5") {
            fields.optJSONArray("5")?.let { field5 ->
                modifiedFields.put("5", extractFileAttachmentsData(messageHash, field5))
                return
            }
        }

        // Fields 6 and 7: image/audio in ["format", "hex_data"] format
        if (key == "6" || key == "7") {
            fields.optJSONArray(key)?.takeIf { it.length() >= 2 }?.let { fieldArray ->
                extractMediaField(messageHash, key, fieldArray, modifiedFields)
                return
            }
        }

        // Generic string field
        extractStringField(messageHash, fields, key, modifiedFields)
    }

    private fun extractMediaField(
        messageHash: String,
        key: String,
        fieldArray: JSONArray,
        modifiedFields: JSONObject,
    ) {
        val hexData = fieldArray.optString(1, "")
        if (hexData.length <= AttachmentStorageManager.SIZE_THRESHOLD) {
            modifiedFields.put(key, hexData)
            return
        }

        val filePath = attachmentStorage?.saveAttachment(messageHash, key, hexData)
        if (filePath != null) {
            val refObj = JSONObject().apply { put(AttachmentStorageManager.FILE_REF_KEY, filePath) }
            modifiedFields.put(key, refObj)
            Log.i(TAG, "Extracted field '$key' (${hexData.length} chars) to disk: $filePath")
        } else {
            modifiedFields.put(key, fieldArray)
            Log.w(TAG, "Failed to extract field '$key', keeping inline")
        }
    }

    private fun extractStringField(
        messageHash: String,
        fields: JSONObject,
        key: String,
        modifiedFields: JSONObject,
    ) {
        val value = fields.optString(key, "")
        if (value.length <= AttachmentStorageManager.SIZE_THRESHOLD) {
            modifiedFields.put(key, value)
            return
        }

        val filePath = attachmentStorage?.saveAttachment(messageHash, key, value)
        if (filePath != null) {
            val refObj = JSONObject().apply { put(AttachmentStorageManager.FILE_REF_KEY, filePath) }
            modifiedFields.put(key, refObj)
            Log.i(TAG, "Extracted field '$key' (${value.length} chars) to disk: $filePath")
        } else {
            modifiedFields.put(key, value)
            Log.w(TAG, "Failed to extract field '$key', keeping inline")
        }
    }

    /**
     * Extract file attachment data to disk, keeping metadata inline.
     *
     * Input format: [{"filename": "doc.pdf", "size": 12345, "data": "hex..."}, ...]
     * Output format: [{"filename": "doc.pdf", "size": 12345, "_data_ref": "/path/to/file"}, ...]
     *
     * @param messageHash Message identifier for storage path
     * @param attachments Original file attachments array
     * @return Modified array with data extracted to disk
     */
    private fun extractFileAttachmentsData(
        messageHash: String,
        attachments: JSONArray,
    ): JSONArray {
        val result = JSONArray()

        for (i in 0 until attachments.length()) {
            try {
                val attachment = attachments.getJSONObject(i)
                val filename = attachment.optString("filename", "unknown")
                val size = attachment.optInt("size", 0)
                val data = attachment.optString("data", "")

                val modifiedAttachment =
                    JSONObject().apply {
                        put("filename", filename)
                        put("size", size)
                    }

                // Extract data to disk if present
                if (data.isNotEmpty()) {
                    val filePath =
                        attachmentStorage?.saveAttachment(
                            messageHash,
                            // Unique key per file: "5_0", "5_1", etc.
                            "5_$i",
                            data,
                        )
                    if (filePath != null) {
                        modifiedAttachment.put("_data_ref", filePath)
                        Log.d(TAG, "Extracted file '$filename' data to: $filePath")
                    } else {
                        // Keep data inline if save failed
                        modifiedAttachment.put("data", data)
                        Log.w(TAG, "Failed to extract file '$filename', keeping inline")
                    }
                }

                result.put(modifiedAttachment)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process file attachment at index $i", e)
                // Keep original attachment if processing fails
                result.put(attachments.opt(i))
            }
        }

        Log.i(TAG, "Extracted ${attachments.length()} file attachment(s) to disk")
        return result
    }
}
