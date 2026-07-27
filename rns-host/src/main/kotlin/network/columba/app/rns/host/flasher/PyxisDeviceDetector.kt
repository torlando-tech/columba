package network.columba.app.rns.host.flasher

/** Identity returned by a running Pyxis firmware over its production USB VERSION command. */
data class PyxisDeviceIdentity(
    val version: String,
)

internal fun interface PyxisVersionQuery {
    suspend fun query(deviceId: Int): String?
}

/** Distinguishes a running Pyxis device from generic ESP32-S3 and RNode USB devices. */
internal class PyxisDeviceDetector(
    private val query: PyxisVersionQuery,
) {
    suspend fun detect(deviceId: Int): PyxisDeviceIdentity? = parseIdentity(query.query(deviceId))

    companion object {
        internal fun parseIdentity(transcript: String?): PyxisDeviceIdentity? {
            val response =
                transcript
                    ?.lineSequence()
                    ?.map(String::trim)
                    ?.firstOrNull { it.startsWith(RESPONSE_PREFIX) }
                    ?: return null
            val version = response.removePrefix(RESPONSE_PREFIX).trim()
            return version.takeIf(String::isNotEmpty)?.let(::PyxisDeviceIdentity)
        }

        private const val RESPONSE_PREFIX = "Pyxis v"
    }
}
