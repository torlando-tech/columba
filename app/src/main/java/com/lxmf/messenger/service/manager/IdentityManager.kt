package com.lxmf.messenger.service.manager

import android.util.Log
import com.lxmf.messenger.service.manager.PythonWrapperManager.Companion.getDictValue
import org.json.JSONObject

/**
 * Manages identity operations for Reticulum.
 *
 * Handles:
 * - Identity creation
 * - Identity loading/saving
 * - Identity import/export
 * - Identity deletion
 * - LXMF identity/destination retrieval
 */
class IdentityManager(private val wrapperManager: PythonWrapperManager) {
    companion object {
        private const val TAG = "IdentityManager"

        /**
         * Helper to convert ByteArray to Base64 string for JSON serialization.
         */
        private fun ByteArray?.toBase64(): String? {
            return this?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }
    }

    /**
     * Create a new identity.
     *
     * @return JSON string with identity data (hash, public_key, private_key) or error
     */
    fun createIdentity(): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                Log.d(TAG, "Creating identity")
                val result = wrapper.callAttr("create_identity")

                val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as? ByteArray
                val publicKey = result.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
                val privateKey = result.getDictValue("private_key")?.toJava(ByteArray::class.java) as? ByteArray

                Log.d(TAG, "Identity created - hash=${hash?.size ?: 0} bytes")

                JSONObject().apply {
                    put("hash", hash.toBase64())
                    put("public_key", publicKey.toBase64())
                    put("private_key", privateKey.toBase64())
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating identity", e)
                errorJson(e.message)
            }
        } ?: notInitializedError("createIdentity")
    }

    /**
     * Create a new identity with display name.
     *
     * @param displayName Display name for the identity
     * @return JSON string with identity data or error
     */
    fun createIdentityWithName(displayName: String): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("create_identity", displayName)

                // Check for error
                val error = result.getDictValue("error")?.toString()
                if (error != null) {
                    return@withWrapper JSONObject().apply {
                        put("error", error)
                    }.toString()
                }

                // Extract fields and properly convert to JSON
                val identityHash = result.getDictValue("identity_hash")?.toString()
                val destinationHash = result.getDictValue("destination_hash")?.toString()
                val filePath = result.getDictValue("file_path")?.toString()
                val keyData = result.getDictValue("key_data")?.toJava(ByteArray::class.java) as? ByteArray
                val name = result.getDictValue("display_name")?.toString()

                JSONObject().apply {
                    put("identity_hash", identityHash)
                    put("destination_hash", destinationHash)
                    put("file_path", filePath)
                    if (keyData != null) {
                        put("key_data", keyData.toBase64())
                    }
                    put("display_name", name)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating identity with name", e)
                errorJson(e.message)
            }
        } ?: notInitializedError("createIdentityWithName")
    }

    /**
     * Load identity from file.
     *
     * @param path File path to load from
     * @return JSON string with identity data or error
     */
    fun loadIdentity(path: String): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("load_identity", path)

                val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as? ByteArray
                val publicKey = result.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
                val privateKey = result.getDictValue("private_key")?.toJava(ByteArray::class.java) as? ByteArray

                JSONObject().apply {
                    put("hash", hash.toBase64())
                    put("public_key", publicKey.toBase64())
                    put("private_key", privateKey.toBase64())
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading identity", e)
                errorJson(e.message)
            }
        } ?: notInitializedError("loadIdentity")
    }

    /**
     * Save identity to file.
     *
     * @param privateKey Private key bytes
     * @param path File path to save to
     * @return JSON string with result
     */
    fun saveIdentity(
        privateKey: ByteArray,
        path: String,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("save_identity", privateKey, path)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving identity", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        } ?: JSONObject().apply {
            put("success", false)
            put("error", "Service not initialized")
        }.toString()
    }

    /**
     * Delete an identity file.
     *
     * @param identityHash Identity hash to delete
     * @return JSON string with result
     */
    fun deleteIdentityFile(identityHash: String): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("delete_identity_file", identityHash)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting identity file", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        } ?: JSONObject().apply {
            put("success", false)
            put("error", "Service not initialized")
        }.toString()
    }

    /**
     * Import an identity from file data.
     *
     * @param fileData Identity file data bytes
     * @param displayName Display name for the identity
     * @return JSON string with identity data or error
     */
    fun importIdentityFile(
        fileData: ByteArray,
        displayName: String,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("import_identity_file", fileData, displayName)

                // Check for error
                val error = result.getDictValue("error")?.toString()
                if (error != null) {
                    return@withWrapper JSONObject().apply {
                        put("error", error)
                    }.toString()
                }

                // Extract fields and properly convert to JSON
                val identityHash = result.getDictValue("identity_hash")?.toString()
                val destinationHash = result.getDictValue("destination_hash")?.toString()
                val filePath = result.getDictValue("file_path")?.toString()
                val keyData = result.getDictValue("key_data")?.toJava(ByteArray::class.java) as? ByteArray
                val name = result.getDictValue("display_name")?.toString()

                JSONObject().apply {
                    put("identity_hash", identityHash)
                    put("destination_hash", destinationHash)
                    put("file_path", filePath)
                    if (keyData != null) {
                        put("key_data", keyData.toBase64())
                    }
                    put("display_name", name)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error importing identity file", e)
                errorJson(e.message)
            }
        } ?: notInitializedError("importIdentityFile")
    }

    /**
     * Export an identity file.
     *
     * @param identityHash Identity hash to export
     * @param filePath Direct path to identity file
     * @return Identity file data bytes, or empty array on error
     */
    fun exportIdentityFile(
        identityHash: String,
        filePath: String,
    ): ByteArray {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("export_identity_file", identityHash, filePath)
                result.toJava(ByteArray::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting identity file", e)
                ByteArray(0)
            }
        } ?: ByteArray(0)
    }

    /**
     * Recover an identity file from backup key data.
     *
     * @param identityHash Expected identity hash
     * @param keyData Raw 64-byte identity key data from database backup
     * @param filePath Path where identity file should be restored
     * @return JSON string with result (success, file_path, or error)
     */
    fun recoverIdentityFile(
        identityHash: String,
        keyData: ByteArray,
        filePath: String,
    ): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("recover_identity_file", identityHash, keyData, filePath)
                val success = result.getDictValue("success")?.toBoolean() ?: false
                val error = result.getDictValue("error")?.toString()
                val recoveredPath = result.getDictValue("file_path")?.toString()

                JSONObject().apply {
                    put("success", success)
                    if (error != null) put("error", error)
                    if (recoveredPath != null) put("file_path", recoveredPath)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering identity file", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            }
        } ?: JSONObject().apply {
            put("success", false)
            put("error", "Wrapper not available")
        }.toString()
    }

    /**
     * Get the LXMF router's identity.
     *
     * @return JSON string with identity data or error
     */
    fun getLxmfIdentity(): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("get_lxmf_identity")

                if (result.getDictValue("error") != null) {
                    return@withWrapper JSONObject().apply {
                        put("error", result.getDictValue("error")?.toString())
                    }.toString()
                }

                val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as? ByteArray
                val publicKey = result.getDictValue("public_key")?.toJava(ByteArray::class.java) as? ByteArray
                val privateKey = result.getDictValue("private_key")?.toJava(ByteArray::class.java) as? ByteArray

                JSONObject().apply {
                    put("hash", hash.toBase64())
                    put("public_key", publicKey.toBase64())
                    put("private_key", privateKey.toBase64())
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting LXMF identity", e)
                errorJson(e.message)
            }
        } ?: notInitializedError("getLxmfIdentity")
    }

    /**
     * Get the local LXMF destination hash.
     *
     * @return JSON string with destination data or error
     */
    fun getLxmfDestination(): String {
        return wrapperManager.withWrapper { wrapper ->
            try {
                val result = wrapper.callAttr("get_lxmf_destination")

                if (result.getDictValue("error") != null) {
                    return@withWrapper JSONObject().apply {
                        put("error", result.getDictValue("error")?.toString())
                    }.toString()
                }

                val hash = result.getDictValue("hash")?.toJava(ByteArray::class.java) as? ByteArray
                val hexHash = result.getDictValue("hex_hash")?.toString()

                JSONObject().apply {
                    put("hash", hash.toBase64())
                    put("hex_hash", hexHash)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting LXMF destination", e)
                errorJson(e.message)
            }
        } ?: notInitializedError("getLxmfDestination")
    }

    private fun errorJson(message: String?): String {
        return JSONObject().apply {
            put("error", message)
        }.toString()
    }

    private fun notInitializedError(method: String): String {
        Log.w(TAG, "$method called but wrapper is null (service not initialized)")
        return errorJson("Service not initialized")
    }
}
