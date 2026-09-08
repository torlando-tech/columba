package network.columba.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Registry of destinations that may appear in the user-configurable bottom
 * navigation bar.
 *
 * The user chooses which tabs appear and in what order; the persisted form is
 * a comma-separated list of [id] values (see SettingsRepository). [SETTINGS]
 * is pinned: it is always present and always rendered last, so Settings can
 * never be removed from the bar. [sanitize] is the single authority for what
 * the bar renders - it drops unknown ids (forward/backward compatible),
 * dedupes, enforces the pin, and clamps the total to [MAX_TABS].
 *
 * [routePrefix] drives tab selection: a tab is selected while the current
 * navigation route starts with its prefix, which keeps a tab highlighted
 * across parameterized routes (e.g. `announce_stream?filterType=all`).
 */
enum class NavTab(
    val id: String,
    val routePrefix: String,
    val tabRoute: String,
    val label: String,
    val icon: ImageVector,
) {
    CHATS("chats", "chats", "chats", "Chats", Icons.Default.Chat),
    ANNOUNCES("announces", "announce_stream", "announce_stream", "Announces", Icons.Default.Sensors),
    CONTACTS("contacts", "contacts", "contacts", "Contacts", Icons.Default.People),
    MAP("map", "map", "map", "Map", Icons.Default.Map),

    // The NomadNet tab lands on nomadnet_home (which resolves the last-browsed
    // node); its selection prefix also covers nomadnet_browser/... so the tab
    // stays highlighted while browsing pages with the bar visible.
    NOMADNET("nomadnet", "nomadnet", "nomadnet_home", "NomadNet", Icons.Default.Language),
    SETTINGS("settings", "settings", "settings", "Settings", Icons.Default.Settings),
    ;

    /** True when [currentRoute] belongs to this tab. */
    fun matchesRoute(currentRoute: String?): Boolean =
        currentRoute != null && currentRoute.startsWith(routePrefix)

    companion object {
        /** Maximum number of tabs in the bar, including the pinned Settings tab. */
        const val MAX_TABS = 5

        /** Bar layout shown before the user configures anything. */
        val DEFAULT: List<NavTab> = listOf(CHATS, CONTACTS, MAP, SETTINGS)

        /** All tabs the user may toggle in the settings card (Settings is pinned). */
        val CONFIGURABLE: List<NavTab> = entries.filter { it != SETTINGS }

        fun fromId(id: String): NavTab? = entries.firstOrNull { it.id == id }

        /**
         * Parse and normalize a persisted CSV of tab ids into the exact list of
         * tabs the bottom bar should render. Malformed, unknown, or duplicate
         * entries are dropped; Settings is appended if missing; the result is
         * clamped to [MAX_TABS] entries. Falls back to [DEFAULT] when nothing
         * valid remains, so the bar can never render empty.
         */
        fun sanitize(csv: String?): List<NavTab> {
            val parsed =
                csv
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.mapNotNull { fromId(it) }
                    ?.distinct()
                    .orEmpty()
            if (parsed.isEmpty()) return DEFAULT
            val withoutSettings = parsed.filter { it != SETTINGS }
            val overflow = withoutSettings.size - (MAX_TABS - 1)
            val kept = if (overflow > 0) withoutSettings.dropLast(overflow) else withoutSettings
            return kept + SETTINGS
        }
    }
}
