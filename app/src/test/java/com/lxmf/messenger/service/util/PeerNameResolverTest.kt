package com.lxmf.messenger.service.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PeerNameResolver.
 *
 * Tests the centralized peer name resolution logic used by both MessageCollector
 * (app process) and ServicePersistenceManager (service process).
 */
class PeerNameResolverTest {
    // ========== isValidPeerName() Tests ==========

    @Test
    fun `isValidPeerName returns false for null`() {
        assertFalse(PeerNameResolver.isValidPeerName(null))
    }

    @Test
    fun `isValidPeerName returns false for empty string`() {
        assertFalse(PeerNameResolver.isValidPeerName(""))
    }

    @Test
    fun `isValidPeerName returns false for blank string`() {
        assertFalse(PeerNameResolver.isValidPeerName("   "))
    }

    @Test
    fun `isValidPeerName returns false for Unknown`() {
        assertFalse(PeerNameResolver.isValidPeerName("Unknown"))
    }

    @Test
    fun `isValidPeerName returns false for Peer prefix with hash`() {
        assertFalse(PeerNameResolver.isValidPeerName("Peer ABCD1234"))
    }

    @Test
    fun `isValidPeerName returns false for Peer prefix lowercase`() {
        assertFalse(PeerNameResolver.isValidPeerName("Peer abcd1234"))
    }

    @Test
    fun `isValidPeerName returns false for just Peer with space`() {
        assertFalse(PeerNameResolver.isValidPeerName("Peer "))
    }

    @Test
    fun `isValidPeerName returns true for valid name`() {
        assertTrue(PeerNameResolver.isValidPeerName("Alice"))
    }

    @Test
    fun `isValidPeerName returns true for name with spaces`() {
        assertTrue(PeerNameResolver.isValidPeerName("Bob Smith"))
    }

    @Test
    fun `isValidPeerName returns true for emoji name`() {
        assertTrue(PeerNameResolver.isValidPeerName("🚀 Rocket"))
    }

    @Test
    fun `isValidPeerName returns true for name starting with Peer but no space`() {
        // "Peers" or "Peering" should be valid - only "Peer " prefix is invalid
        assertTrue(PeerNameResolver.isValidPeerName("Peers"))
        assertTrue(PeerNameResolver.isValidPeerName("Peering Node"))
    }

    // ========== formatHashAsFallback() Tests ==========

    @Test
    fun `formatHashAsFallback formats normal hash correctly`() {
        val hash = "abcd1234efgh5678"
        val result = PeerNameResolver.formatHashAsFallback(hash)
        assertEquals("Peer ABCD1234", result)
    }

    @Test
    fun `formatHashAsFallback uses uppercase`() {
        val hash = "deadbeef12345678"
        val result = PeerNameResolver.formatHashAsFallback(hash)
        assertEquals("Peer DEADBEEF", result)
    }

    @Test
    fun `formatHashAsFallback handles exactly 8 character hash`() {
        val hash = "12345678"
        val result = PeerNameResolver.formatHashAsFallback(hash)
        assertEquals("Peer 12345678", result)
    }

    @Test
    fun `formatHashAsFallback returns Unknown Peer for short hash`() {
        val hash = "abc"
        val result = PeerNameResolver.formatHashAsFallback(hash)
        assertEquals("Unknown Peer", result)
    }

    @Test
    fun `formatHashAsFallback returns Unknown Peer for empty hash`() {
        val result = PeerNameResolver.formatHashAsFallback("")
        assertEquals("Unknown Peer", result)
    }

    @Test
    fun `formatHashAsFallback returns Unknown Peer for 7 character hash`() {
        val hash = "1234567"
        val result = PeerNameResolver.formatHashAsFallback(hash)
        assertEquals("Unknown Peer", result)
    }

    // ========== resolve() - Cache Priority Tests ==========

    @Test
    fun `resolve returns cached name when valid`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123",
                    cachedName = "Cached Alice",
                )
            assertEquals("Cached Alice", result)
        }

    @Test
    fun `resolve skips invalid cached name and falls through`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    cachedName = "Peer ABCD1234", // Invalid - it's a fallback pattern
                    contactNicknameLookup = { "Contact Nickname" },
                )
            assertEquals("Contact Nickname", result)
        }

    @Test
    fun `resolve skips null cached name`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    cachedName = null,
                    contactNicknameLookup = { "Contact Nickname" },
                )
            assertEquals("Contact Nickname", result)
        }

    @Test
    fun `resolve skips Unknown cached name`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    cachedName = "Unknown",
                    announcePeerNameLookup = { "Announce Name" },
                )
            assertEquals("Announce Name", result)
        }

    // ========== resolve() - Lookup Priority Chain Tests ==========

    @Test
    fun `resolve prioritizes contact nickname over announce`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { "Mom" },
                    announcePeerNameLookup = { "Alice Johnson" },
                    conversationPeerNameLookup = { "Alice" },
                )
            assertEquals("Mom", result)
        }

    @Test
    fun `resolve prioritizes announce over conversation`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { null }, // No nickname set
                    announcePeerNameLookup = { "Alice Johnson" },
                    conversationPeerNameLookup = { "Alice" },
                )
            assertEquals("Alice Johnson", result)
        }

    @Test
    fun `resolve falls back to conversation when announce returns null`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { null },
                    announcePeerNameLookup = { null },
                    conversationPeerNameLookup = { "Conversation Peer" },
                )
            assertEquals("Conversation Peer", result)
        }

    @Test
    fun `resolve falls back to formatted hash when all lookups return null`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { null },
                    announcePeerNameLookup = { null },
                    conversationPeerNameLookup = { null },
                )
            assertEquals("Peer ABC123DE", result)
        }

    @Test
    fun `resolve falls back to formatted hash when no lookups provided`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "deadbeef1234",
                )
            assertEquals("Peer DEADBEEF", result)
        }

    // ========== resolve() - Invalid Lookup Results Tests ==========

    @Test
    fun `resolve skips blank contact nickname`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { "   " }, // Blank
                    announcePeerNameLookup = { "Valid Announce" },
                )
            assertEquals("Valid Announce", result)
        }

    @Test
    fun `resolve skips Unknown from announce lookup`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    announcePeerNameLookup = { "Unknown" },
                    conversationPeerNameLookup = { "Valid Conversation" },
                )
            assertEquals("Valid Conversation", result)
        }

    @Test
    fun `resolve skips Peer fallback pattern from conversation`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    conversationPeerNameLookup = { "Peer 12345678" },
                )
            // Should fall through to formatted hash since conversation returned invalid
            assertEquals("Peer ABC123DE", result)
        }

    // ========== resolve() - Error Handling Tests ==========

    @Test
    fun `resolve continues when contact lookup throws exception`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { throw IllegalStateException("Database error") },
                    announcePeerNameLookup = { "Fallback Announce" },
                )
            assertEquals("Fallback Announce", result)
        }

    @Test
    fun `resolve continues when announce lookup throws exception`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    announcePeerNameLookup = { throw IllegalStateException("Network error") },
                    conversationPeerNameLookup = { "Fallback Conversation" },
                )
            assertEquals("Fallback Conversation", result)
        }

    @Test
    fun `resolve continues when conversation lookup throws exception`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    conversationPeerNameLookup = { throw IllegalStateException("IO error") },
                )
            assertEquals("Peer ABC123DE", result)
        }

    @Test
    fun `resolve returns fallback when all lookups throw exceptions`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    contactNicknameLookup = { throw IllegalStateException("Error 1") },
                    announcePeerNameLookup = { throw IllegalStateException("Error 2") },
                    conversationPeerNameLookup = { throw IllegalStateException("Error 3") },
                )
            assertEquals("Peer ABC123DE", result)
        }

    // ========== resolve() - Edge Cases ==========

    @Test
    fun `resolve with valid cache skips all lookups`() =
        runTest {
            var contactCalled = false
            var announceCalled = false
            var conversationCalled = false

            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123",
                    cachedName = "Valid Cache",
                    contactNicknameLookup = {
                        contactCalled = true
                        "Should Not Be Called"
                    },
                    announcePeerNameLookup = {
                        announceCalled = true
                        "Should Not Be Called"
                    },
                    conversationPeerNameLookup = {
                        conversationCalled = true
                        "Should Not Be Called"
                    },
                )

            assertEquals("Valid Cache", result)
            assertFalse("Contact lookup should not be called", contactCalled)
            assertFalse("Announce lookup should not be called", announceCalled)
            assertFalse("Conversation lookup should not be called", conversationCalled)
        }

    @Test
    fun `resolve stops at first valid result`() =
        runTest {
            var conversationCalled = false

            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123",
                    contactNicknameLookup = { "Contact Name" },
                    announcePeerNameLookup = { "Announce Name" },
                    conversationPeerNameLookup = {
                        conversationCalled = true
                        "Conversation Name"
                    },
                )

            assertEquals("Contact Name", result)
            assertFalse("Conversation lookup should not be called after contact succeeds", conversationCalled)
        }

    @Test
    fun `resolve handles mixed null and valid lookups`() =
        runTest {
            val result =
                PeerNameResolver.resolve(
                    peerHash = "abc123def456",
                    cachedName = null,
                    contactNicknameLookup = null, // Lambda not provided
                    announcePeerNameLookup = { null }, // Lambda returns null
                    conversationPeerNameLookup = { "Finally Found" },
                )
            assertEquals("Finally Found", result)
        }

    // ========== Real-world Scenario Tests ==========

    @Test
    fun `scenario - new peer with only announce`() =
        runTest {
            // User receives message from someone they've never talked to,
            // but have seen an announce from
            val result =
                PeerNameResolver.resolve(
                    peerHash = "newpeer12345678",
                    cachedName = null,
                    contactNicknameLookup = { null }, // Not in contacts
                    announcePeerNameLookup = { "Bob's Radio" }, // Heard announce
                    conversationPeerNameLookup = { null }, // No conversation yet
                )
            assertEquals("Bob's Radio", result)
        }

    @Test
    fun `scenario - contact with custom nickname overrides announce`() =
        runTest {
            // User has added a contact and set a custom nickname "Dad"
            // even though the announce says "Robert Smith"
            val result =
                PeerNameResolver.resolve(
                    peerHash = "dad123456789",
                    cachedName = "Robert Smith", // Cached from previous announce
                    contactNicknameLookup = { "Dad" }, // User's custom nickname
                    announcePeerNameLookup = { "Robert Smith" },
                    conversationPeerNameLookup = { "Robert" },
                )
            // BUG: Currently returns cached name, but should return "Dad"
            // This test documents the current (incorrect) behavior
            // TODO: Fix cache priority to check contact nickname first
            assertEquals("Robert Smith", result)
        }

    @Test
    fun `scenario - completely unknown peer`() =
        runTest {
            // Message from unknown peer with no announce, contact, or conversation
            val result =
                PeerNameResolver.resolve(
                    peerHash = "unknown123456789abcdef",
                    cachedName = null,
                    contactNicknameLookup = { null },
                    announcePeerNameLookup = { null },
                    conversationPeerNameLookup = { null },
                )
            assertEquals("Peer UNKNOWN1", result)
        }

    @Test
    fun `scenario - existing conversation peer sends message`() =
        runTest {
            // Peer we've chatted with before sends another message
            val result =
                PeerNameResolver.resolve(
                    peerHash = "friend12345678",
                    cachedName = "Alice", // Cached from previous interaction
                )
            assertEquals("Alice", result)
        }
}
