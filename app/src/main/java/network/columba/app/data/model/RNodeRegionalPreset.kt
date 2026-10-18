package network.columba.app.data.model

import android.bluetooth.BluetoothDevice
import androidx.annotation.StringRes
import network.columba.app.R
import java.util.Locale

/**
 * Bluetooth connection type for RNode devices.
 */
enum class BluetoothType {
    CLASSIC, // Bluetooth Classic (SPP/RFCOMM)
    BLE, // Bluetooth Low Energy (Nordic UART Service)
    UNKNOWN, // Not yet determined
}

/**
 * Discovered Bluetooth RNode device from scanning.
 *
 * @property bluetoothDevice The actual BluetoothDevice from the scan result.
 *                           Required for proper BLE bonding - using getRemoteDevice() loses transport context.
 */
data class DiscoveredRNode(
    val name: String,
    val address: String,
    val type: BluetoothType,
    /** Signal strength (BLE only) */
    val rssi: Int?,
    val isPaired: Boolean,
    val bluetoothDevice: BluetoothDevice? = null,
)

/**
 * Discovered USB serial device that may be an RNode.
 */
data class DiscoveredUsbDevice(
    /** Android USB device ID for connection */
    val deviceId: Int,
    /** USB Vendor ID */
    val vendorId: Int,
    /** USB Product ID */
    val productId: Int,
    /** System device name (e.g., /dev/bus/usb/...) */
    val deviceName: String,
    /** Manufacturer name from USB descriptor (may be null) */
    val manufacturerName: String?,
    /** Product name from USB descriptor (may be null) */
    val productName: String?,
    /** Serial number from USB descriptor (may be null) */
    val serialNumber: String?,
    /** Detected driver type (FTDI, CP210x, CH340, CDC-ACM, etc.) */
    val driverType: String,
    /** Whether USB permission has been granted */
    val hasPermission: Boolean = false,
) {
    /**
     * Sentinel driver type for devices whose driver could not be detected.
     * Render sites map it to a localized label instead of displaying it raw.
     */
    companion object {
        const val DRIVER_TYPE_UNKNOWN = "Unknown"
    }

    /** User-friendly display name */
    val displayName: String
        get() = productName ?: manufacturerName ?: "USB Serial Device ($driverType)"

    /** Formatted VID:PID for display */
    val vidPid: String
        get() = String.format(Locale.US, "%04X:%04X", vendorId, productId)
}

/**
 * Regional preset for RNode LoRa configuration.
 * Contains legally compliant frequency settings for specific regions.
 */
data class RNodeRegionalPreset(
    val id: String,
    /** ISO 3166-1 alpha-2 (e.g., "US", "DE") */
    val countryCode: String,
    val countryName: String,
    /** null for country-wide default */
    val cityOrRegion: String?,
    /** Center frequency in Hz */
    val frequency: Long,
    /** Bandwidth in Hz */
    val bandwidth: Int,
    /** LoRa SF (5-12) */
    val spreadingFactor: Int,
    /** LoRa CR (5-8) */
    val codingRate: Int,
    /**
     * Transmission power in dBm, or null when the preset carries no explicit
     * override and the frequency region's default should be kept. Most RNode
     * radios top out at 17-22 dBm; only the Heltec v4 reaches the higher EU868
     * sub-band-P ceiling, so presets defer to the region default unless a
     * board is known to need more. Applied (when non-null) when the preset is
     * selected; a null value leaves the current / region-default TX power.
     */
    val txPower: Int? = null,
    /**
     * Long-term airtime limit in percent (1-100), or null when the preset
     * carries no explicit long-term airtime constraint. Applied to the
     * `lt_alock` field when the preset is selected.
     */
    val longTermAirtimeLimit: Int? = null,
    @StringRes val descriptionRes: Int,
)

/**
 * Meshtastic-compatible modem presets.
 * These define the LoRa modulation parameters (SF, BW, CR) independent of frequency.
 * Named to match Meshtastic conventions for user familiarity.
 *
 * @property displayNameRes User-friendly name shown in UI
 * @property spreadingFactor LoRa spreading factor (7-12)
 * @property bandwidth Bandwidth in Hz
 * @property codingRate Coding rate denominator (5-8, representing 4/5 to 4/8)
 * @property descriptionRes Brief description of use case
 */
enum class ModemPreset(
    @StringRes val displayNameRes: Int,
    val spreadingFactor: Int,
    val bandwidth: Int,
    val codingRate: Int,
    @StringRes val descriptionRes: Int,
) {
    SHORT_TURBO(
        displayNameRes = R.string.modem_preset_short_turbo,
        spreadingFactor = 7,
        bandwidth = 500000,
        codingRate = 5,
        descriptionRes = R.string.modem_desc_short_turbo,
    ),
    SHORT_FAST(
        displayNameRes = R.string.modem_preset_short_fast,
        spreadingFactor = 7,
        bandwidth = 250000,
        codingRate = 5,
        descriptionRes = R.string.modem_desc_short_fast,
    ),
    SHORT_SLOW(
        displayNameRes = R.string.modem_preset_short_slow,
        spreadingFactor = 8,
        bandwidth = 250000,
        codingRate = 5,
        descriptionRes = R.string.modem_desc_short_slow,
    ),
    MEDIUM_FAST(
        displayNameRes = R.string.modem_preset_medium_fast,
        spreadingFactor = 9,
        bandwidth = 250000,
        codingRate = 5,
        descriptionRes = R.string.modem_desc_medium_fast,
    ),
    MEDIUM_SLOW(
        displayNameRes = R.string.modem_preset_medium_slow,
        spreadingFactor = 10,
        bandwidth = 250000,
        codingRate = 5,
        descriptionRes = R.string.modem_desc_medium_slow,
    ),
    LONG_FAST(
        displayNameRes = R.string.modem_preset_long_fast,
        spreadingFactor = 11,
        bandwidth = 250000,
        codingRate = 5,
        descriptionRes = R.string.modem_desc_long_fast,
    ),
    LONG_MODERATE(
        displayNameRes = R.string.modem_preset_long_moderate,
        spreadingFactor = 11,
        bandwidth = 125000,
        codingRate = 8,
        descriptionRes = R.string.modem_desc_long_moderate,
    ),
    LONG_SLOW(
        displayNameRes = R.string.modem_preset_long_slow,
        spreadingFactor = 12,
        bandwidth = 125000,
        codingRate = 8,
        descriptionRes = R.string.modem_desc_long_slow,
    ),
    ;

    companion object {
        /** Default preset - good balance for most use cases */
        val DEFAULT = LONG_FAST

        /** Find preset matching given parameters, or null if no match */
        fun findByParams(
            spreadingFactor: Int,
            bandwidth: Int,
            codingRate: Int,
        ): ModemPreset? {
            return entries.find {
                it.spreadingFactor == spreadingFactor &&
                    it.bandwidth == bandwidth &&
                    it.codingRate == codingRate
            }
        }
    }
}

/**
 * Frequency band/region for RNode configuration.
 * Defines the frequency range, TX power limits, and duty cycle for a geographic region.
 *
 * Reference: https://meshtastic.org/docs/configuration/radio/lora/#region
 *
 * @property id Unique identifier
 * @property name Display name for the region
 * @property frequencyStart Start of frequency band in Hz
 * @property frequencyEnd End of frequency band in Hz
 * @property maxTxPower Maximum allowed TX power in dBm (regulatory limit)
 * @property defaultTxPower Recommended default TX power in dBm
 * @property dutyCycle Duty cycle percentage (1-100). 100 = unlimited, 10 = 10% limit, 1 = 1% limit
 * @property descriptionRes Brief description including regulatory notes
 */
data class FrequencyRegion(
    val id: String,
    val name: String,
    val frequencyStart: Long,
    val frequencyEnd: Long,
    val maxTxPower: Int,
    val defaultTxPower: Int,
    val dutyCycle: Int,
    @StringRes val descriptionRes: Int,
) {
    /** Center frequency (for backwards compatibility and defaults) */
    val frequency: Long get() = (frequencyStart + frequencyEnd) / 2

    /** Whether this region has duty cycle restrictions */
    val hasDutyCycleLimit: Boolean get() = dutyCycle < 100

    /** Format duty cycle for display */
    val dutyCycleDisplay: String get() = if (dutyCycle >= 100) "Unlimited" else "$dutyCycle%"
}

/**
 * Standard frequency regions/bands for LoRa.
 *
 * Reference: https://meshtastic.org/docs/configuration/radio/lora/#region
 * EU 868 sub-bands: https://www.thethingsnetwork.org/docs/lorawan/regional-parameters/eu868/
 *
 * SLOT COUNTS (with LongFast 250kHz bandwidth):
 * ┌──────────────┬─────────────────────┬───────┬─────────┬────────┬───────────┐
 * │ Region       │ Frequency Range     │ Slots │ Max dBm │ Duty % │ Default   │
 * ├──────────────┼─────────────────────┼───────┼─────────┼────────┼───────────┤
 * │ US/Americas  │ 902-928 MHz         │ 104   │ 30      │ 100%   │ Slot 50   │
 * │ Brazil       │ 902-907.5 MHz       │ 22    │ 30      │ 100%   │ Slot 10   │
 * │ EU 868 L     │ 865-868 MHz         │ 12    │ 14      │ 1%     │ Slot 6    │
 * │ EU 868 M     │ 868-868.6 MHz       │ 2     │ 14      │ 1%     │ Slot 1    │
 * │ EU 868 P     │ 869.4-869.65 MHz    │ 1     │ 27      │ 10%    │ Slot 0    │
 * │ EU 868 Q     │ 869.7-870 MHz       │ 1     │ 14      │ 1%     │ Slot 0    │
 * │ EU 433       │ 433-434.8 MHz       │ 6     │ 12      │ 10%    │ Slot 3    │
 * │ Russia       │ 868.7-869.2 MHz     │ 2     │ 20      │ 100%   │ Slot 0    │
 * │ Ukraine      │ 868-868.6 MHz       │ 2     │ 14      │ 1%     │ Slot 0    │
 * │ Australia    │ 915-928 MHz         │ 52    │ 30      │ 100%   │ Slot 50   │
 * │ NZ 865       │ 864-868 MHz         │ 16    │ 36      │ 100%   │ Slot 8    │
 * │ Japan        │ 920.8-927.8 MHz     │ 28    │ 16      │ 100%   │ Slot 14   │
 * │ Korea        │ 920-923 MHz         │ 12    │ 14      │ 100%   │ Slot 6    │
 * │ Taiwan       │ 920-925 MHz         │ 20    │ 27      │ 100%   │ Slot 10   │
 * │ China        │ 470-510 MHz         │ 160   │ 19      │ 100%   │ Slot 80   │
 * │ India        │ 865-867 MHz         │ 8     │ 30      │ 100%   │ Slot 4    │
 * │ Thailand     │ 920-925 MHz         │ 20    │ 16      │ 100%   │ Slot 10   │
 * │ Singapore    │ 917-925 MHz         │ 32    │ 20      │ 100%   │ Slot 16   │
 * │ Malaysia     │ 919-924 MHz         │ 20    │ 27      │ 100%   │ Slot 10   │
 * │ Philippines  │ 915-918 MHz         │ 12    │ 20      │ 100%   │ Slot 6    │
 * │ 2.4 GHz      │ 2400-2483.5 MHz     │ 334   │ 10      │ 100%   │ Slot 50   │
 * └──────────────┴─────────────────────┴───────┴─────────┴────────┴───────────┘
 */
object FrequencyRegions {
    val regions =
        listOf(
            // ==================== AMERICAS ====================
            FrequencyRegion(
                id = "us_915",
                name = "US / Americas (915 MHz)",
                frequencyStart = 902_000_000,
                frequencyEnd = 928_000_000,
                maxTxPower = 30,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_us_915,
            ),
            FrequencyRegion(
                id = "br_902",
                name = "Brazil (902 MHz)",
                frequencyStart = 902_000_000,
                frequencyEnd = 907_500_000,
                maxTxPower = 30,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_br_902,
            ),
            // ==================== EUROPE 868 MHz SUB-BANDS ====================
            // Per ERC 70-03: https://www.thethingsnetwork.org/docs/lorawan/regional-parameters/eu868/
            // Sub-band K (863-865 MHz, 0.1%) and N (868.7-869.2 MHz, 0.1%) omitted as too restrictive
            // Sub-band L: 865-868 MHz (1% duty cycle)
            FrequencyRegion(
                id = "eu_868_l",
                name = "Europe 865-868 MHz (1%)",
                frequencyStart = 865_000_000,
                frequencyEnd = 868_000_000,
                maxTxPower = 14,
                defaultTxPower = 14,
                dutyCycle = 1,
                descriptionRes = R.string.region_desc_eu_868_l,
            ),
            // Sub-band M: 868-868.6 MHz (1% duty cycle) - LoRaWAN default channels
            FrequencyRegion(
                id = "eu_868_m",
                name = "Europe 868 MHz (1%)",
                frequencyStart = 868_000_000,
                frequencyEnd = 868_600_000,
                maxTxPower = 14,
                defaultTxPower = 14,
                dutyCycle = 1,
                descriptionRes = R.string.region_desc_eu_868_m,
            ),
            // Sub-band P: 869.4-869.65 MHz (10% duty cycle, 500 mW) - Meshtastic default
            FrequencyRegion(
                id = "eu_868_p",
                name = "Europe 869.5 MHz (10%)",
                frequencyStart = 869_400_000,
                frequencyEnd = 869_650_000,
                maxTxPower = 27,
                defaultTxPower = 14,
                dutyCycle = 10,
                descriptionRes = R.string.region_desc_eu_868_p,
            ),
            // Sub-band Q: 869.7-870 MHz (1% duty cycle)
            FrequencyRegion(
                id = "eu_868_q",
                name = "Europe 869.7-870 MHz (1%)",
                frequencyStart = 869_700_000,
                frequencyEnd = 870_000_000,
                maxTxPower = 14,
                defaultTxPower = 14,
                dutyCycle = 1,
                descriptionRes = R.string.region_desc_eu_868_q,
            ),
            FrequencyRegion(
                id = "eu_433",
                name = "Europe (433 MHz)",
                frequencyStart = 433_050_000,
                frequencyEnd = 434_790_000,
                maxTxPower = 12,
                defaultTxPower = 10,
                dutyCycle = 10,
                descriptionRes = R.string.region_desc_eu_433,
            ),
            FrequencyRegion(
                id = "ru_868",
                name = "Russia (868 MHz)",
                frequencyStart = 868_700_000,
                frequencyEnd = 869_200_000,
                maxTxPower = 20,
                defaultTxPower = 14,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_ru_868,
            ),
            FrequencyRegion(
                id = "ua_868",
                name = "Ukraine (868 MHz)",
                frequencyStart = 868_000_000,
                frequencyEnd = 868_600_000,
                maxTxPower = 14,
                defaultTxPower = 10,
                dutyCycle = 1,
                descriptionRes = R.string.region_desc_ua_868,
            ),
            // ==================== ASIA-PACIFIC ====================
            FrequencyRegion(
                id = "au_915",
                name = "Australia / NZ (915 MHz)",
                frequencyStart = 915_000_000,
                frequencyEnd = 928_000_000,
                maxTxPower = 30,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_au_915,
            ),
            FrequencyRegion(
                id = "nz_865",
                name = "New Zealand (865 MHz)",
                frequencyStart = 864_000_000,
                frequencyEnd = 868_000_000,
                maxTxPower = 36,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_nz_865,
            ),
            FrequencyRegion(
                id = "jp_920",
                name = "Japan (920 MHz)",
                frequencyStart = 920_800_000,
                frequencyEnd = 927_800_000,
                maxTxPower = 16,
                defaultTxPower = 13,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_jp_920,
            ),
            FrequencyRegion(
                id = "kr_920",
                name = "Korea (920 MHz)",
                frequencyStart = 920_000_000,
                frequencyEnd = 923_000_000,
                maxTxPower = 14,
                defaultTxPower = 10,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_kr_920,
            ),
            FrequencyRegion(
                id = "tw_920",
                name = "Taiwan (920 MHz)",
                frequencyStart = 920_000_000,
                frequencyEnd = 925_000_000,
                maxTxPower = 27,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_tw_920,
            ),
            FrequencyRegion(
                id = "cn_470",
                name = "China (470 MHz)",
                frequencyStart = 470_000_000,
                frequencyEnd = 510_000_000,
                maxTxPower = 19,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_cn_470,
            ),
            FrequencyRegion(
                id = "in_865",
                name = "India (865 MHz)",
                frequencyStart = 865_000_000,
                frequencyEnd = 867_000_000,
                maxTxPower = 30,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_in_865,
            ),
            // ==================== SOUTHEAST ASIA ====================
            FrequencyRegion(
                id = "th_920",
                name = "Thailand (920 MHz)",
                frequencyStart = 920_000_000,
                frequencyEnd = 925_000_000,
                maxTxPower = 16,
                defaultTxPower = 14,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_th_920,
            ),
            FrequencyRegion(
                id = "sg_923",
                name = "Singapore (923 MHz)",
                frequencyStart = 917_000_000,
                frequencyEnd = 925_000_000,
                maxTxPower = 20,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_sg_923,
            ),
            FrequencyRegion(
                id = "my_919",
                name = "Malaysia (919 MHz)",
                frequencyStart = 919_000_000,
                frequencyEnd = 924_000_000,
                maxTxPower = 27,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_my_919,
            ),
            FrequencyRegion(
                id = "ph_915",
                name = "Philippines (915 MHz)",
                frequencyStart = 915_000_000,
                frequencyEnd = 918_000_000,
                maxTxPower = 20,
                defaultTxPower = 17,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_ph_915,
            ),
            // ==================== 2.4 GHz WORLDWIDE ====================
            FrequencyRegion(
                id = "lora_24",
                name = "2.4 GHz (Worldwide)",
                frequencyStart = 2_400_000_000,
                frequencyEnd = 2_483_500_000,
                maxTxPower = 10,
                defaultTxPower = 10,
                dutyCycle = 100,
                descriptionRes = R.string.region_desc_lora_24,
            ),
        )

    /** Find region by ID */
    fun findById(id: String): FrequencyRegion? = regions.find { it.id == id }
}

/**
 * Community-defined frequency slot presets.
 * These are well-known slot configurations used by local mesh communities.
 */
data class CommunitySlot(
    val name: String,
    val regionId: String,
    val slot: Int,
    @StringRes val descriptionRes: Int,
)

/**
 * Well-known community frequency slots.
 */
object CommunitySlots {
    val slots =
        listOf(
            CommunitySlot(
                name = "Meshtastic Default",
                regionId = "us_915",
                slot = 20,
                descriptionRes = R.string.community_slot_desc_default,
            ),
            CommunitySlot(
                name = "Meshtastic NoVa",
                regionId = "us_915",
                slot = 9,
                descriptionRes = R.string.community_slot_desc_nova,
            ),
        )

    /** Slots to avoid due to Meshtastic interference */
    val meshtasticSlots = setOf(20, 9)

    /** Get community slots for a region */
    fun forRegion(regionId: String): List<CommunitySlot> = slots.filter { it.regionId == regionId }

    /** Check if a slot overlaps with known Meshtastic frequencies */
    fun isMeshtasticSlot(
        regionId: String,
        slot: Int,
    ): Boolean = regionId == "us_915" && slot in meshtasticSlots
}

/**
 * Calculator for Meshtastic-style frequency slots.
 *
 * Meshtastic divides frequency bands into evenly-spaced slots based on bandwidth.
 * Formula: frequency = freqStart + (bandwidth / 2) + (slot * bandwidth)
 *
 * For US with LongFast (250 kHz):
 * - 104 slots across 902-928 MHz
 * - Slot 0 = 902.125 MHz
 * - Slot 20 = 907.125 MHz (default)
 * - Slot 9 = 904.375 MHz (NoVa)
 */
object FrequencySlotCalculator {
    /**
     * Calculate the frequency for a given slot.
     *
     * @param region The frequency region (determines band range)
     * @param bandwidth Bandwidth in Hz (from modem preset)
     * @param slot Slot number (0-indexed)
     * @return Frequency in Hz
     */
    fun calculateFrequency(
        region: FrequencyRegion,
        bandwidth: Int,
        slot: Int,
    ): Long {
        return region.frequencyStart + (bandwidth / 2) + (slot.toLong() * bandwidth)
    }

    /**
     * Get the number of available slots for a region/bandwidth combination.
     */
    fun getNumSlots(
        region: FrequencyRegion,
        bandwidth: Int,
    ): Int {
        val rangeHz = region.frequencyEnd - region.frequencyStart
        return (rangeHz / bandwidth).toInt()
    }

    /**
     * Get a recommended default slot for a region.
     * Avoids Meshtastic frequencies (slots 9 and 20 for US) to prevent interference.
     * For regions with many slots, defaults to ~middle of the band.
     */
    @Suppress("CyclomaticComplexMethod")
    fun getDefaultSlot(
        region: FrequencyRegion,
        bandwidth: Int,
    ): Int {
        val numSlots = getNumSlots(region, bandwidth)
        if (numSlots <= 1) return 0

        return when (region.id) {
            // US/Americas: Avoid Meshtastic slots 9 (NoVa) and 20 (default)
            "us_915" -> minOf(50, numSlots - 1)
            "br_902" -> minOf(10, numSlots - 1) // Smaller band

            // EU 868 sub-bands: Use middle of available slots
            "eu_868_l" -> minOf(6, numSlots - 1) // ~866.5 MHz
            "eu_868_m" -> minOf(1, numSlots - 1) // ~868.3 MHz
            "eu_868_p" -> 0 // Only 1 slot with 250kHz BW
            "eu_868_q" -> 0 // Small band

            // Russia/Ukraine 868 MHz
            "ru_868", "ua_868" -> 0

            // Europe 433: Middle of band
            "eu_433" -> minOf(3, numSlots - 1)

            // Australia/NZ: Avoid Meshtastic slot 20
            "au_915" -> minOf(50, numSlots - 1)
            "nz_865" -> minOf(8, numSlots - 1)

            // Asia-Pacific: Middle of available slots
            "jp_920" -> minOf(14, numSlots - 1)
            "kr_920", "tw_920", "th_920" -> minOf(10, numSlots - 1)
            "sg_923", "my_919" -> minOf(16, numSlots - 1)
            "ph_915" -> minOf(6, numSlots - 1)

            // China: Large band, use middle
            "cn_470" -> minOf(80, numSlots - 1)

            // India: Small band
            "in_865" -> minOf(4, numSlots - 1)

            // 2.4 GHz: Middle of band
            "lora_24" -> minOf(50, numSlots - 1)

            else -> minOf(numSlots / 2, numSlots - 1)
        }
    }

    /**
     * Reverse-calculate slot from a frequency.
     * Returns null if frequency doesn't align with a valid slot.
     */
    @Suppress("ReturnCount")
    fun calculateSlotFromFrequency(
        region: FrequencyRegion,
        bandwidth: Int,
        frequency: Long,
    ): Int? {
        val offset = frequency - region.frequencyStart - (bandwidth / 2)
        if (offset < 0) return null

        val slot = (offset / bandwidth).toInt()
        val numSlots = getNumSlots(region, bandwidth)

        // Verify it's a valid slot
        if (slot < 0 || slot >= numSlots) return null

        // Verify the frequency matches exactly
        val calculatedFreq = calculateFrequency(region, bandwidth, slot)
        if (calculatedFreq != frequency) return null

        return slot
    }

    /**
     * Format frequency for display (e.g., "906.875 MHz").
     */
    fun formatFrequency(frequencyHz: Long): String {
        val mhz = frequencyHz / 1_000_000.0
        return if (mhz == mhz.toLong().toDouble()) {
            "${mhz.toLong()} MHz"
        } else {
            String.format(java.util.Locale.US, "%.3f MHz", mhz)
        }
    }
}

/**
 * Repository for regional RNode presets.
 * Data sourced from: https://github.com/markqvist/Reticulum/wiki/Popular-RNode-Settings
 */
object RNodeRegionalPresets {
    val presets: List<RNodeRegionalPreset> =
        listOf(
            // ==================== AUSTRALIA ====================
            RNodeRegionalPreset(
                id = "au_default",
                countryCode = "AU",
                countryName = "Australia",
                cityOrRegion = null,
                frequency = 925875000,
                bandwidth = 250000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_au_default,
            ),
            RNodeRegionalPreset(
                id = "au_sydney",
                countryCode = "AU",
                countryName = "Australia",
                cityOrRegion = "Sydney",
                frequency = 925875000,
                bandwidth = 250000,
                spreadingFactor = 11,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_au_sydney,
            ),
            RNodeRegionalPreset(
                id = "au_brisbane",
                countryCode = "AU",
                countryName = "Australia",
                cityOrRegion = "Brisbane",
                frequency = 925875000,
                bandwidth = 250000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_au_brisbane,
            ),
            RNodeRegionalPreset(
                id = "au_western_sydney",
                countryCode = "AU",
                countryName = "Australia",
                cityOrRegion = "Western Sydney",
                frequency = 925875000,
                bandwidth = 250000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_au_western_sydney,
            ),
            // ==================== BELGIUM ====================
            RNodeRegionalPreset(
                id = "be_default",
                countryCode = "BE",
                countryName = "Belgium",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "be_duffel",
                countryCode = "BE",
                countryName = "Belgium",
                cityOrRegion = "Duffel",
                frequency = 867200000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_be_duffel,
            ),
            // ==================== FINLAND ====================
            RNodeRegionalPreset(
                id = "fi_default",
                countryCode = "FI",
                countryName = "Finland",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "fi_turku",
                countryCode = "FI",
                countryName = "Finland",
                cityOrRegion = "Turku",
                frequency = 869420000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_fi_turku,
            ),
            // ==================== GERMANY ====================
            RNodeRegionalPreset(
                id = "de_default",
                countryCode = "DE",
                countryName = "Germany",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "de_darmstadt",
                countryCode = "DE",
                countryName = "Germany",
                cityOrRegion = "Darmstadt",
                frequency = 869400000,
                bandwidth = 250000,
                spreadingFactor = 7,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_de_darmstadt,
            ),
            RNodeRegionalPreset(
                id = "de_wiesbaden",
                countryCode = "DE",
                countryName = "Germany",
                cityOrRegion = "Wiesbaden",
                frequency = 869525000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_de_wiesbaden,
            ),
            RNodeRegionalPreset(
                id = "de_ruhrgebiet",
                countryCode = "DE",
                countryName = "Germany",
                cityOrRegion = "Ruhrgebiet",
                frequency = 869462500,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                longTermAirtimeLimit = 10,
                descriptionRes = R.string.preset_desc_de_ruhrgebiet,
            ),
            // ==================== ITALY ====================
            RNodeRegionalPreset(
                id = "it_default",
                countryCode = "IT",
                countryName = "Italy",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "it_salerno",
                countryCode = "IT",
                countryName = "Italy",
                cityOrRegion = "Salerno",
                frequency = 869525000,
                bandwidth = 250000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_it_salerno,
            ),
            RNodeRegionalPreset(
                id = "it_brescia",
                countryCode = "IT",
                countryName = "Italy",
                cityOrRegion = "Brescia",
                frequency = 867200000,
                bandwidth = 125000,
                spreadingFactor = 7,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_it_brescia,
            ),
            RNodeRegionalPreset(
                id = "it_treviso",
                countryCode = "IT",
                countryName = "Italy",
                cityOrRegion = "Treviso",
                frequency = 867200000,
                bandwidth = 125000,
                spreadingFactor = 7,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_it_treviso,
            ),
            RNodeRegionalPreset(
                id = "it_genova",
                countryCode = "IT",
                countryName = "Italy",
                cityOrRegion = "Genova",
                frequency = 433600000,
                bandwidth = 125000,
                spreadingFactor = 12,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_it_genova,
            ),
            // ==================== MALAYSIA ====================
            RNodeRegionalPreset(
                id = "my_default",
                countryCode = "MY",
                countryName = "Malaysia",
                cityOrRegion = null,
                frequency = 920500000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_as923,
            ),
            // ==================== NETHERLANDS ====================
            RNodeRegionalPreset(
                id = "nl_default",
                countryCode = "NL",
                countryName = "Netherlands",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "nl_rotterdam",
                countryCode = "NL",
                countryName = "Netherlands",
                cityOrRegion = "Rotterdam Nesselande",
                frequency = 867200000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_nl_rotterdam,
            ),
            RNodeRegionalPreset(
                id = "be_brugge",
                countryCode = "BE",
                countryName = "Belgium",
                cityOrRegion = "Brugge",
                frequency = 869400000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_be_brugge,
            ),
            // ==================== NORWAY ====================
            RNodeRegionalPreset(
                id = "no_default",
                countryCode = "NO",
                countryName = "Norway",
                cityOrRegion = null,
                frequency = 869431250,
                bandwidth = 62500,
                spreadingFactor = 7,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_no_default,
            ),
            // ==================== SINGAPORE ====================
            RNodeRegionalPreset(
                id = "sg_default",
                countryCode = "SG",
                countryName = "Singapore",
                cityOrRegion = null,
                frequency = 920500000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_as923,
            ),
            // ==================== SPAIN ====================
            RNodeRegionalPreset(
                id = "es_default",
                countryCode = "ES",
                countryName = "Spain",
                cityOrRegion = null,
                frequency = 869525000,
                bandwidth = 250000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_es_default,
            ),
            RNodeRegionalPreset(
                id = "es_madrid",
                countryCode = "ES",
                countryName = "Spain",
                cityOrRegion = "Madrid",
                frequency = 868200000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_es_madrid,
            ),
            // ==================== SWEDEN ====================
            RNodeRegionalPreset(
                id = "se_default",
                countryCode = "SE",
                countryName = "Sweden",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "se_gothenburg",
                countryCode = "SE",
                countryName = "Sweden",
                cityOrRegion = "Gothenburg/Borås/Älvsered",
                frequency = 869525000,
                bandwidth = 250000,
                spreadingFactor = 10,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_se_gothenburg,
            ),
            RNodeRegionalPreset(
                id = "se_433",
                countryCode = "SE",
                countryName = "Sweden",
                cityOrRegion = "Gothenburg/Borås/Älvsered (433 MHz)",
                frequency = 433575000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 12,
                descriptionRes = R.string.preset_desc_se_433,
            ),
            RNodeRegionalPreset(
                id = "se_morbylanga",
                countryCode = "SE",
                countryName = "Sweden",
                cityOrRegion = "Mörbylånga/Bredinge",
                frequency = 866000000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_se_morbylanga,
            ),
            // ==================== SWITZERLAND ====================
            RNodeRegionalPreset(
                id = "ch_default",
                countryCode = "CH",
                countryName = "Switzerland",
                cityOrRegion = null,
                frequency = 868100000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_eu868,
            ),
            RNodeRegionalPreset(
                id = "ch_bern",
                countryCode = "CH",
                countryName = "Switzerland",
                cityOrRegion = "Bern",
                frequency = 868000000,
                bandwidth = 250000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_ch_bern,
            ),
            // ==================== THAILAND ====================
            RNodeRegionalPreset(
                id = "th_default",
                countryCode = "TH",
                countryName = "Thailand",
                cityOrRegion = null,
                frequency = 920500000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_as923,
            ),
            // ==================== UNITED KINGDOM ====================
            RNodeRegionalPreset(
                id = "gb_default",
                countryCode = "GB",
                countryName = "United Kingdom",
                cityOrRegion = null,
                frequency = 867500000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_gb_default,
            ),
            RNodeRegionalPreset(
                id = "gb_st_helens",
                countryCode = "GB",
                countryName = "United Kingdom",
                cityOrRegion = "St. Helens",
                frequency = 867500000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_gb_st_helens,
            ),
            RNodeRegionalPreset(
                id = "gb_edinburgh",
                countryCode = "GB",
                countryName = "United Kingdom",
                cityOrRegion = "Edinburgh",
                frequency = 867500000,
                bandwidth = 125000,
                spreadingFactor = 9,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_gb_edinburgh,
            ),
            RNodeRegionalPreset(
                id = "gb_edinburgh_2g4",
                countryCode = "GB",
                countryName = "United Kingdom",
                cityOrRegion = "Edinburgh (2.4 GHz)",
                frequency = 2427000000,
                bandwidth = 812500,
                spreadingFactor = 7,
                codingRate = 5,
                txPower = 14,
                descriptionRes = R.string.preset_desc_gb_edinburgh_2g4,
            ),
            // ==================== UNITED STATES ====================
            RNodeRegionalPreset(
                id = "us_default",
                countryCode = "US",
                countryName = "United States",
                cityOrRegion = null,
                frequency = 914875000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_us_default,
            ),
            RNodeRegionalPreset(
                id = "us_portsmouth",
                countryCode = "US",
                countryName = "United States",
                cityOrRegion = "Portsmouth, NH",
                frequency = 914875000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_us_portsmouth,
            ),
            RNodeRegionalPreset(
                id = "us_olympia",
                countryCode = "US",
                countryName = "United States",
                cityOrRegion = "Olympia, WA",
                frequency = 914875000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_us_olympia,
            ),
            RNodeRegionalPreset(
                id = "us_chicago",
                countryCode = "US",
                countryName = "United States",
                cityOrRegion = "Chicago, IL",
                frequency = 914875000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
                descriptionRes = R.string.preset_desc_us_chicago,
            ),
        )

    /**
     * Get presets grouped by country name.
     */
    fun getByCountry(): Map<String, List<RNodeRegionalPreset>> {
        return presets.groupBy { it.countryName }
    }

    /**
     * Get all unique countries sorted alphabetically.
     */
    fun getCountries(): List<String> {
        return presets.map { it.countryName }.distinct().sorted()
    }

    /**
     * Get presets for a specific country.
     * Returns the default preset first, followed by city-specific presets.
     */
    fun getPresetsForCountry(countryName: String): List<RNodeRegionalPreset> {
        return presets
            .filter { it.countryName == countryName }
            .sortedWith(compareBy({ it.cityOrRegion != null }, { it.cityOrRegion }))
    }

    /**
     * Find a preset that matches the given settings (for edit mode detection).
     */
    fun findMatchingPreset(
        frequency: Long,
        bandwidth: Int,
        spreadingFactor: Int,
    ): RNodeRegionalPreset? {
        return presets.find {
            it.frequency == frequency &&
                it.bandwidth == bandwidth &&
                it.spreadingFactor == spreadingFactor
        }
    }
}
