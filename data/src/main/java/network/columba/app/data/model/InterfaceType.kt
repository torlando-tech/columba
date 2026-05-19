package network.columba.app.data.model

/**
 * Canonical classifier for the transport an RNS interface runs over.
 *
 * Sole source of truth for "what kind of interface is this?" decisions
 * anywhere in the codebase — UI categorization, announce filtering, the
 * RNode-specific UI affordance check, etc. — replace ad-hoc
 * `name.contains("RNode")`-style matching with [fromInterfaceName] so a
 * single grep updates every consumer when a new interface variant lands
 * upstream.
 *
 * Identifiers are the five operator-facing transports the user configures
 * in Settings → Interface Management:
 *
 * - [AUTO]       — `AutoInterface` (LAN multicast discovery) and its
 *                  per-peer `AutoInterfacePeer` children.
 * - [TCP_CLIENT] — `TCPClientInterface`, `TCPInterface[host:port]`,
 *                  Backbone, and any future TCP-client variant.
 * - [TCP_SERVER] — `TCPServerInterface` (listen-side, distinct because
 *                  routing semantics + UI affordances differ from client).
 * - [BLE]        — `AndroidBLE` (in-app Reticulum-over-BLE), legacy
 *                  `Bluetooth` strings.
 * - [RNODE]      — `RNodeInterface`, `RNodeMultiInterface`,
 *                  `ColumbaRNodeInterface` (BLE-attached RNode hardware).
 *
 * [displayLabel] is the short string shown in chips / pills; [storageName]
 * is what gets persisted to the `announces.receivingInterfaceType` column.
 * Backwards-compat: [fromName] also recognises the legacy stored values
 * (`"AUTO_INTERFACE"`, `"ANDROID_BLE"`) so pre-rename rows still classify
 * correctly without a DB migration.
 */
enum class InterfaceType(
    val displayLabel: String,
    val storageName: String,
) {
    AUTO("Local", "AUTO"),
    TCP_CLIENT("TCP", "TCP_CLIENT"),
    TCP_SERVER("TCP Server", "TCP_SERVER"),
    BLE("BLE", "BLE"),
    RNODE("RNode", "RNODE"),
    UNKNOWN("Unknown", "UNKNOWN"),
    ;

    companion object {
        /**
         * Classify a raw interface display string (as seen on
         * `announces.receivingInterface`, `messages.sentInterface`,
         * `LXMessage.method`-side debug logs, etc.) into one of the five
         * canonical transports.
         *
         * Observed inputs include:
         *   - `"AutoInterface[Local]"`, `"AutoInterface[fe80::…]"`,
         *     `"AutoInterfacePeer[wlan0/fe80::…]"`, `"Auto Discovery"`
         *   - `"TCPClientInterface[192.168.1.100:4965]"`,
         *     `"TCPInterface[host/ip:port]"`, `"Backbone…"`
         *   - `"TCPServerInterface[0.0.0.0:4242]"`
         *   - `"AndroidBLE"`, `"BLE"`, `"Bluetooth…"`
         *   - `"RNodeInterface[My Radio]"`, `"RNodeMultiInterface[…]"`,
         *     `"ColumbaRNodeInterface[…]"`
         *
         * Returns [UNKNOWN] for null, blank, `"None"`, or anything else.
         */
        fun fromName(rawName: String?): InterfaceType {
            val raw = rawName?.takeUnless { it.isBlank() || it == "None" } ?: return UNKNOWN
            // Exact-match legacy storage values first (cheap, deterministic)
            // so the parser stays correct against pre-rename DB rows.
            return EXACT_STORAGE_NAMES[raw] ?: classifyByPattern(raw.lowercase())
        }

        // Exact-match storage names. Kept as a Map so the classification path
        // stays a single Map lookup before falling through to substring matching.
        private val EXACT_STORAGE_NAMES =
            mapOf(
                "AUTO_INTERFACE" to AUTO,
                "AUTO" to AUTO,
                "TCP_CLIENT" to TCP_CLIENT,
                "TCP_SERVER" to TCP_SERVER,
                "ANDROID_BLE" to BLE,
                "BLE" to BLE,
                "RNODE" to RNODE,
                "UNKNOWN" to UNKNOWN,
            )

        // Order matters — "tcpserver" must be checked before the looser "tcp*"
        // patterns. "rnode" must be checked before BLE-keyword fallback because
        // the BLE-attached RNode driver string is `ColumbaRNodeInterface` which
        // contains neither "BLE" nor "Bluetooth". KISSInterface is RNode's
        // serial wire framing; "lora" / "weave" cover legacy and downstream
        // LoRa interface variants that all bottom out at RNode hardware.
        private fun classifyByPattern(name: String): InterfaceType =
            when {
                name.contains("autointerface") ||
                    name.contains("autointerfacepeer") ||
                    name.contains("auto discovery") -> AUTO
                name.contains("rnode") ||
                    name.contains("kiss") ||
                    name.contains("lora") ||
                    name.contains("weave") -> RNODE
                name.contains("tcpserver") -> TCP_SERVER
                name.contains("tcpclient") ||
                    name.contains("tcpinterface") ||
                    name.contains("backbone") -> TCP_CLIENT
                name.contains("androidble") ||
                    name.contains("ble") ||
                    name.contains("bluetooth") -> BLE
                else -> UNKNOWN
            }

        /**
         * Legacy alias: pre-existing call sites used [fromInterfaceName].
         * Kept as a thin delegate so renaming everywhere isn't required for
         * the canonical-enum migration. Prefer [fromName] in new code.
         */
        @Deprecated(
            "Use fromName for parity with the new naming.",
            ReplaceWith("fromName(interfaceName)"),
        )
        fun fromInterfaceName(interfaceName: String?): InterfaceType = fromName(interfaceName)
    }
}
