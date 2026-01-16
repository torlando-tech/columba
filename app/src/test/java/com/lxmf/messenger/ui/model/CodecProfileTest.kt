package com.lxmf.messenger.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecProfileTest {
    @Test
    fun `fromCode returns BANDWIDTH_ULTRA_LOW for code 0x10`() {
        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, CodecProfile.fromCode(0x10))
    }

    @Test
    fun `fromCode returns BANDWIDTH_VERY_LOW for code 0x20`() {
        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, CodecProfile.fromCode(0x20))
    }

    @Test
    fun `fromCode returns BANDWIDTH_LOW for code 0x30`() {
        assertEquals(CodecProfile.BANDWIDTH_LOW, CodecProfile.fromCode(0x30))
    }

    @Test
    fun `fromCode returns QUALITY_MEDIUM for code 0x40`() {
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.fromCode(0x40))
    }

    @Test
    fun `fromCode returns QUALITY_HIGH for code 0x50`() {
        assertEquals(CodecProfile.QUALITY_HIGH, CodecProfile.fromCode(0x50))
    }

    @Test
    fun `fromCode returns QUALITY_MAX for code 0x60`() {
        assertEquals(CodecProfile.QUALITY_MAX, CodecProfile.fromCode(0x60))
    }

    @Test
    fun `fromCode returns LATENCY_ULTRA_LOW for code 0x70`() {
        assertEquals(CodecProfile.LATENCY_ULTRA_LOW, CodecProfile.fromCode(0x70))
    }

    @Test
    fun `fromCode returns LATENCY_LOW for code 0x80`() {
        assertEquals(CodecProfile.LATENCY_LOW, CodecProfile.fromCode(0x80))
    }

    @Test
    fun `fromCode returns null for code 0x00`() {
        assertNull(CodecProfile.fromCode(0x00))
    }

    @Test
    fun `fromCode returns null for code 0xFF`() {
        assertNull(CodecProfile.fromCode(0xFF))
    }

    @Test
    fun `fromCode returns null for code 0x15`() {
        assertNull(CodecProfile.fromCode(0x15))
    }

    @Test
    fun `fromCode returns null for negative code`() {
        assertNull(CodecProfile.fromCode(-1))
    }

    @Test
    fun `DEFAULT constant is QUALITY_MEDIUM`() {
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.DEFAULT)
    }

    @Test
    fun `each profile has non-empty displayName`() {
        CodecProfile.entries.forEach { profile ->
            assertTrue(
                "Profile ${profile.name} should have non-empty displayName",
                profile.displayName.isNotEmpty(),
            )
        }
    }

    @Test
    fun `each profile has non-empty description`() {
        CodecProfile.entries.forEach { profile ->
            assertTrue(
                "Profile ${profile.name} should have non-empty description",
                profile.description.isNotEmpty(),
            )
        }
    }

    @Test
    fun `profile codes are unique across all profiles`() {
        val codes = CodecProfile.entries.map { it.code }
        val uniqueCodes = codes.toSet()
        assertEquals(
            "All profile codes should be unique",
            codes.size,
            uniqueCodes.size,
        )
    }

    @Test
    fun `all expected profiles exist`() {
        val expectedProfiles =
            listOf(
                "BANDWIDTH_ULTRA_LOW",
                "BANDWIDTH_VERY_LOW",
                "BANDWIDTH_LOW",
                "QUALITY_MEDIUM",
                "QUALITY_HIGH",
                "QUALITY_MAX",
                "LATENCY_ULTRA_LOW",
                "LATENCY_LOW",
            )
        val actualProfiles = CodecProfile.entries.map { it.name }
        assertEquals(expectedProfiles.size, actualProfiles.size)
        expectedProfiles.forEach { expected ->
            assertTrue(
                "Profile $expected should exist",
                actualProfiles.contains(expected),
            )
        }
    }

    @Test
    fun `fromCode is inverse of code property for all profiles`() {
        CodecProfile.entries.forEach { profile ->
            val retrieved = CodecProfile.fromCode(profile.code)
            assertNotNull("fromCode(${profile.code}) should not return null", retrieved)
            assertEquals(
                "fromCode should return the same profile for code ${profile.code}",
                profile,
                retrieved,
            )
        }
    }
}
