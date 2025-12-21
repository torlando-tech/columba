package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationHashValidatorTest {
    companion object {
        private const val VALID_HASH_LOWERCASE = "0123456789abcdef0123456789abcdef"
        private const val VALID_HASH_UPPERCASE = "0123456789ABCDEF0123456789ABCDEF"
        private const val VALID_HASH_MIXED = "0123456789AbCdEf0123456789aBcDeF"
    }

    // ==================== Valid Hash Tests ====================

    @Test
    fun `validate validLowercase returnsValid`() {
        val result = DestinationHashValidator.validate(VALID_HASH_LOWERCASE)

        assertTrue(result is DestinationHashValidator.ValidationResult.Valid)
        assertEquals(
            VALID_HASH_LOWERCASE,
            (result as DestinationHashValidator.ValidationResult.Valid).normalizedHash,
        )
    }

    @Test
    fun `validate validUppercase returnsValidNormalized`() {
        val result = DestinationHashValidator.validate(VALID_HASH_UPPERCASE)

        assertTrue(result is DestinationHashValidator.ValidationResult.Valid)
        assertEquals(
            VALID_HASH_LOWERCASE,
            (result as DestinationHashValidator.ValidationResult.Valid).normalizedHash,
        )
    }

    @Test
    fun `validate validMixedCase returnsValidNormalized`() {
        val result = DestinationHashValidator.validate(VALID_HASH_MIXED)

        assertTrue(result is DestinationHashValidator.ValidationResult.Valid)
        assertEquals(
            VALID_HASH_LOWERCASE,
            (result as DestinationHashValidator.ValidationResult.Valid).normalizedHash,
        )
    }

    // ==================== Empty Input Tests ====================

    @Test
    fun `validate empty returnsError`() {
        val result = DestinationHashValidator.validate("")

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
        assertEquals(
            "Hash cannot be empty",
            (result as DestinationHashValidator.ValidationResult.Error).message,
        )
    }

    @Test
    fun `validate whitespaceOnly returnsError`() {
        val result = DestinationHashValidator.validate("   ")

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
        assertEquals(
            "Hash cannot be empty",
            (result as DestinationHashValidator.ValidationResult.Error).message,
        )
    }

    // ==================== Length Tests ====================

    @Test
    fun `validate tooShort returnsErrorWithLength`() {
        val shortHash = "0123456789abcdef" // 16 characters

        val result = DestinationHashValidator.validate(shortHash)

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
        val error = result as DestinationHashValidator.ValidationResult.Error
        assertTrue(error.message.contains("16"))
        assertTrue(error.message.contains("32"))
    }

    @Test
    fun `validate tooLong returnsErrorWithLength`() {
        val longHash = "0123456789abcdef0123456789abcdef0123456789abcdef" // 48 characters

        val result = DestinationHashValidator.validate(longHash)

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
        val error = result as DestinationHashValidator.ValidationResult.Error
        assertTrue(error.message.contains("48"))
        assertTrue(error.message.contains("32"))
    }

    // ==================== Invalid Character Tests ====================

    @Test
    fun `validate invalidChars letterG returnsError`() {
        val hashWithG = "0123456789abcdefg123456789abcdef" // 'g' is not valid hex

        val result = DestinationHashValidator.validate(hashWithG)

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
        assertTrue(
            (result as DestinationHashValidator.ValidationResult.Error)
                .message.contains("hex"),
        )
    }

    @Test
    fun `validate invalidChars specialCharacter returnsError`() {
        val hashWithSpecial = "0123456789abcdef!123456789abcdef"

        val result = DestinationHashValidator.validate(hashWithSpecial)

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
    }

    @Test
    fun `validate invalidChars internalSpace returnsError`() {
        val hashWithSpace = "0123456789abcdef 123456789abcdef" // Space in middle

        val result = DestinationHashValidator.validate(hashWithSpace)

        assertTrue(result is DestinationHashValidator.ValidationResult.Error)
    }

    // ==================== Whitespace Handling Tests ====================

    @Test
    fun `validate leadingWhitespace trimsAndValidates`() {
        val hashWithLeadingSpace = "  $VALID_HASH_LOWERCASE"

        val result = DestinationHashValidator.validate(hashWithLeadingSpace)

        assertTrue(result is DestinationHashValidator.ValidationResult.Valid)
        assertEquals(
            VALID_HASH_LOWERCASE,
            (result as DestinationHashValidator.ValidationResult.Valid).normalizedHash,
        )
    }

    @Test
    fun `validate trailingWhitespace trimsAndValidates`() {
        val hashWithTrailingSpace = "$VALID_HASH_LOWERCASE  "

        val result = DestinationHashValidator.validate(hashWithTrailingSpace)

        assertTrue(result is DestinationHashValidator.ValidationResult.Valid)
        assertEquals(
            VALID_HASH_LOWERCASE,
            (result as DestinationHashValidator.ValidationResult.Valid).normalizedHash,
        )
    }

    // ==================== Utility Method Tests ====================

    @Test
    fun `isValid validHash returnsTrue`() {
        assertTrue(DestinationHashValidator.isValid(VALID_HASH_LOWERCASE))
    }

    @Test
    fun `isValid invalidHash returnsFalse`() {
        assertFalse(DestinationHashValidator.isValid("invalid"))
    }

    @Test
    fun `getCharacterCount empty returnsZeroOf32`() {
        assertEquals("0/32", DestinationHashValidator.getCharacterCount(""))
    }

    @Test
    fun `getCharacterCount partial returns12Of32`() {
        assertEquals("12/32", DestinationHashValidator.getCharacterCount("0123456789ab"))
    }

    @Test
    fun `getCharacterCount full returns32Of32`() {
        assertEquals("32/32", DestinationHashValidator.getCharacterCount(VALID_HASH_LOWERCASE))
    }

    @Test
    fun `getCharacterCount withWhitespace trimsForCount`() {
        assertEquals("12/32", DestinationHashValidator.getCharacterCount("  0123456789ab  "))
    }
}
