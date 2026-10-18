package network.columba.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import network.columba.app.util.DestinationHashValidator
import network.columba.app.util.messageResource

/**
 * Resolves a [DestinationHashValidator] error's `@StringRes` reason + format args
 * into display text (i18n ticket 10: the validator carries reason enums instead of
 * English literals; render sites resolve at the UI edge).
 */
@Composable
@Suppress("SpreadOperator") // stringResource's vararg format-args API requires the spread
fun localizedHashErrorText(
    reason: DestinationHashValidator.HashErrorReason,
    args: List<Any>,
): String = stringResource(reason.messageResource(), *args.toTypedArray())
