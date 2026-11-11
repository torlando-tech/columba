package network.columba.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Height of the app's bottom Material 3 [androidx.compose.material3.NavigationBar]
 * overlaying tab screens, including the system navigation-bar strip the bar
 * paints over.
 *
 * Scrollable content passes this as bottom `contentPadding` (or trailing
 * scroll-inner padding): content stays flush under the bar while the last
 * item still scrolls fully clear of the overlay.
 *
 * The bar's footprint is its 80.dp container height (NavigationBarTokens
 * value; material3 1.4.0 exposes no public constant) plus the raw system
 * navigation-bar inset. The inset is read from the view's root window
 * insets via [ViewCompat] (available on every supported API level) rather
 * than through Compose `WindowInsets`, because the host Scaffold consumes
 * the system-bar insets for its content — a Compose-side read returns 0
 * inside screens and made every list end ~135px behind the bar (the
 * recurring occlusion bug; the previous implementation subtracted
 * `NavigationBarDefaults.windowInsets` from `systemBars`, which in
 * material3 1.4.0 are the same insets, so the difference was always zero).
 */
@Composable
internal fun bottomBarClearance(): Dp {
    val density = LocalDensity.current
    val systemNavPx = ViewCompat.getRootWindowInsets(LocalView.current)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
        ?: 0
    return with(density) { (80.dp.toPx().toInt() + systemNavPx).toDp() }
}
