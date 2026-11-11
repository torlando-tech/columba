package network.columba.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * navigation-bar inset. The inset is read from the view's
 * [android.view.WindowInsets] directly rather than through Compose
 * `WindowInsets`, because the host Scaffold consumes the system-bar insets
 * for its content — a Compose-side read returns 0 inside screens and made
 * every list end ~135px behind the bar (the recurring occlusion bug; the
 * previous implementation subtracted `NavigationBarDefaults.windowInsets`,
 * which in material3 1.4.0 is *only* the system bars, so the difference was
 * always zero).
 */
@Composable
internal fun bottomBarClearance(): Dp {
    val density = LocalDensity.current
    val systemNavPx = LocalView.current.rootWindowInsets
        ?.let { it.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom }
        ?: 0
    return with(density) { (80.dp.toPx().toInt() + systemNavPx).toDp() }
}
