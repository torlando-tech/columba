package network.columba.app.detekt.rules

/**
 * Single source of truth for "which call arguments render user-facing text".
 *
 * Shared by [NoHardcodedUiString] (enforcement) and the sink-equivalence dumper
 * (`.scratch/i18n-string-extraction` ticket 23) so the rule and the before/after
 * proof can never drift apart.
 *
 * Derived from the exhaustive 2026-10-17 inventory
 * (`.scratch/i18n-string-extraction/inventory/`). When a new label-taking helper
 * composable is added, add it here — the equivalence gate and the rule both follow.
 */
object UiSinkTable {
    /**
     * Callee name -> positional value-argument indices that are user-facing text.
     * Matched syntactically (no type resolution), so same-named functions on other
     * receivers also fire; suppress genuine false positives with
     * `@Suppress("NoHardcodedUiString")` and a comment.
     */
    val positionalSinks: Map<String, Set<Int>> =
        mapOf(
            "Text" to setOf(0),
            "Icon" to setOf(1),
            "makeText" to setOf(1), // Toast.makeText(context, text, duration)
            "make" to setOf(1), // Snackbar.make(view, text, duration)
            "showSnackbar" to setOf(0), // SnackbarHostState.showSnackbar(message)
            "newPlainText" to setOf(0), // ClipData.newPlainText(label, text)
            "NotificationChannel" to setOf(1), // (id, name, importance)
            "setContentTitle" to setOf(0),
            "setContentText" to setOf(0),
            "setBigText" to setOf(0),
            "setSubText" to setOf(0),
            "setSummaryText" to setOf(0),
            "setContentInfo" to setOf(0),
            "setInfo" to setOf(0),
            // Project label-first helpers (composables + data classes). Verified to
            // exist via `fun <Name>(` sweep on 2026-10-17.
            "CollapsibleSettingsCard" to setOf(0),
            "ConnectionDetailRow" to setOf(0),
            "DetailRow" to setOf(0),
            "DeviceInfoRow" to setOf(0),
            "HashSection" to setOf(0),
            "InfoRow" to setOf(0),
            "InfoSection" to setOf(0),
            "InterfaceTypeOption" to setOf(0),
            "LinkButton" to setOf(0),
            "ParamChip" to setOf(0),
            "PermissionRow" to setOf(0),
            "PermissionStatusRow" to setOf(0),
            "PrivacyFeature" to setOf(0),
            "QualityOption" to setOf(0),
            "SettingChip" to setOf(0),
            "SettingsRow" to setOf(0),
            "StatBox" to setOf(0),
            "StatsInfoRow" to setOf(0),
            "SummaryItem" to setOf(0),
            "SummaryRow" to setOf(0),
            "TelemetryIntervalChip" to setOf(0),
        )

    /**
     * Named argument names that are user-facing text. Only honored when the callee
     * starts uppercase (Composable / constructor convention) or the callee is listed
     * in [lowercaseNamedSinkCallers] — this is what exempts `animate*AsState(label=)`
     * and `rememberInfiniteTransition(label=)` automatically.
     */
    val namedSinks: Set<String> =
        setOf(
            "text",
            "label",
            "title",
            "subtitle",
            "message",
            "contentDescription",
            "placeholder",
            "description",
            "secondaryDescription",
            "displayName",
            "headlineContent",
            "supportingContent",
            "overlineContent",
            "trailingContent",
            "rationale",
            "actionLabel",
            "stateDescription",
            "confirmButtonText",
            "onClickLabel",
            "onLongClickLabel",
            "primaryActionLabel",
        )

    /** Lowercase callees whose named args are still user-facing text. */
    val lowercaseNamedSinkCallers: Set<String> = setOf("showSnackbar")

    /**
     * Property names whose string assignment is user-facing text
     * (`semantics { contentDescription = "…" }`, `channel.description = "…"`).
     */
    val assignmentSinks: Set<String> = setOf("contentDescription", "stateDescription", "description")

    /**
     * Lowercase tokens that are technical units/protocol vocabulary, not English
     * prose. A literal whose only alphabetic tokens are in this set is treated as
     * format-only (e.g. `"$it dBm"`, `"#RRGGBB"`, `"host:port"`) and not reported.
     */
    val technicalTokens: Set<String> =
        setOf(
            "mhz", "khz", "ghz", "dbm", "kb", "mb", "gb", "tb", "mib", "kib", "gib",
            "sf", "tx", "rx", "http", "https", "www", "com", "org", "net",
            "usb", "ble", "tcp", "udp", "ip", "dns", "mac", "qr", "pin", "rst",
            "spi", "dfu", "apk", "mime", "json", "xml", "html", "css", "mtu",
            "ssid", "rgb", "hex", "rrggbb", "lxma", "host", "port", "id", "ok",
            "png", "jpg", "jpeg", "webp", "ogg", "opus", "codec2", "rns", "lxmf",
            "mpl", "api", "url", "uri", "pdf", "csv", "sql", "db", "ms", "cpu",
            "ram", "vm", "e", "g", "example", "zip",
        )
}
