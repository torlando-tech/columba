package network.columba.app.data.model

/**
 * User preference for the map base style.
 *
 * [AUTO] follows the system day/night theme at render time.
 * [LIGHT] and [DARK] override the system setting.
 */
enum class MapStylePreference(
    val displayName: String,
) {
    AUTO("Auto"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        val DEFAULT = AUTO

        fun fromName(name: String): MapStylePreference = entries.find { it.name == name } ?: DEFAULT
    }
}
