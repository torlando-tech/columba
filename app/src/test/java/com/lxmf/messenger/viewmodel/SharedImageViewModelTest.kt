package com.lxmf.messenger.viewmodel

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SharedImageViewModel.
 *
 * Tests the lifecycle of shared image URIs: setting, assigning to a destination,
 * consuming for a destination, and clearing.
 * Uses Robolectric because android.net.Uri requires the Android framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedImageViewModelTest {
    private lateinit var viewModel: SharedImageViewModel

    @Before
    fun setup() {
        viewModel = SharedImageViewModel()
    }

    // ========== Initial State ==========

    @Test
    fun initialState_isNull() {
        assertNull(viewModel.sharedImages.value)
    }

    // ========== setImages ==========

    @Test
    fun setImages_singleUri_storesUri() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))

        val pending = viewModel.sharedImages.value
        assertNotNull(pending)
        assertEquals(1, pending!!.uris.size)
        assertEquals(uri, pending.uris[0])
        assertNull(pending.targetDestinationHash)
    }

    @Test
    fun setImages_multipleUris_storesAllUris() {
        val uris = listOf(
            Uri.parse("content://media/external/images/1"),
            Uri.parse("content://media/external/images/2"),
            Uri.parse("content://media/external/images/3"),
        )
        viewModel.setImages(uris)

        val pending = viewModel.sharedImages.value
        assertNotNull(pending)
        assertEquals(3, pending!!.uris.size)
        assertEquals(uris, pending.uris)
        assertNull(pending.targetDestinationHash)
    }

    @Test
    fun setImages_replacesExistingImages() {
        val uri1 = Uri.parse("content://media/external/images/1")
        val uri2 = Uri.parse("content://media/external/images/2")

        viewModel.setImages(listOf(uri1))
        viewModel.setImages(listOf(uri2))

        val pending = viewModel.sharedImages.value
        assertNotNull(pending)
        assertEquals(1, pending!!.uris.size)
        assertEquals(uri2, pending.uris[0])
    }

    // ========== assignToDestination ==========

    @Test
    fun assignToDestination_setsTargetHash() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))

        viewModel.assignToDestination("dest_hash_abc")

        val pending = viewModel.sharedImages.value
        assertNotNull(pending)
        assertEquals("dest_hash_abc", pending!!.targetDestinationHash)
        assertEquals(uri, pending.uris[0])
    }

    @Test
    fun assignToDestination_withNoImages_doesNothing() {
        viewModel.assignToDestination("dest_hash_abc")

        assertNull(viewModel.sharedImages.value)
    }

    @Test
    fun assignToDestination_canReassign() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))

        viewModel.assignToDestination("dest_hash_1")
        viewModel.assignToDestination("dest_hash_2")

        val pending = viewModel.sharedImages.value
        assertNotNull(pending)
        assertEquals("dest_hash_2", pending!!.targetDestinationHash)
    }

    // ========== consumeForDestination ==========

    @Test
    fun consumeForDestination_matchingHash_returnsUrisAndClears() {
        val uris = listOf(
            Uri.parse("content://media/external/images/1"),
            Uri.parse("content://media/external/images/2"),
        )
        viewModel.setImages(uris)
        viewModel.assignToDestination("dest_hash_abc")

        val consumed = viewModel.consumeForDestination("dest_hash_abc")

        assertNotNull(consumed)
        assertEquals(uris, consumed)
        assertNull(viewModel.sharedImages.value)
    }

    @Test
    fun consumeForDestination_nonMatchingHash_returnsNull() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))
        viewModel.assignToDestination("dest_hash_abc")

        val consumed = viewModel.consumeForDestination("dest_hash_other")

        assertNull(consumed)
        // State should remain unchanged
        assertNotNull(viewModel.sharedImages.value)
    }

    @Test
    fun consumeForDestination_noImages_returnsNull() {
        val consumed = viewModel.consumeForDestination("dest_hash_abc")
        assertNull(consumed)
    }

    @Test
    fun consumeForDestination_unassigned_returnsNull() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))

        val consumed = viewModel.consumeForDestination("dest_hash_abc")

        assertNull(consumed)
        // State should remain unchanged
        assertNotNull(viewModel.sharedImages.value)
    }

    @Test
    fun consumeForDestination_calledTwice_secondCallReturnsNull() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))
        viewModel.assignToDestination("dest_hash_abc")

        val first = viewModel.consumeForDestination("dest_hash_abc")
        val second = viewModel.consumeForDestination("dest_hash_abc")

        assertNotNull(first)
        assertNull(second)
    }

    // ========== clearIfUnassigned ==========

    @Test
    fun clearIfUnassigned_unassigned_clearsState() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))

        viewModel.clearIfUnassigned()

        assertNull(viewModel.sharedImages.value)
    }

    @Test
    fun clearIfUnassigned_assigned_doesNotClear() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))
        viewModel.assignToDestination("dest_hash_abc")

        viewModel.clearIfUnassigned()

        assertNotNull(viewModel.sharedImages.value)
    }

    @Test
    fun clearIfUnassigned_noImages_doesNothing() {
        viewModel.clearIfUnassigned()
        assertNull(viewModel.sharedImages.value)
    }

    // ========== clear ==========

    @Test
    fun clear_removesAllState() {
        val uri = Uri.parse("content://media/external/images/1")
        viewModel.setImages(listOf(uri))
        viewModel.assignToDestination("dest_hash_abc")

        viewModel.clear()

        assertNull(viewModel.sharedImages.value)
    }

    @Test
    fun clear_whenEmpty_doesNothing() {
        viewModel.clear()
        assertNull(viewModel.sharedImages.value)
    }
}
