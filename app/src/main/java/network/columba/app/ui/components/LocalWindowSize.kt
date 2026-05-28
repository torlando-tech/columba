package network.columba.app.ui.components

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.staticCompositionLocalOf
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
 * `staticCompositionLocalOf` (not `compositionLocalOf`): size-class changes
 * happen on rotation / fold / resize — events that already force a broad
 * recomposition. Skipping read-tracking saves overhead in the steady-state
 * recomposition cycles where the value never moves.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
val LocalWindowSize =
    staticCompositionLocalOf {
        WindowSizeClass.calculateFromSize(DpSize(360.dp, 800.dp))
    }
