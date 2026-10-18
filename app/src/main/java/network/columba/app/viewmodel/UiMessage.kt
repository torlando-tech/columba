package network.columba.app.viewmodel

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * A localized user-facing message produced by a ViewModel. ViewModels have no
 * resource access, so they emit the resource id plus format arguments and the
 * render site resolves the text (`stringResource` / `context.getString`).
 */
sealed interface UiMessage {
    /** Resolve this message to display text for the current locale. */
    fun resolve(context: Context): String

    /** A plain string resource with optional format arguments. */
    data class Text(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage {
        @Suppress("SpreadOperator") // getString's vararg signature is the platform API shape
        override fun resolve(context: Context): String = context.getString(id, *args.toTypedArray())
    }

    /** A plurals resource resolved with [quantity] plus format arguments. */
    data class Plural(
        @PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage {
        @Suppress("SpreadOperator") // getQuantityString's vararg signature is the platform API shape
        override fun resolve(context: Context): String =
            context.resources.getQuantityString(id, quantity, *args.toTypedArray())
    }

    /**
     * Raw pass-through text with no resource wrapper: runtime data (host,
     * device names) or exception messages surfaced verbatim. NEVER wrap an
     * English literal in this type — use [Text] with a resource instead.
     */
    data class Raw(val text: String) : UiMessage {
        override fun resolve(context: Context): String = text
    }
}
