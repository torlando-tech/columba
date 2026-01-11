package com.lxmf.messenger.test

/**
 * Test fixtures for AutoAnnounceCard UI tests.
 */
object AutoAnnounceTestFixtures {
    object Constants {
        const val DEFAULT_INTERVAL_HOURS = 3
        const val CUSTOM_INTERVAL_HOURS = 5 // Not a preset
        const val MIN_INTERVAL_HOURS = 1
        const val MAX_INTERVAL_HOURS = 12
    }

    /**
     * Configuration for creating test card states.
     */
    data class CardConfig(
        val enabled: Boolean = true,
        val intervalHours: Int = Constants.DEFAULT_INTERVAL_HOURS,
        val lastAnnounceTime: Long? = System.currentTimeMillis() - 30 * 60 * 1000L, // 30 min ago
        val nextAnnounceTime: Long? = System.currentTimeMillis() + 2 * 60 * 60 * 1000L, // 2 hours from now
        val isManualAnnouncing: Boolean = false,
        val showManualAnnounceSuccess: Boolean = false,
        val manualAnnounceError: String? = null,
    )

    // ========== Pre-configured State Factories ==========

    fun defaultState() = CardConfig()

    fun disabledState() = CardConfig(enabled = false)

    fun enabledState() = CardConfig(enabled = true)

    // ========== Interval States ==========

    fun interval1hState() = CardConfig(intervalHours = 1)

    fun interval3hState() = CardConfig(intervalHours = 3)

    fun interval6hState() = CardConfig(intervalHours = 6)

    fun interval12hState() = CardConfig(intervalHours = 12)

    fun customIntervalState() = CardConfig(intervalHours = Constants.CUSTOM_INTERVAL_HOURS)

    fun customInterval2hState() = CardConfig(intervalHours = 2) // Not a preset

    // ========== Announce Status States ==========

    fun noLastAnnounceState() = CardConfig(lastAnnounceTime = null)

    fun noNextAnnounceState() = CardConfig(nextAnnounceTime = null)

    fun justAnnouncedState() =
        CardConfig(
            lastAnnounceTime = System.currentTimeMillis() - 30_000L, // 30 seconds ago
        )

    fun announcedMinutesAgoState(minutes: Int = 15) =
        CardConfig(
            lastAnnounceTime = System.currentTimeMillis() - (minutes * 60 * 1000L),
        )

    fun announcedHoursAgoState(hours: Int = 2) =
        CardConfig(
            lastAnnounceTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L),
        )

    fun nextAnnounceInMinutesState(minutes: Int = 30) =
        CardConfig(
            nextAnnounceTime = System.currentTimeMillis() + (minutes * 60 * 1000L),
        )

    fun nextAnnounceInHoursState(hours: Int = 2) =
        CardConfig(
            nextAnnounceTime = System.currentTimeMillis() + (hours * 60 * 60 * 1000L),
        )

    fun nextAnnounceSoonState() =
        CardConfig(
            nextAnnounceTime = System.currentTimeMillis() - 1000L, // In the past = "soon"
        )

    // ========== Manual Announce States ==========

    fun manualAnnouncingState() = CardConfig(isManualAnnouncing = true)

    fun manualAnnounceSuccessState() = CardConfig(showManualAnnounceSuccess = true)

    fun manualAnnounceErrorState(error: String = "Network error") =
        CardConfig(
            manualAnnounceError = error,
        )

    // ========== Edge Case States ==========

    fun allNullTimestampsState() =
        CardConfig(
            lastAnnounceTime = null,
            nextAnnounceTime = null,
        )

    fun disabledWithCustomIntervalState() =
        CardConfig(
            enabled = false,
            intervalHours = Constants.CUSTOM_INTERVAL_HOURS,
        )
}
