package com.lxmf.messenger.reticulum.ble.model

data class BlePowerSettings(
    val preset: BlePowerPreset = BlePowerPreset.BALANCED,
    val discoveryIntervalMs: Long = 5000L,
    val discoveryIntervalIdleMs: Long = 30000L,
    val scanDurationMs: Long = 10000L,
    val advertisingRefreshIntervalMs: Long = 60_000L,
)

enum class BlePowerPreset {
    PERFORMANCE,
    BALANCED,
    BATTERY_SAVER,
    CUSTOM,
    ;

    companion object {
        // Parameters: discoveryIntervalMs, discoveryIntervalIdleMs, scanDurationMs, advertisingRefreshIntervalMs
        // scanDurationMs is intentionally non-monotonic across profiles (5s, 10s, 8s):
        //   PERFORMANCE: short scans + frequent cycling (3s gap) = fastest response to new devices
        //   BALANCED: long scans = most radio windows per scan for reliable detection
        //   BATTERY_SAVER: medium scans = sufficient windows while conserving battery on infrequent scans
        fun getSettings(preset: BlePowerPreset): BlePowerSettings =
            when (preset) {
                // scanDuration(5s) > interval(3s) is intentional — high duty-cycle sequential scanning
                PERFORMANCE -> BlePowerSettings(PERFORMANCE, 3000L, 15000L, 5000L, 30_000L)
                BALANCED -> BlePowerSettings(BALANCED, 5000L, 30000L, 10000L, 60_000L)
                BATTERY_SAVER -> BlePowerSettings(BATTERY_SAVER, 15000L, 120000L, 8000L, 90_000L)
                CUSTOM -> BlePowerSettings(CUSTOM) // Fallback defaults; configurePower() supplies real values
            }

        fun fromString(name: String): BlePowerPreset =
            try {
                valueOf(name.uppercase())
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                BALANCED // Unknown preset name — fall back to balanced
            }
    }
}
