package network.columba.app.data.model

import androidx.annotation.StringRes
import network.columba.app.R

/**
 * User preference for the map base style.
 *
 * [AUTO] follows the system day/night theme at render time.
 * [LIGHT] and [DARK] override the system setting.
 */
enum class MapStylePreference(
    @StringRes val displayNameRes: Int,
) {
    AUTO(R.string.map_style_auto),
    LIGHT(R.string.map_style_light),
    DARK(R.string.map_style_dark),
    ;

    companion object {
        val DEFAULT = AUTO

        fun fromName(name: String): MapStylePreference = entries.find { it.name == name } ?: DEFAULT
    }
}
