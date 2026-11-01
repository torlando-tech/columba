package network.columba.app.util.validation

import network.columba.app.viewmodel.UiMessage
import network.columba.app.util.validation.ValidationConstants.ALLOWED_INTERFACE_PARAMS
import network.columba.app.util.validation.ValidationConstants.DESTINATION_HASH_LENGTH
import network.columba.app.util.validation.ValidationConstants.HEX_REGEX
import network.columba.app.util.validation.ValidationConstants.HOSTNAME_REGEX
import network.columba.app.util.validation.ValidationConstants.IDENTITY_PARTS_COUNT
import network.columba.app.util.validation.ValidationConstants.IPV4_REGEX
import network.columba.app.util.validation.ValidationConstants.IPV6_REGEX
import network.columba.app.util.validation.ValidationConstants.LXMF_IDENTITY_PREFIX
import network.columba.app.util.validation.ValidationConstants.MAX_BLE_PACKET_SIZE
import network.columba.app.util.validation.ValidationConstants.MAX_DEVICE_NAME_LENGTH
import network.columba.app.util.validation.ValidationConstants.MAX_INTERFACE_NAME_LENGTH
import network.columba.app.util.validation.ValidationConstants.MAX_MESSAGE_LENGTH
import network.columba.app.util.validation.ValidationConstants.MAX_NICKNAME_LENGTH
import network.columba.app.util.validation.ValidationConstants.MAX_PORT
import network.columba.app.util.validation.ValidationConstants.MAX_SEARCH_QUERY_LENGTH
import network.columba.app.util.validation.ValidationConstants.MIN_PORT
import network.columba.app.util.validation.ValidationConstants.PUBLIC_KEY_LENGTH

/**
 * Represents parsed identity input from user.
 * Can be either a full identity (hash + pubkey) or just a destination hash.
 */
sealed class IdentityInput {
    /**
     * Full identity with both destination hash and public key.
     * Can be added as an active contact immediately.
     *
     * @property destinationHash The 32-character hex destination hash
     * @property publicKey The 64-byte public key as ByteArray
     */
    data class FullIdentity(
        val destinationHash: String,
        val publicKey: ByteArray,
    ) : IdentityInput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FullIdentity
            if (destinationHash != other.destinationHash) return false
            if (!publicKey.contentEquals(other.publicKey)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = destinationHash.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    /**
     * Only destination hash available.
     * Must resolve public key from network before messaging.
     *
     * @property destinationHash The 32-character hex destination hash
     */
    data class DestinationHashOnly(
        val destinationHash: String,
    ) : IdentityInput()
}

/**
 * Core input validation utilities for the application.
 *
 * Provides comprehensive validation functions for:
 * - Message content and cryptographic data
 * - Network configuration (hostnames, IPs, ports)
 * - User inputs (nicknames, search queries)
 * - Interface configuration
 * - Text sanitization
 *
 * All validation functions return ValidationResult for type-safe error handling.
 */
object InputValidator {
    // ========== MESSAGE VALIDATION ==========

    /**
     * Validates message content.
     *
     * Checks:
     * - Message is not empty after trimming
     * - Message length is within limits
     *
     * @param content The message content to validate
     * @return ValidationResult.Success with trimmed content, or ValidationResult.Error
     */
    fun validateMessageContent(content: String): ValidationResult<String> {
        val trimmed = content.trim()

        return when {
            trimmed.isEmpty() ->
                ValidationResult.Error(InputErrorReason.MESSAGE_EMPTY)
            trimmed.length > MAX_MESSAGE_LENGTH ->
                ValidationResult.Error(InputErrorReason.MESSAGE_TOO_LONG, listOf(MAX_MESSAGE_LENGTH))
            else ->
                ValidationResult.Success(trimmed)
        }
    }

    // ========== CRYPTOGRAPHIC DATA VALIDATION ==========

    /**
     * Validates and converts a destination hash from hex string to byte array.
     *
     * Checks:
     * - Correct length (32 hex characters = 16 bytes)
     * - Valid hexadecimal format
     * - Safe conversion without crashes
     *
     * @param hash The hex-encoded destination hash
     * @return ValidationResult.Success with byte array, or ValidationResult.Error
     */
    fun validateDestinationHash(hash: String): ValidationResult<ByteArray> {
        val cleaned = hash.trim()

        // Check length (32 hex chars = 16 bytes)
        if (cleaned.length != DESTINATION_HASH_LENGTH * 2) {
            return ValidationResult.Error(
                InputErrorReason.HASH_LENGTH,
                listOf(DESTINATION_HASH_LENGTH * 2, cleaned.length),
            )
        }

        // Check format (hex only)
        if (!HEX_REGEX.matches(cleaned)) {
            return ValidationResult.Error(InputErrorReason.HASH_NOT_HEX)
        }

        // Safe conversion
        return try {
            val bytes =
                cleaned
                    .chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
            ValidationResult.Success(bytes)
        } catch (e: NumberFormatException) {
            ValidationResult.Error(InputErrorReason.HEX_FORMAT_INVALID)
        }
    }

    /**
     * Validates and converts a public key from hex string to byte array.
     *
     * Checks:
     * - Correct length (64 hex characters = 32 bytes)
     * - Valid hexadecimal format
     * - Safe conversion without crashes
     *
     * @param key The hex-encoded public key
     * @return ValidationResult.Success with byte array, or ValidationResult.Error
     */
    fun validatePublicKey(key: String): ValidationResult<ByteArray> {
        val cleaned = key.trim()

        // Check length (64 hex chars = 32 bytes)
        if (cleaned.length != PUBLIC_KEY_LENGTH * 2) {
            return ValidationResult.Error(
                InputErrorReason.PUBKEY_LENGTH,
                listOf(PUBLIC_KEY_LENGTH * 2, cleaned.length),
            )
        }

        // Check format (hex only)
        if (!HEX_REGEX.matches(cleaned)) {
            return ValidationResult.Error(InputErrorReason.PUBKEY_NOT_HEX)
        }

        // Safe conversion
        return try {
            val bytes =
                cleaned
                    .chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
            ValidationResult.Success(bytes)
        } catch (e: NumberFormatException) {
            ValidationResult.Error(InputErrorReason.HEX_FORMAT_INVALID)
        }
    }

    /**
     * Validates a complete LXMF identity string (lxma://hash:pubkey).
     *
     * @param identityString The full identity string
     * @return ValidationResult.Success with pair of (hash bytes, pubkey bytes), or ValidationResult.Error
     */
    fun validateIdentityString(identityString: String): ValidationResult<Pair<ByteArray, ByteArray>> {
        val trimmed = identityString.trim()

        if (!trimmed.startsWith(LXMF_IDENTITY_PREFIX)) {
            return ValidationResult.Error(InputErrorReason.IDENTITY_PREFIX, listOf(LXMF_IDENTITY_PREFIX))
        }

        val data = trimmed.removePrefix(LXMF_IDENTITY_PREFIX)
        val parts = data.split(":")

        if (parts.size != IDENTITY_PARTS_COUNT) {
            return ValidationResult.Error(
                InputErrorReason.IDENTITY_FORMAT,
                listOf(LXMF_IDENTITY_PREFIX + "hash:pubkey"),
            )
        }

        // Validate hash
        val hashResult = validateDestinationHash(parts[0])
        if (hashResult is ValidationResult.Error) {
            return ValidationResult.Error(
                InputErrorReason.HASH_INVALID,
                listOf(UiMessage.Text(hashResult.reason.messageResource(), hashResult.args)),
            )
        }

        // Validate public key
        val keyResult = validatePublicKey(parts[1])
        if (keyResult is ValidationResult.Error) {
            return ValidationResult.Error(
                InputErrorReason.PUBKEY_INVALID,
                listOf(UiMessage.Text(keyResult.reason.messageResource(), keyResult.args)),
            )
        }

        return ValidationResult.Success(
            Pair(hashResult.getOrThrow(), keyResult.getOrThrow()),
        )
    }

    /**
     * Parses user input and determines the identity format.
     *
     * Supports two formats:
     * 1. Full lxma:// URL: "lxma://<32-char-hash>:<128-char-pubkey>"
     * 2. Destination hash only: 32 hexadecimal characters (from Sideband's "Copy Address")
     *
     * @param input Raw user input string
     * @return ValidationResult containing parsed IdentityInput or error message
     */
    @Suppress("ReturnCount") // Early returns make validation logic clearer
    fun parseIdentityInput(input: String): ValidationResult<IdentityInput> {
        val trimmed = input.trim().lowercase()

        // Check for empty input
        if (trimmed.isEmpty()) {
            return ValidationResult.Error(InputErrorReason.IDENTITY_INPUT_REQUIRED)
        }

        // Try full lxma:// format first
        if (trimmed.startsWith(LXMF_IDENTITY_PREFIX)) {
            return when (val result = validateIdentityString(trimmed)) {
                is ValidationResult.Success -> {
                    val (hashBytes, pubKeyBytes) = result.value
                    // Convert hash bytes back to hex string for storage
                    val destHash = hashBytes.joinToString("") { "%02x".format(it) }
                    ValidationResult.Success(IdentityInput.FullIdentity(destHash, pubKeyBytes))
                }
                is ValidationResult.Error -> ValidationResult.Error(result.reason, result.args)
            }
        }

        // Try destination hash only (32 hex chars)
        if (trimmed.length == DESTINATION_HASH_LENGTH * 2) {
            // Validate it's valid hex
            if (!HEX_REGEX.matches(trimmed)) {
                return ValidationResult.Error(InputErrorReason.DEST_HASH_NOT_HEX)
            }
            return ValidationResult.Success(IdentityInput.DestinationHashOnly(trimmed))
        }

        // Neither format matches - provide helpful error
        return ValidationResult.Error(InputErrorReason.IDENTITY_INPUT_FORMAT)
    }

    // ========== NETWORK VALIDATION ==========

    /**
     * Validates a hostname or IP address.
     *
     * Accepts:
     * - Valid IPv4 addresses (192.168.1.1)
     * - Valid IPv6 addresses
     * - Valid DNS hostnames (example.com, sub.example.com)
     * - Single-label hostnames (localhost, server1)
     *
     * @param host The hostname or IP address to validate
     * @return ValidationResult.Success with cleaned host, or ValidationResult.Error
     */
    fun validateHostname(host: String): ValidationResult<String> {
        // Strip URI scheme prefixes — users sometimes paste full URLs instead of bare hostnames
        var cleaned =
            host
                .trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .removeSuffix("/")
                .split("/")
                .first() // Strip any path component after the hostname

        // Strip trailing :port (e.g. "example.com:8080" → "example.com")
        // IPv6 addresses always have multiple colons; hostname:port has exactly one.
        // This is safer than relying on IPV6_REGEX which may not cover all compressed forms.
        val colonCount = cleaned.count { it == ':' }
        if (colonCount == 1 && cleaned.matches(Regex("^.+:\\d+$"))) {
            cleaned = cleaned.substringBeforeLast(":")
        }

        return when {
            cleaned.isEmpty() ->
                ValidationResult.Error(InputErrorReason.HOSTNAME_EMPTY)
            IPV4_REGEX.matches(cleaned) ->
                ValidationResult.Success(cleaned)
            IPV6_REGEX.matches(cleaned) ->
                ValidationResult.Success(cleaned)
            // If it looks like an IPv4 (only digits and dots) but didn't match IPv4_REGEX, reject it
            // This prevents invalid IPs like "256.1.1.1" from being accepted as hostnames
            Regex("^[0-9.]+$").matches(cleaned) ->
                ValidationResult.Error(InputErrorReason.IP_INVALID)
            HOSTNAME_REGEX.matches(cleaned) ->
                ValidationResult.Success(cleaned)
            else ->
                ValidationResult.Error(InputErrorReason.HOSTNAME_INVALID)
        }
    }

    /**
     * Validates a port number.
     *
     * Checks:
     * - Value is numeric
     * - Value is in range 1-65535
     *
     * @param port The port as a string
     * @return ValidationResult.Success with integer port, or ValidationResult.Error
     */
    fun validatePort(port: String): ValidationResult<Int> {
        val parsed = port.trim().toIntOrNull()

        return when {
            parsed == null ->
                ValidationResult.Error(InputErrorReason.PORT_NOT_NUMBER)
            parsed !in MIN_PORT..MAX_PORT ->
                ValidationResult.Error(InputErrorReason.PORT_RANGE, listOf(MIN_PORT, MAX_PORT))
            else ->
                ValidationResult.Success(parsed)
        }
    }

    // ========== USER INPUT VALIDATION ==========

    /**
     * Validates a contact nickname.
     *
     * Checks:
     * - Not blank
     * - Within length limit
     *
     * @param nickname The nickname to validate
     * @return ValidationResult.Success with trimmed nickname, or ValidationResult.Error
     */
    fun validateNickname(nickname: String): ValidationResult<String> {
        val trimmed = nickname.trim()

        return when {
            trimmed.isBlank() ->
                ValidationResult.Error(InputErrorReason.NICKNAME_EMPTY)
            trimmed.length > MAX_NICKNAME_LENGTH ->
                ValidationResult.Error(InputErrorReason.NICKNAME_TOO_LONG, listOf(MAX_NICKNAME_LENGTH))
            else ->
                ValidationResult.Success(trimmed)
        }
    }

    /**
     * Validates an interface name.
     *
     * Checks:
     * - Not blank
     * - Within length limit
     *
     * @param name The interface name to validate
     * @return ValidationResult.Success with trimmed name, or ValidationResult.Error
     */
    fun validateInterfaceName(name: String): ValidationResult<String> {
        val trimmed = name.trim()

        return when {
            trimmed.isBlank() ->
                ValidationResult.Error(InputErrorReason.INTERFACE_NAME_EMPTY)
            trimmed.length > MAX_INTERFACE_NAME_LENGTH ->
                ValidationResult.Error(InputErrorReason.INTERFACE_NAME_TOO_LONG, listOf(MAX_INTERFACE_NAME_LENGTH))
            else ->
                ValidationResult.Success(trimmed)
        }
    }

    /**
     * Validates that an interface name is unique among existing interfaces.
     *
     * RNS config files use section names like [[Interface Name]], so duplicate names
     * will cause config parsing errors that crash the service.
     *
     * @param name The interface name to validate
     * @param existingNames List of names already in use by other interfaces
     * @param excludeName Optional name to exclude from the check (for editing existing interfaces)
     * @return ValidationResult.Success if unique, or ValidationResult.Error if duplicate
     */
    fun validateInterfaceNameUniqueness(
        name: String,
        existingNames: List<String>,
        excludeName: String? = null,
    ): ValidationResult<String> {
        val trimmed = name.trim()
        val namesToCheck =
            if (excludeName != null) {
                existingNames.filter { it != excludeName }
            } else {
                existingNames
            }

        return if (namesToCheck.any { it.equals(trimmed, ignoreCase = true) }) {
            ValidationResult.Error(InputErrorReason.INTERFACE_NAME_DUPLICATE)
        } else {
            ValidationResult.Success(trimmed)
        }
    }

    /**
     * Validates a BLE device name.
     *
     * Checks:
     * - Not blank
     * - Within length limit
     *
     * @param name The device name to validate
     * @return ValidationResult.Success with trimmed name, or ValidationResult.Error
     */
    fun validateDeviceName(name: String): ValidationResult<String> {
        val trimmed = name.trim()

        return when {
            trimmed.isBlank() ->
                ValidationResult.Error(InputErrorReason.DEVICE_NAME_EMPTY)
            trimmed.length > MAX_DEVICE_NAME_LENGTH ->
                ValidationResult.Error(InputErrorReason.DEVICE_NAME_TOO_LONG, listOf(MAX_DEVICE_NAME_LENGTH))
            else ->
                ValidationResult.Success(trimmed)
        }
    }

    /**
     * Validates a search query.
     *
     * Checks:
     * - Within length limit
     * - Returns empty string if blank (search queries can be empty)
     *
     * Leading/trailing whitespace is preserved here and trimmed at match time
     * by the consumers, so users can type spaces in live search fields without
     * the field fighting them on every keystroke.
     *
     * @param query The search query to validate
     * @return ValidationResult.Success with sanitized query
     */
    fun validateSearchQuery(query: String): ValidationResult<String> {
        val sanitized = sanitizeSearchQuery(query, MAX_SEARCH_QUERY_LENGTH)
        return ValidationResult.Success(sanitized)
    }

    /**
     * Validates an interface configuration parameter name.
     *
     * @param paramName The parameter name to validate
     * @return ValidationResult.Success if whitelisted, or ValidationResult.Error
     */
    fun validateConfigParameter(paramName: String): ValidationResult<String> =
        if (paramName in ALLOWED_INTERFACE_PARAMS) {
            ValidationResult.Success(paramName)
        } else {
            ValidationResult.Error(InputErrorReason.CONFIG_PARAM_UNKNOWN, listOf(paramName))
        }

    // ========== BLE VALIDATION ==========

    /**
     * Validates BLE packet data size.
     *
     * @param data The BLE data packet
     * @return ValidationResult.Success if within size limits, or ValidationResult.Error
     */
    fun validateBlePacketSize(data: ByteArray): ValidationResult<ByteArray> =
        if (data.size > MAX_BLE_PACKET_SIZE) {
            ValidationResult.Error(InputErrorReason.BLE_PACKET_TOO_LARGE, listOf(data.size, MAX_BLE_PACKET_SIZE))
        } else {
            ValidationResult.Success(data)
        }

    // ========== TEXT SANITIZATION ==========

    /**
     * Sanitizes text input by removing control characters and enforcing length limits.
     *
     * Operations performed:
     * - Trim whitespace from start and end
     * - Remove all control characters (including null bytes)
     * - Normalize multiple spaces to single space
     * - Truncate to maximum length
     *
     * @param text The text to sanitize
     * @param maxLength Maximum allowed length after sanitization
     * @return Sanitized text
     */
    fun sanitizeText(
        text: String,
        maxLength: Int,
    ): String =
        text
            .trim()
            .replace(Regex("\\p{C}"), "") // Remove control characters
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .take(maxLength)

    /**
     * Sanitizes a live search query without trimming leading/trailing whitespace.
     *
     * Unlike [sanitizeText], this variant keeps the text the user is currently
     * composing: control characters are removed and runs of whitespace are
     * normalized, but spaces typed at the end of the query are preserved so a
     * controlled text field does not fight the user on every keystroke.
     * Consumers trim the query at match time, so preserved trailing spaces have
     * no effect on filtering.
     *
     * @param query The search query to sanitize
     * @param maxLength Maximum allowed length after sanitization
     * @return Sanitized query with internal spacing and trailing spaces intact
     */
    fun sanitizeSearchQuery(
        query: String,
        maxLength: Int,
    ): String =
        query
            .replace(Regex("\\p{C}"), "") // Remove control characters
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .take(maxLength)

    /**
     * Extension function for safe hex string to byte array conversion.
     *
     * Validates format before conversion to prevent crashes.
     *
     * @return Result.success with byte array, or Result.failure with exception
     */
    fun String.safeHexToBytes(): Result<ByteArray> {
        val cleaned = this.trim()

        // Empty string is valid (empty byte array)
        if (cleaned.isEmpty()) {
            return Result.success(byteArrayOf())
        }

        // Check format
        if (!HEX_REGEX.matches(cleaned)) {
            return Result.failure(IllegalArgumentException("Invalid hexadecimal format"))
        }

        // Check even length
        if (cleaned.length % 2 != 0) {
            return Result.failure(IllegalArgumentException("Hex string must have even length"))
        }

        return try {
            val bytes =
                cleaned
                    .chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
            Result.success(bytes)
        } catch (e: NumberFormatException) {
            Result.failure(e)
        }
    }

    /**
     * Extension function to convert byte array to hex string.
     *
     * @return Lowercase hex string representation
     */
    fun ByteArray.toHexString(): String = this.joinToString("") { "%02x".format(it) }
}
