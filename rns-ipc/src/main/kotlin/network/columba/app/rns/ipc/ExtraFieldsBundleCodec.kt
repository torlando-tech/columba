package network.columba.app.rns.ipc

import android.os.Bundle

private const val LIST_MARKER = "network.columba.extra_fields.list"
private const val VALUE_KIND = "__kind"
private const val VALUE_SIZE = "__size"

/**
 * Losslessly carries structured LXMF optional fields across Binder.
 *
 * LXMF field values may contain nested lists and binary payloads. Falling back
 * to `toString()` for an unsupported value corrupts the protocol shape, so this
 * codec rejects unsupported types instead.
 */
internal fun Map<Int, Any>.toExtraFieldsBundle(): Bundle =
    Bundle().apply {
        for ((field, value) in this@toExtraFieldsBundle) {
            putExtraFieldValue(field.toString(), value)
        }
    }

/** Inverse of [toExtraFieldsBundle]. */
internal fun Bundle.toExtraFieldsMap(): Map<Int, Any> {
    val result = LinkedHashMap<Int, Any>(size())
    for (key in keySet()) {
        val field = key.toIntOrNull() ?: continue
        @Suppress("DEPRECATION")
        val encoded = get(key) ?: continue
        result[field] = decodeExtraFieldValue(encoded)
    }
    return result
}

private fun Bundle.putExtraFieldValue(
    key: String,
    value: Any,
) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Double -> putDouble(key, value)
        is String -> putString(key, value)
        is ByteArray -> putByteArray(key, value)
        is List<*> ->
            putBundle(
                key,
                Bundle().apply {
                    putString(VALUE_KIND, LIST_MARKER)
                    putInt(VALUE_SIZE, value.size)
                    value.forEachIndexed { index, element ->
                        requireNotNull(element) { "LXMF extra-field lists cannot contain null values" }
                        putExtraFieldValue(index.toString(), element)
                    }
                },
            )
        else -> throw IllegalArgumentException("Unsupported LXMF extra-field value type: ${value::class.java.name}")
    }
}

private fun decodeExtraFieldValue(value: Any): Any {
    if (value !is Bundle) return value
    require(value.getString(VALUE_KIND) == LIST_MARKER) { "Unsupported LXMF extra-field bundle value" }
    val size = value.getInt(VALUE_SIZE, -1)
    require(size >= 0) { "Invalid LXMF extra-field list size" }
    return List(size) { index ->
        @Suppress("DEPRECATION")
        val element = value.get(index.toString())
            ?: throw IllegalArgumentException("Missing LXMF extra-field list element $index")
        decodeExtraFieldValue(element)
    }
}
