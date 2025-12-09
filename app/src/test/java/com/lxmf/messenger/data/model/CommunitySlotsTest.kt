package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CommunitySlots.
 * Tests Meshtastic interference avoidance and community slot management.
 */
class CommunitySlotsTest {
    // ========== isMeshtasticSlot Tests ==========

    @Test
    fun `isMeshtasticSlot returns true for US slot 9`() {
        assertTrue(CommunitySlots.isMeshtasticSlot("us_915", 9))
    }

    @Test
    fun `isMeshtasticSlot returns true for US slot 20`() {
        assertTrue(CommunitySlots.isMeshtasticSlot("us_915", 20))
    }

    @Test
    fun `isMeshtasticSlot returns false for US slot 50`() {
        assertFalse(CommunitySlots.isMeshtasticSlot("us_915", 50))
    }

    @Test
    fun `isMeshtasticSlot returns false for US slot 0`() {
        assertFalse(CommunitySlots.isMeshtasticSlot("us_915", 0))
    }

    @Test
    fun `isMeshtasticSlot returns false for US slot 100`() {
        assertFalse(CommunitySlots.isMeshtasticSlot("us_915", 100))
    }

    @Test
    fun `isMeshtasticSlot returns false for EU region`() {
        // EU regions don't have Meshtastic interference slots defined
        assertFalse(CommunitySlots.isMeshtasticSlot("eu_868_p", 9))
        assertFalse(CommunitySlots.isMeshtasticSlot("eu_868_p", 20))
    }

    @Test
    fun `isMeshtasticSlot returns false for Australia region`() {
        assertFalse(CommunitySlots.isMeshtasticSlot("au_915", 9))
        assertFalse(CommunitySlots.isMeshtasticSlot("au_915", 20))
    }

    @Test
    fun `isMeshtasticSlot returns false for invalid region`() {
        assertFalse(CommunitySlots.isMeshtasticSlot("invalid_region", 9))
        assertFalse(CommunitySlots.isMeshtasticSlot("invalid_region", 20))
    }

    // ========== forRegion Tests ==========

    @Test
    fun `forRegion returns slots for US region`() {
        val slots = CommunitySlots.forRegion("us_915")

        assertTrue("US should have community slots", slots.isNotEmpty())
        assertEquals(2, slots.size) // Meshtastic Default and NoVa
    }

    @Test
    fun `forRegion returns empty for EU region`() {
        val slots = CommunitySlots.forRegion("eu_868_p")
        assertTrue("EU should have no community slots defined", slots.isEmpty())
    }

    @Test
    fun `forRegion returns empty for invalid region`() {
        val slots = CommunitySlots.forRegion("invalid_region")
        assertTrue(slots.isEmpty())
    }

    @Test
    fun `forRegion US slots have correct data`() {
        val slots = CommunitySlots.forRegion("us_915")

        val meshtasticDefault = slots.find { it.slot == 20 }
        val novaSlot = slots.find { it.slot == 9 }

        assertFalse("Should have Meshtastic default slot", meshtasticDefault == null)
        assertFalse("Should have NoVa slot", novaSlot == null)

        assertTrue(
            "Meshtastic slot description should warn about interference",
            meshtasticDefault!!.description.contains("⚠️"),
        )
        assertTrue(
            "NoVa slot description should warn about interference",
            novaSlot!!.description.contains("⚠️"),
        )
    }

    // ========== meshtasticSlots Set Tests ==========

    @Test
    fun `meshtasticSlots contains expected slots`() {
        assertEquals(setOf(20, 9), CommunitySlots.meshtasticSlots)
    }

    @Test
    fun `meshtasticSlots has exactly 2 entries`() {
        assertEquals(2, CommunitySlots.meshtasticSlots.size)
    }

    // ========== CommunitySlot Data Tests ==========

    @Test
    fun `all community slots have required fields`() {
        CommunitySlots.slots.forEach { slot ->
            assertTrue("Slot name should not be blank", slot.name.isNotBlank())
            assertTrue("Slot region should not be blank", slot.regionId.isNotBlank())
            assertTrue("Slot number should be >= 0", slot.slot >= 0)
            assertTrue("Slot description should not be blank", slot.description.isNotBlank())
        }
    }

    @Test
    fun `all community slots have valid region IDs`() {
        CommunitySlots.slots.forEach { slot ->
            val region = FrequencyRegions.findById(slot.regionId)
            assertTrue(
                "Region ${slot.regionId} should exist",
                region != null,
            )
        }
    }
}
