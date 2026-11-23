package network.columba.app.rns.api.model

/**
 * An output device the backend can route call audio to.
 *
 * Mirrors LXST-kt's `CallAudioDevice` across the process seam, with the
 * platform's current route folded in so a picker can preselect it.
 *
 * @param deviceId `AudioDeviceInfo.id` on Android 12+; below Android 12 the
 *   builtin [type] doubles as the id because the platform exposes no
 *   selectable-device ids.
 * @param type `AudioDeviceInfo.TYPE_*` of the device. Builtin earpiece and
 *   speaker rows should be labeled from app resources rather than [name],
 *   which is the platform product name.
 * @param name Platform product name (e.g. the Bluetooth headset's name).
 * @param isActive True for the device call audio is currently routed to.
 */
data class CallAudioRoute(
    val deviceId: Int,
    val type: Int,
    val name: String,
    val isActive: Boolean,
)
