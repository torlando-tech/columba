package com.lxmf.messenger.data.db.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for Migration 25 to 26 - Reply To Message ID.
 *
 * This migration adds replyToMessageId column and backfills it from fieldsJson.
 * The backfill uses json_extract on SQLite 3.38+ (Android 14+) with a Kotlin
 * fallback for older Android versions where json_extract is not available.
 *
 * These tests focus on the Kotlin JSON extraction helper that serves as the
 * fallback for older devices (COLUMBA-N fix).
 */
class Migration25To26Test {
    // ========== extractReplyToFromFieldsJson tests ==========

    @Test
    fun `extractReplyToFromFieldsJson returns reply_to when present`() {
        val fieldsJson = """{"16":{"reply_to":"abc123"}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertEquals("abc123", result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null when field 16 missing`() {
        val fieldsJson = """{"15":{"some_field":"value"}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null when reply_to missing`() {
        val fieldsJson = """{"16":{"other_field":"value"}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null for empty reply_to`() {
        val fieldsJson = """{"16":{"reply_to":""}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null for null input`() {
        val result = Migration25To26Helper.extractReplyToFromFieldsJson(null)

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null for empty string`() {
        val result = Migration25To26Helper.extractReplyToFromFieldsJson("")

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null for malformed JSON`() {
        val result = Migration25To26Helper.extractReplyToFromFieldsJson("not valid json")

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson returns null for JSON array`() {
        val result = Migration25To26Helper.extractReplyToFromFieldsJson("[1, 2, 3]")

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson handles nested structure correctly`() {
        // Real-world example with multiple fields
        val fieldsJson = """{"1":{"title":"Hello"},"16":{"reply_to":"msg_xyz_789"},"7":{"location":[1.0,2.0]}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertEquals("msg_xyz_789", result)
    }

    @Test
    fun `extractReplyToFromFieldsJson handles field 16 being non-object`() {
        val fieldsJson = """{"16":"not an object"}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson handles reply_to being non-string`() {
        val fieldsJson = """{"16":{"reply_to":12345}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        // Should still extract as string representation or return null
        // Let's say we return null for non-string values to be safe
        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson handles whitespace in reply_to`() {
        val fieldsJson = """{"16":{"reply_to":"   "}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        // Whitespace-only should be treated as empty
        assertNull(result)
    }

    @Test
    fun `extractReplyToFromFieldsJson handles unicode in reply_to`() {
        val fieldsJson = """{"16":{"reply_to":"msg_🎉_123"}}"""

        val result = Migration25To26Helper.extractReplyToFromFieldsJson(fieldsJson)

        assertEquals("msg_🎉_123", result)
    }
}
