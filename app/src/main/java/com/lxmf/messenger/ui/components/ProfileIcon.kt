package com.lxmf.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import com.lxmf.messenger.R
import com.lxmf.messenger.ui.theme.MaterialDesignIcons

/**
 * Material Design Icons font family.
 * Uses the Pictogrammers MDI font for full icon coverage (7000+ icons).
 */
private val MdiFont = FontFamily(Font(R.font.materialdesignicons))

/**
 * Displays a profile icon with custom colors, or falls back to an identicon.
 * Used for peer avatars throughout the app.
 *
 * Interoperable with Sideband and MeshChat profile icons - uses the same
 * Pictogrammers Material Design Icons font for identical rendering.
 *
 * @param iconName Material Design Icon name (e.g., "account", "star", "radio")
 * @param foregroundColor Hex RGB color for the icon (e.g., "FFFFFF")
 * @param backgroundColor Hex RGB color for the background (e.g., "1E88E5")
 * @param size Size of the icon in Dp
 * @param fallbackHash ByteArray hash for identicon fallback (typically public key)
 * @param modifier Modifier for the composable
 */
@Composable
fun ProfileIcon(
    iconName: String?,
    foregroundColor: String?,
    backgroundColor: String?,
    size: Dp,
    fallbackHash: ByteArray,
    modifier: Modifier = Modifier,
) {
    if (iconName != null && foregroundColor != null && backgroundColor != null) {
        // Get the icon codepoint - only render as MDI icon if we have a valid codepoint
        val codepoint = MaterialDesignIcons.getCodepointOrNull(iconName)

        if (codepoint != null) {
            // Intentionally swallow parse exceptions - fall back to default colors for invalid hex values
            @Suppress("SwallowedException")
            val fgColor = try {
                Color(android.graphics.Color.parseColor("#$foregroundColor"))
            } catch (e: IllegalArgumentException) {
                Color.White
            }
            @Suppress("SwallowedException")
            val bgColor = try {
                Color(android.graphics.Color.parseColor("#$backgroundColor"))
            } catch (e: IllegalArgumentException) {
                Color.Gray
            }

            // Calculate font size - icon should be about 60% of the container
            val density = LocalDensity.current
            val fontSize = with(density) { (size * 0.6f).toSp() }

            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = codepoint,
                    fontFamily = MdiFont,
                    fontSize = fontSize,
                    color = fgColor,
                )
            }
        } else {
            // Icon name not found in MDI library - fall back to identicon
            Identicon(hash = fallbackHash, size = size, modifier = modifier)
        }
    } else {
        Identicon(hash = fallbackHash, size = size, modifier = modifier)
    }
}
