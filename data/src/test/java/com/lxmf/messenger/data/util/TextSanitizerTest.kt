package com.lxmf.messenger.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSanitizerTest {
    @Test
    fun `sanitize trims leading and trailing whitespace`() {
        assertEquals("hello", TextSanitizer.sanitize("  hello  ", 100))
        assertEquals("hello", TextSanitizer.sanitize("\thello\t", 100))
        assertEquals("hello", TextSanitizer.sanitize("\nhello\n", 100))
    }

    @Test
    fun `sanitize removes control characters except newlines`() {
        // Null character
        assertEquals("hello", TextSanitizer.sanitize("hel\u0000lo", 100))
        // Bell character
        assertEquals("hello", TextSanitizer.sanitize("hel\u0007lo", 100))
        // Backspace
        assertEquals("hello", TextSanitizer.sanitize("hel\u0008lo", 100))
        // Escape
        assertEquals("hello", TextSanitizer.sanitize("hel\u001Blo", 100))
    }

    @Test
    fun `sanitize preserves newlines and carriage returns`() {
        assertEquals("hello\nworld", TextSanitizer.sanitize("hello\nworld", 100))
        assertEquals("hello\rworld", TextSanitizer.sanitize("hello\rworld", 100))
        assertEquals("hello\r\nworld", TextSanitizer.sanitize("hello\r\nworld", 100))
    }

    @Test
    fun `sanitize normalizes multiple spaces to single space`() {
        assertEquals("hello world", TextSanitizer.sanitize("hello   world", 100))
        assertEquals("hello world", TextSanitizer.sanitize("hello     world", 100))
    }

    @Test
    fun `sanitize normalizes tabs to single space`() {
        assertEquals("hello world", TextSanitizer.sanitize("hello\tworld", 100))
        assertEquals("hello world", TextSanitizer.sanitize("hello\t\tworld", 100))
    }

    @Test
    fun `sanitize normalizes mixed spaces and tabs`() {
        assertEquals("hello world", TextSanitizer.sanitize("hello \t world", 100))
        assertEquals("hello world", TextSanitizer.sanitize("hello\t \t world", 100))
    }

    @Test
    fun `sanitize truncates to max length`() {
        assertEquals("hello", TextSanitizer.sanitize("hello world", 5))
        assertEquals("hel", TextSanitizer.sanitize("hello", 3))
    }

    @Test
    fun `sanitize handles empty string`() {
        assertEquals("", TextSanitizer.sanitize("", 100))
    }

    @Test
    fun `sanitize handles whitespace-only string`() {
        assertEquals("", TextSanitizer.sanitize("   ", 100))
        assertEquals("", TextSanitizer.sanitize("\t\t\t", 100))
    }

    @Test
    fun `sanitizeMessage uses MAX_MESSAGE_LENGTH`() {
        val longMessage = "a".repeat(TextSanitizer.MAX_MESSAGE_LENGTH + 100)
        val result = TextSanitizer.sanitizeMessage(longMessage)
        assertEquals(TextSanitizer.MAX_MESSAGE_LENGTH, result.length)
    }

    @Test
    fun `sanitizePeerName uses MAX_PEER_NAME_LENGTH`() {
        val longName = "a".repeat(TextSanitizer.MAX_PEER_NAME_LENGTH + 50)
        val result = TextSanitizer.sanitizePeerName(longName)
        assertEquals(TextSanitizer.MAX_PEER_NAME_LENGTH, result.length)
    }

    @Test
    fun `sanitizePreview uses MAX_PREVIEW_LENGTH`() {
        val longPreview = "a".repeat(TextSanitizer.MAX_PREVIEW_LENGTH + 50)
        val result = TextSanitizer.sanitizePreview(longPreview)
        assertEquals(TextSanitizer.MAX_PREVIEW_LENGTH, result.length)
    }

    @Test
    fun `sanitize handles complex mixed input`() {
        // Input with control chars, multiple spaces, tabs, and needs truncation
        val input = "  Hello\u0000  \t World\u0007  "
        val result = TextSanitizer.sanitize(input, 11)
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitize preserves unicode characters`() {
        assertEquals("Hello 世界", TextSanitizer.sanitize("Hello 世界", 100))
        assertEquals("Привет мир", TextSanitizer.sanitize("Привет мир", 100))
        assertEquals("مرحبا", TextSanitizer.sanitize("مرحبا", 100))
    }

    @Test
    fun `sanitize preserves emoji`() {
        assertEquals("Hello 👋 World", TextSanitizer.sanitize("Hello 👋 World", 100))
        assertEquals("🎉🎊🎁", TextSanitizer.sanitize("🎉🎊🎁", 100))
    }

    @Test
    fun `constants have expected values`() {
        assertEquals(10_000, TextSanitizer.MAX_MESSAGE_LENGTH)
        assertEquals(100, TextSanitizer.MAX_PEER_NAME_LENGTH)
        assertEquals(100, TextSanitizer.MAX_PREVIEW_LENGTH)
    }
}
