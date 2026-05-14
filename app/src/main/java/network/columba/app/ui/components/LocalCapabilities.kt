package network.columba.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import network.columba.app.rns.api.BackendCapabilities

/**
 * Ambient [BackendCapabilities] for the active RNS backend.
 *
 * Provided once above the NavHost in `ColumbaNavigation` from
 * `SettingsViewModel.capabilities` (which forwards `RnsBackend.capabilities`).
 * UI that differs by backend reads this — directly or via [CapabilityGate] — so
 * a screen does not need the capabilities object prop-drilled to it.
 *
 * Defaults to [BackendCapabilities.UNKNOWN] (safe defaults: every capability
 * `UNSUPPORTED`, hot-reload off) for `@Preview`s and any subtree that somehow
 * mounts before the provider — gating on the default degrades gracefully
 * rather than exposing a feature the backend can't honour.
 *
 * `staticCompositionLocalOf` (not `compositionLocalOf`): the backend is fixed
 * for a build, and the rare runtime capability change (e.g. an RNode
 * disconnect downgrading an interface capability) re-provides at the
 * `ColumbaNavigation` level, recomposing the subtree — read-tracking would buy
 * nothing for a value that changes this seldom.
 */
val LocalCapabilities = staticCompositionLocalOf { BackendCapabilities.UNKNOWN }
