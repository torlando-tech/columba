package network.columba.app.micron

/**
 * Heading styles matching NomadNet's STYLES_DARK and STYLES_LIGHT tables.
 * Each heading level has its own foreground/background colors.
 */
object MicronTheme {
    data class HeadingStyle(
        val foreground: MicronColor,
        val background: MicronColor,
    )

    private val DARK_HEADINGS =
        mapOf(
            1 to
                HeadingStyle(
                    foreground = MicronColor.Hex(0x22, 0x22, 0x22),
                    background = MicronColor.Hex(0xBB, 0xBB, 0xBB),
                ),
            2 to
                HeadingStyle(
                    foreground = MicronColor.Hex(0x11, 0x11, 0x11),
                    background = MicronColor.Hex(0x99, 0x99, 0x99),
                ),
            3 to
                HeadingStyle(
                    foreground = MicronColor.Hex(0x00, 0x00, 0x00),
                    background = MicronColor.Hex(0x77, 0x77, 0x77),
                ),
        )

    private val LIGHT_HEADINGS =
        mapOf(
            1 to
                HeadingStyle(
                    foreground = MicronColor.Hex(0x00, 0x00, 0x00),
                    background = MicronColor.Hex(0x77, 0x77, 0x77),
                ),
            2 to
                HeadingStyle(
                    foreground = MicronColor.Hex(0x11, 0x11, 0x11),
                    background = MicronColor.Hex(0xAA, 0xAA, 0xAA),
                ),
            3 to
                HeadingStyle(
                    foreground = MicronColor.Hex(0x22, 0x22, 0x22),
                    background = MicronColor.Hex(0xCC, 0xCC, 0xCC),
                ),
        )

    fun headingStyle(
        level: Int,
        isDark: Boolean,
    ): HeadingStyle {
        val styles = if (isDark) DARK_HEADINGS else LIGHT_HEADINGS
        return styles[level.coerceIn(1, 3)]!!
    }

    fun defaultForeground(isDark: Boolean): MicronColor =
        if (isDark) {
            MicronColor.Hex(0xDD, 0xDD, 0xDD)
        } else {
            MicronColor.Hex(0x22, 0x22, 0x22)
        }
}
