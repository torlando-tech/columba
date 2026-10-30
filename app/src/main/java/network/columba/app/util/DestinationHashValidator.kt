package network.columba.app.util

import androidx.annotation.StringRes
import network.columba.app.R

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
     * Classification of hash validation failures; the render site resolves
     * [messageResource] with [ValidationResult.Error.args].
     */
    enum class HashErrorReason {
        EMPTY,
        LENGTH,
        NOT_HEX,
    }

    /**
     * Result of destination hash validation.
     */
    sealed class ValidationResult {
        /**
         * The hash is valid and has been normalized to lowercase.
         */
        data class Valid(
            val normalizedHash: String,
        ) : ValidationResult()

        /**
         * The hash is invalid; [reason] + [args] resolve to the localized
         * message describing the issue.
         */
        data class Error(
            val reason: HashErrorReason,
            val args: List<Any> = emptyList(),
        ) : ValidationResult()
    }

    /**
     * Validate a destination hash string.
     *
     * @param hash The destination hash to validate
     * @return ValidationResult.Valid with normalized hash, or ValidationResult.Error with reason
     */
    fun validate(hash: String): ValidationResult {
        val trimmed = hash.trim()

        return when {
            trimmed.isEmpty() -> ValidationResult.Error(HashErrorReason.EMPTY)
            trimmed.length != REQUIRED_LENGTH ->
                ValidationResult.Error(
                    HashErrorReason.LENGTH,
                    listOf(REQUIRED_LENGTH, trimmed.length),
                )
            !HEX_PATTERN.matches(trimmed) ->
                ValidationResult.Error(HashErrorReason.NOT_HEX)
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

/** Localized message resource for each [DestinationHashValidator.HashErrorReason]. */
@StringRes
fun DestinationHashValidator.HashErrorReason.messageResource(): Int =
    when (this) {
        DestinationHashValidator.HashErrorReason.EMPTY -> R.string.hash_cannot_be_empty
        DestinationHashValidator.HashErrorReason.LENGTH -> R.string.hash_length_mismatch
        DestinationHashValidator.HashErrorReason.NOT_HEX -> R.string.hash_not_hex
    }
