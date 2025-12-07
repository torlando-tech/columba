package com.lxmf.messenger.service.manager

import android.util.Base64
import org.json.JSONObject

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
