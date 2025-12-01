package com.lxmf.messenger.reticulum.util

import android.util.Log
import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType
import java.nio.charset.StandardCharsets

/**
 * Utility for parsing announce app_data, which can be either:
 * 1. Plain UTF-8 text (simple display names)
 * 2. Msgpack-encoded data (structured metadata with name field)
 *
 * This parser intelligently detects the format and extracts the peer name,
 * preventing display of garbage characters from incorrectly interpreted binary data.
 */
object AppDataParser {
    private const val TAG = "Columba:Kotlin:AppDataParser"

    /**
     * Extract a peer name from announce app_data.
     *
     * @param appData The app_data bytes from an announce (may be null or empty)
     * @param destinationHash The destination hash hex string (used for fallback name)
     * @return A human-readable peer name
     */
    fun extractPeerName(
        appData: ByteArray?,
        destinationHash: String,
    ): String {
        if (appData == null || appData.isEmpty()) {
            return generateFallbackName(destinationHash)
        }

        // Try msgpack parsing first (more structured)
        val msgpackName = tryParseMsgpack(appData)
        if (msgpackName != null) {
            Log.d(TAG, "Extracted name from msgpack: $msgpackName")
            return msgpackName
        }

        // Try plain UTF-8 parsing
        val utf8Name = tryParseUtf8(appData)
        if (utf8Name != null) {
            Log.d(TAG, "Extracted name from UTF-8: $utf8Name")
            return utf8Name
        }

        // Fall back to hash-based name
        Log.d(TAG, "Could not parse app_data, using fallback name")
        return generateFallbackName(destinationHash)
    }

    /**
     * Try to parse app_data as msgpack and extract the name field.
     *
     * Common msgpack structures:
     * - Map with "n" or "name" key (LXMF propagation nodes)
     * - Map with "display_name" or "app_name" key
     * - Array with name as first element
     *
     * @return The extracted name, or null if parsing fails
     */
    private fun tryParseMsgpack(data: ByteArray): String? {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(data)

            if (!unpacker.hasNext()) {
                return null
            }

            val value = unpacker.unpackValue()

            when (value.valueType) {
                ValueType.MAP -> {
                    // Try common name field keys
                    val map = value.asMapValue().map()

                    // Common keys used for names in LXMF msgpack data
                    val nameKeys = setOf("n", "name", "display_name", "app_name", "peer_name")

                    // Iterate through map looking for name fields
                    for ((k, v) in map) {
                        if (k.isStringValue && v.isStringValue) {
                            val keyStr = k.asStringValue().asString()

                            // Check if this is a known name key
                            if (keyStr in nameKeys) {
                                val name = v.asStringValue().asString()
                                if (name.isNotBlank() && isValidDisplayName(name)) {
                                    Log.d(TAG, "Found name from msgpack key '$keyStr': $name")
                                    return name
                                }
                            }
                        }
                    }

                    // If no name key found, try first valid string value
                    for ((k, v) in map) {
                        if (v.isStringValue) {
                            val str = v.asStringValue().asString()
                            if (str.isNotBlank() && isValidDisplayName(str)) {
                                val keyStr = if (k.isStringValue) k.asStringValue().asString() else "unknown"
                                Log.d(TAG, "Found name in map value (key: $keyStr): $str")
                                return str
                            }
                        }
                    }
                }

                ValueType.ARRAY -> {
                    // Some implementations use array format: [name, timestamp, ...]
                    val array = value.asArrayValue().list()
                    if (array.isNotEmpty() && array[0].isStringValue) {
                        val name = array[0].asStringValue().asString()
                        if (name.isNotBlank() && isValidDisplayName(name)) {
                            return name
                        }
                    }
                }

                ValueType.STRING -> {
                    // Sometimes msgpack just contains a string
                    val name = value.asStringValue().asString()
                    if (name.isNotBlank() && isValidDisplayName(name)) {
                        return name
                    }
                }

                else -> {
                    Log.d(TAG, "Msgpack value type not handled: ${value.valueType}")
                }
            }

            unpacker.close()
        } catch (e: Exception) {
            // Log first few bytes for debugging
            val preview = data.take(20).joinToString(" ") { "%02x".format(it) }
            Log.d(TAG, "Failed to parse as msgpack (${e.javaClass.simpleName}): ${e.message}")
            Log.d(TAG, "Data preview (first 20 bytes): $preview")
        }

        return null
    }

    /**
     * Try to parse app_data as plain UTF-8 text.
     *
     * @return The parsed string if valid, or null if invalid
     */
    private fun tryParseUtf8(data: ByteArray): String? {
        try {
            val str = String(data, StandardCharsets.UTF_8)

            // Validate the string
            if (str.isBlank()) {
                return null
            }

            // Check for invalid UTF-8 replacement characters (�) or control characters
            val hasReplacementChars = str.contains('\uFFFD')
            val hasControlChars = str.any { it.isISOControl() && !it.isWhitespace() }

            if (hasReplacementChars || hasControlChars) {
                // Try to extract a valid substring from the mixed binary/text data
                Log.d(TAG, "UTF-8 string contains binary data, attempting to extract valid substring")
                val extracted = extractValidSubstring(str)
                if (extracted != null) {
                    Log.d(TAG, "Extracted valid name from mixed data: $extracted")
                    return extracted
                }
                Log.d(TAG, "Could not extract valid name from mixed data")
                return null
            }

            // Validate as display name
            if (!isValidDisplayName(str)) {
                return null
            }

            return str.trim()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse as UTF-8: ${e.message}")
            return null
        }
    }

    /**
     * Extract a valid display name substring from mixed binary/text data.
     *
     * This handles cases where app_data contains a valid name surrounded by
     * binary junk, like: "\x02\x05Nemik\x00" → "Nemik"
     *
     * @param str The string that may contain replacement characters and control chars
     * @return The extracted valid name, or null if no valid substring found
     */
    private fun extractValidSubstring(str: String): String? {
        // Remove replacement characters and control characters
        val cleaned =
            str.filter { ch ->
                ch != '\uFFFD' && (!ch.isISOControl() || ch.isWhitespace())
            }.trim()

        if (cleaned.isBlank()) {
            return null
        }

        // Find the longest continuous sequence of valid display name characters
        val validChars =
            cleaned.filter { ch ->
                ch.isLetterOrDigit() || ch.isWhitespace() || ch in ".-_@#'\"()[]{},:;!?"
            }.trim()

        // Must have at least 3 alphanumeric characters to be a valid name
        if (validChars.length < 3) {
            return null
        }

        if (!isValidDisplayName(validChars)) {
            return null
        }

        return validChars
    }

    /**
     * Check if a string is a valid display name.
     *
     * Valid display names:
     * - Reasonable length (1-128 chars)
     * - Mostly printable characters
     * - No newlines
     * - At least some alphanumeric content
     */
    private fun isValidDisplayName(str: String): Boolean {
        if (str.length > 128) {
            return false
        }

        if (str.contains('\n') || str.contains('\r')) {
            return false
        }

        // Must have at least one alphanumeric character
        if (!str.any { it.isLetterOrDigit() }) {
            return false
        }

        // Most characters should be printable
        val printableRatio = str.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".-_@#'\"()[]{},:;!?" } / str.length.toFloat()
        if (printableRatio < 0.7f) {
            return false
        }

        return true
    }

    /**
     * Generate a fallback name based on the destination hash.
     *
     * @param destinationHash The hex string of the destination hash
     * @return A formatted name like "Peer 970A60FC"
     */
    private fun generateFallbackName(destinationHash: String): String {
        return if (destinationHash.length >= 8) {
            "Peer ${destinationHash.take(8).uppercase()}"
        } else {
            "Unknown Peer"
        }
    }
}
