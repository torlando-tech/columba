package network.columba.app.util.validation

import androidx.annotation.StringRes
import network.columba.app.R

/**
 * Classification of every user-facing [InputValidator] failure. The render
 * site resolves [messageResource] and formats it with [ValidationResult.Error.args],
 * so validation messages follow the app locale instead of a baked-in English
 * string.
 */
enum class InputErrorReason {
    MESSAGE_EMPTY,
    MESSAGE_TOO_LONG,
    HASH_LENGTH,
    HASH_NOT_HEX,
    HEX_FORMAT_INVALID,
    PUBKEY_LENGTH,
    PUBKEY_NOT_HEX,
    IDENTITY_PREFIX,
    IDENTITY_FORMAT,
    HASH_INVALID,
    PUBKEY_INVALID,
    IDENTITY_INPUT_REQUIRED,
    DEST_HASH_NOT_HEX,
    IDENTITY_INPUT_FORMAT,
    HOSTNAME_EMPTY,
    IP_INVALID,
    HOSTNAME_INVALID,
    PORT_NOT_NUMBER,
    PORT_RANGE,
    NICKNAME_EMPTY,
    NICKNAME_TOO_LONG,
    INTERFACE_NAME_EMPTY,
    INTERFACE_NAME_TOO_LONG,
    INTERFACE_NAME_DUPLICATE,
    DEVICE_NAME_EMPTY,
    DEVICE_NAME_TOO_LONG,
    CONFIG_PARAM_UNKNOWN,
    BLE_PACKET_TOO_LARGE,
}

/** Localized message resource for each [InputErrorReason]. */
@StringRes
@Suppress("CyclomaticComplexMethod") // exhaustive enum-to-resource mapping
fun InputErrorReason.messageResource(): Int =
    when (this) {
        InputErrorReason.MESSAGE_EMPTY -> R.string.validation_message_empty
        InputErrorReason.MESSAGE_TOO_LONG -> R.string.validation_message_too_long
        InputErrorReason.HASH_LENGTH -> R.string.validation_hash_length
        InputErrorReason.HASH_NOT_HEX -> R.string.validation_hash_not_hex
        InputErrorReason.HEX_FORMAT_INVALID -> R.string.validation_invalid_hex_format
        InputErrorReason.PUBKEY_LENGTH -> R.string.validation_pubkey_length
        InputErrorReason.PUBKEY_NOT_HEX -> R.string.validation_pubkey_not_hex
        InputErrorReason.IDENTITY_PREFIX -> R.string.validation_identity_prefix
        InputErrorReason.IDENTITY_FORMAT -> R.string.validation_identity_format
        InputErrorReason.HASH_INVALID -> R.string.validation_invalid_hash
        InputErrorReason.PUBKEY_INVALID -> R.string.validation_invalid_pubkey
        InputErrorReason.IDENTITY_INPUT_REQUIRED -> R.string.validation_identity_input_required
        InputErrorReason.DEST_HASH_NOT_HEX -> R.string.validation_dest_hash_not_hex
        InputErrorReason.IDENTITY_INPUT_FORMAT -> R.string.validation_identity_input_format
        InputErrorReason.HOSTNAME_EMPTY -> R.string.validation_hostname_empty
        InputErrorReason.IP_INVALID -> R.string.validation_invalid_ip
        InputErrorReason.HOSTNAME_INVALID -> R.string.validation_hostname_invalid
        InputErrorReason.PORT_NOT_NUMBER -> R.string.validation_port_not_number
        InputErrorReason.PORT_RANGE -> R.string.validation_port_range
        InputErrorReason.NICKNAME_EMPTY -> R.string.validation_nickname_empty
        InputErrorReason.NICKNAME_TOO_LONG -> R.string.validation_nickname_too_long
        InputErrorReason.INTERFACE_NAME_EMPTY -> R.string.validation_interface_name_empty
        InputErrorReason.INTERFACE_NAME_TOO_LONG -> R.string.validation_interface_name_too_long
        InputErrorReason.INTERFACE_NAME_DUPLICATE -> R.string.validation_interface_name_duplicate
        InputErrorReason.DEVICE_NAME_EMPTY -> R.string.validation_device_name_empty
        InputErrorReason.DEVICE_NAME_TOO_LONG -> R.string.validation_device_name_too_long
        InputErrorReason.CONFIG_PARAM_UNKNOWN -> R.string.validation_unknown_config_param
        InputErrorReason.BLE_PACKET_TOO_LARGE -> R.string.validation_ble_packet_too_large
    }

/**
 * Represents the result of a validation operation.
 *
 * Validation can either succeed with a validated value or fail with a typed
 * error. This sealed class provides a type-safe way to handle validation
 * results.
 */
sealed class ValidationResult<out T> {
    /**
     * Validation succeeded with the given value.
     *
     * @param value The validated and potentially transformed value
     */
    data class Success<T>(val value: T) : ValidationResult<T>()

    /**
     * Validation failed with a localized-message reason.
     *
     * @param reason Classification of the failure; resolve via [messageResource]
     * @param args Format arguments for the localized message (numbers, nested
     *   localized messages, etc.)
     * @param field Optional field name for context (useful in forms with multiple fields)
     */
    data class Error(
        val reason: InputErrorReason,
        val args: List<Any> = emptyList(),
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
            is Error -> throw IllegalArgumentException(reason.name)
        }
}
