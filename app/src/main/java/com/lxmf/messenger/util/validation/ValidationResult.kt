package com.lxmf.messenger.util.validation

/**
 * Represents the result of a validation operation.
 *
 * Validation can either succeed with a validated value or fail with an error message.
 * This sealed class provides a type-safe way to handle validation results.
 */
sealed class ValidationResult<out T> {
    /**
     * Validation succeeded with the given value.
     *
     * @param value The validated and potentially transformed value
     */
    data class Success<T>(val value: T) : ValidationResult<T>()

    /**
     * Validation failed with an error message.
     *
     * @param message User-friendly error message describing why validation failed
     * @param field Optional field name for context (useful in forms with multiple fields)
     */
    data class Error(
        val message: String,
        val field: String? = null,
    ) : ValidationResult<Nothing>()

    /**
     * Returns true if this is a Success result.
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * Returns true if this is an Error result.
     */
    fun isError(): Boolean = this is Error

    /**
     * Returns the value if Success, or null if Error.
     */
    fun getOrNull(): T? =
        when (this) {
            is Success -> value
            is Error -> null
        }

    /**
     * Returns the value if Success, or throws an exception if Error.
     */
    fun getOrThrow(): T =
        when (this) {
            is Success -> value
            is Error -> throw IllegalArgumentException(message)
        }
}
