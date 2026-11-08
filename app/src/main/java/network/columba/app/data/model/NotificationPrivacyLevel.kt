package network.columba.app.data.model

import androidx.annotation.StringRes
import network.columba.app.R

/**
 * How much a received-message notification reveals.
 *
 * Enforced where the notification is built: [FULL] is the only level that hands the
 * message text to Android. [NAME_ONLY] and [NONE] never do, so the text cannot appear
 * on the lock screen, be retained by the system's notification history, or be read by
 * any app holding notification access.
 */
enum class NotificationPrivacyLevel(
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    FULL(
        displayNameRes = R.string.message_privacy_level_full_name,
        descriptionRes = R.string.message_privacy_level_full_desc,
    ),
    NAME_ONLY(
        displayNameRes = R.string.message_privacy_level_name_only_name,
        descriptionRes = R.string.message_privacy_level_name_only_desc,
    ),
    NONE(
        displayNameRes = R.string.message_privacy_level_none_name,
        descriptionRes = R.string.message_privacy_level_none_desc,
    ),
    ;

    /**
     * True when this level reveals less than [other].
     * Declaration order is the restrictiveness order.
     */
    fun isMoreRestrictiveThan(other: NotificationPrivacyLevel): Boolean = ordinal > other.ordinal

    companion object {
        /** Used when no level has ever been stored: preserves the historical behaviour. */
        val DEFAULT = FULL

        /**
         * Parses a persisted enum name. Unknown values fail closed to [NONE]:
         * a privacy preference must never fall back to revealing message text.
         */
        fun fromStoredName(name: String): NotificationPrivacyLevel =
            entries.find { it.name == name } ?: NONE

        /**
         * Resolve the level for a stored value plus whether DataStore replaced a corrupt
         * settings file. No stored value means a fresh install ([DEFAULT]); a recovered
         * corruption means the choice was lost, which falls back to [NONE] so message text
         * cannot reappear without the user asking for it.
         */
        fun resolve(
            storedName: String?,
            corruptionRecovered: Boolean,
        ): NotificationPrivacyLevel =
            when {
                storedName != null -> fromStoredName(storedName)
                corruptionRecovered -> NONE
                else -> DEFAULT
            }
    }
}
