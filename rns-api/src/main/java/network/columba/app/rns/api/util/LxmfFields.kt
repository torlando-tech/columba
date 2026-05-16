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

    /**
     * Tap-back reaction field — `fields[0x10] = {reaction_to, emoji, sender}`
     * per-event wire shape, shared with MeshChatX
     * (`src/backend/lxmf_utils.py:11 LXMF_APP_EXTENSIONS_FIELD = 16`).
     * `reaction_to` and `sender` are hex strings (no canonical case);
     * `emoji` is Unicode. One reaction per LXMessage on the wire;
     * receiver aggregates per-target-message locally for UI rendering.
     *
     * Upstream LXMF doesn't allocate this byte yet; both Columba and
     * MeshChatX work in the unallocated range. Spec discussion:
     * https://github.com/thatSFguy/reticulum-specifications/issues/8
     */
    const val FIELD_REACTION = 0x10

    /**
     * Reply-target message hash — `fields[0x30] = ByteArray(32)` raw
     * bytes (NOT a hex string). MeshChatX format
     * (`meshchat.py:16697`). Saves ~32 bytes on the wire per reply vs.
     * the hex-string-in-dict overload Columba previously used at 0x10.
     *
     * Inbound parse may also fall back to a legacy
     * `fields[0x10] = {reply_to: "<hex>"}` shape for un-upgraded
     * Columba peers — see `MessageMapper.parseReplyToFromFields`.
     */
    const val FIELD_REPLY_HASH = 0x30

    /**
     * Optional reply quoted-content — `fields[0x31] = ByteArray` UTF-8
     * bytes of the original message's content the sender saw. Lets the
     * recipient render the reply preview even when they don't have the
     * original message in their local store (cross-app interop case;
     * also covers OPP-only peers whose local store cycles). MeshChatX
     * format (`meshchat.py:16698`).
     */
    const val FIELD_REPLY_QUOTE = 0x31

    /**
     * Upstream LXMF `FIELD_CUSTOM_META` (0xFD) — documented extension point
     * for app-specific metadata that other LXMF clients should ignore.
     * Columba uses this to carry the `cease` / `expires` / `approxRadius`
     * extras that ride alongside a Sideband-compatible
     * [FIELD_TELEMETRY] location share. Sideband's `core.py` has zero
     * references to FIELD_CUSTOM_* — interop-safe.
     *
     * Previously this was a Columba-invented `0x70`; flipped to upstream's
     * canonical 0xFD because invented field IDs in the unassigned range
     * risk collision if upstream LXMF later assigns them. See also
     * [LocationTelemetry.COLUMBA_META_FIELD_ID].
     */
    const val FIELD_CUSTOM_META = 0xFD
}
