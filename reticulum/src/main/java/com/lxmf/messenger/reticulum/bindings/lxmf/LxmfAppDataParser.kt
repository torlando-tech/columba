package com.lxmf.messenger.reticulum.bindings.lxmf

/**
 * Utilities for parsing LXMF announce app_data fields.
 *
 * App data is a msgpack-encoded structure attached to LXMF destination announces.
 * Contains display name, propagation node info, stamp cost, and other metadata.
 */
interface LxmfAppDataParser {
    fun displayNameFromAppData(appData: ByteArray): String?

    fun propagationNodeNameFromAppData(appData: ByteArray): String?

    fun stampCostFromAppData(appData: ByteArray): Int?

    fun isPropagationNodeAnnounceValid(appData: ByteArray): Boolean
}
