package com.lxmf.messenger.util.validation

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
     * Result of device name validation.
     */
    sealed class ValidationResult {
        /**
         * The device name is valid with no issues.
         */
        object Valid : ValidationResult()

        /**
         * The device name has a critical error that prevents proceeding.
         * @param message The error message to display to the user
         */
        data class Error(val message: String) : ValidationResult()

        /**
         * The device name has a warning but can still proceed with caution.
         * @param message The warning message to display to the user
         */
        data class Warning(val message: String) : ValidationResult()
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
                ValidationResult.Error("Device name must be $MAX_DEVICE_NAME_LENGTH characters or less")
            name.isNotBlank() && !name.startsWith("RNode", ignoreCase = true) ->
                ValidationResult.Warning("Device may not be an RNode. Proceed with caution.")
            else -> ValidationResult.Valid
        }
    }
}
