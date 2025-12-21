package com.lxmf.messenger.util.validation

import com.lxmf.messenger.util.validation.InputValidator.safeHexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for InputValidator validation functions.
 *
 * Tests cover:
 * - Valid inputs that should pass
 * - Invalid inputs that should fail
 * - Edge cases (empty, max length, special characters)
 * - Boundary conditions
 */
@OptIn(ExperimentalStdlibApi::class)
class InputValidatorTest {
    // ========== MESSAGE VALIDATION TESTS ==========

    @Test
    fun `validateMessageContent - valid message passes`() {
        val result = InputValidator.validateMessageContent("Hello, world!")
        assertTrue(result is ValidationResult.Success)
        assertEquals("Hello, world!", result.getOrNull())
    }

    @Test
    fun `validateMessageContent - trims whitespace`() {
        val result = InputValidator.validateMessageContent("  Hello  ")
        assertTrue(result is ValidationResult.Success)
        assertEquals("Hello", result.getOrNull())
    }

    @Test
    fun `validateMessageContent - empty string fails`() {
        val result = InputValidator.validateMessageContent("")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateMessageContent - whitespace only fails`() {
        val result = InputValidator.validateMessageContent("   ")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateMessageContent - too long message fails`() {
        val longMessage = "a".repeat(20000)
        val result = InputValidator.validateMessageContent(longMessage)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("too long"))
    }

    @Test
    fun `validateMessageContent - max length passes`() {
        val maxMessage = "a".repeat(ValidationConstants.MAX_MESSAGE_LENGTH)
        val result = InputValidator.validateMessageContent(maxMessage)
        assertTrue(result is ValidationResult.Success)
    }

    // ========== DESTINATION HASH VALIDATION TESTS ==========

    @Test
    fun `validateDestinationHash - valid 32-char hex passes`() {
        val validHash = "0123456789abcdef0123456789abcdef"
        val result = InputValidator.validateDestinationHash(validHash)
        assertTrue(result is ValidationResult.Success)
        assertEquals(16, result.getOrNull()?.size)
    }

    @Test
    fun `validateDestinationHash - uppercase hex passes`() {
        val validHash = "0123456789ABCDEF0123456789ABCDEF"
        val result = InputValidator.validateDestinationHash(validHash)
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateDestinationHash - mixed case hex passes`() {
        val validHash = "0123456789AbCdEf0123456789aBcDeF"
        val result = InputValidator.validateDestinationHash(validHash)
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateDestinationHash - invalid hex fails`() {
        val invalidHash = "invalid_hash_xyz"
        val result = InputValidator.validateDestinationHash(invalidHash)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateDestinationHash - wrong length fails`() {
        val shortHash = "0123456789abcdef"
        val result = InputValidator.validateDestinationHash(shortHash)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("length"))
    }

    @Test
    fun `validateDestinationHash - contains non-hex characters fails`() {
        val invalidHash = "0123456789abcdef0123456789abcdeg" // 'g' is not hex
        val result = InputValidator.validateDestinationHash(invalidHash)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateDestinationHash - trims whitespace`() {
        val validHash = "  0123456789abcdef0123456789abcdef  "
        val result = InputValidator.validateDestinationHash(validHash)
        assertTrue(result is ValidationResult.Success)
    }

    // ========== PUBLIC KEY VALIDATION TESTS ==========

    @Test
    fun `validatePublicKey - valid 128-char hex passes`() {
        val validKey =
            "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef"
        val result = InputValidator.validatePublicKey(validKey)
        assertTrue(result is ValidationResult.Success)
        assertEquals(64, result.getOrNull()?.size)
    }

    @Test
    fun `validatePublicKey - wrong length fails`() {
        val shortKey = "0123456789abcdef"
        val result = InputValidator.validatePublicKey(shortKey)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("length"))
    }

    @Test
    fun `validatePublicKey - invalid hex fails`() {
        val invalidKey = "z".repeat(64)
        val result = InputValidator.validatePublicKey(invalidKey)
        assertTrue(result is ValidationResult.Error)
    }

    // ========== IDENTITY STRING VALIDATION TESTS ==========

    @Test
    fun `validateIdentityString - valid format passes`() {
        val validIdentity =
            "lxma://0123456789abcdef0123456789abcdef:" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef"
        val result = InputValidator.validateIdentityString(validIdentity)
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateIdentityString - missing prefix fails`() {
        val invalidIdentity = "0123456789abcdef0123456789abcdef:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val result = InputValidator.validateIdentityString(invalidIdentity)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("lxma://"))
    }

    @Test
    fun `validateIdentityString - missing colon fails`() {
        val invalidIdentity = "lxma://0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val result = InputValidator.validateIdentityString(invalidIdentity)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("format"))
    }

    @Test
    fun `validateIdentityString - invalid hash fails`() {
        val invalidIdentity = "lxma://invalid:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val result = InputValidator.validateIdentityString(invalidIdentity)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateIdentityString - invalid pubkey fails`() {
        val invalidIdentity = "lxma://0123456789abcdef0123456789abcdef:invalid"
        val result = InputValidator.validateIdentityString(invalidIdentity)
        assertTrue(result is ValidationResult.Error)
    }

    // ========== HOSTNAME VALIDATION TESTS ==========

    @Test
    fun `validateHostname - valid IPv4 passes`() {
        val validIPs = listOf("192.168.1.1", "10.0.0.1", "172.16.0.1", "255.255.255.255")
        validIPs.forEach { ip ->
            val result = InputValidator.validateHostname(ip)
            assertTrue("$ip should be valid", result is ValidationResult.Success)
        }
    }

    @Test
    fun `validateHostname - invalid IPv4 fails`() {
        val invalidIPs = listOf("256.1.1.1", "192.168.1", "192.168.1.1.1", "192.168.-1.1")
        invalidIPs.forEach { ip ->
            val result = InputValidator.validateHostname(ip)
            assertTrue("$ip should be invalid", result is ValidationResult.Error)
        }
    }

    @Test
    fun `validateHostname - valid DNS names pass`() {
        val validDNS = listOf("example.com", "sub.example.com", "my-server.local", "localhost")
        validDNS.forEach { dns ->
            val result = InputValidator.validateHostname(dns)
            assertTrue("$dns should be valid", result is ValidationResult.Success)
        }
    }

    @Test
    fun `validateHostname - invalid DNS names fail`() {
        val invalidDNS = listOf("not a valid host!!!", "-invalid.com", "invalid-.com", "")
        invalidDNS.forEach { dns ->
            val result = InputValidator.validateHostname(dns)
            assertTrue("$dns should be invalid", result is ValidationResult.Error)
        }
    }

    @Test
    fun `validateHostname - empty string fails`() {
        val result = InputValidator.validateHostname("")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateHostname - trims whitespace`() {
        val result = InputValidator.validateHostname("  example.com  ")
        assertTrue(result is ValidationResult.Success)
        assertEquals("example.com", result.getOrNull())
    }

    // ========== PORT VALIDATION TESTS ==========

    @Test
    fun `validatePort - valid ports pass`() {
        val validPorts = listOf("1", "80", "443", "8080", "65535")
        validPorts.forEach { port ->
            val result = InputValidator.validatePort(port)
            assertTrue("Port $port should be valid", result is ValidationResult.Success)
        }
    }

    @Test
    fun `validatePort - invalid ports fail`() {
        val invalidPorts = listOf("0", "-1", "65536", "100000", "abc", "")
        invalidPorts.forEach { port ->
            val result = InputValidator.validatePort(port)
            assertTrue("Port $port should be invalid", result is ValidationResult.Error)
        }
    }

    @Test
    fun `validatePort - boundary values`() {
        val minPort = InputValidator.validatePort("1")
        assertTrue(minPort is ValidationResult.Success)
        assertEquals(1, minPort.getOrNull())

        val maxPort = InputValidator.validatePort("65535")
        assertTrue(maxPort is ValidationResult.Success)
        assertEquals(65535, maxPort.getOrNull())

        val tooLow = InputValidator.validatePort("0")
        assertTrue(tooLow is ValidationResult.Error)

        val tooHigh = InputValidator.validatePort("65536")
        assertTrue(tooHigh is ValidationResult.Error)
    }

    // ========== NICKNAME VALIDATION TESTS ==========

    @Test
    fun `validateNickname - valid nickname passes`() {
        val result = InputValidator.validateNickname("Alice")
        assertTrue(result is ValidationResult.Success)
        assertEquals("Alice", result.getOrNull())
    }

    @Test
    fun `validateNickname - trims whitespace`() {
        val result = InputValidator.validateNickname("  Bob  ")
        assertTrue(result is ValidationResult.Success)
        assertEquals("Bob", result.getOrNull())
    }

    @Test
    fun `validateNickname - empty fails`() {
        val result = InputValidator.validateNickname("")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateNickname - too long fails`() {
        val longNickname = "a".repeat(200)
        val result = InputValidator.validateNickname(longNickname)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("too long"))
    }

    @Test
    fun `validateNickname - max length passes`() {
        val maxNickname = "a".repeat(ValidationConstants.MAX_NICKNAME_LENGTH)
        val result = InputValidator.validateNickname(maxNickname)
        assertTrue(result is ValidationResult.Success)
    }

    // ========== INTERFACE NAME VALIDATION TESTS ==========

    @Test
    fun `validateInterfaceName - valid name passes`() {
        val result = InputValidator.validateInterfaceName("TCP Client 1")
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateInterfaceName - empty fails`() {
        val result = InputValidator.validateInterfaceName("")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateInterfaceName - too long fails`() {
        val longName = "a".repeat(100)
        val result = InputValidator.validateInterfaceName(longName)
        assertTrue(result is ValidationResult.Error)
    }

    // ========== INTERFACE NAME UNIQUENESS TESTS ==========

    @Test
    fun `validateInterfaceNameUniqueness - unique name passes`() {
        val existingNames = listOf("TCP Client 1", "RNode LoRa", "AutoInterface")
        val result = InputValidator.validateInterfaceNameUniqueness("TCP Client 2", existingNames)
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateInterfaceNameUniqueness - duplicate name fails`() {
        val existingNames = listOf("TCP Client 1", "RNode LoRa", "AutoInterface")
        val result = InputValidator.validateInterfaceNameUniqueness("RNode LoRa", existingNames)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("already exists"))
    }

    @Test
    fun `validateInterfaceNameUniqueness - case insensitive duplicate fails`() {
        val existingNames = listOf("TCP Client 1", "RNode LoRa", "AutoInterface")
        val result = InputValidator.validateInterfaceNameUniqueness("rnode lora", existingNames)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateInterfaceNameUniqueness - excludeName allows editing same interface`() {
        val existingNames = listOf("TCP Client 1", "RNode LoRa", "AutoInterface")
        // When editing "RNode LoRa", we should exclude it from the duplicate check
        val result =
            InputValidator.validateInterfaceNameUniqueness(
                "RNode LoRa",
                existingNames,
                excludeName = "RNode LoRa",
            )
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateInterfaceNameUniqueness - excludeName still catches other duplicates`() {
        val existingNames = listOf("TCP Client 1", "RNode LoRa", "AutoInterface")
        // When editing "TCP Client 1" but trying to rename to "RNode LoRa"
        val result =
            InputValidator.validateInterfaceNameUniqueness(
                "RNode LoRa",
                existingNames,
                excludeName = "TCP Client 1",
            )
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateInterfaceNameUniqueness - trims whitespace before comparison`() {
        val existingNames = listOf("TCP Client 1", "RNode LoRa")
        val result = InputValidator.validateInterfaceNameUniqueness("  RNode LoRa  ", existingNames)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateInterfaceNameUniqueness - empty existing names passes`() {
        val existingNames = emptyList<String>()
        val result = InputValidator.validateInterfaceNameUniqueness("Any Name", existingNames)
        assertTrue(result is ValidationResult.Success)
    }

    // ========== DEVICE NAME VALIDATION TESTS ==========

    @Test
    fun `validateDeviceName - valid name passes`() {
        val result = InputValidator.validateDeviceName("MyDevice")
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateDeviceName - empty fails`() {
        // Note: In practice, empty device names ARE allowed by skipping validation
        // when deviceName.isBlank(). This test verifies the validator itself rejects
        // empty strings, but the validator is only called for non-blank names.
        // See InterfaceRepository.kt for usage.
        val result = InputValidator.validateDeviceName("")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `validateDeviceName - too long fails`() {
        val longName = "a".repeat(50)
        val result = InputValidator.validateDeviceName(longName)
        assertTrue(result is ValidationResult.Error)
    }

    // ========== SEARCH QUERY VALIDATION TESTS ==========

    @Test
    fun `validateSearchQuery - always succeeds and sanitizes`() {
        val result = InputValidator.validateSearchQuery("search term")
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateSearchQuery - empty query is valid`() {
        val result = InputValidator.validateSearchQuery("")
        assertTrue(result is ValidationResult.Success)
        assertEquals("", result.getOrNull())
    }

    @Test
    fun `validateSearchQuery - truncates to max length`() {
        val longQuery = "a".repeat(500)
        val result = InputValidator.validateSearchQuery(longQuery)
        assertTrue(result is ValidationResult.Success)
        assertEquals(ValidationConstants.MAX_SEARCH_QUERY_LENGTH, result.getOrNull()?.length)
    }

    // ========== CONFIG PARAMETER VALIDATION TESTS ==========

    @Test
    fun `validateConfigParameter - whitelisted params pass`() {
        val validParams = listOf("target_host", "target_port", "device_name", "type")
        validParams.forEach { param ->
            val result = InputValidator.validateConfigParameter(param)
            assertTrue("$param should be valid", result is ValidationResult.Success)
        }
    }

    @Test
    fun `validateConfigParameter - unknown params fail`() {
        val invalidParams = listOf("malicious_param", "unknown_setting", "hack_attempt")
        invalidParams.forEach { param ->
            val result = InputValidator.validateConfigParameter(param)
            assertTrue("$param should be invalid", result is ValidationResult.Error)
        }
    }

    // ========== BLE PACKET SIZE VALIDATION TESTS ==========

    @Test
    fun `validateBlePacketSize - small packet passes`() {
        val data = ByteArray(256)
        val result = InputValidator.validateBlePacketSize(data)
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateBlePacketSize - max size passes`() {
        val data = ByteArray(ValidationConstants.MAX_BLE_PACKET_SIZE)
        val result = InputValidator.validateBlePacketSize(data)
        assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun `validateBlePacketSize - too large fails`() {
        val data = ByteArray(1024)
        val result = InputValidator.validateBlePacketSize(data)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("too large"))
    }

    // ========== TEXT SANITIZATION TESTS ==========

    @Test
    fun `sanitizeText - trims whitespace`() {
        val result = InputValidator.sanitizeText("  hello  ", 100)
        assertEquals("hello", result)
    }

    @Test
    fun `sanitizeText - removes control characters`() {
        val result = InputValidator.sanitizeText("hello\u0000world\u0001test", 100)
        assertEquals("helloworldtest", result)
    }

    @Test
    fun `sanitizeText - normalizes multiple spaces`() {
        val result = InputValidator.sanitizeText("hello    world", 100)
        assertEquals("hello world", result)
    }

    @Test
    fun `sanitizeText - truncates to max length`() {
        val longText = "a".repeat(200)
        val result = InputValidator.sanitizeText(longText, 50)
        assertEquals(50, result.length)
    }

    @Test
    fun `sanitizeText - handles unicode properly`() {
        val unicode = "Hello 世界 🌍"
        val result = InputValidator.sanitizeText(unicode, 100)
        assertEquals("Hello 世界 🌍", result)
    }

    // ========== HEX CONVERSION EXTENSION TESTS ==========

    @Test
    fun `safeHexToBytes - valid hex succeeds`() {
        val hex = "0123456789abcdef"
        val result = hex.safeHexToBytes()
        assertTrue(result.isSuccess)
        assertEquals(8, result.getOrNull()?.size)
    }

    @Test
    fun `safeHexToBytes - invalid hex fails`() {
        val hex = "invalid"
        val result = hex.safeHexToBytes()
        assertTrue(result.isFailure)
    }

    @Test
    fun `safeHexToBytes - odd length fails`() {
        val hex = "123" // odd length
        val result = hex.safeHexToBytes()
        assertTrue(result.isFailure)
    }

    @Test
    fun `safeHexToBytes - empty string succeeds`() {
        val hex = ""
        val result = hex.safeHexToBytes()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull()?.size)
    }

    @Test
    fun `toHexString - converts correctly`() {
        val bytes = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        val hex = bytes.toHexString()
        assertEquals("0123456789abcdef", hex)
    }

    @Test
    fun `hex conversion round trip`() {
        val original = "0123456789abcdef"
        val bytes = original.safeHexToBytes().getOrThrow()
        val roundTrip = bytes.toHexString()
        assertEquals(original, roundTrip)
    }

    // ========== PARSE IDENTITY INPUT TESTS (Sideband Import) ==========

    @Test
    fun `parseIdentityInput - valid lxma URL returns FullIdentity`() {
        val validLxma =
            "lxma://0123456789abcdef0123456789abcdef:" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef"
        val result = InputValidator.parseIdentityInput(validLxma)
        assertTrue(result is ValidationResult.Success)
        val identity = (result as ValidationResult.Success).value
        assertTrue(identity is IdentityInput.FullIdentity)
        assertEquals("0123456789abcdef0123456789abcdef", (identity as IdentityInput.FullIdentity).destinationHash)
        assertEquals(64, identity.publicKey.size)
    }

    @Test
    fun `parseIdentityInput - valid 32-char hex returns DestinationHashOnly`() {
        val validHash = "0123456789abcdef0123456789abcdef"
        val result = InputValidator.parseIdentityInput(validHash)
        assertTrue(result is ValidationResult.Success)
        val identity = (result as ValidationResult.Success).value
        assertTrue(identity is IdentityInput.DestinationHashOnly)
        val hashOnly = identity as IdentityInput.DestinationHashOnly
        assertEquals("0123456789abcdef0123456789abcdef", hashOnly.destinationHash)
    }

    @Test
    fun `parseIdentityInput - uppercase 32-char hex returns DestinationHashOnly`() {
        val validHash = "0123456789ABCDEF0123456789ABCDEF"
        val result = InputValidator.parseIdentityInput(validHash)
        assertTrue(result is ValidationResult.Success)
        val identity = (result as ValidationResult.Success).value
        assertTrue(identity is IdentityInput.DestinationHashOnly)
        // Hash should be normalized to lowercase
        val hashOnly = identity as IdentityInput.DestinationHashOnly
        assertEquals("0123456789abcdef0123456789abcdef", hashOnly.destinationHash)
    }

    @Test
    fun `parseIdentityInput - trims whitespace from hash`() {
        val validHash = "  0123456789abcdef0123456789abcdef  "
        val result = InputValidator.parseIdentityInput(validHash)
        assertTrue(result is ValidationResult.Success)
        val identity = (result as ValidationResult.Success).value
        assertTrue(identity is IdentityInput.DestinationHashOnly)
    }

    @Test
    fun `parseIdentityInput - trims whitespace from lxma URL`() {
        val validLxma =
            "  lxma://0123456789abcdef0123456789abcdef:" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef  "
        val result = InputValidator.parseIdentityInput(validLxma)
        assertTrue(result is ValidationResult.Success)
        val identity = (result as ValidationResult.Success).value
        assertTrue(identity is IdentityInput.FullIdentity)
    }

    @Test
    fun `parseIdentityInput - empty string fails`() {
        val result = InputValidator.parseIdentityInput("")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `parseIdentityInput - whitespace only fails`() {
        val result = InputValidator.parseIdentityInput("   ")
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `parseIdentityInput - short hash fails`() {
        val shortHash = "0123456789abcdef" // Only 16 chars
        val result = InputValidator.parseIdentityInput(shortHash)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("32"))
    }

    @Test
    fun `parseIdentityInput - long hash fails`() {
        val longHash = "0123456789abcdef0123456789abcdef0123" // 36 chars
        val result = InputValidator.parseIdentityInput(longHash)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `parseIdentityInput - invalid hex characters fail`() {
        val invalidHash = "0123456789abcdef0123456789abcdeg" // 'g' is invalid
        val result = InputValidator.parseIdentityInput(invalidHash)
        assertTrue(result is ValidationResult.Error)
        assertTrue((result as ValidationResult.Error).message.contains("hexadecimal"))
    }

    @Test
    fun `parseIdentityInput - random text fails`() {
        val randomText = "this is not a valid input"
        val result = InputValidator.parseIdentityInput(randomText)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `parseIdentityInput - malformed lxma URL fails`() {
        val malformedLxma = "lxma://invalid"
        val result = InputValidator.parseIdentityInput(malformedLxma)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `parseIdentityInput - lxma URL with invalid hash fails`() {
        val invalidLxma =
            "lxma://tooshort:" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef"
        val result = InputValidator.parseIdentityInput(invalidLxma)
        assertTrue(result is ValidationResult.Error)
    }

    @Test
    fun `parseIdentityInput - lxma URL with invalid public key fails`() {
        val invalidLxma = "lxma://0123456789abcdef0123456789abcdef:tooshort"
        val result = InputValidator.parseIdentityInput(invalidLxma)
        assertTrue(result is ValidationResult.Error)
    }
}
