package network.columba.app.reticulum.ble.model

/**
 * BLE PHY codec selection for Bluetooth 5 connections.
 *
 * S=2 and S=8 use the LE Coded PHY for extended range at reduced throughput.
 * S=8 is the most range-friendly option (125 Kbps, ~4x range vs 1M PHY).
 */
enum class BleCodec(
    val displayName: String,
    val description: String,
) {
    PHY_1M("1M", "1 Mbps — standard range and throughput (default)"),
    PHY_2M("2M", "2 Mbps — higher throughput, similar range"),
    CODED_S2("S=2", "Coded PHY S=2 — 500 Kbps, ~2× range"),
    CODED_S8("S=8", "Coded PHY S=8 — 125 Kbps, ~4× range (long range)"),
    ;

    companion object {
        fun fromString(name: String): BleCodec =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PHY_1M
    }
}

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
