package com.lxmf.messenger.util

/**
 * Validates destination hash strings for Reticulum network addresses.
 *
 * A valid destination hash is:
 * - Exactly 32 hexadecimal characters (representing 16 bytes)
 * - Case-insensitive (normalized to lowercase)
 */
object DestinationHashValidator {
    private const val REQUIRED_LENGTH = 32
    private val HEX_PATTERN = Regex("^[a-fA-F0-9]{$REQUIRED_LENGTH}$")

    /**
     * Result of destination hash validation.
     */
    sealed class ValidationResult {
        /**
         * The hash is valid and has been normalized to lowercase.
         */
        data class Valid(val normalizedHash: String) : ValidationResult()

        /**
         * The hash is invalid with an error message describing the issue.
         */
        data class Error(val message: String) : ValidationResult()
    }

    /**
     * Validate a destination hash string.
     *
     * @param hash The destination hash to validate
     * @return ValidationResult.Valid with normalized hash, or ValidationResult.Error with message
     */
    fun validate(hash: String): ValidationResult {
        val trimmed = hash.trim()

        return when {
            trimmed.isEmpty() -> ValidationResult.Error("Hash cannot be empty")
            trimmed.length != REQUIRED_LENGTH ->
                ValidationResult.Error(
                    "Hash must be $REQUIRED_LENGTH characters (got ${trimmed.length})",
                )
            !HEX_PATTERN.matches(trimmed) ->
                ValidationResult.Error(
                    "Hash must contain only hex characters (0-9, a-f)",
                )
            else -> ValidationResult.Valid(trimmed.lowercase())
        }
    }

    /**
     * Check if a hash is valid without returning the normalized value.
     * Useful for quick validation checks in UI.
     */
    fun isValid(hash: String): Boolean = validate(hash) is ValidationResult.Valid

    /**
     * Get current character count for display (e.g., "12/32").
     */
    fun getCharacterCount(hash: String): String = "${hash.trim().length}/$REQUIRED_LENGTH"
}
