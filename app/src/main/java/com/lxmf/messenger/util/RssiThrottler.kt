package com.lxmf.messenger.util

/**
 * Throttles RSSI updates for discovered Bluetooth devices to prevent excessive UI updates.
 * Ensures RSSI updates are not processed more frequently than the specified interval.
 *
 * Extracted from RNodeWizardViewModel for testability and reusability.
 *
 * @param intervalMs Minimum time in milliseconds between updates for the same device address
 * @param timeProvider Function to get current time in milliseconds (defaults to System.currentTimeMillis)
 */
class RssiThrottler(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Map of device address to last update timestamp.
     */
    private val lastUpdate = mutableMapOf<String, Long>()

    /**
     * Checks if an RSSI update should be allowed for the given device address.
     * If enough time has elapsed since the last update, records the current time and returns true.
     * Otherwise, returns false to indicate the update should be throttled.
     *
     * @param address The device address (typically Bluetooth MAC address)
     * @return true if the update should be processed, false if it should be throttled
     */
    fun shouldUpdate(address: String): Boolean {
        val now = timeProvider()
        val lastUpdateTime = lastUpdate[address]

        // Allow first update (no previous timestamp recorded)
        if (lastUpdateTime == null) {
            lastUpdate[address] = now
            return true
        }

        // Check if enough time has elapsed since last update
        return if (now - lastUpdateTime >= intervalMs) {
            lastUpdate[address] = now
            true
        } else {
            false
        }
    }

    /**
     * Resets the throttling state for a specific device address.
     * The next update for this address will be allowed immediately.
     *
     * @param address The device address to reset
     */
    fun reset(address: String) {
        lastUpdate.remove(address)
    }

    /**
     * Clears all throttling state for all device addresses.
     * All subsequent updates will be allowed immediately.
     */
    fun clear() {
        lastUpdate.clear()
    }

    companion object {
        /**
         * Default interval between RSSI updates in milliseconds (3 seconds).
         * This prevents excessive UI updates while still providing reasonable real-time feedback.
         */
        const val DEFAULT_INTERVAL_MS = 3000L
    }
}
