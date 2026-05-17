package network.columba.app.rns.ipc

import android.os.Bundle

/**
 * Pack a free-form `Map<String, Any>` into a [Bundle] for transport over an
 * `IRnsResultCallback` payload. Used for [network.columba.app.rns.api.RnsTransportAdmin.getDebugInfo]
 * and [network.columba.app.rns.api.RnsTransportAdmin.getInterfaceStats], whose
 * values are typically primitives or strings (the developer/diagnostic
 * screens render them verbatim).
 *
 * Nested shapes — `Map<String, Any>` and `List<Map<String, Any>>` — are
 * preserved by recursing into sub-Bundles (and a `Bundle` parcelable list for
 * the list case). This matters for `getDebugInfo`'s `"interfaces"` key, which
 * is the per-interface online/RX/TX shape `DebugViewModel` and
 * `InterfaceManagementViewModel` cast back as `List<Map<String, Any>>`.
 * Before A.10 the UI ran the backend in-process, so this map never touched
 * the AIDL seam; A.10 promoted it to a cross-process call, which surfaced
 * the original lossy fallback (`value.toString()`) as a UI regression — the
 * "Network Interfaces (0)" / empty interface list symptom.
 *
 * Other unrecognized value types still fall through to `toString()` — lossy
 * by design, since the consumer of debug-info is a developer-facing read-only
 * view and the shapes we actively need are enumerated above.
 */
internal fun Map<String, Any>.toBundle(): Bundle {
    val bundle = Bundle()
    for ((key, value) in this) {
        bundle.putBundlingValue(key, value)
    }
    return bundle
}

private fun Bundle.putBundlingValue(key: String, value: Any) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Double -> putDouble(key, value)
        is String -> putString(key, value)
        is ByteArray -> putByteArray(key, value)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            putBundle(key, (value as Map<String, Any>).toBundle())
        }
        is List<*> -> {
            // Empty list: drop the entry so the reader's `as? List<Map>`
            // returns the same empty result via the absent-key path.
            if (value.isEmpty()) return
            val first = value.first()
            if (first is Map<*, *>) {
                val bundles = ArrayList<Bundle>(value.size)
                for (item in value) {
                    @Suppress("UNCHECKED_CAST")
                    bundles.add((item as Map<String, Any>).toBundle())
                }
                putParcelableArrayList(key, bundles)
            } else {
                // List<primitive> isn't a shape the diagnostics screens
                // currently consume — fall back to lossy stringification.
                putString(key, value.toString())
            }
        }
        else -> putString(key, value.toString())
    }
}

/**
 * Inverse of [toBundle] — read every key out of [bundle] (skipping the
 * convention keys [skip]) and return a `Map<String, Any>`. Nested
 * `Bundle` values are recursively decoded back into `Map<String, Any>`,
 * and `ArrayList<Bundle>` values back into `List<Map<String, Any>>` so the
 * shape `DebugViewModel` / `InterfaceManagementViewModel` cast against
 * (`as? List<Map<String, Any>>`) succeeds round-trip.
 */
internal fun Bundle.toAnyMap(skip: Set<String> = emptySet()): Map<String, Any> {
    val out = LinkedHashMap<String, Any>(size())
    for (key in keySet()) {
        if (key in skip) continue
        @Suppress("DEPRECATION")
        val value = get(key) ?: continue
        out[key] = when (value) {
            is Bundle -> value.toAnyMap()
            is ArrayList<*> -> {
                // The only ArrayList shape `toBundle` writes is the
                // List<Bundle> path for nested Maps. Decode symmetrically.
                if (value.isNotEmpty() && value.first() is Bundle) {
                    value.map { (it as Bundle).toAnyMap() }
                } else {
                    value
                }
            }
            else -> value
        }
    }
    return out
}
