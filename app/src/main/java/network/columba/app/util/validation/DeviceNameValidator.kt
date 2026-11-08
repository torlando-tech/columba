package network.columba.app.util.validation

import androidx.annotation.StringRes
import network.columba.app.R

/**
 * Validates Bluetooth device names for RNode connections.
 * Extracted from RNodeWizardViewModel for testability.
 */
object DeviceNameValidator {
    /**
     * Maximum allowed length for Bluetooth device names.
     * Standard Bluetooth device name limit.
     */
    const val MAX_DEVICE_NAME_LENGTH = 32

    /**
     * Why device name validation failed; each reason maps to a string
     * resource at the render site (i18n ticket 07).
     */
    enum class DeviceNameErrorReason {
        TOO_LONG,
        NOT_RNODE,
    }

    /** A validation failure: [reason] plus positional format args for its resource. */
    data class DeviceNameError(
        val reason: DeviceNameErrorReason,
        val args: List<Any> = emptyList(),
    )

    /**
     * Result of device name validation.
     */
    sealed class ValidationResult {
        /**
         * The device name is valid with no issues.
         */
        object Valid : ValidationResult()

        /**
         * The device name has a critical error that prevents proceeding.
         * @param error The localized error to display to the user
         */
        data class Error(val error: DeviceNameError) : ValidationResult()

        /**
         * The device name has a warning but can still proceed with caution.
         * @param warning The localized warning to display to the user
         */
        data class Warning(val warning: DeviceNameError) : ValidationResult()
    }

    /**
     * Validates a device name for RNode connection.
     *
     * Validation rules:
     * - Device name must not exceed [MAX_DEVICE_NAME_LENGTH] characters (returns Error)
     * - Device name should start with "RNode" (case-insensitive) or be blank (returns Warning if not)
     *
     * @param name The device name to validate
     * @return ValidationResult indicating if the name is Valid, has an Error, or has a Warning
     */
    fun validate(name: String): ValidationResult {
        return when {
            name.length > MAX_DEVICE_NAME_LENGTH ->
                ValidationResult.Error(
                    DeviceNameError(
                        DeviceNameErrorReason.TOO_LONG,
                        listOf(MAX_DEVICE_NAME_LENGTH),
                    ),
                )
            name.isNotBlank() && !name.startsWith("RNode", ignoreCase = true) ->
                ValidationResult.Warning(
                    DeviceNameError(DeviceNameErrorReason.NOT_RNODE),
                )
            else -> ValidationResult.Valid
        }
    }
}

@StringRes
fun DeviceNameValidator.DeviceNameErrorReason.messageResource(): Int =
    when (this) {
        DeviceNameValidator.DeviceNameErrorReason.TOO_LONG -> R.string.rnode_device_name_too_long
        DeviceNameValidator.DeviceNameErrorReason.NOT_RNODE -> R.string.rnode_device_name_not_rnode
    }

