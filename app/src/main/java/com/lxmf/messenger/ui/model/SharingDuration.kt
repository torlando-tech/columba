package com.lxmf.messenger.ui.model

import java.util.Calendar

/**
 * Duration options for location sharing.
 *
 * @property displayText User-facing text for the duration option
 * @property durationMillis Duration in milliseconds, or null for computed/indefinite durations
 */
enum class SharingDuration(val displayText: String, val durationMillis: Long?) {
    FIFTEEN_MINUTES("15 min", 15 * 60 * 1000L),
    ONE_HOUR("1 hour", 60 * 60 * 1000L),
    FOUR_HOURS("4 hours", 4 * 60 * 60 * 1000L),
    UNTIL_MIDNIGHT("Until midnight", null),
    INDEFINITE("Until I stop", null),
    ;

    /**
     * Calculate the end timestamp for this sharing duration.
     *
     * @param startTimeMillis The start time in milliseconds since epoch
     * @return The end time in milliseconds since epoch, or null for INDEFINITE
     */
    fun calculateEndTime(startTimeMillis: Long = System.currentTimeMillis()): Long? {
        return when (this) {
            INDEFINITE -> null
            UNTIL_MIDNIGHT -> {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = startTimeMillis
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                calendar.timeInMillis
            }
            else -> startTimeMillis + (durationMillis ?: 0L)
        }
    }
}
