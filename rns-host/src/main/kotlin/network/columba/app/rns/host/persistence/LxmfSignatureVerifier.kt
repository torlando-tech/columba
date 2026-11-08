package network.columba.app.rns.host.persistence

import network.columba.app.domain.model.SignatureStatus
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.msgpack.core.MessagePack
import java.security.MessageDigest

/** Verifies retained LXMF bytes, never reconstructed/sanitized display content. */
internal object LxmfSignatureVerifier {
    private const val ADDRESS_BYTES = 16
    private const val SIGNATURE_OFFSET = 2 * ADDRESS_BYTES
    private const val PAYLOAD_OFFSET = SIGNATURE_OFFSET + 64

    fun senderHash(publicKey: ByteArray): String? {
        if (publicKey.size != 64) return null
        return Destination.hash(Hashes.truncatedHash(publicKey), "lxmf", "delivery").toHexString()
    }

    /** Null means unusable evidence or an unrelated key, not a failed signature check. */
    @Suppress("ReturnCount") // Reject unusable evidence before making any authenticity claim.
    fun verify(
        evidence: ByteArray,
        messageHash: String,
        senderHash: String,
        destinationHash: String,
        publicKey: ByteArray,
    ): SignatureStatus? {
        if (senderHash(publicKey) != senderHash || evidence.size <= PAYLOAD_OFFSET) return null
        if (evidence.copyOfRange(0, ADDRESS_BYTES).toHexString() != destinationHash) return null
        if (evidence.copyOfRange(ADDRESS_BYTES, SIGNATURE_OFFSET).toHexString() != senderHash) return null

        return runCatching {
            MessagePack.newDefaultUnpacker(evidence, PAYLOAD_OFFSET, evidence.size - PAYLOAD_OFFSET).use { unpacker ->
                val count = unpacker.unpackArrayHeader()
                if (count != 4 && count != 5) return null
                // Both LXMF implementations canonicalize the four signed
                // values only when stripping an optional stamp. Stampless
                // messages must retain their original MessagePack encodings.
                val canonicalPayload =
                    if (count == 5) {
                        MessagePack.newDefaultBufferPacker().use { packer ->
                            packer.packArrayHeader(4)
                            repeat(4) { packer.packValue(unpacker.unpackValue()) }
                            unpacker.skipValue()
                            packer.toByteArray()
                        }
                    } else {
                        repeat(4) { unpacker.skipValue() }
                        null
                    }
                if (unpacker.hasNext()) return null
                val payloadSize = canonicalPayload?.size ?: (evidence.size - PAYLOAD_OFFSET)
                val bodySize = SIGNATURE_OFFSET + payloadSize
                val signedPart = ByteArray(bodySize + 32)
                evidence.copyInto(signedPart, endIndex = SIGNATURE_OFFSET)
                if (canonicalPayload != null) {
                    canonicalPayload.copyInto(signedPart, destinationOffset = SIGNATURE_OFFSET)
                } else {
                    evidence.copyInto(signedPart, destinationOffset = SIGNATURE_OFFSET, startIndex = PAYLOAD_OFFSET)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(signedPart, 0, bodySize)
                val hash = digest.digest()
                if (hash.toHexString() != messageHash) return null
                hash.copyInto(signedPart, destinationOffset = bodySize)
                val signature = evidence.copyOfRange(SIGNATURE_OFFSET, PAYLOAD_OFFSET)
                if (Identity.fromPublicKey(publicKey).validate(signature, signedPart)) {
                    SignatureStatus.VERIFIED
                } else {
                    SignatureStatus.INVALID
                }
            }
        }.getOrNull()
    }
}
