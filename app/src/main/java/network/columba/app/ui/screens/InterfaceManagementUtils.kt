package network.columba.app.ui.screens

import android.bluetooth.BluetoothAdapter
import android.util.Log
import network.columba.app.data.database.entity.InterfaceEntity
import network.columba.app.reticulum.model.NetworkRestriction
import network.columba.app.service.manager.CurrentTransport
import org.json.JSONException
import org.json.JSONObject

// Interface Management UI helper utilities.

/**
 * Check if this interface requires Bluetooth to operate.
 * - AndroidBLE interfaces always require Bluetooth
 * - RNode interfaces require Bluetooth ONLY for classic or ble connection modes
 *   (TCP and USB modes do not require Bluetooth)
 */
@Suppress("SwallowedException")
fun InterfaceEntity.isBleInterface(): Boolean {
    return when (type) {
        "AndroidBLE" -> true
        "RNode" -> {
            try {
                val json = JSONObject(configJson)
                val connectionMode = json.optString("connection_mode", "classic")
                // Only classic and ble modes require Bluetooth
                connectionMode == "classic" || connectionMode == "ble"
            } catch (e: JSONException) {
                // Malformed JSON defaults to requiring Bluetooth (conservative fallback)
                true
            }
        }
        else -> false
    }
}

/**
 * Determine if this BLE interface can currently operate.
 *
 * @param bluetoothOn Whether Bluetooth is turned on
 * @param permissionsGranted Whether BLE permissions are granted
 * @return true if the interface can operate, false otherwise
 */
fun InterfaceEntity.canOperate(
    bluetoothOn: Boolean,
    permissionsGranted: Boolean,
): Boolean {
    if (!isBleInterface()) {
        // Non-BLE interfaces can always operate
        return true
    }

    // BLE interfaces require both Bluetooth on AND permissions granted
    return bluetoothOn && permissionsGranted
}

/**
 * Get an error message explaining why this interface has an issue.
 *
 * @param bluetoothState Current Bluetooth adapter state
 * @param permissionsGranted Whether BLE permissions are granted
 * @param isOnline Whether the interface is currently online (from debug info)
 * @return Error message string, or null if no error
 */
@Suppress("ReturnCount")
fun InterfaceEntity.getErrorMessage(
    bluetoothState: Int,
    permissionsGranted: Boolean,
    isOnline: Boolean? = null,
): String? {
    // Check BLE-related errors for BLE interfaces
    if (isBleInterface()) {
        when {
            bluetoothState != BluetoothAdapter.STATE_ON -> return "Bluetooth Off"
            !permissionsGranted -> return "Permission Required"
        }
    }

    // Check online status for all enabled interfaces
    if (enabled && isOnline == false) {
        return "Interface Offline"
    }

    return null
}

/**
 * Check if Bluetooth is currently in the ON state.
 */
fun Int.isBluetoothOn(): Boolean {
    return this == BluetoothAdapter.STATE_ON
}

/**
 * Determine if the toggle should be enabled for this interface.
 *
 * @param bluetoothState Current Bluetooth adapter state
 * @param permissionsGranted Whether BLE permissions are granted
 * @return true if toggle should be enabled, false if it should be disabled/greyed
 */
fun InterfaceEntity.shouldToggleBeEnabled(
    bluetoothState: Int,
    permissionsGranted: Boolean,
): Boolean {
    val isBle = isBleInterface()
    val btOn = bluetoothState.isBluetoothOn()
    val result =
        if (!isBle) {
            // Non-BLE interfaces can always be toggled
            true
        } else {
            // BLE toggle is enabled only when BT is on AND permissions granted
            btOn && permissionsGranted
        }

    Log.d(
        "InterfaceUtils",
        "shouldToggleBeEnabled(${this.name}): isBLE=$isBle, btState=$bluetoothState, btOn=$btOn, permissions=$permissionsGranted, result=$result",
    )
    return result
}

/**
 * The visible network-restriction state of an interface card given the device's current
 * transport. Card render reads this and either hides the chip ([NotApplicable]) or shows
 * one of three variants — the chip plus the right-column status text follow from the
 * variant, so the rendering site stays branch-free.
 *
 * Kept colocated with [restrictionView] so the parse/decision logic and its result type
 * live in one file.
 */
sealed interface InterfaceRestrictionView {
    /** Restriction does not apply — non-IP interface, or interface is ANY. Hide chip. */
    data object NotApplicable : InterfaceRestrictionView

    /** Restriction applies and the current transport matches — chip rendered in info tone. */
    data class Allowed(val restriction: NetworkRestriction) : InterfaceRestrictionView

    /**
     * Restriction applies and current transport excludes this interface — chip rendered in
     * tertiary tone (informational, not error — the user configured this), status text
     * reads "Restricted" instead of "Offline".
     */
    data class Blocked(val restriction: NetworkRestriction) : InterfaceRestrictionView

    /** No active default network — IP interfaces are inactive regardless of restriction. */
    data class NoNetwork(val restriction: NetworkRestriction) : InterfaceRestrictionView
}

/**
 * Decide how to surface this entity's network restriction in the card given the device's
 * current transport. Mirrors the truth table inside `InterfaceTransportFilter` so the UI
 * never claims a state inconsistent with what the runtime filter actually does — the
 * `entityRidesOnIpCarrier_truthTable_matchesInterfaceTransportFilter` test pins drift.
 */
fun InterfaceEntity.restrictionView(currentTransport: CurrentTransport): InterfaceRestrictionView {
    if (!entityRidesOnIpCarrier(this)) return InterfaceRestrictionView.NotApplicable
    val restriction = parseRestrictionForEntity(this)
    return when {
        restriction == NetworkRestriction.ANY -> InterfaceRestrictionView.NotApplicable
        currentTransport == CurrentTransport.NONE -> InterfaceRestrictionView.NoNetwork(restriction)
        restrictionMatchesTransport(restriction, currentTransport) -> InterfaceRestrictionView.Allowed(restriction)
        else -> InterfaceRestrictionView.Blocked(restriction)
    }
}

private fun restrictionMatchesTransport(
    restriction: NetworkRestriction,
    currentTransport: CurrentTransport,
): Boolean =
    when (restriction) {
        NetworkRestriction.WIFI_ONLY -> currentTransport == CurrentTransport.WIFI_LIKE
        NetworkRestriction.CELLULAR_ONLY -> currentTransport == CurrentTransport.CELLULAR
        // ANY is handled by `restrictionView` before reaching here; kept exhaustive
        // so adding a new NetworkRestriction value forces an update at this site.
        NetworkRestriction.ANY -> true
    }

/**
 * Mirror of `InterfaceTransportFilter.ridesOnIpCarrier`, keyed off the entity's raw
 * `(type, connection_mode)` rather than a parsed `InterfaceConfig`. The card path doesn't
 * have a config, only the entity, so we duplicate the predicate and pin both with the
 * `entityRidesOnIpCarrier_truthTable_matchesInterfaceTransportFilter` test to catch
 * drift if a new interface type is added.
 */
internal fun entityRidesOnIpCarrier(entity: InterfaceEntity): Boolean =
    when (entity.type) {
        "AutoInterface", "TCPClient", "TCPServer", "UDP" -> true
        "AndroidBLE" -> false
        "RNode" -> readConnectionMode(entity.configJson) == "tcp"
        else -> false
    }

/**
 * Pull the persisted `network_restriction` off the entity's JSON, applying the same
 * type-aware default as `InterfaceRepository.parseRestriction` so legacy rows (saved
 * before the restriction column existed) render the chip the runtime filter actually
 * applies.
 */
internal fun parseRestrictionForEntity(entity: InterfaceEntity): NetworkRestriction {
    val defaultForType =
        if (entity.type == "AutoInterface") NetworkRestriction.WIFI_ONLY else NetworkRestriction.ANY
    val raw =
        try {
            JSONObject(entity.configJson).optString("network_restriction", "")
        } catch (e: JSONException) {
            Log.v("InterfaceUtils", "Malformed configJson for ${entity.name}", e)
            ""
        }
    if (raw.isEmpty()) return defaultForType
    return NetworkRestriction.fromValue(raw) ?: defaultForType
}

private fun readConnectionMode(configJson: String): String? =
    try {
        JSONObject(configJson).optString("connection_mode", "")
            .takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        Log.v("InterfaceUtils", "Malformed configJson while reading connection_mode", e)
        null
    }
