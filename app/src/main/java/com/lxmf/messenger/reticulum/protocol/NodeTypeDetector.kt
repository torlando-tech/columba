package com.lxmf.messenger.reticulum.protocol

import android.util.Log
import com.lxmf.messenger.reticulum.model.NodeType
import java.nio.charset.StandardCharsets

/**
 * Utility object for detecting node types from announce data.
 *
 * This detector analyzes app_data and aspect information to determine
 * whether an announce is from a regular LXMF peer, a NomadNet node,
 * a propagation node, or another type of Reticulum application.
 */
object NodeTypeDetector {
    private const val TAG = "NodeTypeDetector"

    /**
     * Detect the node type from announce data
     *
     * @param appData The app_data field from the announce (may be null)
     * @param aspect The aspect filter of the destination (e.g., "lxmf.delivery", "nomadnetwork.node")
     * @return The detected NodeType
     */
    fun detectNodeType(
        appData: ByteArray?,
        aspect: String? = null,
    ): NodeType {
        // First check aspect if available - this is the most reliable indicator
        aspect?.let {
            when (it) {
                "lxmf.propagation" -> return NodeType.PROPAGATION_NODE
                "nomadnetwork.node" -> return NodeType.NODE
                "lxmf.delivery" -> return NodeType.PEER
                // Audio call service destinations - these are SERVICE nodes, not messaging peers
                "call.audio" -> return NodeType.NODE
                // Meshchat room destinations - these are also SERVICE nodes
                "meshchat.room" -> return NodeType.NODE
                else -> {
                    // Unknown aspect, will try to detect from app_data below
                    Log.d(TAG, "Unknown aspect: $it, attempting detection from app_data")
                }
            }
        }

        // If no aspect available, try to detect from app_data
        if (appData == null || appData.isEmpty()) {
            // No app_data typically means a basic Reticulum node
            return NodeType.NODE
        }

        // Try to parse app_data as UTF-8 string first
        val appDataString =
            try {
                String(appData, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                null
            }

        if (appDataString != null && appDataString.isNotBlank()) {
            // Check for known application identifiers
            when {
                // Columba peers
                appDataString.contains("Columba", ignoreCase = true) -> {
                    Log.d(TAG, "Detected Columba peer from app_data: $appDataString")
                    return NodeType.PEER
                }

                // Sideband peers (another LXMF messenger)
                appDataString.contains("Sideband", ignoreCase = true) -> {
                    Log.d(TAG, "Detected Sideband peer from app_data: $appDataString")
                    return NodeType.PEER
                }

                // NomadNet nodes typically have descriptive names without specific markers
                // This is a heuristic - NomadNet nodes often have names like "John's Node"
                appDataString.contains("Node", ignoreCase = true) ||
                    appDataString.contains("'s ", ignoreCase = false) -> {
                    Log.d(TAG, "Possible NomadNet node from app_data: $appDataString")
                    return NodeType.NODE
                }

                // Check if it looks like a standard LXMF display name
                // LXMF display names are typically just names or handles
                appDataString.length < 50 && !appDataString.contains("\n") -> {
                    Log.d(TAG, "Detected LXMF peer from display name: $appDataString")
                    return NodeType.PEER
                }
            }
        }

        // Check for msgpack-encoded propagation node data
        // Propagation nodes send msgpack data starting with specific bytes
        if (isPropagationNodeData(appData)) {
            Log.d(TAG, "Detected propagation node from msgpack data")
            return NodeType.PROPAGATION_NODE
        }

        // Check for LXMF standard display name format (if not already caught above)
        if (isLxmfDisplayNameFormat(appData)) {
            Log.d(TAG, "Detected LXMF peer from standard format")
            return NodeType.PEER
        }

        // Default to PEER as it's the most common and allows interaction
        Log.d(TAG, "Could not determine specific type, defaulting to PEER")
        return NodeType.PEER
    }

    /**
     * Check if the app_data appears to be msgpack-encoded propagation node data
     *
     * Propagation nodes typically send msgpack data with:
     * - Boolean flag for active status
     * - Transfer limits and timebase information
     */
    private fun isPropagationNodeData(appData: ByteArray): Boolean {
        if (appData.size < 3) return false

        // msgpack format markers
        // Check for msgpack array or map markers
        val firstByte = appData[0].toInt() and 0xFF

        // msgpack array formats: 0x90-0x9f (fixarray) or 0xdc-0xdd (array 16/32)
        // msgpack map formats: 0x80-0x8f (fixmap) or 0xde-0xdf (map 16/32)
        // msgpack true: 0xc3, false: 0xc2
        return when (firstByte) {
            in 0x90..0x9f -> true // fixarray
            0xdc, 0xdd -> true // array 16/32
            in 0x80..0x8f -> true // fixmap
            0xde, 0xdf -> true // map 16/32
            0xc2, 0xc3 -> appData.size > 1 // boolean followed by more data
            else -> false
        }
    }

    /**
     * Check if the app_data follows LXMF standard display name format
     *
     * LXMF 0.5.0+ uses a specific format for display names in announces
     */
    private fun isLxmfDisplayNameFormat(appData: ByteArray): Boolean {
        // LXMF display names are typically UTF-8 encoded strings
        // without special formatting or markers
        return try {
            val str = String(appData, StandardCharsets.UTF_8)
            // Valid display names are printable, reasonable length, single line
            str.isNotBlank() &&
                str.length <= 128 &&
                !str.contains("\n") &&
                str.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".-_@#" }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get a human-readable description of the node type
     */
    fun getNodeTypeDescription(nodeType: NodeType): String {
        return when (nodeType) {
            NodeType.PEER -> "LXMF messaging peer"
            NodeType.NODE -> "Content/service node"
            NodeType.PROPAGATION_NODE -> "Message relay node"
        }
    }
}
