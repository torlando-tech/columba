package com.lxmf.messenger.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for MessagingScreenLinkUtils.
 *
 * Tests the URL parsing and cleaning logic used for clickable links in messages.
 * Tests use toBrowsableUrl (returns String) instead of toBrowsableUri (returns Android Uri)
 * to avoid Android framework dependencies in unit tests.
 */
class MessagingScreenLinkUtilsTest {
    // ==========================================
    // Scheme Handling Tests
    // ==========================================

    @Test
    fun `adds https scheme to bare domain`() {
        val result = toBrowsableUrl("example.com")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `preserves existing http scheme`() {
        val result = toBrowsableUrl("http://example.com")
        assertEquals("http://example.com", result)
    }

    @Test
    fun `preserves existing https scheme`() {
        val result = toBrowsableUrl("https://example.com")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `handles uppercase HTTP scheme`() {
        val result = toBrowsableUrl("HTTP://example.com")
        assertEquals("HTTP://example.com", result)
    }

    @Test
    fun `handles uppercase HTTPS scheme`() {
        val result = toBrowsableUrl("HTTPS://example.com")
        assertEquals("HTTPS://example.com", result)
    }

    @Test
    fun `handles mixed case scheme`() {
        val result = toBrowsableUrl("HtTpS://example.com")
        assertEquals("HtTpS://example.com", result)
    }

    // ==========================================
    // Trailing Punctuation Stripping Tests
    // ==========================================

    @Test
    fun `strips trailing period`() {
        val result = toBrowsableUrl("example.com.")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing comma`() {
        val result = toBrowsableUrl("example.com,")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing exclamation mark`() {
        val result = toBrowsableUrl("example.com!")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing question mark`() {
        val result = toBrowsableUrl("example.com?")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing semicolon`() {
        val result = toBrowsableUrl("example.com;")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing colon`() {
        val result = toBrowsableUrl("example.com:")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing closing parenthesis`() {
        val result = toBrowsableUrl("example.com)")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing closing bracket`() {
        val result = toBrowsableUrl("example.com]")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips trailing closing brace`() {
        val result = toBrowsableUrl("example.com}")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips multiple trailing punctuation marks`() {
        val result = toBrowsableUrl("example.com!!")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `strips mixed trailing punctuation`() {
        val result = toBrowsableUrl("example.com).")
        assertEquals("https://example.com", result)
    }

    // ==========================================
    // Whitespace Handling Tests
    // ==========================================

    @Test
    fun `trims leading whitespace`() {
        val result = toBrowsableUrl("  example.com")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `trims trailing whitespace`() {
        val result = toBrowsableUrl("example.com  ")
        assertEquals("https://example.com", result)
    }

    @Test
    fun `trims both leading and trailing whitespace`() {
        val result = toBrowsableUrl("  example.com  ")
        assertEquals("https://example.com", result)
    }

    // ==========================================
    // Path and Query String Tests
    // ==========================================

    @Test
    fun `preserves path`() {
        val result = toBrowsableUrl("example.com/path/to/page")
        assertEquals("https://example.com/path/to/page", result)
    }

    @Test
    fun `preserves query string`() {
        val result = toBrowsableUrl("example.com/search?q=test")
        assertEquals("https://example.com/search?q=test", result)
    }

    @Test
    fun `preserves fragment`() {
        val result = toBrowsableUrl("example.com/page#section")
        assertEquals("https://example.com/page#section", result)
    }

    @Test
    fun `preserves complex URL with path query and fragment`() {
        val result = toBrowsableUrl("https://example.com/path?key=value&other=123#section")
        assertEquals("https://example.com/path?key=value&other=123#section", result)
    }

    @Test
    fun `strips trailing punctuation but preserves query string`() {
        // Note: trailing exclamation is stripped from end of URL
        val result = toBrowsableUrl("example.com/search?q=test!")
        assertEquals("https://example.com/search?q=test", result)
    }

    // ==========================================
    // Port Number Tests
    // ==========================================

    @Test
    fun `preserves port number`() {
        val result = toBrowsableUrl("example.com:8080")
        assertEquals("https://example.com:8080", result)
    }

    @Test
    fun `preserves port number with path`() {
        val result = toBrowsableUrl("localhost:3000/api/users")
        assertEquals("https://localhost:3000/api/users", result)
    }

    // ==========================================
    // IP Address Tests
    // ==========================================

    @Test
    fun `handles IPv4 address`() {
        val result = toBrowsableUrl("192.168.1.1")
        assertEquals("https://192.168.1.1", result)
    }

    @Test
    fun `handles IPv4 address with port`() {
        val result = toBrowsableUrl("192.168.1.1:8080")
        assertEquals("https://192.168.1.1:8080", result)
    }

    // ==========================================
    // Edge Cases
    // ==========================================

    @Test
    fun `handles URL with www prefix`() {
        val result = toBrowsableUrl("www.example.com")
        assertEquals("https://www.example.com", result)
    }

    @Test
    fun `handles subdomain`() {
        val result = toBrowsableUrl("blog.example.com")
        assertEquals("https://blog.example.com", result)
    }

    @Test
    fun `handles URL with authentication`() {
        val result = toBrowsableUrl("https://user:pass@example.com")
        assertEquals("https://user:pass@example.com", result)
    }
}
