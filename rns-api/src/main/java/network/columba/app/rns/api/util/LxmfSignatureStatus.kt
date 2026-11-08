package network.columba.app.rns.api.util

import network.columba.app.domain.model.SignatureStatus

/** Interpret upstream LXMF's verification flag and unverified-reason wire values. */
fun lxmfSignatureStatus(
    signatureValidated: Boolean?,
    unverifiedReason: Int?,
): SignatureStatus =
    if (signatureValidated == true) {
        SignatureStatus.VERIFIED
    } else {
        when (unverifiedReason) {
            0x01 -> SignatureStatus.SOURCE_UNKNOWN // LXMessage.SOURCE_UNKNOWN
            0x02 -> SignatureStatus.INVALID // LXMessage.SIGNATURE_INVALID
            else -> SignatureStatus.UNVERIFIED
        }
    }
