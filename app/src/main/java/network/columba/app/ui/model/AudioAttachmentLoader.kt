package network.columba.app.ui.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AudioAttachmentLoader(
    private val context: Context,
) {
    suspend fun loadBytes(attachment: AudioAttachmentUi): ByteArray? =
        withContext(Dispatchers.IO) {
            resolvePayload(attachment.fieldsJson, attachment.payloadRef)?.let { readPayloadBytes(it) }
        }

    private fun resolvePayload(fieldsJson: String?, payloadRef: AudioAttachmentPayloadRef?): AudioAttachmentPayloadRef? {
        if (payloadRef != null) return payloadRef
        if (fieldsJson == null) return null
        val fields = runCatching { JSONObject(fieldsJson) }.getOrNull() ?: return null
        val field7 = fields.opt("7") ?: return null
        return when (field7) {
            is JSONArray -> parseArrayPayload(field7)
            is JSONObject -> parseObjectPayload(field7)
            else -> null
        }
    }

    private fun parseArrayPayload(field7: JSONArray): AudioAttachmentPayloadRef? {
        if (field7.length() < 2) return null
        return when (val payload = field7.opt(1)) {
            is String -> payload.toAudioPayloadRefOrNull()
            is JSONObject -> parseObjectPayload(payload)
            else -> null
        }
    }

    private fun parseObjectPayload(field7: JSONObject): AudioAttachmentPayloadRef? {
        field7.optString("data", "").takeIf { it.isNotEmpty() }?.let { return it.toAudioPayloadRefOrNull() }
        field7.optString("_file_ref", "").takeIf { it.isNotEmpty() }?.let { return AudioAttachmentPayloadRef.FileRef(it) }
        field7.optJSONObject("payload")?.let { nested ->
            val nestedRef = parseObjectPayload(nested) ?: return null
            return AudioAttachmentPayloadRef.NestedFieldRef("payload", nestedRef)
        }
        return null
    }

    private fun readPayloadBytes(
        ref: AudioAttachmentPayloadRef,
        visitedPaths: MutableSet<String> = mutableSetOf(),
    ): ByteArray? =
        when (ref) {
            is AudioAttachmentPayloadRef.InlineHex -> decodeHex(ref.hex)
            is AudioAttachmentPayloadRef.FileRef -> readFilePayload(ref.path, visitedPaths)
            is AudioAttachmentPayloadRef.NestedFieldRef -> readPayloadBytes(ref.payload, visitedPaths)
        }

    private fun readFilePayload(path: String, visitedPaths: MutableSet<String>): ByteArray? {
        val file = File(path)
        if (!file.isFile || !visitedPaths.add(file.canonicalPath)) return null
        val raw = file.readBytes()
        if (raw.size >= 4 && raw.copyOfRange(0, 4).contentEquals("OggS".encodeToByteArray())) {
            return raw
        }
        val stored = raw.toString(Charsets.UTF_8).trim()
        decodeHex(stored)?.let { return it }
        val nested =
            runCatching {
                when {
                    stored.startsWith("[") -> parseArrayPayload(JSONArray(stored))
                    stored.startsWith("{") -> parseObjectPayload(JSONObject(stored))
                    else -> null
                }
            }.getOrNull()
        return nested?.let { readPayloadBytes(it, visitedPaths) }
    }

    private fun decodeHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) + lo).toByte()
        }
        return out
        }
    }

    private fun String.toAudioPayloadRefOrNull(): AudioAttachmentPayloadRef? {
        if (isEmpty() || length % 2 != 0) return null
        if (!all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return AudioAttachmentPayloadRef.InlineHex(this)
    }
