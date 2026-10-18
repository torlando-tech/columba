package network.columba.app.viewmodel

import network.columba.app.R
import java.io.InputStream

/**
 * Raised when an imported identity file fails structural validation. Carries
 * the localized message as a [UiMessage] resource so render sites resolve it
 * without string interpolation.
 */
class IdentityFileInvalidException(
    val uiMessage: UiMessage,
    cause: Throwable? = null,
) : Exception(cause)

internal object IdentityFileReader {
    const val IDENTITY_BYTES = 64
    private const val PROBE_BYTES = IDENTITY_BYTES + 1

    fun read(input: InputStream): Result<ByteArray> =
        runCatching {
            val probe = ByteArray(PROBE_BYTES)
            var count = 0
            while (count < probe.size) {
                val read = input.read(probe, count, probe.size - count)
                if (read <= 0) break
                count += read
            }
            if (count > IDENTITY_BYTES) {
                throw IdentityFileInvalidException(UiMessage.Text(R.string.identity_file_error_too_large))
            }
            if (count != IDENTITY_BYTES) {
                throw IdentityFileInvalidException(
                    UiMessage.Text(R.string.identity_file_error_wrong_size, listOf(count)),
                )
            }
            probe.copyOf(IDENTITY_BYTES)
        }
}
