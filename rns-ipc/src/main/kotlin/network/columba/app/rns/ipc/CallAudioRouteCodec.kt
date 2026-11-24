package network.columba.app.rns.ipc

import network.columba.app.rns.api.model.CallAudioRoute

/**
 * Wire format for [CallAudioRoute] over `IRnsTelephony`'s string-list AIDL
 * calls.
 *
 * One list entry per route: `deviceId<US>type<US>active<US>name`, where `<US>`
 * is the ASCII unit separator (0x1F). `AudioDeviceInfo.productName` is
 * printable text and cannot contain it, so the name needs no escaping; the
 * four-field split uses a limit so any stray separator stays in the name.
 */
object CallAudioRouteCodec {
    internal const val FIELD_SEPARATOR = '\u001F'
    internal const val ACTIVE_TRUE = "1"
    internal const val ACTIVE_FALSE = "0"

    fun encode(route: CallAudioRoute): String =
        listOf(
            route.deviceId.toString(),
            route.type.toString(),
            if (route.isActive) ACTIVE_TRUE else ACTIVE_FALSE,
            route.name,
        ).joinToString(FIELD_SEPARATOR.toString())

    fun encode(routes: List<CallAudioRoute>): List<String> = routes.map(::encode)

    fun decode(entries: List<String>): List<CallAudioRoute> =
        entries.mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR, limit = 4)
            if (fields.size != 4) return@mapNotNull null
            val deviceId = fields[0].toIntOrNull() ?: return@mapNotNull null
            val type = fields[1].toIntOrNull() ?: return@mapNotNull null
            CallAudioRoute(
                deviceId = deviceId,
                type = type,
                name = fields[3],
                isActive = fields[2] == ACTIVE_TRUE,
            )
        }
}
