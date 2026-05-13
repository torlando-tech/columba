package network.columba.app.rns.ipc

import android.os.Bundle

/**
 * Pack a free-form `Map<String, Any>` into a [Bundle] for transport over an
 * `IRnsResultCallback` payload. Used for [network.columba.app.rns.api.RnsTransportAdmin.getDebugInfo]
 * and [network.columba.app.rns.api.RnsTransportAdmin.getInterfaceStats], whose
 * values are typically primitives or strings (the developer/diagnostic
 * screens render them verbatim).
 *
 * Unrecognized value types fall through to `toString()` — lossy by design,
 * since the consumer of debug-info is a developer-facing read-only view.
 */
internal fun Map<String, Any>.toBundle(): Bundle {
    val bundle = Bundle()
    for ((key, value) in this) {
        when (value) {
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
            is ByteArray -> bundle.putByteArray(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }
    return bundle
}

/**
 * Inverse of [toBundle] — read every key out of [bundle] (skipping the
 * convention keys [skip]) and return a `Map<String, Any>`. Values come back
 * with whatever boxed type [Bundle.get] returns.
 */
internal fun Bundle.toAnyMap(skip: Set<String> = emptySet()): Map<String, Any> {
    val out = LinkedHashMap<String, Any>(size())
    for (key in keySet()) {
        if (key in skip) continue
        @Suppress("DEPRECATION")
        val value = get(key) ?: continue
        out[key] = value
    }
    return out
}
