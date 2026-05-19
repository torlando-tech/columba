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
) : Parcelable
