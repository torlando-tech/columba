package com.lxmf.messenger.service.binder

import android.util.Base64
import com.chaquo.python.PyObject
import org.json.JSONObject

/**
 * Converts Python dict results to valid JSON strings.
 *
 * This is needed because Python dicts with bytes values cannot be directly
 * converted to JSON using toString() - they produce Python repr format like:
 * {'success': True, 'message_hash': b'\x13\x1f...'}
 *
 * Instead, we must extract values and encode bytes as Base64.
 */
object PythonResultConverter {
    /**
     * Abstraction for accessing dict values.
     * Allows testing without requiring Chaquopy's native PyObject class.
     */
    interface DictAccessor {
        fun getBoolean(key: String): Boolean?

        fun getLong(key: String): Long?

        fun getString(key: String): String?

        fun getByteArray(key: String): ByteArray?
    }

    /**
     * Implementation that wraps a PyObject for production use.
     */
    class PyObjectDictAccessor(private val pyObject: PyObject) : DictAccessor {
        override fun getBoolean(key: String): Boolean? = pyObject.callAttr("get", key)?.toBoolean()

        override fun getLong(key: String): Long? = pyObject.callAttr("get", key)?.toLong()

        override fun getString(key: String): String? = pyObject.callAttr("get", key)?.toString()

        override fun getByteArray(key: String): ByteArray? = pyObject.callAttr("get", key)?.toJava(ByteArray::class.java)
    }

    /**
     * Convert a Python send_lxmf_message_with_method result to valid JSON.
     *
     * Expected Python dict structure:
     * - success: bool
     * - message_hash: bytes (32 bytes) - only on success
     * - timestamp: int (milliseconds) - only on success
     * - delivery_method: str ("opportunistic", "direct", "propagated") - only on success
     * - destination_hash: bytes (16 bytes) - only on success
     * - error: str - only on failure
     */
    fun convertSendMessageResult(accessor: DictAccessor?): String {
        if (accessor == null) {
            return """{"success": false, "error": "No result"}"""
        }

        return try {
            val json = JSONObject()
            val success = accessor.getBoolean("success") ?: false
            json.put("success", success)

            if (success) {
                // Extract and encode bytes as Base64
                val messageHash = accessor.getByteArray("message_hash")
                json.put("message_hash", messageHash?.toBase64() ?: "")

                val timestamp = accessor.getLong("timestamp") ?: System.currentTimeMillis()
                json.put("timestamp", timestamp)

                val deliveryMethod = accessor.getString("delivery_method") ?: "unknown"
                json.put("delivery_method", deliveryMethod)

                val destHash = accessor.getByteArray("destination_hash")
                json.put("destination_hash", destHash?.toBase64() ?: "")
            } else {
                val error = accessor.getString("error") ?: "Unknown error"
                json.put("error", error)
            }

            json.toString()
        } catch (e: Exception) {
            """{"success": false, "error": "${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    /**
     * Convenience overload for PyObject - wraps in PyObjectDictAccessor.
     */
    fun convertSendMessageResult(pyObject: PyObject?): String {
        return if (pyObject == null) {
            """{"success": false, "error": "No result"}"""
        } else {
            convertSendMessageResult(PyObjectDictAccessor(pyObject))
        }
    }

    /**
     * Convert ByteArray to Base64 string for JSON transport.
     */
    private fun ByteArray?.toBase64(): String {
        return this?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
    }
}
