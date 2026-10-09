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
 * INTERNAL is currently unrepresentable: the pinned reticulum-kt [ReticulumMode]
 * has no INTERNAL value, and its stray [ReticulumMode.POINT_TO_POINT] corresponds
 * to no RNS config mode string. Per #1169 it must stay a no-op until #91
 * reconciles the enum with the Python reference vocabulary - mapping it to
 * POINT_TO_POINT (or FULL) would change announce re-broadcast behavior without
 * conformance proof.
 */
internal fun mapInterfaceMode(configName: String, modeString: String): ReticulumMode? {
    val mapped =
        when (ColumbaMode.fromValue(modeString)) {
            ColumbaMode.FULL -> ReticulumMode.FULL
            ColumbaMode.GATEWAY -> ReticulumMode.GATEWAY
            ColumbaMode.ACCESS_POINT -> ReticulumMode.ACCESS_POINT
            ColumbaMode.ROAMING -> ReticulumMode.ROAMING
            ColumbaMode.BOUNDARY -> ReticulumMode.BOUNDARY
            ColumbaMode.INTERNAL -> null // unrepresentable until reticulum-kt #91
            null -> {
                Log.w(TAG, "Interface $configName: unknown mode '$modeString', leaving default")
                null
            }
        }
    if (mapped == null && ColumbaMode.fromValue(modeString) == ColumbaMode.INTERNAL) {
        Log.w(
            TAG,
            "Interface $configName: mode '$modeString' not supported by pinned reticulum-kt " +
                "(no INTERNAL in InterfaceMode, pending #91); leaving interface at default mode",
        )
    }
    return mapped
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
