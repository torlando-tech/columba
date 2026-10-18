package network.columba.app.viewmodel

import androidx.annotation.StringRes
import network.columba.app.R
import network.columba.app.data.model.FrequencyRegion

/**
 * Localizable validation failure for an RNode configuration field: a reason
 * enum mapped to a string resource at the render site, plus format arguments.
 * Same shape as the other validation reason enums (i18n ticket 07); the
 * `util/validation` validators adopt it in ticket 10.
 */
enum class RNodeFieldErrorReason {
    NAME_REQUIRED,
    INVALID_NUMBER,
    FREQUENCY_RANGE,
    FREQUENCY_REQUIRED_RANGE,
    BANDWIDTH_MIN,
    BANDWIDTH_MAX,
    BANDWIDTH_REQUIRED,
    SF_MIN,
    SF_MAX,
    SF_REQUIRED,
    CR_MIN,
    CR_MAX,
    CR_REQUIRED,
    TX_POWER_MIN,
    TX_POWER_MAX,
    TX_POWER_REQUIRED,
    TX_POWER_REQUIRED_NO_REGION,
    AIRTIME_MIN,
    AIRTIME_MAX,
    AIRTIME_REGIONAL_MAX,
}

@Suppress("CyclomaticComplexMethod") // exhaustive enum-to-resource mapping
@StringRes
fun RNodeFieldErrorReason.messageResource(): Int =
    when (this) {
        RNodeFieldErrorReason.NAME_REQUIRED -> R.string.rnode_val_name_required
        RNodeFieldErrorReason.INVALID_NUMBER -> R.string.rnode_val_invalid_number
        RNodeFieldErrorReason.FREQUENCY_RANGE -> R.string.rnode_val_frequency_range
        RNodeFieldErrorReason.FREQUENCY_REQUIRED_RANGE -> R.string.rnode_val_frequency_required_range
        RNodeFieldErrorReason.BANDWIDTH_MIN -> R.string.rnode_val_bandwidth_min
        RNodeFieldErrorReason.BANDWIDTH_MAX -> R.string.rnode_val_bandwidth_max
        RNodeFieldErrorReason.BANDWIDTH_REQUIRED -> R.string.rnode_val_bandwidth_required
        RNodeFieldErrorReason.SF_MIN -> R.string.rnode_val_sf_min
        RNodeFieldErrorReason.SF_MAX -> R.string.rnode_val_sf_max
        RNodeFieldErrorReason.SF_REQUIRED -> R.string.rnode_val_sf_required
        RNodeFieldErrorReason.CR_MIN -> R.string.rnode_val_cr_min
        RNodeFieldErrorReason.CR_MAX -> R.string.rnode_val_cr_max
        RNodeFieldErrorReason.CR_REQUIRED -> R.string.rnode_val_cr_required
        RNodeFieldErrorReason.TX_POWER_MIN -> R.string.rnode_val_tx_power_min
        RNodeFieldErrorReason.TX_POWER_MAX -> R.string.rnode_val_tx_power_max
        RNodeFieldErrorReason.TX_POWER_REQUIRED -> R.string.rnode_val_tx_power_required
        RNodeFieldErrorReason.TX_POWER_REQUIRED_NO_REGION -> R.string.rnode_val_tx_power_required_no_region
        RNodeFieldErrorReason.AIRTIME_MIN -> R.string.rnode_val_airtime_min
        RNodeFieldErrorReason.AIRTIME_MAX -> R.string.rnode_val_airtime_max
        RNodeFieldErrorReason.AIRTIME_REGIONAL_MAX -> R.string.rnode_val_airtime_regional_max
    }

/** A field validation failure: [reason] plus positional format args for its resource. */
data class FieldError(
    val reason: RNodeFieldErrorReason,
    val args: List<Any> = emptyList(),
)

/** Wraps this error as a [UiMessage] so ViewModel state can carry it. */
fun FieldError.toUiMessage(): UiMessage = UiMessage.Text(reason.messageResource(), args)

/**
 * Validation result for a single field.
 */
data class FieldValidation(
    val isValid: Boolean,
    val error: FieldError? = null,
)

/**
 * Validation result for the entire RNode configuration.
 */
data class ConfigValidationResult(
    val isValid: Boolean,
    val nameError: FieldError? = null,
    val frequencyError: FieldError? = null,
    val bandwidthError: FieldError? = null,
    val spreadingFactorError: FieldError? = null,
    val codingRateError: FieldError? = null,
    val txPowerError: FieldError? = null,
    val stAlockError: FieldError? = null,
    val ltAlockError: FieldError? = null,
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
    private const val MIN_SF = 5
    private const val MAX_SF = 12
    private const val MIN_CR = 5
    private const val MAX_CR = 8
    private const val MIN_TX_POWER = 0
    private const val DEFAULT_MAX_TX_POWER = 27

    // Default frequency range (when no region is selected)
    private const val DEFAULT_MIN_FREQ = 137_000_000L
    private const val DEFAULT_MAX_FREQ = 3_000_000_000L

    /**
     * Validate the interface name.
     */
    fun validateName(name: String): FieldValidation {
        return if (name.isBlank()) {
            FieldValidation(false, FieldError(RNodeFieldErrorReason.NAME_REQUIRED))
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
            freq == null -> FieldValidation(false, FieldError(RNodeFieldErrorReason.INVALID_NUMBER))
            freq < minFreq || freq > maxFreq -> {
                val minMhz = minFreq / 1_000_000.0
                val maxMhz = maxFreq / 1_000_000.0
                FieldValidation(
                    false,
                    FieldError(RNodeFieldErrorReason.FREQUENCY_RANGE, listOf(minMhz, maxMhz)),
                )
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
            bw == null -> FieldValidation(false, FieldError(RNodeFieldErrorReason.INVALID_NUMBER))
            bw < MIN_BANDWIDTH -> FieldValidation(false, FieldError(RNodeFieldErrorReason.BANDWIDTH_MIN))
            bw > MAX_BANDWIDTH -> FieldValidation(false, FieldError(RNodeFieldErrorReason.BANDWIDTH_MAX))
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
            sf == null -> FieldValidation(false, FieldError(RNodeFieldErrorReason.INVALID_NUMBER))
            sf < MIN_SF -> FieldValidation(false, FieldError(RNodeFieldErrorReason.SF_MIN, listOf(MIN_SF)))
            sf > MAX_SF -> FieldValidation(false, FieldError(RNodeFieldErrorReason.SF_MAX, listOf(MAX_SF)))
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
            cr == null -> FieldValidation(false, FieldError(RNodeFieldErrorReason.INVALID_NUMBER))
            cr < MIN_CR -> FieldValidation(false, FieldError(RNodeFieldErrorReason.CR_MIN, listOf(MIN_CR)))
            cr > MAX_CR -> FieldValidation(false, FieldError(RNodeFieldErrorReason.CR_MAX, listOf(MAX_CR)))
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
            txp == null -> FieldValidation(false, FieldError(RNodeFieldErrorReason.INVALID_NUMBER))
            txp < MIN_TX_POWER -> FieldValidation(false, FieldError(RNodeFieldErrorReason.TX_POWER_MIN, listOf(MIN_TX_POWER)))
            txp > maxPower -> FieldValidation(false, FieldError(RNodeFieldErrorReason.TX_POWER_MAX, listOf(maxPower)))
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
            parsed == null -> FieldValidation(false, FieldError(RNodeFieldErrorReason.INVALID_NUMBER))
            parsed < 0 -> FieldValidation(false, FieldError(RNodeFieldErrorReason.AIRTIME_MIN))
            parsed > 100 -> FieldValidation(false, FieldError(RNodeFieldErrorReason.AIRTIME_MAX))
            maxAirtime != null && parsed > maxAirtime ->
                FieldValidation(
                    false,
                    FieldError(RNodeFieldErrorReason.AIRTIME_REGIONAL_MAX, listOf(maxAirtime)),
                )
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
                FieldError(
                    RNodeFieldErrorReason.FREQUENCY_REQUIRED_RANGE,
                    listOf(minFreq / 1_000_000.0, maxFreq / 1_000_000.0),
                )
            } else {
                freqResult.error
            }

        val bandwidthError =
            if (config.bandwidth.isBlank()) {
                FieldError(RNodeFieldErrorReason.BANDWIDTH_REQUIRED)
            } else {
                bwResult.error
            }

        val sfError =
            if (config.spreadingFactor.isBlank()) {
                FieldError(RNodeFieldErrorReason.SF_REQUIRED, listOf(MIN_SF, MAX_SF))
            } else {
                sfResult.error
            }

        val crError =
            if (config.codingRate.isBlank()) {
                FieldError(RNodeFieldErrorReason.CR_REQUIRED, listOf(MIN_CR, MAX_CR))
            } else {
                crResult.error
            }

        val txPowerError =
            if (config.txPower.isBlank()) {
                val maxPower = config.region?.maxTxPower ?: DEFAULT_MAX_TX_POWER
                val regionName = config.region?.name
                if (regionName != null) {
                    FieldError(RNodeFieldErrorReason.TX_POWER_REQUIRED, listOf(MIN_TX_POWER, maxPower, regionName))
                } else {
                    FieldError(RNodeFieldErrorReason.TX_POWER_REQUIRED_NO_REGION, listOf(MIN_TX_POWER, maxPower))
                }
            } else {
                txpResult.error
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
            nameError = nameResult.error,
            frequencyError = frequencyError,
            bandwidthError = bandwidthError,
            spreadingFactorError = sfError,
            codingRateError = crError,
            txPowerError = txPowerError,
            stAlockError = stAlockResult.error,
            ltAlockError = ltAlockResult.error,
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
