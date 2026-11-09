package network.columba.app.domain.model

/** Recorded LXMF sender verification result. A null status means no result was recorded. */
enum class SignatureStatus {
    /** The sender's known identity validated the signature. */
    VERIFIED,

    /** The source identity is not known yet; the signature has not been checked. */
    SOURCE_UNKNOWN,

    /** Signature validation against a known source identity failed. */
    INVALID,

    /** Verification failed without a definitive reason, including exceptional failures. */
    UNVERIFIED,
}
