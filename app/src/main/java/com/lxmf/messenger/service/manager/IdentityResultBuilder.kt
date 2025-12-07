package com.lxmf.messenger.service.manager

import android.util.Base64
import org.json.JSONObject

/**
 * Parses identity result JSON to a Map.
 * Extracted to reduce code duplication between createIdentityWithName and importIdentityFile
 * in ServiceReticulumProtocol.
 *
 * Uses java.util.Base64 for decoding to enable JVM unit testing.
 */
fun parseIdentityResultJson(json: String): Map<String, Any> {
    val result = JSONObject(json)
    val map = mutableMapOf<String, Any>()

    if (result.has("identity_hash")) {
        map["identity_hash"] = result.getString("identity_hash")
    }
    if (result.has("destination_hash")) {
        map["destination_hash"] = result.getString("destination_hash")
    }
    if (result.has("file_path")) {
        map["file_path"] = result.getString("file_path")
    }
    if (result.has("key_data")) {
        try {
            val keyData = java.util.Base64.getDecoder().decode(result.getString("key_data"))
            map["key_data"] = keyData
        } catch (_: IllegalArgumentException) {
            // Invalid base64, omit key_data
        }
    }
    if (result.has("error")) {
        map["error"] = result.getString("error")
    }

    return map
}

/**
 * Builds JSON result strings for identity operations.
 * Extracted to reduce code duplication between createIdentityWithName and importIdentityFile.
 *
 * @param keyDataBase64 Pre-encoded base64 string for key data (null if no key data)
 */
fun buildIdentityResultJson(
    identityHash: String?,
    destinationHash: String?,
    filePath: String?,
    keyDataBase64: String?,
    displayName: String?,
): String {
    return JSONObject().apply {
        put("identity_hash", identityHash)
        put("destination_hash", destinationHash)
        put("file_path", filePath)
        if (keyDataBase64 != null) {
            put("key_data", keyDataBase64)
        }
        put("display_name", displayName)
    }.toString()
}

/**
 * Encode ByteArray to Base64 string using Android's Base64.
 */
fun ByteArray?.toBase64String(): String? {
    return this?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
}
