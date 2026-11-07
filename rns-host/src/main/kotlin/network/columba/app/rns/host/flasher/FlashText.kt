package network.columba.app.rns.host.flasher

import androidx.annotation.StringRes

/**
 * Display text carried across the flasher contract.
 *
 * Producers (flashers, downloaders, TNC controller) never embed English display
 * text in callbacks or [RNodeFlasher.FlashState]; they reference a string
 * resource in this module's `res/values/strings.xml` plus positional format
 * args (dynamic data such as counts, sizes, and interpolated exception
 * messages). UI render sites resolve it with
 * `stringResource(id, *args.toTypedArray())`, so the rendered text is
 * identical to the pre-migration literals while remaining translatable.
 *
 * [Raw] carries text that has no resource wrapper — raw exception messages
 * passed through unchanged by app-side catch blocks.
 */
sealed interface FlashText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : FlashText

    data class Raw(
        val text: String,
    ) : FlashText
}

/** Convenience factory for [FlashText.Resource]. */
internal fun flashText(
    @StringRes id: Int,
    vararg args: Any,
): FlashText = FlashText.Resource(id, args.toList())
