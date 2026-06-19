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
     * Canonical tap-back reaction field — `fields[0x40] = {0x00: bytes, 0x01: bytes}`
     * per-event wire shape, standardised upstream in LXMF.py (commit
     * `764758d`, "to be finalized in 1.0.0"):
     *   - [REACTION_TO] (`0x00`) = raw bytes of the target `LXMessage.hash`.
     *   - [REACTION_CONTENT] (`0x01`) = UTF-8 bytes of the reaction content
     *     (the emoji). The sender is NOT carried on the wire — it is derived
     *     from the inbound LXMF message's source hash on receive.
     *
     * One reaction per LXMessage on the wire; the receiver aggregates
     * per-target-message locally (the flat `reactionsJson` column) for UI
     * rendering. Outbound writes this shape only; inbound parsing falls back
     * to the legacy [FIELD_REACTION_LEGACY] for un-upgraded Columba peers —
     * see `ReactionWireCodec`.
     */
    const val FIELD_REACTION = 0x40

    /** [FIELD_REACTION] dict key — raw bytes of the target `LXMessage.hash`. */
    const val REACTION_TO = 0x00

    /** [FIELD_REACTION] dict key — UTF-8 bytes of the reaction content (emoji). */
    const val REACTION_CONTENT = 0x01

    /**
     * Legacy tap-back reaction field — `fields[0x10] = {reaction_to, emoji, sender}`
     * string-keyed dict (hex-string target + sender, Unicode emoji), shared
     * historically with MeshChatX
     * (`src/backend/lxmf_utils.py:11 LXMF_APP_EXTENSIONS_FIELD = 16`).
     *
     * **Parse-only fallback.** Outbound no longer writes this — it was
     * superseded by the canonical [FIELD_REACTION] (`0x40`) once upstream
     * LXMF standardised the field. `0x10` also now sits inside upstream's
     * reserved `0x00`–`0x80` range, so squatting it risks a future
     * collision. Kept on the inbound path so reactions from un-upgraded
     * Columba peers still resolve — see `ReactionWireCodec`.
     */
    const val FIELD_REACTION_LEGACY = 0x10

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
     * Upstream LXMF `FIELD_CUSTOM_TYPE` (0xFB) — app-defined type tag that
     * names the opaque payload in [FIELD_CUSTOM_DATA]. Read-only here:
     * Columba uses it to recover reactor attribution for reactions relayed
     * through a re-originating group relay (e.g. reticulum-forwarding-service).
     * A relay re-signs each reaction as itself, so the carrying
     * `source_hash` is the relay, not the reactor; the relay stamps
     * `FIELD_CUSTOM_TYPE = "originator-identity"` +
     * `FIELD_CUSTOM_DATA = <reactor source_hash>` so cooperating clients
     * attribute the reaction to the real reactor. See `ReactionWireCodec`.
     */
    const val FIELD_CUSTOM_TYPE = 0xFB

    /**
     * Upstream LXMF `FIELD_CUSTOM_DATA` (0xFC) — app-defined opaque payload
     * whose meaning is given by [FIELD_CUSTOM_TYPE]. For the
     * `"originator-identity"` type this is the reactor's raw 16-byte
     * `source_hash` — its `lxmf.delivery` destination hash, the same value
     * a direct reaction carries and what contacts are keyed by (NOT the raw
     * identity hash, which would orphan the lookup). It arrives hex-encoded
     * in the serialized fields JSON. See `ReactionWireCodec`.
     */
    const val FIELD_CUSTOM_DATA = 0xFC

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
