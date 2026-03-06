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
    CUSTOM;

    companion object {
        fun getSettings(preset: BlePowerPreset): BlePowerSettings =
            when (preset) {
                PERFORMANCE -> BlePowerSettings(PERFORMANCE, 3000L, 15000L, 5000L, 30_000L)
                BALANCED -> BlePowerSettings(BALANCED, 5000L, 30000L, 10000L, 60_000L)
                BATTERY_SAVER -> BlePowerSettings(BATTERY_SAVER, 15000L, 120000L, 5000L, 180_000L)
                CUSTOM -> BlePowerSettings(CUSTOM)
            }

        fun fromString(name: String): BlePowerPreset =
            try {
                valueOf(name.uppercase())
            } catch (e: IllegalArgumentException) {
                BALANCED
            }
    }
}
