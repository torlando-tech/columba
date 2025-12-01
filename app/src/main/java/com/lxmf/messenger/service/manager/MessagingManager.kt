package com.lxmf.messenger.service.manager

import android.util.Log
import com.chaquo.python.Python
import com.lxmf.messenger.service.manager.PythonWrapperManager.Companion.getDictValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages LXMF messaging operations for Reticulum.
 *
 * Handles:
 * - Sending LXMF messages (with optional image attachments)
 * - Sending raw packets
 * - Creating destinations
 * - Announcing destinations
 * - Restoring peer identities
 */
class MessagingManager(private val wrapperManager: PythonWrapperManager) {
    companion object {
        private const val TAG = "MessagingManager"

        /**
         * Helper to convert ByteArray to Base64 string for JSON serialization.
         */
        private fun ByteArray?.toBase64(): String? {
            return this?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }
    }

    /**
     * Send an LXMF message to a destination.
     *
     * @param destHash Destination hash bytes
     * @param content Message content string
     * @param sourceIdentityPrivateKey Source identity private key bytes
     * @param imageData Optional image data bytes
     * @param imageFormat Optional image format string (e.g., "jpg", "png")
     * @return JSON string with result
     */
    fun sendLxmfMessage(
        destHash: ByteArray,
        content: String,
        sourceIdentityPrivateKey: ByteArray,
        imageData: ByteArray?,
        imageFormat: String?,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                Log.d(
                    TAG,
                    "Sending LXMF message" +
                        if (imageData != null) " with image attachment (${imageData.size} bytes, format=$imageFormat)" else "",
                )

                val result =
                    wrapper.callAttr(
                        "send_lxmf_message",
                        destHash,
                        content,
                        sourceIdentityPrivateKey,
                        imageData,
                        imageFormat,
                    )

                val success = result.getDictValue("success")?.toBoolean() ?: false
                if (success) {
                    val msgHash =
                        result.getDictValue("message_hash")
                            ?.toJava(ByteArray::class.java) as? ByteArray
                    val timestamp = result.getDictValue("timestamp")?.toLong()
                    val destHashUsed =
                        result.getDictValue("destination_hash")
                            ?.toJava(ByteArray::class.java) as? ByteArray

                    JSONObject().apply {
                        put("success", true)
                        put("message_hash", msgHash.toBase64())
                        put("timestamp", timestamp)
                        put("destination_hash", destHashUsed.toBase64())
                    }.toString()
                } else {
                    val error = result.getDictValue("error")?.toString() ?: "Unknown error"
                    JSONObject().apply {
                        put("success", false)
                        put("error", error)
                    }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending LXMF message", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            Log.w(TAG, "sendLxmfMessage called but wrapper is null (service not initialized)")
            JSONObject().apply {
                put("success", false)
                put("error", "Service not initialized")
            }.toString()
        }
    }

    /**
     * Send a packet to a destination.
     *
     * @param destHash Destination hash bytes
     * @param data Packet data bytes
     * @param packetType Packet type string
     * @return JSON string with packet receipt
     */
    fun sendPacket(
        destHash: ByteArray,
        data: ByteArray,
        packetType: String,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("send_packet", destHash, data, packetType)

                val receiptHash =
                    result.getDictValue("receipt_hash")
                        ?.toJava(ByteArray::class.java) as? ByteArray
                val delivered = result.getDictValue("delivered")?.toBoolean()
                val timestamp = result.getDictValue("timestamp")?.toLong()

                JSONObject().apply {
                    put("receipt_hash", receiptHash.toBase64())
                    put("delivered", delivered)
                    put("timestamp", timestamp)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet", e)
                JSONObject().apply {
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            Log.w(TAG, "sendPacket called but wrapper is null (service not initialized)")
            JSONObject().apply {
                put("error", "Service not initialized")
            }.toString()
        }
    }

    /**
     * Create a destination.
     *
     * @param identityJson JSON string with identity data
     * @param direction Direction string ("IN" or "OUT")
     * @param destType Destination type string ("SINGLE", "GROUP", "PLAIN")
     * @param appName Application name
     * @param aspectsJson JSON array of aspect strings
     * @return JSON string with destination data
     */
    fun createDestination(
        identityJson: String,
        direction: String,
        destType: String,
        appName: String,
        aspectsJson: String,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val py = Python.getInstance()
                val builtins = py.getBuiltins()

                // Parse identity JSON and decode Base64 ByteArrays
                val identityObj = JSONObject(identityJson)
                val identityHash =
                    identityObj.optString("hash")?.let {
                        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    }
                val identityPublicKey =
                    identityObj.optString("public_key")?.let {
                        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    }
                val identityPrivateKey =
                    identityObj.optString("private_key")?.let {
                        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    }

                val identityDict = builtins.callAttr("dict")
                identityDict.callAttr("__setitem__", "hash", identityHash)
                identityDict.callAttr("__setitem__", "public_key", identityPublicKey)
                identityDict.callAttr("__setitem__", "private_key", identityPrivateKey)

                // Parse aspects JSON
                val aspectsArray = JSONArray(aspectsJson)
                val aspectsList = builtins.callAttr("list")
                for (i in 0 until aspectsArray.length()) {
                    aspectsList.callAttr("append", aspectsArray.getString(i))
                }

                val result =
                    wrapper.callAttr(
                        "create_destination",
                        identityDict,
                        direction,
                        destType,
                        appName,
                        aspectsList,
                    )

                val destHash =
                    result.getDictValue("hash")
                        ?.toJava(ByteArray::class.java) as? ByteArray
                val destHexHash = result.getDictValue("hex_hash")?.toString()

                JSONObject().apply {
                    put("hash", destHash.toBase64())
                    put("hex_hash", destHexHash)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating destination", e)
                JSONObject().apply {
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            Log.w(TAG, "createDestination called but wrapper is null (service not initialized)")
            JSONObject().apply {
                put("error", "Service not initialized")
            }.toString()
        }
    }

    /**
     * Announce a destination on the network.
     *
     * @param destHash Destination hash bytes
     * @param appData Optional application data bytes
     * @return JSON string with result
     */
    fun announceDestination(
        destHash: ByteArray,
        appData: ByteArray?,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("announce_destination", destHash, appData)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error announcing destination", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            Log.w(TAG, "announceDestination called but wrapper is null (service not initialized)")
            JSONObject().apply {
                put("success", false)
                put("error", "Service not initialized")
            }.toString()
        }
    }

    /**
     * Restore peer identities to enable message sending to previously known peers.
     *
     * @param peerIdentitiesJson JSON array containing objects with 'hash' and 'public_key' fields
     * @return JSON string with result
     */
    fun restorePeerIdentities(peerIdentitiesJson: String): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                Log.d(TAG, "Restoring peer identities")
                val result = wrapper.callAttr("restore_all_peer_identities", peerIdentitiesJson)

                // Python returns {"success_count": int, "errors": list}
                val successCount = result.getDictValue("success_count")?.toInt() ?: 0
                val errors = result.getDictValue("errors")?.toString() ?: "[]"

                val success = successCount > 0
                Log.d(TAG, "Restored $successCount peer identities")

                if (errors != "[]") {
                    Log.e(TAG, "Some identities failed to restore: $errors")
                }

                JSONObject().apply {
                    put("success", success)
                    put("restored_count", successCount)
                    if (!success) {
                        put("error", if (errors != "[]") errors else "No identities restored")
                    }
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring peer identities", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        } ?: run {
            JSONObject().apply {
                put("success", false)
                put("error", "Wrapper not initialized")
            }.toString()
        }
    }
}
