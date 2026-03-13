package com.lxmf.messenger.micron

/**
 * Represents a color in Micron markup.
 *
 * Micron supports four color formats:
 * - 3-char hex: each digit is doubled (e.g., "ddd" → 0xDD, 0xDD, 0xDD)
 * - 6-char true color hex: full 24-bit RGB (e.g., "ff8800" → 0xFF, 0x88, 0x00)
 * - Grayscale: "gNN" where NN is 0-99 (0 = black, 99 = white)
 * - Default: inherit from theme
 */
sealed class MicronColor {
    data object Default : MicronColor()

    data class Hex(
        val r: Int,
        val g: Int,
        val b: Int,
    ) : MicronColor()

    data class Grayscale(
        val level: Int,
    ) : MicronColor()

    /**
     * Convert to ARGB integer (0xAARRGGBB format).
     * For [Default], returns null — caller should use theme color.
     */
    fun toArgb(): Int? =
        when (this) {
            is Default -> null
            is Hex -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            is Grayscale -> {
                // Map 0-99 to 0-255
                val gray = (level * 255) / 99
                (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }

    companion object {
        private const val HEX_RADIX = 16
        private const val HEX_DOUBLER = 0x11
        private const val MAX_GRAYSCALE = 99
        private const val TRUE_COLOR_LEN = 6

        /**
         * Parse a Micron color string (3 chars after F/B command).
         * - "ddd" → Hex(0xDD, 0xDD, 0xDD)
         * - "g50" → Grayscale(50)
         */
        @Suppress("ReturnCount")
        fun parse(colorStr: String): MicronColor? {
            if (colorStr.length < 3) return null

            // Grayscale: starts with 'g' followed by two digits
            if (colorStr[0] == 'g') {
                val level = colorStr.substring(1, 3).toIntOrNull() ?: return null
                return if (level in 0..MAX_GRAYSCALE) Grayscale(level) else null
            }

            // 3-char hex: each digit doubled
            val r = colorStr[0].digitToIntOrNull(HEX_RADIX) ?: return null
            val g = colorStr[1].digitToIntOrNull(HEX_RADIX) ?: return null
            val b = colorStr[2].digitToIntOrNull(HEX_RADIX) ?: return null

            return Hex(r * HEX_DOUBLER, g * HEX_DOUBLER, b * HEX_DOUBLER)
        }

        /**
         * Parse a 6-char true color hex string (after FT/BT command).
         * - "ff8800" → Hex(0xFF, 0x88, 0x00)
         */
        @Suppress("ReturnCount")
        fun parseTrueColor(colorStr: String): MicronColor? {
            if (colorStr.length < TRUE_COLOR_LEN) return null

            val rHi = colorStr[0].digitToIntOrNull(HEX_RADIX) ?: return null
            val rLo = colorStr[1].digitToIntOrNull(HEX_RADIX) ?: return null
            val gHi = colorStr[2].digitToIntOrNull(HEX_RADIX) ?: return null
            val gLo = colorStr[3].digitToIntOrNull(HEX_RADIX) ?: return null
            val bHi = colorStr[4].digitToIntOrNull(HEX_RADIX) ?: return null
            val bLo = colorStr[5].digitToIntOrNull(HEX_RADIX) ?: return null

            return Hex(
                (rHi shl 4) or rLo,
                (gHi shl 4) or gLo,
                (bHi shl 4) or bLo,
            )
        }
    }
}
