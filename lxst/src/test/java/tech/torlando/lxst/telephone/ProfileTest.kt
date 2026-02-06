package com.lxmf.messenger.reticulum.call.telephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Profile class configuration logic.
 *
 * Tests profile IDs, frame times, and lookup functions without requiring
 * Android or JNI (no codec instantiation - that requires instrumented tests).
 *
 * Profile IDs must match Python LXST Telephony.py exactly for wire compatibility.
 */
class ProfileTest {

    // ===== Profile IDs (must match Python LXST exactly) =====

    @Test
    fun `ULBW has correct ID`() {
        assertEquals(0x10, Profile.ULBW.id)
    }

    @Test
    fun `VLBW has correct ID`() {
        assertEquals(0x20, Profile.VLBW.id)
    }

    @Test
    fun `LBW has correct ID`() {
        assertEquals(0x30, Profile.LBW.id)
    }

    @Test
    fun `MQ has correct ID`() {
        assertEquals(0x40, Profile.MQ.id)
    }

    @Test
    fun `HQ has correct ID`() {
        assertEquals(0x50, Profile.HQ.id)
    }

    @Test
    fun `SHQ has correct ID`() {
        assertEquals(0x60, Profile.SHQ.id)
    }

    @Test
    fun `LL has correct ID`() {
        assertEquals(0x70, Profile.LL.id)
    }

    @Test
    fun `ULL has correct ID`() {
        assertEquals(0x80, Profile.ULL.id)
    }

    // ===== Frame Times (ms) =====

    @Test
    fun `ULBW has 400ms frame time`() {
        assertEquals(400, Profile.ULBW.frameTimeMs)
    }

    @Test
    fun `VLBW has 320ms frame time`() {
        assertEquals(320, Profile.VLBW.frameTimeMs)
    }

    @Test
    fun `LBW has 200ms frame time`() {
        assertEquals(200, Profile.LBW.frameTimeMs)
    }

    @Test
    fun `MQ has 60ms frame time`() {
        assertEquals(60, Profile.MQ.frameTimeMs)
    }

    @Test
    fun `HQ has 60ms frame time`() {
        assertEquals(60, Profile.HQ.frameTimeMs)
    }

    @Test
    fun `SHQ has 60ms frame time`() {
        assertEquals(60, Profile.SHQ.frameTimeMs)
    }

    @Test
    fun `LL has 20ms frame time`() {
        assertEquals(20, Profile.LL.frameTimeMs)
    }

    @Test
    fun `ULL has 10ms frame time`() {
        assertEquals(10, Profile.ULL.frameTimeMs)
    }

    // ===== Profile.all list =====

    @Test
    fun `all profiles has 8 profiles`() {
        assertEquals(8, Profile.all.size)
    }

    @Test
    fun `profiles are in expected order`() {
        val expected = listOf(
            Profile.ULBW, Profile.VLBW, Profile.LBW,
            Profile.MQ, Profile.HQ, Profile.SHQ,
            Profile.LL, Profile.ULL
        )
        assertEquals(expected, Profile.all)
    }

    @Test
    fun `all profiles have unique IDs`() {
        val ids = Profile.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    // ===== fromId() lookup =====

    @Test
    fun `fromId returns correct profile for ULBW`() {
        assertEquals(Profile.ULBW, Profile.fromId(0x10))
    }

    @Test
    fun `fromId returns correct profile for MQ`() {
        assertEquals(Profile.MQ, Profile.fromId(0x40))
    }

    @Test
    fun `fromId returns correct profile for ULL`() {
        assertEquals(Profile.ULL, Profile.fromId(0x80))
    }

    @Test
    fun `fromId returns null for invalid ID 0x99`() {
        assertNull(Profile.fromId(0x99))
    }

    @Test
    fun `fromId returns null for invalid ID 0x00`() {
        assertNull(Profile.fromId(0x00))
    }

    @Test
    fun `fromId returns null for invalid ID 0x15`() {
        // Between ULBW (0x10) and VLBW (0x20)
        assertNull(Profile.fromId(0x15))
    }

    @Test
    fun `fromId works for all profiles`() {
        Profile.all.forEach { profile ->
            assertEquals(profile, Profile.fromId(profile.id))
        }
    }

    // ===== next() cycling =====

    @Test
    fun `next cycles from ULBW to VLBW`() {
        assertEquals(Profile.VLBW, Profile.next(Profile.ULBW))
    }

    @Test
    fun `next cycles from ULL to ULBW (wrap around)`() {
        assertEquals(Profile.ULBW, Profile.next(Profile.ULL))
    }

    @Test
    fun `next cycles through all profiles correctly`() {
        var current: Profile = Profile.ULBW
        val visited = mutableListOf<Profile>(current)

        repeat(Profile.all.size - 1) {
            current = Profile.next(current)
            visited.add(current)
        }

        assertEquals(Profile.all, visited)
    }

    @Test
    fun `next wraps correctly after full cycle`() {
        var current: Profile = Profile.ULBW

        // Cycle through all profiles
        repeat(Profile.all.size) {
            current = Profile.next(current)
        }

        // Should be back at ULBW
        assertEquals(Profile.ULBW, current)
    }

    // ===== DEFAULT profile =====

    @Test
    fun `DEFAULT is MQ`() {
        assertEquals(Profile.MQ, Profile.DEFAULT)
    }

    @Test
    fun `DEFAULT has ID 0x40`() {
        assertEquals(0x40, Profile.DEFAULT.id)
    }

    @Test
    fun `DEFAULT has 60ms frame time`() {
        assertEquals(60, Profile.DEFAULT.frameTimeMs)
    }

    // ===== Abbreviations =====

    @Test
    fun `all profiles have non-empty abbreviations`() {
        Profile.all.forEach { profile ->
            assertTrue("${profile.name} should have abbreviation", profile.abbreviation.isNotEmpty())
        }
    }

    @Test
    fun `abbreviations are short (max 4 chars)`() {
        Profile.all.forEach { profile ->
            assertTrue(
                "${profile.name} abbreviation '${profile.abbreviation}' too long",
                profile.abbreviation.length <= 4
            )
        }
    }

    @Test
    fun `ULBW abbreviation is ULBW`() {
        assertEquals("ULBW", Profile.ULBW.abbreviation)
    }

    @Test
    fun `MQ abbreviation is MQ`() {
        assertEquals("MQ", Profile.MQ.abbreviation)
    }

    // ===== Names =====

    @Test
    fun `all profiles have non-empty names`() {
        Profile.all.forEach { profile ->
            assertTrue("Profile should have name", profile.name.isNotEmpty())
        }
    }

    @Test
    fun `ULBW name is Ultra Low Bandwidth`() {
        assertEquals("Ultra Low Bandwidth", Profile.ULBW.name)
    }

    @Test
    fun `MQ name is Medium Quality`() {
        assertEquals("Medium Quality", Profile.MQ.name)
    }

    // ===== Data object equality (Kotlin data object provides automatic equals) =====

    @Test
    fun `profile instances are singletons`() {
        // data objects are singletons
        assertTrue(Profile.MQ === Profile.MQ)
        assertTrue(Profile.ULBW === Profile.ULBW)
    }

    @Test
    fun `fromId returns same instance`() {
        assertTrue(Profile.MQ === Profile.fromId(0x40))
        assertTrue(Profile.ULBW === Profile.fromId(0x10))
    }
}
