package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.columba.app.R
import network.columba.app.ui.theme.MaterialDesignIcons

/**
 * Material Design Icons font family for RNode battery glyphs.
 *
 * The Pictogrammers MDI font is already bundled (see [ProfileIcon]); reusing the
 * same family keeps the battery glyph pixel-identical everywhere it appears.
 */
private val MdiFont = FontFamily(Font(R.font.materialdesignicons))

/**
 * Pick a Material Design Icons battery glyph for a 0-100 percent level:
 * alert at <=15%, full at >=95%, otherwise the nearest 10% step (rounded up).
 *
 * Single source of truth for the glyph choice so the interface-list card and the
 * interface-stats Status card render the same battery level identically.
 */
private fun batteryIconName(percent: Int): String =
    when {
        percent <= 15 -> "battery-alert"
        percent >= 95 -> "battery"
        else -> "battery-${(percent / 10 + if (percent % 10 >= 5) 1 else 0).coerceIn(1, 10) * 10}"
    }

/**
 * Compact RNode battery indicator: an MDI battery glyph (sized to the level) plus
 * the percentage, e.g. "▮ 82%".
 *
 * Rendered only when the caller has a live reading (the backend reports `null` /
 * the -1 sentinel when the RNode is offline or no battery frame has been received
 * yet, so callers simply do not compose this when the value is absent).
 *
 * @param percent Live battery level, 0-100.
 * @param fontSize Size of the battery glyph; the label scales with it.
 * @param textStyle Style for the percentage label. Defaults to bodyMedium
 *   (matching the stats card); pass a smaller style for dense layouts.
 */
@Composable
fun RNodeBatteryIndicator(
    percent: Int,
    fontSize: TextUnit = 16.sp,
    textStyle: TextStyle? = null,
    modifier: Modifier = Modifier,
) {
    val codepoint = MaterialDesignIcons.getCodepointOrNull(batteryIconName(percent))
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (codepoint != null) {
            Text(
                text = codepoint,
                fontFamily = MdiFont,
                fontSize = fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "$percent%",
            style = textStyle ?: MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
