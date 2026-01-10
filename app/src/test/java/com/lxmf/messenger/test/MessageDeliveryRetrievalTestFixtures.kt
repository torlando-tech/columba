package com.lxmf.messenger.test

/**
 * Test fixtures for MessageDeliveryRetrievalCard UI tests.
 */
object MessageDeliveryRetrievalTestFixtures {
    object Constants {
        const val RELAY_NAME_DEFAULT = "TestRelay01"
        const val RELAY_NAME_LONG = "VeryLongRelayNameForEdgeCaseTesting"
        const val DEFAULT_HOPS = 2
        const val DEFAULT_INTERVAL_SECONDS = 300 // 5 min - first preset
        const val CUSTOM_INTERVAL_VALID = 450 // 7.5 min - not a preset
        const val CUSTOM_INTERVAL_MIN = 60
        const val CUSTOM_INTERVAL_MAX = 7200
        const val CUSTOM_INTERVAL_BELOW_MIN = 30
        const val CUSTOM_INTERVAL_ABOVE_MAX = 8000
    }

    /**
     * Configuration for creating test card states.
     */
    data class CardConfig(
        val defaultMethod: String = "direct",
        val tryPropagationOnFail: Boolean = false,
        val currentRelayName: String? = Constants.RELAY_NAME_DEFAULT,
        val currentRelayHops: Int? = Constants.DEFAULT_HOPS,
        val isAutoSelect: Boolean = true,
        val autoRetrieveEnabled: Boolean = true,
        val retrievalIntervalSeconds: Int = Constants.DEFAULT_INTERVAL_SECONDS,
        val lastSyncTimestamp: Long? = System.currentTimeMillis() - 30_000L,
        val isSyncing: Boolean = false,
        val incomingMessageSizeLimitKb: Int = 1024, // Default 1 MB
    )

    // ========== Pre-configured State Factories ==========

    fun defaultState() = CardConfig()

    fun directMethodState() = CardConfig(defaultMethod = "direct")

    fun propagatedMethodState() = CardConfig(defaultMethod = "propagated")

    fun noRelayState() =
        CardConfig(
            currentRelayName = null,
            currentRelayHops = null,
        )

    fun manualRelaySelectionState() =
        CardConfig(
            isAutoSelect = false,
            currentRelayName = "ManualRelay",
            currentRelayHops = 3,
        )

    fun manualNoRelayState() =
        CardConfig(
            isAutoSelect = false,
            currentRelayName = null,
            currentRelayHops = null,
        )

    fun syncingState() = CardConfig(isSyncing = true)

    fun autoRetrieveDisabledState() = CardConfig(autoRetrieveEnabled = false)

    fun customIntervalState() = CardConfig(retrievalIntervalSeconds = Constants.CUSTOM_INTERVAL_VALID)

    fun noLastSyncState() = CardConfig(lastSyncTimestamp = null)

    fun singleHopRelayState() = CardConfig(currentRelayHops = 1)

    fun multiHopRelayState() = CardConfig(currentRelayHops = 5)

    fun zeroHopRelayState() = CardConfig(currentRelayHops = 0)

    fun tryPropagationEnabledState() = CardConfig(tryPropagationOnFail = true)

    fun interval300sState() = CardConfig(retrievalIntervalSeconds = 300) // 5min - first preset

    fun interval600sState() = CardConfig(retrievalIntervalSeconds = 600) // 10min - second preset

    fun interval1800sState() = CardConfig(retrievalIntervalSeconds = 1800) // 30min - third preset

    fun interval3600sState() = CardConfig(retrievalIntervalSeconds = 3600) // 1h - fourth preset

    fun mixedIntervalState() = CardConfig(retrievalIntervalSeconds = 900) // 15min - not a preset

    // ========== Size Limit State Factories ==========

    fun sizeLimit1MbState() = CardConfig(incomingMessageSizeLimitKb = 1024)

    fun sizeLimit5MbState() = CardConfig(incomingMessageSizeLimitKb = 5120)

    fun sizeLimit10MbState() = CardConfig(incomingMessageSizeLimitKb = 10240)

    fun sizeLimit25MbState() = CardConfig(incomingMessageSizeLimitKb = 25600)

    fun sizeLimitUnlimitedState() = CardConfig(incomingMessageSizeLimitKb = 131072)

    fun sizeLimitCustomState() = CardConfig(incomingMessageSizeLimitKb = 3072) // 3 MB - not a preset

    fun sizeLimitSubMbState() = CardConfig(incomingMessageSizeLimitKb = 512) // 512 KB

    // ========== Timestamp Fixtures for formatRelativeTime ==========

    /**
     * Returns a timestamp for "Just now" (less than 5 seconds ago).
     */
    fun timestampJustNow() = System.currentTimeMillis() - 2_000L

    /**
     * Returns a timestamp for "X seconds ago" (5-59 seconds ago).
     */
    fun timestampSecondsAgo(seconds: Int = 30) = System.currentTimeMillis() - (seconds * 1000L)

    /**
     * Returns a timestamp for "1 minute ago" (60-119 seconds ago).
     */
    fun timestamp1MinuteAgo() = System.currentTimeMillis() - 90_000L

    /**
     * Returns a timestamp for "X minutes ago" (2+ minutes ago).
     */
    fun timestampMinutesAgo(minutes: Int = 3) = System.currentTimeMillis() - (minutes * 60_000L)

    /**
     * Returns a timestamp for "1 hour ago" (60-119 minutes ago).
     */
    fun timestamp1HourAgo() = System.currentTimeMillis() - 5_400_000L

    /**
     * Returns a timestamp for "X hours ago" (2+ hours ago).
     */
    fun timestampHoursAgo(hours: Int = 3) = System.currentTimeMillis() - (hours * 3600_000L)

    /**
     * Returns a timestamp for "X days ago".
     */
    fun timestampDaysAgo(days: Int = 2) = System.currentTimeMillis() - (days * 86400_000L)

    /**
     * Returns a timestamp in the future (edge case).
     */
    fun timestampFuture() = System.currentTimeMillis() + 60_000L

    // ========== Edge Case States ==========

    fun allNullOptionalFieldsState() =
        CardConfig(
            currentRelayName = null,
            currentRelayHops = null,
            lastSyncTimestamp = null,
        )

    fun longRelayNameState() =
        CardConfig(
            currentRelayName = Constants.RELAY_NAME_LONG,
        )

    fun largeHopsState() =
        CardConfig(
            currentRelayHops = Int.MAX_VALUE,
        )

    fun unknownMethodState() =
        CardConfig(
            defaultMethod = "unknown_method",
        )
}
