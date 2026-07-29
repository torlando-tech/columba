package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A received LXMF message.
 */
@Parcelize
data class ReceivedMessage(
    val messageHash: String,
    val content: String,
    val sourceHash: ByteArray,
    val destinationHash: ByteArray,
    val timestamp: Long,
    // LXMF fields as JSON: {"6": "hex_image_data", "7": "hex_audio_data"}
    val fieldsJson: String? = null,
    // Sender's public key (if available from RNS identity cache).
    val publicKey: ByteArray? = null,
    // Sender's icon appearance (LXMF Field 4 — Sideband/MeshChat interop).
    val iconAppearance: IconAppearance? = null,
    // Received message routing info (hop count and receiving interface).
    val receivedHopCount: Int? = null,
    val receivedInterface: String? = null,
    // Signal quality metrics (from RNode/BLE — null for TCP/AutoInterface).
    val receivedRssi: Int? = null,
    val receivedSnr: Float? = null,
    // LXMF delivery method this message arrived via:
    //   "opportunistic" — single-packet, no link
    //   "direct"        — link-based with retries
    //   "propagated"    — fetched from a propagation node
    //   null            — pre-feature messages or backend couldn't determine
    // Mirrors the string vocabulary already used for outbound on
    // `MessageEntity.deliveryMethod` and `MessageDetailScreen.getDeliveryMethodInfo`.
    val deliveryMethod: String? = null,
    // Whether the LXMF signature was verified against the sender's known
    // identity at receive time. Surfaces LXMF-kt's `LXMessage.signatureValidated`
    // so the UI can warn on unverified senders.
    //   true  = signature checked against a known source identity, valid.
    //   false = the sender's signature could not be verified — the message is
    //           forgeable/untrusted and the UI warns. On the Kotlin backend
    //           this is always SOURCE_UNKNOWN (we don't yet hold the sender's
    //           identity; LXMF-kt drops SIGNATURE_INVALID at the router). On
    //           the Python backend it may additionally be a failed signature
    //           check, since python LXMF's `lxmf_delivery` delivers those too.
    //   null  = the backend couldn't determine it, or a pre-feature path.
    //           Treated as "no warning".
    val signatureVerified: Boolean? = null,
) : Parcelable
