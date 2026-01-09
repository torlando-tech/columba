package com.lxmf.messenger.reticulum.util

import android.util.Log
import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType

/**
 * Metadata extracted from propagation node announces.
 *
 * LXMF propagation nodes announce structured data including size limits:
 * - transferLimitKb: Per-message size limit in KB (index 3 of announce array)
 * - name: Node display name (from index 6 metadata map)
 */
data class PropagationNodeMetadata(
    val name: String?,
    val transferLimitKb: Int?,
)

/**
 * Utility for extracting peer names from announce data.
 *
 * Name parsing is handled by Python's canonical LXMF functions:
 * - LXMF.display_name_from_app_data() for lxmf.delivery and nomadnetwork.node
 * - LXMF.pn_name_from_app_data() for lxmf.propagation
 *
 * This class just uses the pre-parsed displayName or generates a fallback.
 *
 * See: https://github.com/torlando-tech/columba/issues/41
 */
object AppDataParser {
    private const val TAG = "Columba:Kotlin:AppDataParser"

    /**
     * Extract a peer name from announce data.
     *
     * @param appData The app_data bytes (unused, kept for API compatibility)
     * @param destinationHash The destination hash hex string (used for fallback name)
     * @param displayName Pre-parsed display name from Python's LXMF functions
     * @return A human-readable peer name
     */
    @Suppress("UNUSED_PARAMETER")
    fun extractPeerName(
        appData: ByteArray?,
        destinationHash: String,
        displayName: String? = null,
    ): String {
        // Use Python-provided display name if valid
        if (!displayName.isNullOrBlank()) {
            Log.d(TAG, "Using display name from Python: $displayName")
            return displayName
        }

        // Fallback to hash-based name
        Log.d(TAG, "No display name, using fallback")
        return generateFallbackName(destinationHash)
    }

    /**
     * Generate a fallback name based on the destination hash.
     *
     * @param destinationHash The hex string of the destination hash
     * @return A formatted name like "Peer 970A60FC"
     */
    private fun generateFallbackName(destinationHash: String): String =
        if (destinationHash.length >= 8) {
            "Peer ${destinationHash.take(8).uppercase()}"
        } else {
            "Unknown Peer"
        }

    private val emptyMetadata = PropagationNodeMetadata(name = null, transferLimitKb = null)

    /**
     * Extract propagation node metadata from announce app_data.
     *
     * LXMF propagation nodes announce structured data as a msgpack array:
     * [0] False (legacy support)
     * [1] Timebase (int timestamp)
     * [2] Node state (boolean - is active)
     * [3] Per-message transfer limit in KB ← Primary target
     * [4] Per-sync transfer limit in KB
     * [5] Stamp cost array
     * [6] Metadata map (may contain "name" or "n" key)
     *
     * @param appData The app_data bytes from a propagation node announce
     * @return PropagationNodeMetadata with extracted values (nulls if not parseable)
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun extractPropagationNodeMetadata(appData: ByteArray?): PropagationNodeMetadata {
        Log.d(TAG, "extractPropagationNodeMetadata() called, appData size: ${appData?.size ?: 0}")

        if (appData == null || appData.isEmpty()) {
            Log.d(TAG, "appData is null or empty")
            return emptyMetadata
        }

        return try {
            parseMetadataFromMsgpack(appData)
        } catch (e: Exception) {
            logParseError(appData, e)
            emptyMetadata
        }
    }

    @Suppress("MagicNumber")
    private fun parseMetadataFromMsgpack(appData: ByteArray): PropagationNodeMetadata {
        val unpacker = MessagePack.newDefaultUnpacker(appData)

        if (!unpacker.hasNext()) {
            Log.d(TAG, "No data to unpack")
            return emptyMetadata
        }

        val value = unpacker.unpackValue()

        if (value.valueType != ValueType.ARRAY) {
            Log.d(TAG, "Propagation node app_data is not an array: ${value.valueType}")
            return emptyMetadata
        }

        val array = value.asArrayValue().list()
        Log.d(TAG, "Propagation node array has ${array.size} elements")

        val transferLimitKb = extractTransferLimit(array)
        val name = extractNodeName(array)

        unpacker.close()
        return PropagationNodeMetadata(name = name, transferLimitKb = transferLimitKb)
    }

    @Suppress("MagicNumber")
    private fun extractTransferLimit(array: List<org.msgpack.value.Value>): Int? {
        if (array.size <= 3) {
            Log.d(TAG, "Array too small for transfer limit (need >3, got ${array.size})")
            return null
        }

        val limitValue = array[3]
        Log.d(
            TAG,
            "Index 3 value type: ${limitValue.valueType}, " +
                "isInteger: ${limitValue.isIntegerValue}, isFloat: ${limitValue.isFloatValue}",
        )

        return when {
            limitValue.isIntegerValue -> {
                val limit = limitValue.asIntegerValue().toInt()
                Log.d(TAG, "Extracted propagation transfer limit (int): $limit KB")
                limit
            }
            limitValue.isFloatValue -> {
                val limit = limitValue.asFloatValue().toDouble().toInt()
                Log.d(TAG, "Extracted propagation transfer limit (float): $limit KB")
                limit
            }
            else -> null
        }
    }

    @Suppress("MagicNumber")
    private fun extractNodeName(array: List<org.msgpack.value.Value>): String? {
        if (array.size <= 6) return null

        val metadataValue = array[6]
        if (!metadataValue.isMapValue) return null

        return findNameInMetadata(metadataValue.asMapValue().map())
    }

    private fun findNameInMetadata(metadataMap: Map<org.msgpack.value.Value, org.msgpack.value.Value>): String? {
        val nameKeys = setOf("n", "name", "display_name")

        val nameEntry =
            metadataMap.entries.firstOrNull { (k, v) ->
                k.isStringValue &&
                    v.isStringValue &&
                    k.asStringValue().asString() in nameKeys &&
                    v.asStringValue().asString().isNotBlank()
            }

        return nameEntry?.let {
            val name = it.value.asStringValue().asString()
            Log.d(TAG, "Extracted propagation node name: $name")
            name
        }
    }

    private fun logParseError(
        appData: ByteArray,
        e: Exception,
    ) {
        val preview = appData.take(20).joinToString(" ") { "%02x".format(it) }
        Log.d(TAG, "Failed to parse propagation node metadata: ${e.message}")
        Log.d(TAG, "Data preview (first 20 bytes): $preview")
    }
}
