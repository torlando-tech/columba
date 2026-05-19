package network.columba.app.rns.api.util

/**
 * Hex string ↔ ByteArray extensions used everywhere a destination /
 * identity / packet hash crosses a boundary (logs, JSON keys, dict keys,
 * AIDL surface). Lowercase-hex is the canonical wire form across both
 * backends and the :app process.
 *
 * Centralised here because the toHex / hexToBytes pair was previously
 * declared five separate times (NativeRnsBackendImpl, NativeTelemetryHandler,
 * NativeNetworkTransport, PythonExt, and app/util/HexUtils with slightly
 * different naming) and inlined as `joinToString("") { "%02x".format(it) }`
 * in ~30 more call sites across `:rns-backend-kt` and `:rns-host`.
 */

/** ByteArray -> lowercase hex string. e.g. `byteArrayOf(0x01, 0xab.toByte()).toHex() == "01ab"`. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Hex string -> ByteArray. Caller is responsible for the hex being even-length
 * and well-formed; an odd-length string throws [IllegalArgumentException] and
 * a non-hex character throws [NumberFormatException]. Mixed case is accepted.
 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length: \"${take(32)}\" (length=$length)" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
