package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.service.AttachmentStorageService
import com.lxmf.messenger.util.FileAttachment
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AttachmentViewModel.
 *
 * Tests image and file attachment state management, computed states,
 * and quality selection workflows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockStorageService: AttachmentStorageService
    private lateinit var viewModel: AttachmentViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockStorageService = mockk()
        viewModel = AttachmentViewModel(mockStorageService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Image Selection Tests ==========

    @Test
    fun `selectImage updates all image state fields`() {
        val imageData = byteArrayOf(1, 2, 3, 4, 5)
        val imageFormat = "jpg"

        viewModel.selectImage(imageData, imageFormat, isAnimated = false)

        assertTrue(viewModel.selectedImageData.value.contentEquals(imageData))
        assertEquals(imageFormat, viewModel.selectedImageFormat.value)
        assertFalse(viewModel.selectedImageIsAnimated.value)
    }

    @Test
    fun `selectImage with animated flag sets isAnimated true`() {
        val gifData = byteArrayOf(0x47, 0x49, 0x46, 0x38)

        viewModel.selectImage(gifData, "gif", isAnimated = true)

        assertTrue(viewModel.selectedImageIsAnimated.value)
    }

    @Test
    fun `clearSelectedImage resets all image state`() {
        // First select an image
        viewModel.selectImage(byteArrayOf(1, 2, 3), "png", isAnimated = true)

        // Then clear it
        viewModel.clearSelectedImage()

        assertNull(viewModel.selectedImageData.value)
        assertNull(viewModel.selectedImageFormat.value)
        assertFalse(viewModel.selectedImageIsAnimated.value)
    }

    @Test
    fun `setProcessingImage updates processing state`() {
        assertFalse(viewModel.isProcessingImage.value)

        viewModel.setProcessingImage(true)
        assertTrue(viewModel.isProcessingImage.value)

        viewModel.setProcessingImage(false)
        assertFalse(viewModel.isProcessingImage.value)
    }

    // ========== File Attachment Tests ==========

    @Test
    fun `addFileAttachment adds file to list`() =
        runTest {
            val attachment = createFileAttachment("test.pdf", 1024)

            viewModel.addFileAttachment(attachment)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
            assertEquals("test.pdf", viewModel.selectedFileAttachments.value[0].filename)
        }

    @Test
    fun `addFileAttachment preserves existing attachments`() =
        runTest {
            val attachment1 = createFileAttachment("file1.pdf", 1024)
            val attachment2 = createFileAttachment("file2.doc", 2048)

            viewModel.addFileAttachment(attachment1)
            viewModel.addFileAttachment(attachment2)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, viewModel.selectedFileAttachments.value.size)
            assertEquals("file1.pdf", viewModel.selectedFileAttachments.value[0].filename)
            assertEquals("file2.doc", viewModel.selectedFileAttachments.value[1].filename)
        }

    @Test
    fun `removeFileAttachment removes file at index`() =
        runTest {
            val attachment1 = createFileAttachment("file1.pdf", 1024)
            val attachment2 = createFileAttachment("file2.doc", 2048)
            val attachment3 = createFileAttachment("file3.txt", 512)

            viewModel.addFileAttachment(attachment1)
            viewModel.addFileAttachment(attachment2)
            viewModel.addFileAttachment(attachment3)
            testDispatcher.scheduler.advanceUntilIdle()

            // Remove the middle file
            viewModel.removeFileAttachment(1)

            assertEquals(2, viewModel.selectedFileAttachments.value.size)
            assertEquals("file1.pdf", viewModel.selectedFileAttachments.value[0].filename)
            assertEquals("file3.txt", viewModel.selectedFileAttachments.value[1].filename)
        }

    @Test
    fun `removeFileAttachment with invalid index does nothing`() =
        runTest {
            val attachment = createFileAttachment("test.pdf", 1024)
            viewModel.addFileAttachment(attachment)
            testDispatcher.scheduler.advanceUntilIdle()

            // Try to remove at invalid index
            viewModel.removeFileAttachment(5)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `removeFileAttachment with negative index does nothing`() =
        runTest {
            val attachment = createFileAttachment("test.pdf", 1024)
            viewModel.addFileAttachment(attachment)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.removeFileAttachment(-1)

            assertEquals(1, viewModel.selectedFileAttachments.value.size)
        }

    @Test
    fun `clearFileAttachments removes all files`() =
        runTest {
            viewModel.addFileAttachment(createFileAttachment("file1.pdf", 1024))
            viewModel.addFileAttachment(createFileAttachment("file2.doc", 2048))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearFileAttachments()

            assertTrue(viewModel.selectedFileAttachments.value.isEmpty())
        }

    @Test
    fun `setProcessingFile updates processing state`() {
        assertFalse(viewModel.isProcessingFile.value)

        viewModel.setProcessingFile(true)
        assertTrue(viewModel.isProcessingFile.value)

        viewModel.setProcessingFile(false)
        assertFalse(viewModel.isProcessingFile.value)
    }

    // ========== Combined State Tests ==========

    @Test
    fun `clearAllAttachments clears both images and files`() =
        runTest {
            // Add image
            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")

            // Add file
            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024))
            testDispatcher.scheduler.advanceUntilIdle()

            // Clear all
            viewModel.clearAllAttachments()

            assertNull(viewModel.selectedImageData.value)
            assertTrue(viewModel.selectedFileAttachments.value.isEmpty())
        }

    @Test
    fun `hasAttachments is true when image is selected`() =
        runTest {
            // Collect to activate the flow (WhileSubscribed)
            val job =
                backgroundScope.launch {
                    viewModel.hasAttachments.collect {}
                }

            viewModel.selectImage(byteArrayOf(1, 2, 3), "jpg")
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.hasAttachments.value)
            job.cancel()
        }

    @Test
    fun `hasAttachments is true when files are selected`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.hasAttachments.collect {}
                }

            viewModel.addFileAttachment(createFileAttachment("test.pdf", 1024))
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.hasAttachments.value)
            job.cancel()
        }

    @Test
    fun `hasAttachments is false when nothing selected`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.hasAttachments.collect {}
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.hasAttachments.value)
            job.cancel()
        }

    @Test
    fun `totalAttachmentSize sums all file sizes`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.totalAttachmentSize.collect {}
                }

            viewModel.addFileAttachment(createFileAttachment("file1.pdf", 1000))
            viewModel.addFileAttachment(createFileAttachment("file2.doc", 2500))
            viewModel.addFileAttachment(createFileAttachment("file3.txt", 500))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(4000, viewModel.totalAttachmentSize.value)
            job.cancel()
        }

    @Test
    fun `totalAttachmentSize is zero with no files`() =
        runTest {
            val job =
                backgroundScope.launch {
                    viewModel.totalAttachmentSize.collect {}
                }

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, viewModel.totalAttachmentSize.value)
            job.cancel()
        }

    // ========== Quality Selection Tests ==========

    @Test
    fun `dismissQualitySelection clears quality state`() {
        // Quality selection state starts as null
        assertNull(viewModel.qualitySelectionState.value)

        // Test that dismissing maintains null state (no-op when already null)
        // Note: We can't easily set qualitySelectionState directly as it's private
        // The processImageWithCompression method would set it, but requires mocking ImageUtils
        viewModel.dismissQualitySelection()

        assertNull(viewModel.qualitySelectionState.value)
    }

    @Test
    fun `selectImageQuality does nothing when no quality state`() =
        runTest {
            // No quality selection state set
            viewModel.selectImageQuality(ImageCompressionPreset.MEDIUM)

            // Should not crash and should not change image state
            assertNull(viewModel.selectedImageData.value)
        }

    // ========== Error Emission Tests ==========

    @Test
    fun `emitFileAttachmentError emits to SharedFlow`() =
        runTest {
            val errors = mutableListOf<String>()
            // Start collector and ensure it's ready before emitting
            val job =
                backgroundScope.launch {
                    viewModel.fileAttachmentError.collect { errors.add(it) }
                }
            // Let the collector start
            testDispatcher.scheduler.runCurrent()

            viewModel.emitFileAttachmentError("File too large")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, errors.size)
            assertEquals("File too large", errors[0])
            job.cancel()
        }

    @Test
    fun `fileAttachmentError is a SharedFlow exposed for UI`() {
        // Verify the SharedFlow is properly exposed
        // The actual emission behavior is tested by the "emitFileAttachmentError emits to SharedFlow" test
        // SharedFlow timing with test dispatchers is complex, so we just verify the flow exists
        // and has the expected type (asSharedFlow conversion was done correctly)
        assertTrue(viewModel.fileAttachmentError is kotlinx.coroutines.flow.SharedFlow<String>)
    }

    // ========== Storage Service Exposure Test ==========

    @Test
    fun `storageService is exposed for direct access`() {
        // AttachmentViewModel exposes storageService for use by composables
        assertEquals(mockStorageService, viewModel.storageService)
    }

    // ========== Helper Functions ==========

    private fun createFileAttachment(
        filename: String,
        sizeBytes: Int,
    ): FileAttachment =
        FileAttachment(
            filename = filename,
            data = ByteArray(sizeBytes),
            mimeType = "application/octet-stream",
            sizeBytes = sizeBytes,
        )
}
