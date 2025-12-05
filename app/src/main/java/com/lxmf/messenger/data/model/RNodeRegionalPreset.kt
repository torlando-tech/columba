package com.lxmf.messenger.data.model

import android.bluetooth.BluetoothDevice

/**
 * Bluetooth connection type for RNode devices.
 */
enum class BluetoothType {
    CLASSIC,  // Bluetooth Classic (SPP/RFCOMM)
    BLE,      // Bluetooth Low Energy (Nordic UART Service)
    UNKNOWN,  // Not yet determined
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
    val rssi: Int?,       // Signal strength (BLE only)
    val isPaired: Boolean,
    val bluetoothDevice: BluetoothDevice? = null,
)

/**
 * Regional preset for RNode LoRa configuration.
 * Contains legally compliant frequency settings for specific regions.
 */
data class RNodeRegionalPreset(
    val id: String,
    val countryCode: String,      // ISO 3166-1 alpha-2 (e.g., "US", "DE")
    val countryName: String,
    val cityOrRegion: String?,    // null for country-wide default
    val frequency: Long,          // Center frequency in Hz
    val bandwidth: Int,           // Bandwidth in Hz
    val spreadingFactor: Int,     // LoRa SF (5-12)
    val codingRate: Int,          // LoRa CR (5-8)
    val txPower: Int,             // Transmission power in dBm
    val description: String,
)

/**
 * Repository for regional RNode presets.
 * Data sourced from: https://github.com/markqvist/Reticulum/wiki/Popular-RNode-Settings
 */
object RNodeRegionalPresets {

    val presets: List<RNodeRegionalPreset> = listOf(
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
            txPower = 22,
            description = "915-928 MHz AU band (default)",
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
            txPower = 22,
            description = "Sydney long-range configuration",
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
            txPower = 22,
            description = "Brisbane configuration",
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
            txPower = 22,
            description = "Western Sydney configuration",
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
            description = "868 MHz EU band",
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
            description = "Duffel configuration",
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
            description = "868 MHz EU band",
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
            description = "Turku configuration",
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
            description = "868 MHz EU band",
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
            description = "Darmstadt configuration",
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
            description = "Wiesbaden configuration",
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
            description = "868 MHz EU band",
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
            description = "Salerno configuration",
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
            description = "Brescia configuration",
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
            description = "Treviso configuration",
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
            description = "Genova 433 MHz configuration",
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
            txPower = 20,
            description = "920 MHz AS923 band",
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
            description = "868 MHz EU band",
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
            description = "Rotterdam Nesselande configuration",
        ),
        RNodeRegionalPreset(
            id = "nl_brugge",
            countryCode = "NL",
            countryName = "Netherlands",
            cityOrRegion = "Brugge",
            frequency = 869400000,
            bandwidth = 125000,
            spreadingFactor = 8,
            codingRate = 5,
            txPower = 14,
            description = "Brugge configuration",
        ),

        // ==================== NORWAY ====================
        RNodeRegionalPreset(
            id = "no_default",
            countryCode = "NO",
            countryName = "Norway",
            cityOrRegion = null,
            frequency = 869525000,
            bandwidth = 125000,
            spreadingFactor = 8,
            codingRate = 5,
            txPower = 14,
            description = "868 MHz EU band",
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
            txPower = 20,
            description = "920 MHz AS923 band",
        ),

        // ==================== SPAIN ====================
        RNodeRegionalPreset(
            id = "es_default",
            countryCode = "ES",
            countryName = "Spain",
            cityOrRegion = null,
            frequency = 868100000,
            bandwidth = 125000,
            spreadingFactor = 9,
            codingRate = 5,
            txPower = 14,
            description = "868 MHz EU band",
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
            description = "Madrid configuration",
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
            description = "868 MHz EU band",
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
            description = "Gothenburg area configuration",
        ),
        RNodeRegionalPreset(
            id = "se_gothenburg_433",
            countryCode = "SE",
            countryName = "Sweden",
            cityOrRegion = "Gothenburg (433 MHz)",
            frequency = 433575000,
            bandwidth = 125000,
            spreadingFactor = 8,
            codingRate = 5,
            txPower = 14,
            description = "Gothenburg 433 MHz alternative",
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
            description = "Mörbylånga/Bredinge configuration",
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
            description = "868 MHz EU band",
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
            description = "Bern configuration",
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
            txPower = 20,
            description = "920 MHz AS923 band",
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
            description = "868 MHz UK band",
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
            description = "St. Helens configuration",
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
            description = "Edinburgh 868 MHz configuration",
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
            description = "Edinburgh 2.4 GHz configuration",
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
            txPower = 22,
            description = "915 MHz ISM band (default)",
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
            txPower = 22,
            description = "Portsmouth, NH configuration",
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
            txPower = 22,
            description = "Olympia, WA configuration",
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
            txPower = 22,
            description = "Chicago, IL configuration",
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
