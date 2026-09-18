package network.columba.app.viewmodel

import java.io.InputStream

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
            require(count == IDENTITY_BYTES) {
                if (count > IDENTITY_BYTES) {
                    "Invalid identity file: expected 64 bytes, file is larger than 64 bytes"
                } else {
                    "Invalid identity file: expected 64 bytes, got $count bytes"
                }
            }
            probe.copyOf(IDENTITY_BYTES)
        }
}
