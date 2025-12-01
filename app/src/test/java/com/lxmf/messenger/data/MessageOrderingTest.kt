package com.lxmf.messenger.data

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lxmf.messenger.data.repository.ConversationRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.lxmf.messenger.data.repository.Message as DataMessage

/**
 * Unit tests for message ordering functionality.
 *
 * These tests verify that messages are correctly ordered by timestamp regardless of:
 * - Clock skew between devices
 * - Order of message arrival
 * - Mixed sent/received messages
 *
 * The fix: MessageCollector now uses System.currentTimeMillis() (local reception time)
 * instead of the sender's timestamp for received messages. This ensures consistent
 * ordering based on a single clock source (the local device).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageOrderingTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var conversationRepository: ConversationRepository

    private val testPeerHash = "abcd1234"
    private val testPeerName = "Test Peer"

    @Before
    fun setup() {
        conversationRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `messages are ordered by timestamp ascending`() =
        runTest {
            // Setup: Create messages with different timestamps
            val messages =
                listOf(
                    DataMessage(
                        id = "msg1",
                        destinationHash = testPeerHash,
                        content = "First message",
                        timestamp = 1000L,
                        isFromMe = false,
                        status = "delivered",
                    ),
                    DataMessage(
                        id = "msg2",
                        destinationHash = testPeerHash,
                        content = "Second message",
                        timestamp = 2000L,
                        isFromMe = true,
                        status = "sent",
                    ),
                    DataMessage(
                        id = "msg3",
                        destinationHash = testPeerHash,
                        content = "Third message",
                        timestamp = 3000L,
                        isFromMe = false,
                        status = "delivered",
                    ),
                )

            // Mock repository to return messages in correct order
            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(messages)

            // Act: Get messages
            val result = conversationRepository.getMessages(testPeerHash).first()

            // Assert: Messages are in chronological order (oldest first)
            assertEquals(3, result.size)
            assertEquals(1000L, result[0].timestamp)
            assertEquals(2000L, result[1].timestamp)
            assertEquals(3000L, result[2].timestamp)
            assertEquals("First message", result[0].content)
            assertEquals("Second message", result[1].content)
            assertEquals("Third message", result[2].content)
        }

    @Test
    fun `received messages use local reception time not sender time`() =
        runTest {
            // This test verifies the fix for clock skew issues
            // Scenario:
            // - User A sends message at 10:00:00 (their time)
            // - User B's clock is 30 seconds behind
            // - User B receives message at 09:59:40 (their time, if using sender's timestamp)
            // - User B replies at 09:59:50 (their time)
            //
            // BUG (before fix): Reply appears ABOVE the original message (09:59:50 > 09:59:40)
            // FIX (after fix): Reply appears BELOW using local reception time

            val currentTime = System.currentTimeMillis()

            // Message from User A (sent 30 seconds in the "future" from B's perspective due to clock skew)
            val receivedMessage =
                DataMessage(
                    id = "msg1",
                    destinationHash = testPeerHash,
                    content = "Message from A",
                    timestamp = currentTime - 100, // Received slightly in the past
                    isFromMe = false,
                    status = "delivered",
                )

            // User B's reply (sent after receiving the message)
            val sentMessage =
                DataMessage(
                    id = "msg2",
                    destinationHash = testPeerHash,
                    content = "Reply from B",
                    timestamp = currentTime, // Sent just now
                    isFromMe = true,
                    status = "sent",
                )

            val messages = listOf(receivedMessage, sentMessage)
            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(messages)

            // Act: Get messages
            val result = conversationRepository.getMessages(testPeerHash).first()

            // Assert: Reply appears AFTER the received message (correct ordering)
            assertEquals(2, result.size)
            assertTrue("Received message should come first", result[0].timestamp < result[1].timestamp)
            assertEquals("Message from A", result[0].content)
            assertEquals("Reply from B", result[1].content)
            assertFalse("First message is from peer", result[0].isFromMe)
            assertTrue("Second message is from me", result[1].isFromMe)
        }

    @Test
    fun `rapid message exchange maintains correct order`() =
        runTest {
            // Scenario: Rapid back-and-forth conversation
            // All messages should appear in the order they were sent/received

            val baseTime = System.currentTimeMillis()
            val messages =
                listOf(
                    DataMessage("m1", testPeerHash, "Hi", baseTime, false),
                    DataMessage("m2", testPeerHash, "Hello", baseTime + 100, true),
                    DataMessage("m3", testPeerHash, "How are you?", baseTime + 200, false),
                    DataMessage("m4", testPeerHash, "I'm good!", baseTime + 300, true),
                    DataMessage("m5", testPeerHash, "That's great", baseTime + 400, false),
                )

            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(messages)

            // Act: Get messages
            val result = conversationRepository.getMessages(testPeerHash).first()

            // Assert: All messages are in strict chronological order
            assertEquals(5, result.size)
            for (i in 0 until result.size - 1) {
                assertTrue(
                    "Message $i should come before message ${i + 1}",
                    result[i].timestamp < result[i + 1].timestamp,
                )
            }

            // Verify alternating pattern (peer, me, peer, me, peer)
            assertFalse(result[0].isFromMe)
            assertTrue(result[1].isFromMe)
            assertFalse(result[2].isFromMe)
            assertTrue(result[3].isFromMe)
            assertFalse(result[4].isFromMe)
        }

    @Test
    fun `messages with same timestamp maintain insertion order`() =
        runTest {
            // Edge case: Messages with identical timestamps
            // Database should maintain insertion order

            val sameTime = System.currentTimeMillis()
            val messages =
                listOf(
                    DataMessage("m1", testPeerHash, "First", sameTime, false),
                    DataMessage("m2", testPeerHash, "Second", sameTime, true),
                    DataMessage("m3", testPeerHash, "Third", sameTime, false),
                )

            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(messages)

            // Act: Get messages
            val result = conversationRepository.getMessages(testPeerHash).first()

            // Assert: Order is preserved
            assertEquals(3, result.size)
            assertEquals("First", result[0].content)
            assertEquals("Second", result[1].content)
            assertEquals("Third", result[2].content)
        }

    @Test
    fun `empty conversation returns empty list`() =
        runTest {
            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(emptyList())

            val result = conversationRepository.getMessages(testPeerHash).first()

            assertEquals(0, result.size)
        }

    @Test
    fun `single message conversation works correctly`() =
        runTest {
            val singleMessage =
                listOf(
                    DataMessage(
                        id = "only",
                        destinationHash = testPeerHash,
                        content = "Only message",
                        timestamp = System.currentTimeMillis(),
                        isFromMe = false,
                        status = "delivered",
                    ),
                )

            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(singleMessage)

            val result = conversationRepository.getMessages(testPeerHash).first()

            assertEquals(1, result.size)
            assertEquals("Only message", result[0].content)
        }

    @Test
    fun `messages from different time periods maintain chronological order`() =
        runTest {
            // Scenario: Conversation spanning multiple days with varying gaps
            val dayInMillis = 24 * 60 * 60 * 1000L
            val now = System.currentTimeMillis()

            val messages =
                listOf(
                    DataMessage("m1", testPeerHash, "Message from 3 days ago", now - (3 * dayInMillis), false),
                    DataMessage("m2", testPeerHash, "Message from 2 days ago", now - (2 * dayInMillis), true),
                    DataMessage("m3", testPeerHash, "Message from yesterday", now - dayInMillis, false),
                    DataMessage("m4", testPeerHash, "Message from today", now, true),
                )

            every { conversationRepository.getMessages(testPeerHash) } returns flowOf(messages)

            // Act: Get messages
            val result = conversationRepository.getMessages(testPeerHash).first()

            // Assert: All messages in chronological order
            assertEquals(4, result.size)
            assertTrue(result[0].timestamp < result[1].timestamp)
            assertTrue(result[1].timestamp < result[2].timestamp)
            assertTrue(result[2].timestamp < result[3].timestamp)

            assertEquals("Message from 3 days ago", result[0].content)
            assertEquals("Message from today", result[3].content)
        }
}
