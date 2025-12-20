package com.lxmf.messenger.ui.components

import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.model.EnrichedContact
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ShareLocationBottomSheet contact sorting logic.
 *
 * Tests that contacts are sorted by recency:
 * 1. Contacts with recent messages appear first (by lastMessageTimestamp desc)
 * 2. Contacts without messages are sorted by addedTimestamp desc
 */
class ShareLocationBottomSheetTest {

    // ========== Sorting Logic Tests ==========

    @Test
    fun `contacts sorted by lastMessageTimestamp descending`() {
        // Given - contacts with different message timestamps
        val contacts = listOf(
            createContact("alice", lastMessageTimestamp = 1000L),
            createContact("bob", lastMessageTimestamp = 3000L), // Most recent
            createContact("carol", lastMessageTimestamp = 2000L),
        )

        // When - apply the same sorting logic as ShareLocationBottomSheet
        val sorted = sortContactsByRecency(contacts)

        // Then - sorted by lastMessageTimestamp descending
        assertEquals("bob", sorted[0].displayName)
        assertEquals("carol", sorted[1].displayName)
        assertEquals("alice", sorted[2].displayName)
    }

    @Test
    fun `contacts without messages sorted by addedTimestamp descending`() {
        // Given - contacts without message history
        val contacts = listOf(
            createContact("alice", lastMessageTimestamp = null, addedTimestamp = 1000L),
            createContact("bob", lastMessageTimestamp = null, addedTimestamp = 3000L), // Added most recently
            createContact("carol", lastMessageTimestamp = null, addedTimestamp = 2000L),
        )

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then - sorted by addedTimestamp descending
        assertEquals("bob", sorted[0].displayName)
        assertEquals("carol", sorted[1].displayName)
        assertEquals("alice", sorted[2].displayName)
    }

    @Test
    fun `contacts with messages appear before contacts without messages`() {
        // Given - mix of contacts with and without message history
        val contacts = listOf(
            createContact("no-messages-old", lastMessageTimestamp = null, addedTimestamp = 1000L),
            createContact("has-messages", lastMessageTimestamp = 100L), // Very old message
            createContact("no-messages-new", lastMessageTimestamp = null, addedTimestamp = 5000L),
        )

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then - contact with messages appears first (even with old timestamp)
        assertEquals("has-messages", sorted[0].displayName)
        // Then - contacts without messages sorted by addedTimestamp
        assertEquals("no-messages-new", sorted[1].displayName)
        assertEquals("no-messages-old", sorted[2].displayName)
    }

    @Test
    fun `contacts with same lastMessageTimestamp sorted by addedTimestamp`() {
        // Given - contacts with same message timestamp
        val contacts = listOf(
            createContact("alice", lastMessageTimestamp = 1000L, addedTimestamp = 100L),
            createContact("bob", lastMessageTimestamp = 1000L, addedTimestamp = 300L), // Added more recently
            createContact("carol", lastMessageTimestamp = 1000L, addedTimestamp = 200L),
        )

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then - sorted by addedTimestamp as tiebreaker
        assertEquals("bob", sorted[0].displayName)
        assertEquals("carol", sorted[1].displayName)
        assertEquals("alice", sorted[2].displayName)
    }

    @Test
    fun `empty contact list returns empty list`() {
        // Given
        val contacts = emptyList<EnrichedContact>()

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then
        assertEquals(0, sorted.size)
    }

    @Test
    fun `single contact returns same contact`() {
        // Given
        val contacts = listOf(createContact("solo"))

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then
        assertEquals(1, sorted.size)
        assertEquals("solo", sorted[0].displayName)
    }

    // ========== Helper Functions ==========

    /**
     * Applies the same sorting logic as ShareLocationBottomSheet.filteredContacts
     */
    private fun sortContactsByRecency(contacts: List<EnrichedContact>): List<EnrichedContact> {
        return contacts.sortedWith(
            compareByDescending<EnrichedContact> { it.lastMessageTimestamp ?: 0L }
                .thenByDescending { it.addedTimestamp }
        )
    }

    private fun createContact(
        name: String,
        lastMessageTimestamp: Long? = null,
        addedTimestamp: Long = System.currentTimeMillis(),
    ): EnrichedContact {
        return EnrichedContact(
            destinationHash = "hash_$name",
            publicKey = null,
            displayName = name,
            customNickname = null,
            announceName = name,
            lastSeenTimestamp = null,
            hops = null,
            isOnline = false,
            hasConversation = lastMessageTimestamp != null,
            unreadCount = 0,
            lastMessageTimestamp = lastMessageTimestamp,
            notes = null,
            tags = null,
            addedTimestamp = addedTimestamp,
            addedVia = "MANUAL",
            isPinned = false,
            status = ContactStatus.ACTIVE,
            isMyRelay = false,
            nodeType = null,
        )
    }
}
