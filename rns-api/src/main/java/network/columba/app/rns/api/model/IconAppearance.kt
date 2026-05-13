package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Icon appearance data sent with messages (LXMF Field 4 — Sideband/MeshChat interop).
 */
@Parcelize
data class IconAppearance(
    val iconName: String,
    // Hex RGB e.g. "FFFFFF".
    val foregroundColor: String,
    // Hex RGB e.g. "1E88E5".
    val backgroundColor: String,
) : Parcelable
