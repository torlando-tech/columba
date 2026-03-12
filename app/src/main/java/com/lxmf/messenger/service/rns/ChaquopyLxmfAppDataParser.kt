package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfAppDataParser

/**
 * Chaquopy implementation of [LxmfAppDataParser].
 * Calls `rns_api.RnsApi` static methods that parse LXMF announce app_data.
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyLxmfAppDataParser(
    private val api: PyObject,
) : LxmfAppDataParser {
    override fun displayNameFromAppData(appData: ByteArray): String? {
        val result = api.callAttr("display_name_from_app_data", appData)
        return try {
            if (result != null && result.toString() != "None") {
                result.toString()
            } else {
                null
            }
        } finally {
            result?.close()
        }
    }

    override fun propagationNodeNameFromAppData(appData: ByteArray): String? {
        val result = api.callAttr("propagation_node_name_from_app_data", appData)
        return try {
            if (result != null && result.toString() != "None") {
                result.toString()
            } else {
                null
            }
        } finally {
            result?.close()
        }
    }

    override fun stampCostFromAppData(appData: ByteArray): Int? {
        val result = api.callAttr("stamp_cost_from_app_data", appData)
        return try {
            if (result != null && result.toString() != "None") {
                result.toInt()
            } else {
                null
            }
        } finally {
            result?.close()
        }
    }

    override fun isPropagationNodeAnnounceValid(appData: ByteArray): Boolean {
        val result = api.callAttr("is_propagation_node_announce_valid", appData)
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }
}
