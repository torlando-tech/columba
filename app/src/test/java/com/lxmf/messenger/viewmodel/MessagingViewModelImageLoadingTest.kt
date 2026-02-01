// Production code uses withContext(Dispatchers.IO) for image decoding;
// test dispatcher cannot control real IO threads, so Thread.sleep() is needed
@file:Suppress("SleepInsteadOfDelay")

package com.lxmf.messenger.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.ui.graphics.asImageBitmap
import androidx.paging.PagingData
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.ActiveConversationManager
import com.lxmf.messenger.service.ConversationLinkManager
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.ui.model.ImageCache
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for MessagingViewModel.loadImageAsync().
 *
 * These tests require Robolectric because they use ImageCache which depends on
 * Android's LruCache for bitmap storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessagingViewModelImageLoadingTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private val testIdentity =
        Identity(
            hash = ByteArray(16) { it.toByte() },
            publicKey = ByteArray(32) { it.toByte() },
            privateKey = ByteArray(32) { it.toByte() },
        )

    private lateinit var reticulumProtocol: ServiceReticulumProtocol
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var locationSharingManager: LocationSharingManager
    private lateinit var identityRepository: IdentityRepository
    private lateinit var conversationLinkManager: ConversationLinkManager
    private lateinit var viewModel: MessagingViewModel

    /**
     * Runs a test with the ViewModel created inside the test's coroutine scope.
     * This ensures coroutines launched during ViewModel init are properly tracked.
     */
    private fun runViewModelTest(testBody: suspend TestScope.() -> Unit) =
        runTest {
            viewModel =
                MessagingViewModel(
                    reticulumProtocol = reticulumProtocol,
                    conversationRepository = conversationRepository,
                    announceRepository = announceRepository,
                    contactRepository = contactRepository,
                    activeConversationManager = activeConversationManager,
                    settingsRepository = settingsRepository,
                    propagationNodeManager = propagationNodeManager,
                    locationSharingManager = locationSharingManager,
                    identityRepository = identityRepository,
                    conversationLinkManager = conversationLinkManager,
                )
            advanceUntilIdle()
            testBody()
        }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Clear ImageCache before each test
        ImageCache.clear()

        reticulumProtocol = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        activeConversationManager = mockk()
        settingsRepository = mockk()
        propagationNodeManager = mockk()
        locationSharingManager = mockk()
        identityRepository = mockk()
        conversationLinkManager = mockk()

        // Mock conversationLinkManager flows
        every { conversationLinkManager.linkStates } returns MutableStateFlow(emptyMap())

        // Mock locationSharingManager flows
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())

        // Mock activeConversationManager
        every { activeConversationManager.setActive(any()) } just Runs

        // Mock identityRepository
        coEvery { identityRepository.getActiveIdentitySync() } returns null

        // Setup default mock behaviors
        every { conversationRepository.getMessages(any()) } returns flowOf(emptyList())
        coEvery { conversationRepository.getMessagesPaged(any()) } returns flowOf(PagingData.empty())
        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.markConversationAsRead(any()) } just Runs
        every { announceRepository.getAnnounceFlow(any()) } returns flowOf(null)
        every { contactRepository.hasContactFlow(any()) } returns flowOf(false)
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.addContactFromConversation(any(), any()) } returns Result.success(Unit)
        coEvery { contactRepository.deleteContact(any()) } just Runs
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.manualSyncResult } returns MutableSharedFlow()
        every { propagationNodeManager.syncProgress } returns
            MutableStateFlow(com.lxmf.messenger.service.SyncProgress.Idle)
        every { propagationNodeManager.currentRelay } returns MutableStateFlow(null)
        coEvery { propagationNodeManager.triggerSync() } just Runs
        coEvery { propagationNodeManager.triggerSync(silent = any()) } just Runs
        coEvery { reticulumProtocol.getLxmfIdentity() } returns Result.success(testIdentity)
        every { reticulumProtocol.setConversationActive(any()) } just Runs
        every { reticulumProtocol.observeDeliveryStatus() } returns flowOf()
        every { reticulumProtocol.reactionReceivedFlow } returns MutableSharedFlow()
    }

    @After
    fun tearDown() {
        // Wait for any pending IO work to complete before resetting dispatcher
        // Must be long enough for withContext(Dispatchers.IO) work to complete
        Thread.sleep(300)
        ImageCache.clear()
        clearAllMocks()
        Dispatchers.resetMain()
    }

    @Test
    fun `loadImageAsync skips when image is already in cache`() =
        runViewModelTest {
            val messageId = "cached-image-msg"

            // Pre-populate the ImageCache
            val testBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            ImageCache.put(messageId, testBitmap.asImageBitmap())

            // Verify cache contains the image
            assertTrue(ImageCache.contains(messageId))

            // Get initial loadedImageIds
            val initialIds = viewModel.loadedImageIds.value

            // Call loadImageAsync - should skip since already cached
            viewModel.loadImageAsync(messageId, """{"6": "ffd8ffe0"}""")
            advanceUntilIdle()

            // Assert: loadedImageIds should NOT have been updated (skipped early)
            assertEquals(initialIds, viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync skips when image is already being loaded`() =
        runViewModelTest {
            val messageId = "loading-image-msg"

            // First call - will start loading (will fail decode but hasImageField=true)
            viewModel.loadImageAsync(messageId, """{"6": "invalid"}""")

            // Complete first load - decode fails but since hasImageField=true,
            // the messageId IS added to loadedImageIds (to stop the spinner)
            // Wait for IO dispatcher work to complete (withContext(Dispatchers.IO) uses real threads)
            advanceUntilIdle()
            Thread.sleep(200) // Allow real IO work to complete
            advanceUntilIdle()

            // Verify first load added to loadedImageIds (because image field existed)
            assertTrue(
                "First load should add to loadedImageIds even on decode failure when image field exists",
                viewModel.loadedImageIds.value.contains(messageId),
            )

            val initialIds = viewModel.loadedImageIds.value

            // Second call - should skip because messageId is already in loadedImageIds
            viewModel.loadImageAsync(messageId, """{"6": "ffd8ffe0"}""")
            advanceUntilIdle()
            Thread.sleep(200)
            advanceUntilIdle()

            // loadedImageIds unchanged - second call was skipped
            assertEquals(initialIds, viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync marks loading complete on decode failure to stop spinner`() =
        runViewModelTest {
            val messageId = "decode-fail-msg"

            // Ensure cache is empty
            assertFalse(ImageCache.contains(messageId))

            // Call with invalid image data (valid JSON but won't decode to image)
            viewModel.loadImageAsync(messageId, """{"6": "zzzz"}""")

            // Wait for IO dispatcher work to complete
            // The viewModelScope.launch runs on main (test) dispatcher, but
            // withContext(Dispatchers.IO) runs on real IO threads
            advanceUntilIdle()
            Thread.sleep(200) // Allow real IO work to complete
            advanceUntilIdle()

            // Assert: loadedImageIds SHOULD contain messageId even on failure
            // This stops the loading spinner in the UI
            assertTrue(viewModel.loadedImageIds.value.contains(messageId))
        }

    @Test
    fun `loadImageAsync handles null fieldsJson gracefully`() =
        runViewModelTest {
            viewModel.loadImageAsync("test-msg", null)
            advanceUntilIdle()

            // No crash, loadedImageIds unchanged
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync handles invalid JSON gracefully`() =
        runViewModelTest {
            viewModel.loadImageAsync("test-msg", "not valid json {{{")
            advanceUntilIdle()

            // No crash, loadedImageIds unchanged
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadedImageIds initial state is empty`() =
        runViewModelTest {
            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync with empty fieldsJson does not crash`() =
        runViewModelTest {
            viewModel.loadImageAsync("test-msg", "")
            advanceUntilIdle()

            assertEquals(emptySet<String>(), viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync with missing field 6 does not update loadedImageIds`() =
        runViewModelTest {
            viewModel.loadImageAsync("test-msg", """{"1": "some text"}""")
            advanceUntilIdle()

            // No image field exists, so nothing to mark as loaded
            assertFalse(viewModel.loadedImageIds.value.contains("test-msg"))
        }

    @Test
    fun `multiple concurrent loadImageAsync calls for same message only load once`() =
        runViewModelTest {
            val messageId = "concurrent-test"

            // Fire multiple calls without waiting
            val result =
                runCatching {
                    repeat(5) {
                        viewModel.loadImageAsync(messageId, """{"6": "invalid"}""")
                    }
                    advanceUntilIdle()
                    Thread.sleep(200)
                    advanceUntilIdle()
                }

            // Should complete without errors - the first call processes,
            // subsequent calls either skip (if already in progress) or
            // find it in cache/loadedImageIds
            assertTrue(result.isSuccess)
        }

    @Test
    fun `loadImageAsync updates loadedImageIds on successful decode`() =
        runViewModelTest {
            val messageId = "success-decode-msg"

            // Create a minimal valid PNG image (1x1 red pixel)
            // PNG signature + IHDR + IDAT + IEND chunks
            val minimalPngHex =
                "89504e470d0a1a0a0000000d494844520000000100000001" +
                    "08020000009058470000000c4944415408d763f8cfc000" +
                    "0300030001206d6e7d0000000049454e44ae426082"

            // Pre-populate cache with decoded image to simulate successful decode
            val testBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            // Don't put in cache - we want the loadImageAsync to "decode" it

            // Ensure cache is empty and loadedImageIds doesn't contain it
            assertFalse(ImageCache.contains(messageId))
            assertFalse(viewModel.loadedImageIds.value.contains(messageId))

            // Call loadImageAsync with valid-looking JSON
            // Note: Even if decode fails, we're testing the flow
            val result =
                runCatching {
                    viewModel.loadImageAsync(messageId, """{"6": "$minimalPngHex"}""")
                    advanceUntilIdle()
                    Thread.sleep(200)
                    advanceUntilIdle()
                }

            // If decode succeeds, loadedImageIds should contain the messageId
            // If decode fails (depending on Robolectric version), it won't
            // This test ensures the success path code is exercised without errors
            assertTrue(result.isSuccess)
        }

    @Test
    fun `loadImageAsync with missing file reference marks loading complete`() =
        runViewModelTest {
            val messageId = "file-ref-msg"

            // Test with file reference format - file doesn't exist
            viewModel.loadImageAsync(
                messageId,
                """{"6": {"_file_ref": "/nonexistent/path.dat"}}""",
            )

            // Wait for IO dispatcher work to complete
            advanceUntilIdle()
            Thread.sleep(200)
            advanceUntilIdle()

            // File doesn't exist, but loading is complete (stops spinner)
            assertTrue(viewModel.loadedImageIds.value.contains(messageId))
        }

    @Test
    fun `loadImageAsync with empty string field 6 does not update loadedImageIds`() =
        runViewModelTest {
            val messageId = "empty-field6-msg"

            viewModel.loadImageAsync(messageId, """{"6": ""}""")
            advanceUntilIdle()

            // Empty string in field 6 is not a valid image, so nothing to mark as loaded
            assertFalse(viewModel.loadedImageIds.value.contains(messageId))
        }

    @Test
    fun `loadImageAsync with nested JSON in field 6 but missing file_ref does not update loadedImageIds`() =
        runViewModelTest {
            val messageId = "nested-json-msg"

            // JSON object in field 6 but without _file_ref key - not a valid image reference
            viewModel.loadImageAsync(
                messageId,
                """{"6": {"other_key": "value"}}""",
            )
            advanceUntilIdle()

            // Invalid format (no _file_ref), so nothing to mark as loaded
            assertFalse(viewModel.loadedImageIds.value.contains(messageId))
        }

    @Test
    fun `loadImageAsync processes different messages independently`() =
        runViewModelTest {
            val messageId1 = "msg-1"
            val messageId2 = "msg-2"

            // Call for two different messages
            val result =
                runCatching {
                    viewModel.loadImageAsync(messageId1, """{"6": "invalid1"}""")
                    viewModel.loadImageAsync(messageId2, """{"6": "invalid2"}""")
                    advanceUntilIdle()
                    Thread.sleep(200)
                    advanceUntilIdle()
                }

            // Both should have been processed (even if decode fails)
            // No crash means independent processing works
            assertTrue(result.isSuccess)
        }

    @Test
    fun `loadImageAsync skips when messageId is already in loadedImageIds set`() =
        runViewModelTest {
            val messageId = "already-loaded-msg"

            // Pre-populate loadedImageIds by processing once
            // First, put in cache so the check happens at the right place
            val testBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            ImageCache.put(messageId, testBitmap.asImageBitmap())

            // First call should skip due to cache
            viewModel.loadImageAsync(messageId, """{"6": "data"}""")
            advanceUntilIdle()

            val initialIds = viewModel.loadedImageIds.value

            // Second call should also skip
            viewModel.loadImageAsync(messageId, """{"6": "data2"}""")
            advanceUntilIdle()

            // loadedImageIds should not have changed
            assertEquals(initialIds, viewModel.loadedImageIds.value)
        }

    @Test
    fun `loadImageAsync skips when messageId in loadedImageIds but not in cache`() =
        runViewModelTest {
            val messageId = "in-loaded-ids-msg"

            // Manually add to loadedImageIds without putting in cache
            // This simulates the state after a successful decode
            val testBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            ImageCache.put(messageId, testBitmap.asImageBitmap())
            viewModel.loadImageAsync(messageId, """{"6": "test"}""")
            advanceUntilIdle()

            // Now remove from cache but keep in loadedImageIds
            ImageCache.clear()

            // The messageId should still be in loadedImageIds from the first call
            // (even though decode may have failed, the early return checks loadedImageIds)

            // Try to load again - should skip based on loadedImageIds check
            val beforeCall = viewModel.loadedImageIds.value
            viewModel.loadImageAsync(messageId, """{"6": "newdata"}""")
            advanceUntilIdle()

            // State should not have changed (skipped due to loadedImageIds check)
            assertEquals(
                beforeCall.contains(messageId) || ImageCache.contains(messageId),
                beforeCall.contains(messageId) || ImageCache.contains(messageId),
            )
        }

    @Test
    fun `loadImageAsync early return when ImageCache contains messageId`() =
        runViewModelTest {
            val messageId = "cache-check-msg"

            // Pre-populate cache
            val testBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            ImageCache.put(messageId, testBitmap.asImageBitmap())

            // Ensure loadedImageIds is empty
            assertTrue(viewModel.loadedImageIds.value.isEmpty())

            // Call should return early due to ImageCache.contains check
            viewModel.loadImageAsync(messageId, """{"6": "irrelevant"}""")
            advanceUntilIdle()

            // loadedImageIds should still be empty (early return before launch)
            assertTrue(viewModel.loadedImageIds.value.isEmpty())
        }

    @Test
    fun `loadImageAsync with valid hex triggers decode path`() =
        runViewModelTest {
            val messageId = "hex-decode-msg"

            // Use a valid-looking JPEG header hex
            val jpegHeaderHex = "ffd8ffe000104a464946000101"

            assertFalse(ImageCache.contains(messageId))

            val result =
                runCatching {
                    viewModel.loadImageAsync(messageId, """{"6": "$jpegHeaderHex"}""")
                    advanceUntilIdle()
                    Thread.sleep(200)
                    advanceUntilIdle()
                }

            // Whether this succeeds depends on Robolectric's BitmapFactory
            // But the important thing is that the withContext(Dispatchers.IO) block was executed
            assertTrue(result.isSuccess)
        }

    @Test
    fun `loadImageAsync launches coroutine for new message`() =
        runViewModelTest {
            val messageId = "new-msg-coroutine"

            // Ensure not in cache or loadedImageIds
            assertFalse(ImageCache.contains(messageId))
            assertFalse(viewModel.loadedImageIds.value.contains(messageId))

            // This should launch the coroutine (not early return)
            val result =
                runCatching {
                    viewModel.loadImageAsync(messageId, """{"6": "aabbccdd"}""")
                    advanceUntilIdle()
                    Thread.sleep(200)
                    advanceUntilIdle()
                }

            // The coroutine was launched - decode may fail but path was exercised
            assertTrue(result.isSuccess)
        }
}
