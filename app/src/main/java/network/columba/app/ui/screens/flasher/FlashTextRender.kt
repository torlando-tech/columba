package network.columba.app.ui.screens.flasher

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import network.columba.app.rns.host.flasher.FlashText

/**
 * Resolves a [FlashText] carried by the `:rns-host` flasher contract into
 * display text at the UI edge (i18n ticket 04: the flashers emit
 * `@StringRes` id + args instead of English literals).
 */
@Composable
@Suppress("SpreadOperator") // stringResource's vararg format-args API requires the spread
fun FlashText.asString(): String =
    when (this) {
        is FlashText.Resource -> stringResource(id, *args.toTypedArray())
        is FlashText.Raw -> text
    }
