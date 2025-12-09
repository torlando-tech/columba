package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.data.model.FrequencyRegion

/**
 * Validation result for a single field.
 */
data class FieldValidation(
    val isValid: Boolean,
    val errorMessage: String? = null,
)

/**
 * Validation result for the entire RNode configuration.
 */
data class ConfigValidationResult(
    val isValid: Boolean,
    val nameError: String? = null,
    val frequencyError: String? = null,
    val bandwidthError: String? = null,
    val spreadingFactorError: String? = null,
    val codingRateError: String? = null,
    val txPowerError: String? = null,
    val stAlockError: String? = null,
    val ltAlockError: String? = null,
)

/**
 * Input parameters for RNode configuration validation.
 */
data class RNodeConfigInput(
    val name: String,
    val frequency: String,
    val bandwidth: String,
    val spreadingFactor: String,
    val codingRate: String,
    val txPower: String,
    val stAlock: String,
    val ltAlock: String,
    val region: FrequencyRegion?,
)

/**
 * Validates RNode configuration parameters.
 *
 * Extracted from RNodeWizardViewModel to reduce class complexity and improve testability.
 */
object RNodeConfigValidator {
    // Hardware limits
    private const val MIN_BANDWIDTH = 7800
    private const val MAX_BANDWIDTH = 1625000
    private const val MIN_SF = 7
    private const val MAX_SF = 12
    private const val MIN_CR = 5
    private const val MAX_CR = 8
    private const val MIN_TX_POWER = 0
    private const val DEFAULT_MAX_TX_POWER = 22

    // Default frequency range (when no region is selected)
    private const val DEFAULT_MIN_FREQ = 137_000_000L
    private const val DEFAULT_MAX_FREQ = 3_000_000_000L

    /**
     * Validate the interface name.
     */
    fun validateName(name: String): FieldValidation {
        return if (name.isBlank()) {
            FieldValidation(false, "Interface name is required")
        } else {
            FieldValidation(true)
        }
    }

    /**
     * Validate frequency against region limits.
     */
    fun validateFrequency(
        value: String,
        region: FrequencyRegion?,
    ): FieldValidation {
        val freq = value.toLongOrNull()
        val minFreq = region?.frequencyStart ?: DEFAULT_MIN_FREQ
        val maxFreq = region?.frequencyEnd ?: DEFAULT_MAX_FREQ

        return when {
            value.isBlank() -> FieldValidation(true) // Allow empty while typing
            freq == null -> FieldValidation(false, "Invalid number")
            freq < minFreq || freq > maxFreq -> {
                val minMhz = minFreq / 1_000_000.0
                val maxMhz = maxFreq / 1_000_000.0
                FieldValidation(false, "Must be %.1f-%.1f MHz".format(minMhz, maxMhz))
            }
            else -> FieldValidation(true)
        }
    }

    /**
     * Validate bandwidth.
     */
    fun validateBandwidth(value: String): FieldValidation {
        val bw = value.toIntOrNull()
        return when {
            value.isBlank() -> FieldValidation(true) // Allow empty while typing
            bw == null -> FieldValidation(false, "Invalid number")
            bw < MIN_BANDWIDTH -> FieldValidation(false, "Must be >= 7.8 kHz")
            bw > MAX_BANDWIDTH -> FieldValidation(false, "Must be <= 1625 kHz")
            else -> FieldValidation(true)
        }
    }

    /**
     * Validate spreading factor.
     */
    fun validateSpreadingFactor(value: String): FieldValidation {
        val sf = value.toIntOrNull()
        return when {
            value.isBlank() -> FieldValidation(true) // Allow empty while typing
            sf == null -> FieldValidation(false, "Invalid number")
            sf < MIN_SF -> FieldValidation(false, "Must be >= $MIN_SF")
            sf > MAX_SF -> FieldValidation(false, "Must be <= $MAX_SF")
            else -> FieldValidation(true)
        }
    }

    /**
     * Validate coding rate.
     */
    fun validateCodingRate(value: String): FieldValidation {
        val cr = value.toIntOrNull()
        return when {
            value.isBlank() -> FieldValidation(true) // Allow empty while typing
            cr == null -> FieldValidation(false, "Invalid number")
            cr < MIN_CR -> FieldValidation(false, "Must be >= $MIN_CR")
            cr > MAX_CR -> FieldValidation(false, "Must be <= $MAX_CR")
            else -> FieldValidation(true)
        }
    }

    /**
     * Validate TX power against region limits.
     */
    fun validateTxPower(
        value: String,
        region: FrequencyRegion?,
    ): FieldValidation {
        val maxPower = region?.maxTxPower ?: DEFAULT_MAX_TX_POWER
        val txp = value.toIntOrNull()
        return when {
            value.isBlank() -> FieldValidation(true) // Allow empty while typing
            txp == null -> FieldValidation(false, "Invalid number")
            txp < MIN_TX_POWER -> FieldValidation(false, "Must be >= $MIN_TX_POWER")
            txp > maxPower -> FieldValidation(false, "Max: $maxPower dBm")
            else -> FieldValidation(true)
        }
    }

    /**
     * Validate airtime limit against region duty cycle.
     */
    fun validateAirtimeLimit(
        value: String,
        region: FrequencyRegion?,
    ): FieldValidation {
        val maxAirtime =
            region?.let {
                if (it.dutyCycle < 100) it.dutyCycle.toDouble() else null
            }
        val parsed = value.toDoubleOrNull()
        return when {
            value.isBlank() -> FieldValidation(true) // Empty is allowed (no limit)
            parsed == null -> FieldValidation(false, "Invalid number")
            parsed < 0 -> FieldValidation(false, "Must be >= 0")
            parsed > 100 -> FieldValidation(false, "Must be <= 100%")
            maxAirtime != null && parsed > maxAirtime ->
                FieldValidation(false, "Max: $maxAirtime% (regional limit)")
            else -> FieldValidation(true)
        }
    }

    /**
     * Validate the full configuration silently (no error messages, just pass/fail).
     */
    @Suppress("ReturnCount")
    fun validateConfigSilent(config: RNodeConfigInput): Boolean {
        if (!validateName(config.name).isValid) return false
        if (!validateFrequency(config.frequency, config.region).isValid) return false
        // For silent validation, require non-empty values
        if (config.frequency.isBlank()) return false
        if (!validateBandwidth(config.bandwidth).isValid) return false
        if (config.bandwidth.isBlank()) return false
        if (!validateSpreadingFactor(config.spreadingFactor).isValid) return false
        if (config.spreadingFactor.isBlank()) return false
        if (!validateCodingRate(config.codingRate).isValid) return false
        if (config.codingRate.isBlank()) return false
        if (!validateTxPower(config.txPower, config.region).isValid) return false
        if (config.txPower.isBlank()) return false
        if (!validateAirtimeLimit(config.stAlock, config.region).isValid) return false
        if (!validateAirtimeLimit(config.ltAlock, config.region).isValid) return false
        return true
    }

    /**
     * Validate the full configuration silently (no error messages, just pass/fail).
     * Convenience overload with individual parameters.
     */
    @Suppress("LongParameterList")
    fun validateConfigSilent(
        name: String,
        frequency: String,
        bandwidth: String,
        spreadingFactor: String,
        codingRate: String,
        txPower: String,
        stAlock: String,
        ltAlock: String,
        region: FrequencyRegion?,
    ): Boolean =
        validateConfigSilent(
            RNodeConfigInput(name, frequency, bandwidth, spreadingFactor, codingRate, txPower, stAlock, ltAlock, region),
        )

    /**
     * Validate the full configuration with error messages.
     */
    @Suppress("CyclomaticComplexMethod")
    fun validateConfig(config: RNodeConfigInput): ConfigValidationResult {
        val nameResult = validateName(config.name)
        val freqResult = validateFrequency(config.frequency, config.region)
        val bwResult = validateBandwidth(config.bandwidth)
        val sfResult = validateSpreadingFactor(config.spreadingFactor)
        val crResult = validateCodingRate(config.codingRate)
        val txpResult = validateTxPower(config.txPower, config.region)
        val stAlockResult = validateAirtimeLimit(config.stAlock, config.region)
        val ltAlockResult = validateAirtimeLimit(config.ltAlock, config.region)

        // For full validation, also check that required fields are not empty
        val frequencyError =
            if (config.frequency.isBlank()) {
                val minFreq = config.region?.frequencyStart ?: DEFAULT_MIN_FREQ
                val maxFreq = config.region?.frequencyEnd ?: DEFAULT_MAX_FREQ
                "Frequency must be %.1f-%.1f MHz".format(
                    minFreq / 1_000_000.0,
                    maxFreq / 1_000_000.0,
                )
            } else {
                freqResult.errorMessage
            }

        val bandwidthError =
            if (config.bandwidth.isBlank()) {
                "Bandwidth must be 7.8-1625 kHz"
            } else {
                bwResult.errorMessage
            }

        val sfError =
            if (config.spreadingFactor.isBlank()) {
                "SF must be $MIN_SF-$MAX_SF"
            } else {
                sfResult.errorMessage
            }

        val crError =
            if (config.codingRate.isBlank()) {
                "CR must be $MIN_CR-$MAX_CR"
            } else {
                crResult.errorMessage
            }

        val txPowerError =
            if (config.txPower.isBlank()) {
                val maxPower = config.region?.maxTxPower ?: DEFAULT_MAX_TX_POWER
                val regionName = config.region?.name ?: "this region"
                "TX power must be $MIN_TX_POWER-$maxPower dBm for $regionName"
            } else {
                txpResult.errorMessage
            }

        val isValid =
            nameResult.isValid &&
                config.frequency.isNotBlank() && freqResult.isValid &&
                config.bandwidth.isNotBlank() && bwResult.isValid &&
                config.spreadingFactor.isNotBlank() && sfResult.isValid &&
                config.codingRate.isNotBlank() && crResult.isValid &&
                config.txPower.isNotBlank() && txpResult.isValid &&
                stAlockResult.isValid &&
                ltAlockResult.isValid

        return ConfigValidationResult(
            isValid = isValid,
            nameError = nameResult.errorMessage,
            frequencyError = frequencyError,
            bandwidthError = bandwidthError,
            spreadingFactorError = sfError,
            codingRateError = crError,
            txPowerError = txPowerError,
            stAlockError = stAlockResult.errorMessage,
            ltAlockError = ltAlockResult.errorMessage,
        )
    }

    /**
     * Validate the full configuration with error messages.
     * Convenience overload with individual parameters.
     */
    @Suppress("LongParameterList")
    fun validateConfig(
        name: String,
        frequency: String,
        bandwidth: String,
        spreadingFactor: String,
        codingRate: String,
        txPower: String,
        stAlock: String,
        ltAlock: String,
        region: FrequencyRegion?,
    ): ConfigValidationResult =
        validateConfig(
            RNodeConfigInput(name, frequency, bandwidth, spreadingFactor, codingRate, txPower, stAlock, ltAlock, region),
        )

    /**
     * Get the maximum TX power for a region.
     */
    fun getMaxTxPower(region: FrequencyRegion?): Int {
        return region?.maxTxPower ?: DEFAULT_MAX_TX_POWER
    }

    /**
     * Get the frequency range for a region.
     */
    fun getFrequencyRange(region: FrequencyRegion?): Pair<Long, Long> {
        return if (region != null) {
            region.frequencyStart to region.frequencyEnd
        } else {
            DEFAULT_MIN_FREQ to DEFAULT_MAX_FREQ
        }
    }

    /**
     * Get the maximum airtime limit for a region (null if no limit).
     */
    fun getMaxAirtimeLimit(region: FrequencyRegion?): Double? {
        return region?.let {
            if (it.dutyCycle < 100) it.dutyCycle.toDouble() else null
        }
    }
}
