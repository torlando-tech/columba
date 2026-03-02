package com.lxmf.messenger.service.manager

import com.chaquo.python.PyObject
import com.lxmf.messenger.service.persistence.ServicePersistenceManager
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for EventHandler.
 *
 * Tests the event-driven message delivery and startup drain functionality.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventHandlerTest {
    private lateinit var state: ServiceState
    private lateinit var wrapperManager: PythonWrapperManager
    private lateinit var broadcaster: CallbackBroadcaster
    private lateinit var testScope: TestScope
    private lateinit var eventHandler: EventHandler

    @Before
    fun setup() {
        state = ServiceState()
        wrapperManager = mockk()
        broadcaster = mockk()
        testScope = TestScope(UnconfinedTestDispatcher())

        // Stub broadcaster methods that might be called
        every { broadcaster.broadcastMessage(any()) } returns Unit
        every { broadcaster.broadcastDeliveryStatus(any()) } returns Unit
        every { broadcaster.broadcastReactionReceived(any()) } returns Unit
        every { broadcaster.broadcastAnnounce(any()) } returns Unit

        eventHandler =
            EventHandler(
                state = state,
                wrapperManager = wrapperManager,
                broadcaster = broadcaster,
                scope = testScope,
                attachmentStorage = null,
            )
    }

    // ========== drainPendingMessages() Tests ==========

    @Test
    fun `drainPendingMessages handles empty queue gracefully`() =
        runTest {
            // Setup: Mock wrapper returning empty list
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns emptyList()

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandler.drainPendingMessages()
                    testScope.advanceUntilIdle()
                }

            // Assert: No exception thrown
            assertTrue("drainPendingMessages should handle empty queue", result.isSuccess)
        }

    @Test
    fun `drainPendingMessages handles null response gracefully`() =
        runTest {
            // Setup: Mock wrapper returning null
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns null

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandler.drainPendingMessages()
                    testScope.advanceUntilIdle()
                }

            // Assert: No exception thrown
            assertTrue("drainPendingMessages should handle null response", result.isSuccess)
        }

    @Test
    fun `drainPendingMessages handles exception gracefully`() =
        runTest {
            // Setup: Mock wrapper throwing exception
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } throws RuntimeException("Test error")

            // Act - should complete successfully (exception caught internally)
            val result =
                runCatching {
                    eventHandler.drainPendingMessages()
                    testScope.advanceUntilIdle()
                }

            // Assert: No exception propagated
            assertTrue("drainPendingMessages should handle exception gracefully", result.isSuccess)
        }

    // ========== handleMessageReceivedEvent() Tests ==========

    @Test
    fun `handleMessageReceivedEvent handles empty queue gracefully`() =
        runTest {
            // Setup: Mock wrapper returning empty list
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns emptyList()

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandler.handleMessageReceivedEvent("{\"event\": \"message\"}")
                    testScope.advanceUntilIdle()
                }

            // Assert: No exception thrown
            assertTrue("handleMessageReceivedEvent should handle empty queue", result.isSuccess)
        }

    @Test
    fun `handleMessageReceivedEvent handles null response gracefully`() =
        runTest {
            // Setup: Mock wrapper returning null
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns null

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandler.handleMessageReceivedEvent("{\"event\": \"message\"}")
                    testScope.advanceUntilIdle()
                }

            // Assert: No exception thrown
            assertTrue("handleMessageReceivedEvent should handle null response", result.isSuccess)
        }

    @Test
    fun `handleMessageReceivedEvent handles exception gracefully`() =
        runTest {
            // Setup: Mock wrapper throwing exception
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } throws RuntimeException("Test error")

            // Act - should complete successfully (exception caught internally)
            val result =
                runCatching {
                    eventHandler.handleMessageReceivedEvent("{\"event\": \"message\"}")
                    testScope.advanceUntilIdle()
                }

            // Assert: No exception propagated
            assertTrue("handleMessageReceivedEvent should handle exception gracefully", result.isSuccess)
        }

    // ========== setConversationActive() Tests ==========

    @Test
    fun `setConversationActive sets state to true`() {
        // Initial state
        assertFalse(state.isConversationActive.get())

        // Act
        eventHandler.setConversationActive(true)

        // Assert
        assertTrue(state.isConversationActive.get())
    }

    @Test
    fun `setConversationActive sets state to false`() {
        // Setup
        state.isConversationActive.set(true)
        assertTrue(state.isConversationActive.get())

        // Act
        eventHandler.setConversationActive(false)

        // Assert
        assertFalse(state.isConversationActive.get())
    }

    // ========== stopAll() Tests ==========

    @Test
    fun `stopAll can be called without issues in event-driven mode`() {
        // In event-driven mode, stopAll() is a no-op that just logs
        // This test ensures it can be called without throwing exceptions

        // Act - should not throw
        eventHandler.stopAll()

        // No assertions needed - just verifying no exceptions
    }

    @Test
    fun `stopAll handles null job gracefully`() {
        // Setup: No job set (default in event-driven mode)
        assertNull(state.pollingJob)

        // Act - should not throw
        eventHandler.stopAll()

        // Assert: Job remains null
        assertNull(state.pollingJob)
    }

    // ========== handleDeliveryStatusEvent() Tests ==========

    @Test
    fun `handleDeliveryStatusEvent broadcasts status`() {
        val statusJson = "{\"status\": \"delivered\"}"

        // Act - should complete successfully
        val result = runCatching { eventHandler.handleDeliveryStatusEvent(statusJson) }

        // Assert
        assertTrue("handleDeliveryStatusEvent should complete successfully", result.isSuccess)
    }

    @Test
    fun `handleDeliveryStatusEvent handles exception in broadcaster gracefully`() {
        val statusJson = "{\"status\": \"delivered\"}"
        every { broadcaster.broadcastDeliveryStatus(any()) } throws RuntimeException("Broadcast error")

        // Act - should not throw (exception is caught internally)
        val result = runCatching { eventHandler.handleDeliveryStatusEvent(statusJson) }

        // Assert: Exception is handled internally
        assertTrue("handleDeliveryStatusEvent should handle exception gracefully", result.isSuccess)
    }

    // ========== handleReactionReceivedEvent() Tests ==========

    @Test
    fun `handleReactionReceivedEvent broadcasts reaction`() {
        val reactionJson = """{"reaction_to": "msg123", "emoji": "👍", "sender": "abc"}"""

        // Act - should complete successfully
        val result = runCatching { eventHandler.handleReactionReceivedEvent(reactionJson) }

        // Assert
        assertTrue("handleReactionReceivedEvent should complete successfully", result.isSuccess)
    }

    @Test
    fun `handleReactionReceivedEvent handles exception gracefully`() {
        val reactionJson = """{"reaction_to": "msg123", "emoji": "👍"}"""
        every { broadcaster.broadcastReactionReceived(any()) } throws RuntimeException("Broadcast error")

        // Act - should not throw
        val result = runCatching { eventHandler.handleReactionReceivedEvent(reactionJson) }

        // Assert: Exception is handled internally
        assertTrue("handleReactionReceivedEvent should handle exception gracefully", result.isSuccess)
    }

    // ========== startEventHandling() Tests ==========

    @Test
    fun `startEventHandling drains pending announces on startup`() =
        runTest {
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns emptyList()

            // Act
            eventHandler.startEventHandling()
            testScope.advanceUntilIdle()

            // Assert: wrapper was called to get pending announces
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) }
        }

    @Test
    fun `startEventHandling handles null pending announces gracefully`() =
        runTest {
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns null

            // Act - should not throw
            eventHandler.startEventHandling()
            testScope.advanceUntilIdle()

            // No crash
        }

    @Test
    fun `startEventHandling handles exception in drain gracefully`() =
        runTest {
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } throws RuntimeException("Error")

            // Act - should not throw
            eventHandler.startEventHandling()
            testScope.advanceUntilIdle()

            // No crash
        }

    // Note: handleAnnounceEvent() tests require Android runtime for Base64/Log
    // The fix for issue #233 (propagation node garbled names) is tested via:
    // - AppDataParserTest for msgpack metadata extraction
    // - Integration tests for end-to-end announce handling

    // ========== Conditional Broadcast Tests (Block Unknown Senders fix) ==========
    // These tests verify that EventHandler only broadcasts messages that were successfully persisted.
    // This prevents blocked messages from triggering notifications in the app process.

    @Test
    fun `handleMessageReceivedEvent broadcasts when persistMessage returns true`() =
        runTest {
            // Setup: Create EventHandler with persistence manager
            val persistenceManager = mockk<ServicePersistenceManager>()
            coEvery { persistenceManager.persistMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

            val eventHandlerWithPersistence =
                EventHandler(
                    state = state,
                    wrapperManager = wrapperManager,
                    broadcaster = broadcaster,
                    scope = testScope,
                    attachmentStorage = null,
                    persistenceManager = persistenceManager,
                )

            // Message JSON with full_message flag (truly event-driven path)
            val messageJson =
                """
                {
                    "full_message": true,
                    "message_hash": "test_hash_123",
                    "content": "Hello world",
                    "source_hash": "abcdef123456",
                    "timestamp": 1234567890
                }
                """.trimIndent()

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandlerWithPersistence.handleMessageReceivedEvent(messageJson)
                    testScope.advanceUntilIdle()
                }

            // Assert: Function completed successfully with persistence returning true
            assertTrue("handleMessageReceivedEvent should complete when persistMessage returns true", result.isSuccess)
        }

    @Test
    fun `handleMessageReceivedEvent does not broadcast when persistMessage returns false`() =
        runTest {
            // Setup: Create EventHandler with persistence manager that blocks messages
            val persistenceManager = mockk<ServicePersistenceManager>()
            coEvery { persistenceManager.persistMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns false

            val eventHandlerWithPersistence =
                EventHandler(
                    state = state,
                    wrapperManager = wrapperManager,
                    broadcaster = broadcaster,
                    scope = testScope,
                    attachmentStorage = null,
                    persistenceManager = persistenceManager,
                )

            // Message JSON with full_message flag (truly event-driven path)
            val messageJson =
                """
                {
                    "full_message": true,
                    "message_hash": "blocked_hash_123",
                    "content": "This should be blocked",
                    "source_hash": "unknown_sender",
                    "timestamp": 1234567890
                }
                """.trimIndent()

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandlerWithPersistence.handleMessageReceivedEvent(messageJson)
                    testScope.advanceUntilIdle()
                }

            // Assert: Function completed successfully (message blocked but no exception)
            assertTrue("handleMessageReceivedEvent should complete when message is blocked", result.isSuccess)
        }

    @Test
    fun `handleMessageReceivedEvent calls persistMessage with correct parameters`() =
        runTest {
            // Setup: Create EventHandler with persistence manager
            val persistenceManager = mockk<ServicePersistenceManager>()
            coEvery { persistenceManager.persistMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

            val eventHandlerWithPersistence =
                EventHandler(
                    state = state,
                    wrapperManager = wrapperManager,
                    broadcaster = broadcaster,
                    scope = testScope,
                    attachmentStorage = null,
                    persistenceManager = persistenceManager,
                )

            // Message JSON with full_message flag
            val messageJson =
                """
                {
                    "full_message": true,
                    "message_hash": "hash_abc",
                    "content": "Test content",
                    "source_hash": "sender_xyz",
                    "timestamp": 9876543210
                }
                """.trimIndent()

            // Act - should complete successfully
            val result =
                runCatching {
                    eventHandlerWithPersistence.handleMessageReceivedEvent(messageJson)
                    testScope.advanceUntilIdle()
                }

            // Assert: Function completed successfully
            assertTrue("handleMessageReceivedEvent should complete successfully", result.isSuccess)
            // Verify persistMessage was called with correct parameters
            coVerify {
                persistenceManager.persistMessage(
                    messageHash = "hash_abc",
                    content = "Test content",
                    sourceHash = "sender_xyz",
                    timestamp = 9876543210L,
                    fieldsJson = any(),
                    publicKey = any(),
                    replyToMessageId = any(),
                    deliveryMethod = any(),
                    hasFileAttachments = any(),
                    receivedHopCount = any(),
                    receivedInterface = any(),
                )
            }
        }

    @Test
    fun `handleMessageReceivedEvent broadcasts without persistence manager for backwards compatibility`() =
        runTest {
            // When there's no persistence manager (e.g., testing), messages should still broadcast
            val messageJson =
                """
                {
                    "full_message": true,
                    "message_hash": "no_persistence_hash",
                    "content": "No persistence test",
                    "source_hash": "some_sender",
                    "timestamp": 1111111111
                }
                """.trimIndent()

            // Act: Use the default eventHandler (no persistence manager)
            val result =
                runCatching {
                    eventHandler.handleMessageReceivedEvent(messageJson)
                    testScope.advanceUntilIdle()
                }

            // Assert: Function completed successfully without persistence manager
            assertTrue("handleMessageReceivedEvent should work without persistence manager", result.isSuccess)
        }

    // ========== resolveStagingFiles Tests (Staging File Pipeline) ==========
    // These tests exercise the staging resolution path through the public
    // handleMessageReceivedEvent API with real temp files and mocked storage.

    /** Wait for IO-dispatched child coroutines of testScope to complete. */
    private suspend fun awaitScopeChildren() {
        testScope.advanceUntilIdle()
        testScope.coroutineContext[kotlinx.coroutines.Job]!!
            .children
            .toList()
            .forEach { it.join() }
    }

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun newTempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").also {
            it.mkdirs()
            tempDirs.add(it)
        }

    private fun buildFullMessageJson(
        messageHash: String,
        fields: JSONObject? = null,
    ): String =
        JSONObject()
            .apply {
                put("full_message", true)
                put("message_hash", messageHash)
                put("content", "test")
                put("source_hash", "sender_abc123")
                put("timestamp", 1234567890L)
                if (fields != null) {
                    put("fields", fields.toString())
                }
            }.toString()

    @Test
    fun `resolveStagingFiles moves field 5 staging file and replaces with binary ref`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val stagingDir = File(newTempDir("stg"), "cache/attachment_staging").also { it.mkdirs() }
            val stagingFile = File(stagingDir, "upload.bin").apply { writeText("fake-binary-data") }

            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val fieldsSlot = slot<String>()
            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    messageHash = any(),
                    content = any(),
                    sourceHash = any(),
                    timestamp = any(),
                    fieldsJson = capture(fieldsSlot),
                    publicKey = any(),
                    replyToMessageId = any(),
                    deliveryMethod = any(),
                    hasFileAttachments = any(),
                    receivedHopCount = any(),
                    receivedInterface = any(),
                    receivedRssi = any(),
                    receivedSnr = any(),
                )
            } returns true

            val handler =
                EventHandler(
                    state = state,
                    wrapperManager = wrapperManager,
                    broadcaster = broadcaster,
                    scope = testScope,
                    attachmentStorage = storage,
                    persistenceManager = persistence,
                )

            val fields =
                JSONObject().put(
                    "5",
                    JSONArray().put(
                        JSONObject()
                            .put("file_path", stagingFile.absolutePath)
                            .put("filename", "doc.pdf"),
                    ),
                )

            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_f5", fields))
            awaitScopeChildren()

            // Staging file should be moved away
            assertFalse("Staging file should be moved", stagingFile.exists())

            // Destination file should exist with original content
            val destFile = File(File(attachmentsDir, "hash_f5"), "5_0.bin")
            assertTrue("Destination file should exist", destFile.exists())
            assertTrue(
                "Destination file should have original content",
                destFile.readText() == "fake-binary-data",
            )

            // Persisted JSON should have _binary_ref, not file_path
            assertTrue("fieldsSlot should be captured", fieldsSlot.isCaptured)
            val persisted = JSONObject(fieldsSlot.captured)
            val att = persisted.getJSONArray("5").getJSONObject(0)
            assertTrue("Should have _binary_ref", att.has("_binary_ref"))
            assertFalse("Should not have file_path", att.has("file_path"))
        }

    @Test
    fun `resolveStagingFiles handles missing field 5 staging file gracefully`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns true

            val handler =
                EventHandler(state, wrapperManager, broadcaster, testScope, storage, persistence)

            // Point to a nonexistent file
            val fields =
                JSONObject().put(
                    "5",
                    JSONArray().put(
                        JSONObject()
                            .put("file_path", "/tmp/nonexistent_${System.nanoTime()}.bin")
                            .put("filename", "missing.pdf"),
                    ),
                )

            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_missing5", fields))
            awaitScopeChildren()

            // No destination directory created (early return before mkdirs)
            assertFalse(
                "No dest dir should be created for missing staging file",
                File(attachmentsDir, "hash_missing5").exists(),
            )
        }

    @Test
    fun `resolveStagingFiles skips field 5 entries without file_path`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns true

            val handler =
                EventHandler(state, wrapperManager, broadcaster, testScope, storage, persistence)

            // Field 5 entry with no file_path key
            val fields =
                JSONObject().put(
                    "5",
                    JSONArray().put(
                        JSONObject().put("name", "photo.jpg"),
                    ),
                )

            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_nopath", fields))
            awaitScopeChildren()

            // No destination directory created (no file_path to resolve)
            assertFalse(
                "No dest dir should be created when file_path absent",
                File(attachmentsDir, "hash_nopath").exists(),
            )
        }

    @Test
    fun `resolveStagingFiles moves field 6 media staging file and replaces with binary ref`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val stagingDir = File(newTempDir("stg"), "cache/attachment_staging").also { it.mkdirs() }
            val stagingFile = File(stagingDir, "image.bin").apply { writeText("fake-image-data") }

            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val fieldsSlot = slot<String>()
            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    messageHash = any(),
                    content = any(),
                    sourceHash = any(),
                    timestamp = any(),
                    fieldsJson = capture(fieldsSlot),
                    publicKey = any(),
                    replyToMessageId = any(),
                    deliveryMethod = any(),
                    hasFileAttachments = any(),
                    receivedHopCount = any(),
                    receivedInterface = any(),
                    receivedRssi = any(),
                    receivedSnr = any(),
                )
            } returns true

            val handler =
                EventHandler(state, wrapperManager, broadcaster, testScope, storage, persistence)

            // Field 6: [mime_type, hex_data_or_null, staging_path]
            val fields =
                JSONObject().put(
                    "6",
                    JSONArray()
                        .put("image/jpeg")
                        .put(JSONObject.NULL)
                        .put(stagingFile.absolutePath),
                )

            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_f6", fields))
            awaitScopeChildren()

            // Staging file should be moved
            assertFalse("Staging file should be moved", stagingFile.exists())

            // Destination file should exist
            val destFile = File(File(attachmentsDir, "hash_f6"), "6.bin")
            assertTrue("Destination file should exist", destFile.exists())
            assertTrue(
                "Destination file should have original content",
                destFile.readText() == "fake-image-data",
            )

            // Persisted JSON should have _binary_ref object with mime_type
            assertTrue("fieldsSlot should be captured", fieldsSlot.isCaptured)
            val persisted = JSONObject(fieldsSlot.captured)
            val field6 = persisted.getJSONObject("6")
            assertTrue("Should have _binary_ref", field6.has("_binary_ref"))
            assertTrue(
                "Should have mime_type",
                field6.getString("mime_type") == "image/jpeg",
            )
        }

    @Test
    fun `resolveStagingFiles handles missing media staging file gracefully`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns true

            val handler =
                EventHandler(state, wrapperManager, broadcaster, testScope, storage, persistence)

            // Field 7 with nonexistent staging path
            val fields =
                JSONObject().put(
                    "7",
                    JSONArray()
                        .put("audio/opus")
                        .put(JSONObject.NULL)
                        .put("/tmp/nonexistent_${System.nanoTime()}.bin"),
                )

            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_missing7", fields))
            awaitScopeChildren()

            // No destination directory created (staging file doesn't exist)
            assertFalse(
                "No dest dir should be created for missing media staging file",
                File(attachmentsDir, "hash_missing7").exists(),
            )
        }

    @Test
    fun `resolveStagingFiles ignores short media arrays`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns true

            val handler =
                EventHandler(state, wrapperManager, broadcaster, testScope, storage, persistence)

            // Field 7 with only 1 element (length < 3)
            val fields =
                JSONObject().put(
                    "7",
                    JSONArray().put("audio/opus"),
                )

            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_short7", fields))
            awaitScopeChildren()

            // No destination directory created (array too short to have staging path)
            assertFalse(
                "No dest dir should be created for short media array",
                File(attachmentsDir, "hash_short7").exists(),
            )
        }

    @Test
    fun `resolveStagingFiles returns null fields unchanged`() =
        runTest {
            val attachmentsDir = newTempDir("att")
            val storage = mockk<AttachmentStorageManager>()
            every { storage.attachmentsDir } returns attachmentsDir

            val persistence = mockk<ServicePersistenceManager>()
            coEvery {
                persistence.persistMessage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns true

            val handler =
                EventHandler(state, wrapperManager, broadcaster, testScope, storage, persistence)

            // Message with no fields key at all
            handler.handleMessageReceivedEvent(buildFullMessageJson("hash_nofields"))
            awaitScopeChildren()

            // No destination directory created (null fields → no staging resolution)
            assertFalse(
                "No dest dir should be created when fields is null",
                File(attachmentsDir, "hash_nofields").exists(),
            )
        }

    @Test
    fun `resolveStagingFiles skipped when no attachmentStorage`() =
        runTest {
            val stagingDir = newTempDir("stg")
            val stagingFile =
                File(stagingDir, "should_remain.bin").apply { writeText("untouched") }

            // Default eventHandler has attachmentStorage=null
            val fields =
                JSONObject().put(
                    "5",
                    JSONArray().put(
                        JSONObject()
                            .put("file_path", stagingFile.absolutePath)
                            .put("filename", "doc.pdf"),
                    ),
                )

            eventHandler.handleMessageReceivedEvent(buildFullMessageJson("hash_nostorage", fields))
            awaitScopeChildren()

            // Staging file should NOT be moved (no storage configured)
            assertTrue("Staging file should remain when no storage", stagingFile.exists())
            assertTrue(
                "Staging file content should be untouched",
                stagingFile.readText() == "untouched",
            )
        }
}
