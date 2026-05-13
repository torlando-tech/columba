package network.columba.app.rns.api.model

/**
 * Icon appearance data sent with messages (LXMF Field 4 — Sideband/MeshChat interop).
 */
data class IconAppearance(
    val iconName: String,
    // Hex RGB e.g. "FFFFFF".
    val foregroundColor: String,
    // Hex RGB e.g. "1E88E5".
    val backgroundColor: String,
)
