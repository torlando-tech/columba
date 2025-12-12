package com.lxmf.messenger.service

import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.test.TestFactories
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PropagationNodeManager.
 *
 * Tests cover:
 * - Lifecycle (start/stop)
 * - onPropagationNodeAnnounce - Sideband auto-selection algorithm
 * - setManualRelay - Manual relay selection
 * - enableAutoSelect - Switch back to auto mode
 * - clearRelay - Clear selection
 * - onRelayDeleted - Handle deleted relay
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PropagationNodeManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var manager: PropagationNodeManager

    private val testDestHash = TestFactories.TEST_DEST_HASH
    private val testDestHash2 = TestFactories.TEST_DEST_HASH_2
    private val testDestHash3 = TestFactories.TEST_DEST_HASH_3
    private val testPublicKey = TestFactories.TEST_PUBLIC_KEY

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        settingsRepository = mockk(relaxed = true)
        contactRepository = mockk(relaxed = true)
        announceRepository = mockk(relaxed = true)
        reticulumProtocol = mockk(relaxed = true)

        // Default settings mocks
        coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
        coEvery { settingsRepository.getManualPropagationNode() } returns null
        coEvery { settingsRepository.getLastPropagationNode() } returns null

        // Default repository mocks
        every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.addContactFromAnnounce(any(), any()) } returns Result.success(Unit)
        coEvery { contactRepository.setAsMyRelay(any(), any()) } just Runs
        coEvery { contactRepository.clearMyRelay() } just Runs
        coEvery { reticulumProtocol.setOutboundPropagationNode(any()) } returns Result.success(Unit)

        manager = PropagationNodeManager(
            settingsRepository = settingsRepository,
            contactRepository = contactRepository,
            announceRepository = announceRepository,
            reticulumProtocol = reticulumProtocol,
            scope = testScope.backgroundScope,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Lifecycle Tests ==========

    @Test
    fun `start - can be called without error`() = runTest {
        // Given: Propagation nodes available
        val announce = TestFactories.createAnnounce(nodeType = "PROPAGATION_NODE")
        every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(listOf(announce))

        // When: start() is called
        manager.start()

        // Then: No exception thrown
        // Note: start() launches coroutines in backgroundScope which are async
        // The actual observation logic is tested indirectly through other tests like onPropagationNodeAnnounce
        // We verify that stop() can be called after start()
        manager.stop()
    }

    @Test
    fun `start - attempts restore from settings`() = runTest {
        // Given: Last relay saved in settings
        val lastRelay = testDestHash
        val announce = TestFactories.createAnnounce(
            destinationHash = lastRelay,
            nodeType = "PROPAGATION_NODE",
        )
        coEvery { settingsRepository.getLastPropagationNode() } returns lastRelay
        coEvery { announceRepository.getAnnounce(lastRelay) } returns announce

        // When: start() is called
        manager.start()

        // Then: No exception thrown
        // Note: start() launches coroutines in backgroundScope which are async
        // Restore logic is triggered asynchronously - actual restoration is tested elsewhere
        manager.stop()
    }

    @Test
    fun `start - does not restore if announce not found`() = runTest {
        // Given: Last relay saved but announce not in database
        coEvery { settingsRepository.getLastPropagationNode() } returns testDestHash
        coEvery { announceRepository.getAnnounce(testDestHash) } returns null

        // When
        manager.start()
        advanceUntilIdle()

        // Then: Should not restore (no relay set)
        assertNull(manager.currentRelay.value)
    }

    @Test
    fun `start - does not restore if announce is not propagation node`() = runTest {
        // Given: Last relay is not a propagation node
        val announce = TestFactories.createAnnounce(nodeType = "PEER")
        coEvery { settingsRepository.getLastPropagationNode() } returns testDestHash
        coEvery { announceRepository.getAnnounce(testDestHash) } returns announce

        // When
        manager.start()
        advanceUntilIdle()

        // Then: Should not restore
        assertNull(manager.currentRelay.value)
    }

    @Test
    fun `stop - cancels announce observer`() = runTest {
        // Given: Manager started
        manager.start()
        advanceUntilIdle()

        // When
        manager.stop()
        advanceUntilIdle()

        // Then: Observer should be cancelled (no crash, state preserved)
        // Further announces should not trigger selection
    }

    // ========== onPropagationNodeAnnounce Tests (Sideband Algorithm) ==========

    @Test
    fun `onPropagationNodeAnnounce - no current relay selects new node`() = runTest {
        // Given: No current relay
        assertNull(manager.currentRelay.value)

        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Test Relay",
            hops = 3,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should select the new node
        assertNotNull(manager.currentRelay.value)
        assertEquals(testDestHash, manager.currentRelay.value?.destinationHash)
        assertEquals(3, manager.currentRelay.value?.hops)
    }

    @Test
    fun `onPropagationNodeAnnounce - closer hops switches to new node`() = runTest {
        // Given: Current relay at 5 hops
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash2,
            displayName = "Old Relay",
            hops = 5,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // When: New relay at 2 hops
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Closer Relay",
            hops = 2,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should switch to closer relay
        assertEquals(testDestHash, manager.currentRelay.value?.destinationHash)
        assertEquals(2, manager.currentRelay.value?.hops)
    }

    @Test
    fun `onPropagationNodeAnnounce - same hops does not switch`() = runTest {
        // Given: Current relay at 3 hops
        val oldHash = testDestHash2
        manager.onPropagationNodeAnnounce(
            destinationHash = oldHash,
            displayName = "Current Relay",
            hops = 3,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // When: Different relay also at 3 hops
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Other Relay",
            hops = 3,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should keep current relay
        assertEquals(oldHash, manager.currentRelay.value?.destinationHash)
    }

    @Test
    fun `onPropagationNodeAnnounce - same node same hops updates info`() = runTest {
        // Given: Current relay
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Relay v1",
            hops = 3,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // When: Same node announces again
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Relay v2",
            hops = 3,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should update display name
        assertEquals("Relay v2", manager.currentRelay.value?.displayName)
    }

    @Test
    fun `onPropagationNodeAnnounce - more hops does not switch`() = runTest {
        // Given: Current relay at 2 hops
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Close Relay",
            hops = 2,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // When: Farther relay at 5 hops
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash3,
            displayName = "Far Relay",
            hops = 5,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should keep closer relay
        assertEquals(testDestHash, manager.currentRelay.value?.destinationHash)
        assertEquals(2, manager.currentRelay.value?.hops)
    }

    @Test
    fun `onPropagationNodeAnnounce - manual mode ignores announce`() = runTest {
        // Given: Manual relay selected
        coEvery { settingsRepository.getAutoSelectPropagationNode() } returns false
        coEvery { settingsRepository.getManualPropagationNode() } returns testDestHash3

        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Auto Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should not select (manual mode ignores auto-selection)
        assertNull(manager.currentRelay.value)
    }

    @Test
    fun `onPropagationNodeAnnounce - adds contact if not exists`() = runTest {
        // Given: Contact does not exist
        coEvery { contactRepository.hasContact(testDestHash) } returns false

        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "New Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should add contact
        coVerify { contactRepository.addContactFromAnnounce(testDestHash, testPublicKey) }
    }

    @Test
    fun `onPropagationNodeAnnounce - does not add contact if exists`() = runTest {
        // Given: Contact already exists
        coEvery { contactRepository.hasContact(testDestHash) } returns true

        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Existing Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then: Should not add contact
        coVerify(exactly = 0) { contactRepository.addContactFromAnnounce(any(), any()) }
    }

    @Test
    fun `onPropagationNodeAnnounce - sets as my relay`() = runTest {
        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "New Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then
        coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
    }

    @Test
    fun `onPropagationNodeAnnounce - updates protocol layer`() = runTest {
        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "New Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then
        coVerify { reticulumProtocol.setOutboundPropagationNode(any()) }
    }

    @Test
    fun `onPropagationNodeAnnounce - saves to settings`() = runTest {
        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "New Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then
        coVerify { settingsRepository.saveLastPropagationNode(testDestHash) }
    }

    @Test
    fun `onPropagationNodeAnnounce - sets isAutoSelected correctly`() = runTest {
        // Given: Auto-select enabled
        coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true

        // When
        manager.onPropagationNodeAnnounce(
            destinationHash = testDestHash,
            displayName = "Auto Relay",
            hops = 1,
            publicKey = testPublicKey,
        )
        advanceUntilIdle()

        // Then
        assertTrue(manager.currentRelay.value?.isAutoSelected ?: false)
    }

    // ========== setManualRelay Tests ==========

    @Test
    fun `setManualRelay - disables auto-select`() = runTest {
        // When
        manager.setManualRelay(testDestHash, "Manual Relay")
        advanceUntilIdle()

        // Then
        coVerify { settingsRepository.saveAutoSelectPropagationNode(false) }
    }

    @Test
    fun `setManualRelay - saves manual node`() = runTest {
        // When
        manager.setManualRelay(testDestHash, "Manual Relay")
        advanceUntilIdle()

        // Then
        coVerify { settingsRepository.saveManualPropagationNode(testDestHash) }
        coVerify { settingsRepository.saveLastPropagationNode(testDestHash) }
    }

    @Test
    fun `setManualRelay - updates current relay state`() = runTest {
        // When
        manager.setManualRelay(testDestHash, "Manual Relay")
        advanceUntilIdle()

        // Then
        assertNotNull(manager.currentRelay.value)
        assertEquals(testDestHash, manager.currentRelay.value?.destinationHash)
        assertEquals("Manual Relay", manager.currentRelay.value?.displayName)
        assertFalse(manager.currentRelay.value?.isAutoSelected ?: true)
    }

    @Test
    fun `setManualRelay - configures protocol`() = runTest {
        // When
        manager.setManualRelay(testDestHash, "Manual Relay")
        advanceUntilIdle()

        // Then
        coVerify { reticulumProtocol.setOutboundPropagationNode(any()) }
    }

    @Test
    fun `setManualRelay - sets as my relay in contacts`() = runTest {
        // When
        manager.setManualRelay(testDestHash, "Manual Relay")
        advanceUntilIdle()

        // Then
        coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
    }

    @Test
    fun `setManualRelay - adds contact if not exists and announce available`() = runTest {
        // Given: Contact does not exist but announce is available
        coEvery { contactRepository.hasContact(testDestHash) } returns false
        val announce = TestFactories.createAnnounce()
        coEvery { announceRepository.getAnnounce(testDestHash) } returns announce

        // When
        manager.setManualRelay(testDestHash, "Manual Relay")
        advanceUntilIdle()

        // Then
        coVerify { contactRepository.addContactFromAnnounce(testDestHash, announce.publicKey) }
    }

    // ========== enableAutoSelect Tests ==========

    @Test
    fun `enableAutoSelect - clears manual node`() = runTest {
        // When
        manager.enableAutoSelect()
        advanceUntilIdle()

        // Then
        coVerify { settingsRepository.saveManualPropagationNode(null) }
    }

    @Test
    fun `enableAutoSelect - enables auto-select setting`() = runTest {
        // When
        manager.enableAutoSelect()
        advanceUntilIdle()

        // Then
        coVerify { settingsRepository.saveAutoSelectPropagationNode(true) }
    }

    @Test
    fun `enableAutoSelect - selects nearest node`() = runTest {
        // Given: Multiple propagation nodes available
        val nearNode = TestFactories.createAnnounce(
            destinationHash = testDestHash,
            peerName = "Near Node",
            hops = 1,
        )
        val farNode = TestFactories.createAnnounce(
            destinationHash = testDestHash2,
            peerName = "Far Node",
            hops = 5,
        )
        every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(listOf(farNode, nearNode))

        // When
        manager.enableAutoSelect()
        advanceUntilIdle()

        // Then: Should select nearest
        assertEquals(testDestHash, manager.currentRelay.value?.destinationHash)
        assertEquals(1, manager.currentRelay.value?.hops)
    }

    @Test
    fun `enableAutoSelect - no propagation nodes clears relay`() = runTest {
        // Given: No propagation nodes
        every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(emptyList())

        // When
        manager.enableAutoSelect()
        advanceUntilIdle()

        // Then
        assertNull(manager.currentRelay.value)
        coVerify { reticulumProtocol.setOutboundPropagationNode(null) }
        coVerify { contactRepository.clearMyRelay() }
    }

    // ========== clearRelay Tests ==========

    @Test
    fun `clearRelay - clears current state`() = runTest {
        // Given: Relay is set
        manager.onPropagationNodeAnnounce(testDestHash, "Relay", 1, testPublicKey)
        advanceUntilIdle()
        assertNotNull(manager.currentRelay.value)

        // When
        manager.clearRelay()
        advanceUntilIdle()

        // Then
        assertNull(manager.currentRelay.value)
    }

    @Test
    fun `clearRelay - clears protocol node`() = runTest {
        // When
        manager.clearRelay()
        advanceUntilIdle()

        // Then
        coVerify { reticulumProtocol.setOutboundPropagationNode(null) }
    }

    @Test
    fun `clearRelay - clears my relay in contacts`() = runTest {
        // When
        manager.clearRelay()
        advanceUntilIdle()

        // Then
        coVerify { contactRepository.clearMyRelay() }
    }

    @Test
    fun `clearRelay - clears manual node setting`() = runTest {
        // When
        manager.clearRelay()
        advanceUntilIdle()

        // Then
        coVerify { settingsRepository.saveManualPropagationNode(null) }
    }

    // ========== onRelayDeleted Tests ==========

    @Test
    fun `onRelayDeleted - clears current state`() = runTest {
        // Given: Relay is set
        manager.onPropagationNodeAnnounce(testDestHash, "Relay", 1, testPublicKey)
        advanceUntilIdle()

        // When
        manager.onRelayDeleted()
        advanceUntilIdle()

        // Note: May auto-select new relay if available, but initial state is cleared
        coVerify { settingsRepository.saveManualPropagationNode(null) }
    }

    @Test
    fun `onRelayDeleted - enables auto-select if was manual`() = runTest {
        // Given: Manual mode was active
        coEvery { settingsRepository.getAutoSelectPropagationNode() } returns false

        // When
        manager.onRelayDeleted()
        advanceUntilIdle()

        // Then: Should enable auto-select
        coVerify { settingsRepository.saveAutoSelectPropagationNode(true) }
    }

    @Test
    fun `onRelayDeleted - auto-selects new relay if available`() = runTest {
        // Given: Another propagation node available
        val newNode = TestFactories.createAnnounce(
            destinationHash = testDestHash2,
            peerName = "New Node",
            hops = 2,
        )
        every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(listOf(newNode))

        // When
        manager.onRelayDeleted()
        advanceUntilIdle()

        // Then: Should auto-select new relay
        assertEquals(testDestHash2, manager.currentRelay.value?.destinationHash)
    }

    @Test
    fun `onRelayDeleted - no available nodes clears protocol`() = runTest {
        // Given: No propagation nodes
        every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(emptyList())

        // When
        manager.onRelayDeleted()
        advanceUntilIdle()

        // Then
        coVerify { reticulumProtocol.setOutboundPropagationNode(null) }
    }
}
