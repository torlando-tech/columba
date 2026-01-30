package com.lxmf.messenger.data.db.migration

import org.json.JSONException
import org.json.JSONObject

/**
 * Helper for Migration 25->26 that extracts replyToMessageId from fieldsJson.
 *
 * This provides a Kotlin-based fallback for devices where SQLite's json_extract
 * function is not available (requires SQLite 3.38+ / Android 14+).
 *
 * Fixes COLUMBA-N: SQLiteException on older Android devices.
 */
object Migration25To26Helper {
    /**
     * Extract the reply_to field from fieldsJson.
     *
     * Equivalent to SQLite: json_extract(fieldsJson, '$."16".reply_to')
     *
     * @param fieldsJson The JSON string containing message fields
     * @return The reply_to message ID, or null if not present/invalid
     */
    @Suppress("SwallowedException") // JSONException expected for malformed input, null is correct response
    fun extractReplyToFromFieldsJson(fieldsJson: String?): String? {
        if (fieldsJson.isNullOrBlank()) return null

        return try {
            val root = JSONObject(fieldsJson)
            val field16 = root.optJSONObject("16")
            val replyTo = field16?.opt("reply_to") as? String
            replyTo?.takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            // Malformed JSON - treat as no reply_to present
            null
        }
    }
}
