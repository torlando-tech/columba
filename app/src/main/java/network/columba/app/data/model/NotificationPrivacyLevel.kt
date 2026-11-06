package network.columba.app.data.model

/**
 * How much a received-message notification reveals.
 *
 * Enforced where the notification is built: [FULL] is the only level that hands the
 * message text to Android. [NAME_ONLY] and [NONE] never do, so the text cannot appear
 * on the lock screen, be retained by the system's notification history, or be read by
 * any app holding notification access.
 */
enum class NotificationPrivacyLevel(
    val displayName: String,
    val description: String,
) {
    FULL(
        displayName = "Name and message",
        description = "Notifications show the sender's name and the message text.",
    ),
    NAME_ONLY(
        displayName = "Name only",
        description = "Notifications show the sender's name, never the message text.",
    ),
    NONE(
        displayName = "No name or message",
        description = "Notifications only say that a message arrived.",
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
    }
}
