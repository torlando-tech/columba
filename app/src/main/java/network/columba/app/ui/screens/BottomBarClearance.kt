package network.columba.app.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Height of the app's bottom [androidx.compose.material3.NavigationBar]
 * overlaying tab screens, excluding the system navigation-bar inset that
 * screen content already reserves via its Scaffold padding.
 *
 * Scrollable lists on tab screens pass this as bottom `contentPadding`: the
 * list stays flush under the bar (the bar owns its inset, see 302e446e7)
 * while the last item still scrolls fully clear of the overlay.
 */
@Composable
internal fun bottomBarClearance(): Dp {
    val density = LocalDensity.current
    val barAndSystemPx = NavigationBarDefaults.windowInsets.getBottom(density)
    val systemPx = WindowInsets.systemBars.getBottom(density)
    return with(density) { (barAndSystemPx - systemPx).coerceAtLeast(0).toDp() }
}
