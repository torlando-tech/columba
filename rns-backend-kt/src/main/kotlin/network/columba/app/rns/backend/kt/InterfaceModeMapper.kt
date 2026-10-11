package network.columba.app.rns.backend.kt

import android.util.Log
import network.columba.app.rns.api.model.InterfaceMode as ColumbaMode
import network.reticulum.common.InterfaceMode as ReticulumMode

private const val TAG = "InterfaceModeMapper"

/**
 * Maps a Columba interface-mode string to the reticulum-kt [ReticulumMode] that the
 * constructed interface object should report.
 *
 * Returns `null` for modes that the pinned reticulum-kt enum cannot faithfully
 * represent. A `null` result means "leave the interface at its declared default
 * and log a warning" - the caller must NOT coerce such a mode onto the object,
 * because coercing would silently change runtime behavior to unverified semantics
 * (see issue #1169 and its dependency on reticulum-kt #91).
 *
 * INTERNAL is representable from reticulum-kt v0.0.24 (reticulum-kt #91 landed:
 * the [ReticulumMode] enum gained an INTERNAL value with the RNS 1.3.6+ semi-
 * transport semantics - relays + path discovery, announce re-broadcast gated on
 * the next-hop mode). It maps 1:1 to [ReticulumMode.INTERNAL]. The stray
 * [ReticulumMode.POINT_TO_POINT] still corresponds to no RNS config mode string
 * and is intentionally not mapped.
 */
internal fun mapInterfaceMode(configName: String, modeString: String): ReticulumMode? {
    return when (ColumbaMode.fromValue(modeString)) {
        ColumbaMode.FULL -> ReticulumMode.FULL
        ColumbaMode.GATEWAY -> ReticulumMode.GATEWAY
        ColumbaMode.ACCESS_POINT -> ReticulumMode.ACCESS_POINT
        ColumbaMode.ROAMING -> ReticulumMode.ROAMING
        ColumbaMode.BOUNDARY -> ReticulumMode.BOUNDARY
        ColumbaMode.INTERNAL -> ReticulumMode.INTERNAL
        null -> {
            Log.w(TAG, "Interface $configName: unknown mode '$modeString', leaving default")
            null
        }
    }
}

/**
 * Returns [iface] with its [network.reticulum.interfaces.Interface.modeOverride] set to the
 * mapped reticulum-kt mode for [mode]. When [mapInterfaceMode] returns `null`
 * (INTERNAL on the pinned enum, or an unknown string) the interface is left at its
 * declared default - the mode is intentionally NOT coerced.
 *
 * A generic extension so every interface-construction site applies the mode as a
 * single `.withMode(config.name, config.mode)` call, keeping
 * [NativeInterfaceFactory.createInterface] within detekt's length/complexity
 * budgets. [name] and [mode] are taken explicitly (not the config object) because
 * [network.columba.app.rns.api.model.InterfaceConfig] declares `mode` only on its
 * concrete subclasses, not the base type.
 */
internal fun <T : network.reticulum.interfaces.Interface> T.withMode(name: String, mode: String): T =
    apply {
        mapInterfaceMode(name, mode)?.let { modeOverride = it }
    }
