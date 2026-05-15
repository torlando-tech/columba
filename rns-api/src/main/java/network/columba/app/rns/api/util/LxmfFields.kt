package network.columba.app.rns.api.util

/**
 * LXMF protocol field IDs Columba reads or writes.
 *
 * The numeric values are upstream LXMF spec (`LXMF/LXMF.py`). Centralised
 * here so both backends (kotlin-native + python-flavor) and the UI process
 * all reference one definition — previously these were declared three
 * separate times in `:rns-backend-py` (once each in `PythonEventBridge`,
 * `PythonRnsLxmf`, `PythonRnsTelemetry`) plus referenced via lxmf-kt's
 * `LXMFConstants` on the kotlin backend.
 *
 * Only fields Columba *actually uses* on the wire are listed; the full
 * LXMF surface (audio modes, propagation metadata IDs, states, ...) lives
 * in lxmf-kt's `LXMFConstants` for the native backend and isn't relevant
 * to the Python flavor.
 */
object LxmfFields {
    /**
     * LXMF app name for delivery destinations — matches
     * `LXMF.LXMRouter.APP_NAME` upstream and the kotlin port's
     * `LXMFConstants.APP_NAME`. Combined with [DELIVERY_ASPECT] this is the
     * `<identity>.lxmf.delivery` destination Columba peers send to.
     */
    const val APP_NAME = "lxmf"

    /** Local aspect for LXMF delivery destinations (`LXMRouter.DELIVERY_ASPECT`). */
    const val DELIVERY_ASPECT = "delivery"

    /** Single-shot telemetry payload (Sideband-compatible location JSON). */
    const val FIELD_TELEMETRY = 0x02

    /** Multi-entry telemetry stream — propagation collector responses. */
    const val FIELD_TELEMETRY_STREAM = 0x03

    /** `[name, fgRgbBytes, bgRgbBytes]` — Sideband/MeshChat icon appearance. */
    const val FIELD_ICON_APPEARANCE = 0x04

    /** Sideband-compatible file attachments. */
    const val FIELD_FILE_ATTACHMENTS = 0x05

    /** Image payload `[format, bytes]`. */
    const val FIELD_IMAGE = 0x06

    /** Audio payload `[mode, bytes]`. */
    const val FIELD_AUDIO = 0x07

    /** Command structures (Sideband telemetry-request RPCs). */
    const val FIELD_COMMANDS = 0x09

    /** Columba's custom reaction field (LXMF Field 16 = 0x10). */
    const val FIELD_REACTION = 0x10
}
