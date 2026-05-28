package network.columba.app.ui.components

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Ambient [WindowSizeClass] for the active window — width × height bucketed into
 * Compact / Medium / Expanded per Material 3 guidance.
 *
 * Provided once at the top of the UI tree in `MainActivity.setContent` from
 * `calculateWindowSizeClass(activity)`. Screens that need responsive layout
 * (collapse filters on compact-height landscape, list-detail panes on
 * expanded-width tablets, etc.) read this directly — no prop-drilling.
 *
 * Defaults to a phone-portrait shape (Compact width × Medium height) for
 * `@Preview`s and any subtree that somehow mounts before the provider; the
 * "compact phone" default is the conservative choice (single-pane, expanded
 * filters) so missing the provider degrades to the smallest layout rather
 * than over-promising tablet space.
 *
 * `compositionLocalOf` (not `staticCompositionLocalOf`): `setContent` and the
 * `CompositionLocalProvider` re-run whenever any sibling state changes
 * (e.g. capabilities, settingsState), and `calculateWindowSizeClass(this)`
 * returns a fresh object on each recomposition. `staticCompositionLocalOf`
 * does reference equality and would invalidate every consumer's subtree on
 * each sibling-state change, even when the underlying size class is
 * identical. `compositionLocalOf` uses structural equality on the
 * [WindowSizeClass] data class so consumers only recompose when the actual
 * width/height bucket changes (i.e. rotation / fold / resize).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
val LocalWindowSize =
    compositionLocalOf {
        WindowSizeClass.calculateFromSize(DpSize(360.dp, 800.dp))
    }
