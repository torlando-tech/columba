package network.columba.app.ui.theme

/**
 * Appearance mode: controls whether the light or dark variant of the
 * selected theme is used. SYSTEM follows the device; LIGHT/DARK force a
 * variant for users without a system dark mode or who want a pinned look.
 */
enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    /** Resolve the effective dark-mode flag for this mode. */
    fun resolveDark(isSystemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> isSystemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        /** Parse a stored identifier; null or unknown values fall back to SYSTEM. */
        fun fromIdentifier(identifier: String?): ThemeMode =
            entries.firstOrNull { it.name == identifier } ?: SYSTEM
    }
}
