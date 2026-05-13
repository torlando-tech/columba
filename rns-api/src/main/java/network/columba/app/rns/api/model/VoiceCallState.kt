package network.columba.app.rns.api.model

/**
 * Voice call state from LXST.
 */
data class VoiceCallState(
    val status: String,
    val isActive: Boolean,
    val isMuted: Boolean,
    val remoteIdentity: String?,
    val profile: String?,
)
