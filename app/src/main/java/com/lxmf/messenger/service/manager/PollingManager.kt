package com.lxmf.messenger.service.manager

import android.util.Log
import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.util.SmartPoller
import com.lxmf.messenger.service.manager.PythonWrapperManager.Companion.getDictValue
import com.lxmf.messenger.service.state.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages polling for announces and messages from Python wrapper.
 *
 * Implements context-aware adaptive polling:
 * - Announces: 2-10s adaptive (shorter for better responsiveness)
 * - Messages: 2-30s adaptive when background, 1s when conversation active
 *
 * Also handles event-driven notifications for immediate delivery when available.
 */
class PollingManager(
    private val state: ServiceState,
    private val wrapperManager: PythonWrapperManager,
    private val broadcaster: CallbackBroadcaster,
    private val scope: CoroutineScope,
    private val attachmentStorage: AttachmentStorageManager? = null,
) {
    companion object {
        private const val TAG = "PollingManager"

        /**
         * Helper to convert ByteArray to Base64 string.
         */
        private fun ByteArray?.toBase64(): String? {
            return this?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }
    }

    // Smart pollers for adaptive polling
    private val announcesPoller =
        SmartPoller(
            minInterval = 2_000, // 2s when active
            maxInterval = 10_000, // 10s max (reduced from 30s for better responsiveness)
        )

    private val messagesPoller =
        SmartPoller(
            minInterval = 2_000, // 2s when active
            maxInterval = 30_000, // 30s max when idle
        )

    // Fast poller for when conversation is actively open
    private val conversationPoller =
        SmartPoller(
            minInterval = 1_000, // 1s fixed interval when conversation active
            maxInterval = 1_000, // No backoff - always 1s
        )

    // Mutex for thread-safe job start/stop operations
    private val jobLock = Any()

    /**
     * Start announce polling.
     * Thread-safe: synchronized on jobLock.
     */
    fun startAnnouncesPolling() {
        synchronized(jobLock) {
            state.pollingJob?.cancel()
            state.pollingJob =
                scope.launch {
                    Log.d(TAG, "Started announces polling with callback-based announce queue")
                    announcesPoller.reset()

                    while (isActive) {
                        try {
                            if (state.wrapper == null) {
                                announcesPoller.markIdle()
                                delay(announcesPoller.getNextInterval())
                                continue
                            }

                            val announces =
                                wrapperManager.withWrapper { wrapper ->
                                    wrapper.callAttr("get_pending_announces")?.asList()
                                }

                            if (announces != null && announces.isNotEmpty()) {
                                Log.i(TAG, "Polled ${announces.size} new announces from callback queue!")
                                announcesPoller.markActive()

                                for (announceObj in announces) {
                                    handleAnnounceEvent(announceObj as PyObject)
                                }
                            } else {
                                announcesPoller.markIdle()
                            }
                        } catch (e: CancellationException) {
                            Log.d(TAG, "Announces polling cancelled")
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Error polling announces", e)
                        }

                        val nextInterval = announcesPoller.getNextInterval()
                        Log.d(TAG, "Next announce check in ${nextInterval}ms")
                        delay(nextInterval)
                    }
                }
        }
    }

    /**
     * Start message polling.
     * Thread-safe: synchronized on jobLock.
     */
    fun startMessagesPolling() {
        synchronized(jobLock) {
            state.messagePollingJob?.cancel()
            state.messagePollingJob =
                scope.launch {
                    Log.d(TAG, "Started messages polling with context-aware smart polling")
                    messagesPoller.reset()
                    conversationPoller.reset()
                    var pollCount = 0
                    var lastError: String? = null

                    while (isActive) {
                        try {
                            pollCount++
                            if (pollCount % 10 == 0) {
                                Log.d(TAG, "Message polling iteration $pollCount, wrapper=${state.wrapper != null}, conversationActive=${state.isConversationActive.get()}")
                            }

                            if (state.wrapper == null) {
                                if (lastError != "wrapper_null") {
                                    Log.e(TAG, "Wrapper is null, cannot poll messages")
                                    lastError = "wrapper_null"
                                }
                                val currentPoller = if (state.isConversationActive.get()) conversationPoller else messagesPoller
                                delay(currentPoller.getNextInterval())
                                continue
                            }

                            val messages =
                                wrapperManager.withWrapper { wrapper ->
                                    try {
                                        wrapper.callAttr("poll_received_messages")?.asList()
                                    } catch (e: com.chaquo.python.PyException) {
                                        if (lastError != e.message) {
                                            Log.e(TAG, "PyException calling poll_received_messages: ${e.message}", e)
                                            lastError = e.message
                                        }
                                        null
                                    }
                                }

                            if (messages != null && messages.isNotEmpty()) {
                                Log.d(TAG, "Polled ${messages.size} new messages")
                                messagesPoller.markActive()
                                conversationPoller.markActive()

                                for (messageObj in messages) {
                                    handleMessageEvent(messageObj as PyObject)
                                }
                            } else {
                                messagesPoller.markIdle()
                            }
                        } catch (e: CancellationException) {
                            Log.d(TAG, "Message polling cancelled")
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in message polling loop", e)
                            messagesPoller.markIdle()
                        }

                        // Use conversation poller (1s) when active, otherwise standard adaptive poller
                        val currentPoller = if (state.isConversationActive.get()) conversationPoller else messagesPoller
                        val nextInterval = currentPoller.getNextInterval()
                        Log.d(TAG, "Next message check in ${nextInterval}ms (conversation=${state.isConversationActive.get()})")
                        delay(nextInterval)
                    }
                }
        }
    }

    /**
     * Stop all polling.
     * Thread-safe: synchronized on jobLock.
     */
    fun stopAll() {
        synchronized(jobLock) {
            state.pollingJob?.cancel()
            state.pollingJob = null
            state.messagePollingJob?.cancel()
            state.messagePollingJob = null
            Log.d(TAG, "All polling stopped")
        }
    }

    /**
     * Set conversation active state for context-aware polling.
     *
     * @param active true if conversation screen is open
     */
    fun setConversationActive(active: Boolean) {
        Log.d(TAG, "Conversation active state changed: $active")
        state.isConversationActive.set(active)

        if (active) {
            conversationPoller.reset()
            conversationPoller.markActive()
            Log.d(TAG, "Switched to fast 1s polling for active conversation")
        } else {
            Log.d(TAG, "Returned to adaptive 2-30s polling")
        }
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
     * Handle message received event from Python callback.
     * Triggers immediate message fetch.
     * Uses scope's default context for consistency with polling loops.
     */
    fun handleMessageReceivedEvent(messageJson: String) {
        try {
            Log.d(TAG, "Message received event: $messageJson")

            // Use scope's default context (same as polling loops) for consistent Python wrapper access
            scope.launch {
                try {
                    val startTime = System.currentTimeMillis()

                    val messages =
                        wrapperManager.withWrapper { wrapper ->
                            wrapper.callAttr("poll_received_messages")?.asList()
                        }

                    if (messages != null && messages.isNotEmpty()) {
                        val latency = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Event-driven fetch retrieved ${messages.size} message(s) in ${latency}ms")

                        messagesPoller.markActive()
                        conversationPoller.markActive()

                        for (messageObj in messages) {
                            handleMessageEvent(messageObj as PyObject)
                        }
                    } else {
                        Log.d(TAG, "Event notification received but no messages in queue (may have been polled already)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing event-driven message notification", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message received event", e)
        }
    }

    /**
     * Handle incoming announce event.
     */
    fun handleAnnounceEvent(event: PyObject) {
        try {
            Log.d(TAG, "handleAnnounceEvent() called - processing announce from Python")

            val destinationHash = event.getDictValue("destination_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val identityHash = event.getDictValue("identity_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val publicKey = event.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
            val appData = event.getDictValue("app_data")?.toJava(ByteArray::class.java) as? ByteArray
            val hops = event.getDictValue("hops")?.toInt() ?: 0
            val timestamp = event.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()
            val aspect = event.getDictValue("aspect")?.toString()
            val receivingInterface = event.getDictValue("interface")?.toString()

            Log.i(TAG, "  Hash: ${destinationHash?.take(8)?.joinToString("") { "%02x".format(it) }}")
            Log.i(TAG, "  Hops: $hops, Interface: $receivingInterface, Aspect: $aspect")

            val announceJson =
                JSONObject().apply {
                    put("destination_hash", destinationHash.toBase64())
                    put("identity_hash", identityHash.toBase64())
                    put("public_key", publicKey.toBase64())
                    put("app_data", appData.toBase64())
                    put("hops", hops)
                    put("timestamp", timestamp)
                    if (aspect != null) {
                        put("aspect", aspect)
                    }
                    if (receivingInterface != null && receivingInterface != "None") {
                        put("interface", receivingInterface)
                    }
                }

            broadcaster.broadcastAnnounce(announceJson.toString())
            Log.d(TAG, "Announce broadcast complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling announce event", e)
        }
    }

    /**
     * Handle incoming message event.
     */
    private fun handleMessageEvent(event: PyObject) {
        try {
            val messageHash = event.getDictValue("message_hash")?.toString() ?: ""
            val content = event.getDictValue("content")?.toString() ?: ""
            val sourceHash = event.getDictValue("source_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val destHash = event.getDictValue("destination_hash")?.toJava(ByteArray::class.java) as? ByteArray
            val timestamp = event.getDictValue("timestamp")?.toLong() ?: System.currentTimeMillis()

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

            // Check if fields contain large attachments that need to be saved to disk
            // to avoid AIDL TransactionTooLargeException (~1MB limit)
            fieldsJson = extractLargeAttachments(messageHash, fieldsJson)

            val messageJson =
                JSONObject().apply {
                    put("message_hash", messageHash)
                    put("content", content)
                    put("source_hash", sourceHash.toBase64())
                    put("destination_hash", destHash.toBase64())
                    put("timestamp", timestamp)
                    fieldsJson?.let { put("fields", it) }
                }

            broadcaster.broadcastMessage(messageJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message event", e)
        }
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
            val value = fields.optString(key, "")

            if (value.length > AttachmentStorageManager.SIZE_THRESHOLD) {
                // Save large field to disk
                val filePath = attachmentStorage.saveAttachment(messageHash, key, value)
                if (filePath != null) {
                    // Replace with file reference
                    val refObj =
                        JSONObject().apply {
                            put(AttachmentStorageManager.FILE_REF_KEY, filePath)
                        }
                    modifiedFields.put(key, refObj)
                    Log.i(TAG, "Extracted field '$key' (${value.length} chars) to disk: $filePath")
                } else {
                    // Save failed, keep original (may still fail at AIDL, but at least try)
                    modifiedFields.put(key, value)
                    Log.w(TAG, "Failed to extract field '$key', keeping inline")
                }
            } else {
                // Keep small fields inline
                modifiedFields.put(key, value)
            }
        }

        val newSize = modifiedFields.toString().length
        Log.d(TAG, "Fields size reduced from $totalSize to $newSize chars")
        return modifiedFields
    }
}
