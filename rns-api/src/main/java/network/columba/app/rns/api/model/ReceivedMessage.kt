package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import network.columba.app.domain.model.SignatureStatus

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
    // Null is a legacy message with no recorded verification result.
    val signatureStatus: SignatureStatus? = null,
    // Original packed LXMF bytes, retained only for SOURCE_UNKNOWN so an
    // announce can later resolve the signature without reconstructing content.
    val signatureEvidence: ByteArray? = null,
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedMessage) return false
        return messageHash == other.messageHash &&
            content == other.content &&
            sourceHash.contentEquals(other.sourceHash) &&
            destinationHash.contentEquals(other.destinationHash) &&
            timestamp == other.timestamp &&
            fieldsJson == other.fieldsJson &&
            publicKey.contentEquals(other.publicKey) &&
            iconAppearance == other.iconAppearance &&
            receivedHopCount == other.receivedHopCount &&
            receivedInterface == other.receivedInterface &&
            receivedRssi == other.receivedRssi &&
            receivedSnr == other.receivedSnr &&
            deliveryMethod == other.deliveryMethod &&
            signatureStatus == other.signatureStatus &&
            signatureEvidence.contentEquals(other.signatureEvidence)
    }

    override fun hashCode(): Int {
        var result = messageHash.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + sourceHash.contentHashCode()
        result = 31 * result + destinationHash.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (fieldsJson?.hashCode() ?: 0)
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (iconAppearance?.hashCode() ?: 0)
        result = 31 * result + (receivedHopCount ?: 0)
        result = 31 * result + (receivedInterface?.hashCode() ?: 0)
        result = 31 * result + (receivedRssi ?: 0)
        result = 31 * result + (receivedSnr?.hashCode() ?: 0)
        result = 31 * result + (deliveryMethod?.hashCode() ?: 0)
        result = 31 * result + (signatureStatus?.hashCode() ?: 0)
        result = 31 * result + signatureEvidence.contentHashCode()
        return result
    }
}
