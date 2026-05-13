package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Voice call state from LXST.
 */
@Parcelize
data class VoiceCallState(
    val status: String,
    val isActive: Boolean,
    val isMuted: Boolean,
    val remoteIdentity: String?,
    val profile: String?,
) : Parcelable
