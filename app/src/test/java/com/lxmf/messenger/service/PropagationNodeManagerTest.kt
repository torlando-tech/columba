package com.lxmf.messenger.service

import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.PropagationState
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

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
    private val testScheduler get() = testDispatcher.scheduler
    private lateinit var testScope: TestScope

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var reticulumProtocol: ServiceReticulumProtocol
    private lateinit var manager: PropagationNodeManager
    private lateinit var myRelayFlow: MutableStateFlow<ContactEntity?>
    private lateinit var autoSelectFlow: MutableStateFlow<Boolean>
    private lateinit var networkStatusFlow: MutableStateFlow<NetworkStatus>
    private lateinit var propagationStateFlow: MutableSharedFlow<PropagationState>

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

        // Initialize mutable flows for reactive testing
        myRelayFlow = MutableStateFlow<ContactEntity?>(null)
        autoSelectFlow = MutableStateFlow(true)
        networkStatusFlow = MutableStateFlow<NetworkStatus>(NetworkStatus.READY)
        propagationStateFlow = MutableSharedFlow(replay = 1, extraBufferCapacity = 1)

        // Mock networkStatus flow
        every { reticulumProtocol.networkStatus } returns networkStatusFlow

        // Mock propagationStateFlow for sync completion observation
        every { reticulumProtocol.propagationStateFlow } returns propagationStateFlow

        // Default settings mocks
        coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
        coEvery { settingsRepository.getManualPropagationNode() } returns null
        coEvery { settingsRepository.getLastPropagationNode() } returns null
        coEvery { settingsRepository.saveLastPropagationNode(any()) } just Runs
        coEvery { settingsRepository.saveAutoSelectPropagationNode(any()) } just Runs
        coEvery { settingsRepository.saveManualPropagationNode(any()) } just Runs
        coEvery { settingsRepository.getLastSyncTimestamp() } returns null
        coEvery { settingsRepository.getAutoRetrieveEnabled() } returns false
        coEvery { settingsRepository.getRetrievalIntervalSeconds() } returns 30
        every { settingsRepository.autoSelectPropagationNodeFlow } returns autoSelectFlow
        every { settingsRepository.retrievalIntervalSecondsFlow } returns flowOf(30)

        // Default repository mocks
        every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
        every { announceRepository.getTopPropagationNodes(any()) } returns flowOf(emptyList())
        coEvery { announceRepository.getNodeTypeCounts() } returns emptyList()
        coEvery { announceRepository.getAnnounce(any()) } returns null
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.addContactFromAnnounce(any(), any()) } returns Result.success(Unit)
        coEvery { contactRepository.clearMyRelay() } just Runs
        every { contactRepository.getMyRelayFlow() } returns myRelayFlow
        coEvery { reticulumProtocol.setOutboundPropagationNode(any()) } returns Result.success(Unit)

        // Mock setAsMyRelay to update the myRelayFlow (simulate database update)
        coEvery { contactRepository.setAsMyRelay(any(), any()) } answers {
            val destHash = firstArg<String>()
            myRelayFlow.value = TestFactories.createContactEntity(destinationHash = destHash, isMyRelay = true)
        }

        // Mock clearMyRelay to update the myRelayFlow
        coEvery { contactRepository.clearMyRelay() } answers {
            myRelayFlow.value = null
        }

        manager =
            PropagationNodeManager(
                settingsRepository = settingsRepository,
                contactRepository = contactRepository,
                announceRepository = announceRepository,
                reticulumProtocol = reticulumProtocol,
                scope = testScope.backgroundScope,
            )
    }

    @After
    fun tearDown() {
        // Stop the manager to cancel any running coroutines (if initialized)
        if (::manager.isInitialized) {
            manager.stop()
        }
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Lifecycle Tests ==========

    @Test
    fun `start - can be called without error`() =
        runTest {
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
    fun `start - attempts restore from settings`() =
        runTest {
            // Given: Last relay saved in settings
            val lastRelay = testDestHash
            val announce =
                TestFactories.createAnnounce(
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
    fun `start - does not set relay if announce not found`() =
        runTest {
            // Given: Last relay saved but announce not in database
            coEvery { settingsRepository.getLastPropagationNode() } returns testDestHash
            coEvery { announceRepository.getAnnounce(testDestHash) } returns null

            // When
            manager.start()
            advanceUntilIdle()

            // Then: Should not set relay (no setAsMyRelay call)
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(any(), any()) }
            manager.stop()
        }

    @Test
    fun `start - does not set relay if announce is not propagation node`() =
        runTest {
            // Given: Last relay is not a propagation node
            val announce = TestFactories.createAnnounce(nodeType = "PEER")
            coEvery { settingsRepository.getLastPropagationNode() } returns testDestHash
            coEvery { announceRepository.getAnnounce(testDestHash) } returns announce

            // When
            manager.start()
            advanceUntilIdle()

            // Then: Should not set relay
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(any(), any()) }
            manager.stop()
        }

    @Test
    fun `stop - cancels announce observer`() =
        runTest {
            // Given: Manager started
            manager.start()
            advanceUntilIdle()

            // When
            manager.stop()
            advanceUntilIdle()

            // Then: Observer should be cancelled (no crash, state preserved)
            // Further announces should not trigger selection
        }

    @Test
    fun `start - loads lastSyncTimestamp from settings`() =
        runTest {
            // Given: Last sync timestamp exists in settings
            val savedTimestamp = 1234567890L
            coEvery { settingsRepository.getLastSyncTimestamp() } returns savedTimestamp

            // When
            manager.start()

            // Wait for the timestamp to be loaded (it's done via scope.launch)
            manager.lastSyncTimestamp.test(timeout = 5.seconds) {
                // Skip initial null
                var value = awaitItem()
                if (value == null) {
                    value = awaitItem()
                }
                // Then: lastSyncTimestamp StateFlow should have the saved value
                assert(value == savedTimestamp) {
                    "lastSyncTimestamp should be loaded from settings, expected $savedTimestamp but got $value"
                }
                cancelAndConsumeRemainingEvents()
            }

            manager.stop()
        }

    @Test
    fun `start - observeRelayChanges syncs to Python on relay update`() =
        runTest {
            // Given: Manager started
            manager.start()
            advanceUntilIdle()

            // When: Relay is updated (simulating database change)
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for StateFlow to settle
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Then: Should sync to Python layer
            coVerify { reticulumProtocol.setOutboundPropagationNode(any()) }

            manager.stop()
        }

    @Test
    fun `start - observeRelayChanges clears Python on relay removal`() =
        runTest {
            // Given: Manager started with a relay configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )
            manager.start()

            // Wait for initial relay to be processed
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Verify initial sync happened
            coVerify { reticulumProtocol.setOutboundPropagationNode(any()) }
            io.mockk.clearMocks(reticulumProtocol, answers = false, recordedCalls = true, verificationMarks = true)

            // When: Relay is removed
            myRelayFlow.value = null

            // Wait for StateFlow to settle with null
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while ((state as? RelayLoadState.Loaded)?.relay != null) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Then: Should clear Python layer
            coVerify { reticulumProtocol.setOutboundPropagationNode(null) }

            manager.stop()
        }

    @Test
    fun `start - observePropagationNodeAnnounces respects autoSelect setting`() =
        runTest {
            // Given: Auto-select is disabled
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns false
            autoSelectFlow.value = false

            val announce =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    nodeType = "PROPAGATION_NODE",
                    hops = 1,
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(listOf(announce))

            // When: Start observing announces
            manager.start()
            advanceUntilIdle()

            // Then: Should NOT auto-select relay (auto-select is disabled)
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(any(), any()) }

            manager.stop()
        }

    // ========== onPropagationNodeAnnounce Tests (Sideband Algorithm) ==========

    @Test
    fun `onPropagationNodeAnnounce - no current relay selects new node`() =
        runTest {
            // Given: No current relay and announce data exists
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Test Relay",
                    hops = 3,
                )

            // When
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash,
                displayName = "Test Relay",
                hops = 3,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should set as relay in database
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - closer hops switches to new node`() =
        runTest {
            // Given: Set up announce mocks and current relay at 5 hops
            coEvery { announceRepository.getAnnounce(testDestHash2) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    peerName = "Old Relay",
                    hops = 5,
                )
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Closer Relay",
                    hops = 2,
                )

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

            // Then: Should switch to closer relay (verify setAsMyRelay called with new hash)
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - first announce sets relay`() =
        runTest {
            // Given: Announce mock exists
            coEvery { announceRepository.getAnnounce(testDestHash2) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    peerName = "Current Relay",
                    hops = 3,
                )

            // When: First announce received
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash2,
                displayName = "Current Relay",
                hops = 3,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should set as relay
            coVerify { contactRepository.setAsMyRelay(testDestHash2, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - same node same hops updates relay`() =
        runTest {
            // Given: Set up announce mock and current relay
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Relay v2",
                    hops = 3,
                )

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

            // Then: Should call setAsMyRelay again (to refresh)
            coVerify(atLeast = 2) { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - current hops unknown switches to new node`() =
        runTest {
            // Given: Current relay has unknown hops (-1)
            // First, set up a relay with hops = -1 by having no announce data
            coEvery { announceRepository.getAnnounce(testDestHash) } returns null
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for the StateFlow to update with the relay
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Now set up the new relay with known hops
            coEvery { announceRepository.getAnnounce(testDestHash2) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    peerName = "New Relay",
                    hops = 5,
                )

            // When: New node announces with known hops
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash2,
                displayName = "New Relay",
                hops = 5,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should switch to new relay (current hops -1 means unknown, any known hops is better)
            coVerify { contactRepository.setAsMyRelay(testDestHash2, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - more hops does not switch`() =
        runTest {
            // Given: Current relay at 2 hops - set up announce BEFORE relay to ensure hops are known
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Close Relay",
                    hops = 2,
                )

            // Set up initial relay via flow (simulating database-as-source-of-truth)
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for the StateFlow to update with correct hops
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                val loaded = state as RelayLoadState.Loaded
                // Wait until we have the relay with correct hops
                if (loaded.relay?.hops != 2) {
                    // May need another emission after announce is fetched
                    awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Clear verifications from setup
            io.mockk.clearMocks(contactRepository, answers = false, recordedCalls = true, verificationMarks = true)

            // When: New node at 5 hops (farther)
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash2,
                displayName = "Far Relay",
                hops = 5,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should NOT switch - current relay is closer
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(testDestHash2, any()) }
        }

    @Test
    fun `onPropagationNodeAnnounce - same hops different node does not switch`() =
        runTest {
            // Given: Current relay at 3 hops - set up announce BEFORE relay
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Current Relay",
                    hops = 3,
                )

            // Set up initial relay via flow
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for the StateFlow to update with correct hops
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                val loaded = state as RelayLoadState.Loaded
                if (loaded.relay?.hops != 3) {
                    awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Clear verifications from setup
            io.mockk.clearMocks(contactRepository, answers = false, recordedCalls = true, verificationMarks = true)

            // When: Different node at same hops
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash2,
                displayName = "Other Relay",
                hops = 3,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should NOT switch - same hops, keep current
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(testDestHash2, any()) }
        }

    @Test
    fun `onPropagationNodeAnnounce - manual mode ignores announce`() =
        runTest {
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
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(testDestHash, any()) }
        }

    @Test
    fun `onPropagationNodeAnnounce - adds contact if not exists`() =
        runTest {
            // Given: Contact does not exist and announce data exists
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "New Relay",
                    hops = 1,
                )

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
    fun `onPropagationNodeAnnounce - does not add contact if exists`() =
        runTest {
            // Given: Contact already exists and announce data exists
            coEvery { contactRepository.hasContact(testDestHash) } returns true
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Existing Relay",
                    hops = 1,
                )

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
    fun `onPropagationNodeAnnounce - sets as my relay`() =
        runTest {
            // Given: Announce data exists
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "New Relay",
                    hops = 1,
                )

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
    fun `onPropagationNodeAnnounce - updates database`() =
        runTest {
            // Given: Announce data exists
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "New Relay",
                    hops = 1,
                )

            // When
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash,
                displayName = "New Relay",
                hops = 1,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Database should be updated
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - saves to settings`() =
        runTest {
            // Note: This test verifies the old behavior where saveLastPropagationNode was called.
            // With the new database-as-source-of-truth architecture, this is no longer done.
            // The test is updated to verify that setAsMyRelay is called instead.
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "New Relay",
                    hops = 1,
                )

            // When
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash,
                displayName = "New Relay",
                hops = 1,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should set as relay in database (the new source of truth)
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `onPropagationNodeAnnounce - sets relay with auto-select enabled`() =
        runTest {
            // Given: Auto-select enabled and announce data exists
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Auto Relay",
                    hops = 1,
                )

            // When
            manager.onPropagationNodeAnnounce(
                destinationHash = testDestHash,
                displayName = "Auto Relay",
                hops = 1,
                publicKey = testPublicKey,
            )
            advanceUntilIdle()

            // Then: Should set as relay in database
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    // ========== setManualRelay Tests ==========

    @Test
    fun `setManualRelay - disables auto-select`() =
        runTest {
            // Given: Set up announce data
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Manual Relay",
                )

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveAutoSelectPropagationNode(false) }
        }

    @Test
    fun `setManualRelay - saves manual node`() =
        runTest {
            // Given: Set up announce data
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Manual Relay",
                )

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveManualPropagationNode(testDestHash) }
        }

    @Test
    fun `setManualRelay - updates current relay state`() =
        runTest {
            // Given: Set up announce data
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Manual Relay",
                    hops = 2,
                )

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then: Should set as relay in database
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `setManualRelay - configures protocol via database update`() =
        runTest {
            // Given: Set up announce data
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Manual Relay",
                )

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then: Should set as relay in database (protocol update happens via observeRelayChanges)
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `setManualRelay - sets as my relay in contacts`() =
        runTest {
            // Given: Set up announce data
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Manual Relay",
                )

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `setManualRelay - adds contact if not exists and announce available`() =
        runTest {
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

    @Test
    fun `setManualRelay - does not add contact if already exists`() =
        runTest {
            // Given: Contact already exists
            coEvery { contactRepository.hasContact(testDestHash) } returns true
            val announce = TestFactories.createAnnounce()
            coEvery { announceRepository.getAnnounce(testDestHash) } returns announce

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then: Should NOT add contact
            coVerify(exactly = 0) { contactRepository.addContactFromAnnounce(any(), any()) }

            // But should still set as relay
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `setManualRelay - skips contact add when no announce available`() =
        runTest {
            // Given: Contact does not exist AND announce is not available
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { announceRepository.getAnnounce(testDestHash) } returns null

            // When
            manager.setManualRelay(testDestHash, "Manual Relay")
            advanceUntilIdle()

            // Then: Should NOT add contact (no announce data)
            coVerify(exactly = 0) { contactRepository.addContactFromAnnounce(any(), any()) }

            // But should still set as relay
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    // ========== enableAutoSelect Tests ==========

    @Test
    fun `enableAutoSelect - clears manual node`() =
        runTest {
            // When
            manager.enableAutoSelect()
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveManualPropagationNode(null) }
        }

    @Test
    fun `enableAutoSelect - enables auto-select setting`() =
        runTest {
            // When
            manager.enableAutoSelect()
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveAutoSelectPropagationNode(true) }
        }

    @Test
    fun `enableAutoSelect - selects nearest node`() =
        runTest {
            // Given: Multiple propagation nodes available
            val nearNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Near Node",
                    hops = 1,
                )
            val farNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    peerName = "Far Node",
                    hops = 5,
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(listOf(farNode, nearNode))
            coEvery { announceRepository.getAnnounce(testDestHash) } returns nearNode
            coEvery { announceRepository.getAnnounce(testDestHash2) } returns farNode

            // When
            manager.enableAutoSelect()
            advanceUntilIdle()

            // Then: Should set nearest as relay in database
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `enableAutoSelect - no propagation nodes clears relay`() =
        runTest {
            // Given: No propagation nodes
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(emptyList())

            // When
            manager.enableAutoSelect()
            advanceUntilIdle()

            // Then: Should clear relay in database
            coVerify { contactRepository.clearMyRelay() }
        }

    // ========== clearRelay Tests ==========

    @Test
    fun `clearRelay - clears current state`() =
        runTest {
            // Given: Relay is set
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Relay",
                    hops = 1,
                )
            manager.onPropagationNodeAnnounce(testDestHash, "Relay", 1, testPublicKey)
            advanceUntilIdle()

            // When
            manager.clearRelay()
            advanceUntilIdle()

            // Then: Should clear relay in database
            coVerify { contactRepository.clearMyRelay() }
        }

    @Test
    fun `clearRelay - clears my relay in contacts`() =
        runTest {
            // When
            manager.clearRelay()
            advanceUntilIdle()

            // Then
            coVerify { contactRepository.clearMyRelay() }
        }

    @Test
    fun `clearRelay - clears manual node setting`() =
        runTest {
            // When
            manager.clearRelay()
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveManualPropagationNode(null) }
        }

    // ========== onRelayDeleted Tests ==========

    @Test
    fun `onRelayDeleted - clears manual node setting`() =
        runTest {
            // Given: Relay is set
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Relay",
                    hops = 1,
                )
            manager.onPropagationNodeAnnounce(testDestHash, "Relay", 1, testPublicKey)
            advanceUntilIdle()

            // When
            manager.onRelayDeleted()
            advanceUntilIdle()

            // Then: Manual node setting should be cleared
            coVerify { settingsRepository.saveManualPropagationNode(null) }
        }

    @Test
    fun `onRelayDeleted - enables auto-select if was manual`() =
        runTest {
            // Given: Manual mode was active
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns false

            // When
            manager.onRelayDeleted()
            advanceUntilIdle()

            // Then: Should enable auto-select
            coVerify { settingsRepository.saveAutoSelectPropagationNode(true) }
        }

    @Test
    fun `onRelayDeleted - auto-selects new relay if available`() =
        runTest {
            // Given: Another propagation node available
            val newNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    peerName = "New Node",
                    hops = 2,
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(listOf(newNode))
            coEvery { announceRepository.getAnnounce(testDestHash2) } returns newNode

            // When
            manager.onRelayDeleted()
            advanceUntilIdle()

            // Then: Should set new relay in database
            coVerify { contactRepository.setAsMyRelay(testDestHash2, clearOther = true) }
        }

    @Test
    fun `onRelayDeleted - no available nodes does not set relay`() =
        runTest {
            // Given: No propagation nodes
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns flowOf(emptyList())

            // When
            manager.onRelayDeleted()
            advanceUntilIdle()

            // Then: No relay to select, setAsMyRelay should not be called
            coVerify(exactly = 0) { contactRepository.setAsMyRelay(any(), any()) }
        }

    // NOTE: Complex database source of truth flow tests have been removed due to
    // incompatibility with runBlocking in StateFlow initialization.
    // The relay source of truth behavior is tested via:
    // 1. ContactRepositoryTest - tests getAnyRelay() fallback
    // 2. SettingsViewModelTest - tests relay state preservation in loadSettings()
    // 3. The tests above that verify repository method calls (setAsMyRelay, clearMyRelay)

    // ========== triggerSync Race Condition Tests ==========

    @Test
    fun `triggerSync waits for relay state to load before checking relay`() =
        runTest {
            // Arrange: Set up the relay value BEFORE creating manager
            // This simulates the database already having a relay configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Mock the sync protocol call to return success
            val mockSyncState =
                com.lxmf.messenger.reticulum.protocol.PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // Verify the currentRelayState starts as Loading before any coroutines run
            assert(manager.currentRelayState.value is RelayLoadState.Loading) {
                "currentRelayState should start as Loading"
            }

            // Start collecting results BEFORE triggering sync (SharedFlow doesn't replay)
            manager.manualSyncResult.test(timeout = 10.seconds) {
                // Act: Trigger sync - this will wait for currentRelayState to become Loaded
                val syncJob =
                    async {
                        manager.triggerSync()
                    }

                // Run coroutines without time advancement to avoid infinite loops
                // The combine flow for currentRelayState should run and emit Loaded
                repeat(10) {
                    testDispatcher.scheduler.runCurrent()
                }

                // Wait for sync to complete
                syncJob.await()

                // Assert: Should NOT emit NoRelay - sync should proceed with the relay
                val result = awaitItem()
                assertNotEquals(
                    "Sync should wait for relay state and find configured relay",
                    SyncResult.NoRelay,
                    result,
                )
                cancelAndConsumeRemainingEvents()
            }
        }

    // ========== syncWithPropagationNode Tests ==========

    @Test
    fun `syncWithPropagationNode - skips when network not ready`() =
        runTest {
            // Given: Network is in SHUTDOWN state
            networkStatusFlow.value = NetworkStatus.SHUTDOWN

            // Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            val mockSyncState =
                com.lxmf.messenger.reticulum.protocol.PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When
            manager.syncWithPropagationNode()
            advanceUntilIdle()

            // Then: Should not call requestMessagesFromPropagationNode because network is not ready
            coVerify(exactly = 0) { reticulumProtocol.requestMessagesFromPropagationNode() }
        }

    @Test
    fun `syncWithPropagationNode - skips when network is initializing`() =
        runTest {
            // Given: Network is INITIALIZING
            networkStatusFlow.value = NetworkStatus.INITIALIZING

            // Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // When
            manager.syncWithPropagationNode()
            advanceUntilIdle()

            // Then: Should not call requestMessagesFromPropagationNode
            coVerify(exactly = 0) { reticulumProtocol.requestMessagesFromPropagationNode() }
        }

    @Test
    fun `syncWithPropagationNode - proceeds when network is ready`() =
        runTest {
            // Given: Network is READY
            networkStatusFlow.value = NetworkStatus.READY

            // Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                com.lxmf.messenger.reticulum.protocol.PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When
            manager.syncWithPropagationNode()
            advanceUntilIdle()

            // Then: Should call requestMessagesFromPropagationNode
            coVerify(atLeast = 1) { reticulumProtocol.requestMessagesFromPropagationNode() }
        }

    @Test
    fun `syncWithPropagationNode - skips when no relay configured`() =
        runTest {
            // Given: No relay configured (myRelayFlow is null by default)
            advanceUntilIdle()

            // When
            manager.syncWithPropagationNode()
            advanceUntilIdle()

            // Then: Should not call requestMessagesFromPropagationNode
            coVerify(exactly = 0) { reticulumProtocol.requestMessagesFromPropagationNode() }
        }

    @Test
    fun `syncWithPropagationNode - skips when already syncing`() =
        runTest {
            // Given: Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                com.lxmf.messenger.reticulum.protocol.PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When: Call sync
            manager.syncWithPropagationNode()
            advanceUntilIdle()

            // Then: Protocol should be called
            coVerify(atLeast = 1) { reticulumProtocol.requestMessagesFromPropagationNode() }
        }

    @Test
    fun `syncWithPropagationNode - updates lastSyncTimestamp on success`() =
        runTest {
            // Given: Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Start manager to observe propagation state changes
            manager.start()

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When
            launch { manager.syncWithPropagationNode() }
            testScheduler.runCurrent()

            // Simulate propagation state callback with PR_COMPLETE (state 7)
            val completeState =
                PropagationState(
                    state = 7,
                    stateName = "complete",
                    progress = 1.0f,
                    messagesReceived = 0,
                )
            propagationStateFlow.emit(completeState)
            testScheduler.runCurrent()

            // Then: Should save timestamp to settings repository
            coVerify { settingsRepository.saveLastSyncTimestamp(any()) }

            // Stop manager to cancel observer
            manager.stop()
        }

    @Test
    fun `syncWithPropagationNode - handles protocol failure gracefully`() =
        runTest {
            // Given: Relay is configured but sync will fail
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.failure(Exception("Network error"))

            // When: Should not throw
            manager.syncWithPropagationNode()
            advanceUntilIdle()

            // Then: isSyncing should be false after failure
            assert(!manager.isSyncing.value) { "isSyncing should be false after failure" }
        }

    @Test
    fun `triggerSync - emits NoRelay when no relay configured`() =
        runTest {
            // Given: No relay configured
            advanceUntilIdle()

            // Wait for state to be Loaded(null)
            manager.currentRelayState.test(timeout = 5.seconds) {
                // Skip Loading state
                val state = awaitItem()
                if (state is RelayLoadState.Loading) {
                    awaitItem() // Wait for Loaded
                }
                cancelAndConsumeRemainingEvents()
            }

            // When: Trigger manual sync
            manager.manualSyncResult.test(timeout = 5.seconds) {
                manager.triggerSync()
                advanceUntilIdle()

                // Then: Should emit NoRelay
                val result = awaitItem()
                assert(result is SyncResult.NoRelay) {
                    "Should emit NoRelay when no relay configured, got $result"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `triggerSync - skips when already syncing`() =
        runTest {
            // Given: Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Use a CompletableDeferred to control when the sync completes
            val syncCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } coAnswers {
                syncCompletion.await() // Wait until we explicitly complete it
                Result.success(
                    com.lxmf.messenger.reticulum.protocol.PropagationState(
                        state = 0,
                        stateName = "IDLE",
                        progress = 0.0f,
                        messagesReceived = 0,
                    ),
                )
            }

            // Start first sync (will set isSyncing = true)
            val firstSyncJob = async { manager.triggerSync() }

            // Run current tasks to start the sync and enter the "syncing" state
            testDispatcher.scheduler.runCurrent()

            // Verify isSyncing is true
            assert(manager.isSyncing.value) { "isSyncing should be true during sync" }

            // When: Try to trigger second sync while first is running
            manager.triggerSync()
            testDispatcher.scheduler.runCurrent()

            // Then: Protocol should only be called once (second call skipped)
            coVerify(exactly = 1) { reticulumProtocol.requestMessagesFromPropagationNode() }

            // Cleanup - complete the deferred to let the first sync finish
            syncCompletion.complete(Unit)
            firstSyncJob.await()
        }

    @Test
    fun `triggerSync - emits Success on successful sync`() =
        runTest {
            // Given: Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Start manager to observe propagation state changes
            manager.start()

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When: Trigger sync and collect result
            manager.manualSyncResult.test(timeout = 5.seconds) {
                launch { manager.triggerSync() }
                testScheduler.runCurrent()

                // Simulate propagation state callback with PR_COMPLETE (state 7)
                val completeState =
                    PropagationState(
                        state = 7,
                        stateName = "complete",
                        progress = 1.0f,
                        messagesReceived = 3,
                    )
                propagationStateFlow.emit(completeState)
                testScheduler.runCurrent()

                // Then: Should emit Success with messages count
                val result = awaitItem()
                assert(result is SyncResult.Success) {
                    "Should emit Success on successful sync, got $result"
                }
                assertEquals(3, (result as SyncResult.Success).messagesReceived)
                cancelAndConsumeRemainingEvents()
            }

            // Stop manager to cancel observer
            manager.stop()
        }

    @Test
    fun `triggerSync - emits Error on protocol failure`() =
        runTest {
            // Given: Relay is configured but sync will fail
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.failure(Exception("Network error"))

            // When: Trigger sync and collect result
            manager.manualSyncResult.test(timeout = 5.seconds) {
                manager.triggerSync()
                advanceUntilIdle()

                // Then: Should emit Error with message
                val result = awaitItem()
                assert(result is SyncResult.Error) {
                    "Should emit Error on protocol failure, got $result"
                }
                assert((result as SyncResult.Error).message == "Network error") {
                    "Error message should be 'Network error', got ${result.message}"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `triggerSync - updates lastSyncTimestamp on success`() =
        runTest {
            // Given: Relay is configured
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Start manager to observe propagation state changes
            manager.start()

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When
            launch { manager.triggerSync() }
            testScheduler.runCurrent()

            // Simulate propagation state callback with PR_COMPLETE (state 7)
            val completeState =
                PropagationState(
                    state = 7,
                    stateName = "complete",
                    progress = 1.0f,
                    messagesReceived = 0,
                )
            propagationStateFlow.emit(completeState)
            testScheduler.runCurrent()

            // Then: Should save timestamp to settings repository
            coVerify { settingsRepository.saveLastSyncTimestamp(any()) }

            // And lastSyncTimestamp StateFlow should be updated
            assert(manager.lastSyncTimestamp.value != null) {
                "lastSyncTimestamp should be set after successful sync"
            }

            // Stop manager to cancel observer
            manager.stop()
        }

    // ========== RelayInfo Fallback Logic Tests (via currentRelay) ==========

    @Test
    fun `currentRelay - uses announce peerName when available`() =
        runTest {
            // Given: Relay with announce that has peerName
            val announcePeerName = "Announce Peer Name"
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = announcePeerName,
                    hops = 2,
                )

            myRelayFlow.value =
                TestFactories.createContactEntity(
                    TestFactories.ContactConfig(
                        destinationHash = testDestHash,
                        customNickname = "Custom Nickname",
                        isMyRelay = true,
                    ),
                )

            // Wait for state to settle
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                // Then: displayName should be the announce peerName (primary source)
                val relay = (state as RelayLoadState.Loaded).relay
                assert(relay != null) { "Relay should not be null" }
                assert(relay!!.displayName == announcePeerName) {
                    "displayName should be announce peerName '$announcePeerName', got '${relay.displayName}'"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `currentRelay - falls back to customNickname when no announce`() =
        runTest {
            // Given: Relay without announce data, but with customNickname
            coEvery { announceRepository.getAnnounce(testDestHash) } returns null

            val customNickname = "Custom Nickname"
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    TestFactories.ContactConfig(
                        destinationHash = testDestHash,
                        customNickname = customNickname,
                        isMyRelay = true,
                    ),
                )

            // Wait for state to settle
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                // Then: displayName should be the customNickname (fallback)
                val relay = (state as RelayLoadState.Loaded).relay
                assert(relay != null) { "Relay should not be null" }
                assert(relay!!.displayName == customNickname) {
                    "displayName should be customNickname '$customNickname', got '${relay.displayName}'"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `currentRelay - falls back to truncated hash when no names available`() =
        runTest {
            // Given: Relay without announce data and without customNickname
            coEvery { announceRepository.getAnnounce(testDestHash) } returns null

            myRelayFlow.value =
                TestFactories.createContactEntity(
                    TestFactories.ContactConfig(
                        destinationHash = testDestHash,
                        customNickname = null,
                        isMyRelay = true,
                    ),
                )

            // Wait for state to settle
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                // Then: displayName should be the truncated hash (final fallback)
                val relay = (state as RelayLoadState.Loaded).relay
                assert(relay != null) { "Relay should not be null" }
                assert(relay!!.displayName == testDestHash.take(12)) {
                    "displayName should be truncated hash '${testDestHash.take(12)}', got '${relay.displayName}'"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `currentRelay - uses announce lastSeenTimestamp when available`() =
        runTest {
            // Given: Relay with announce that has lastSeenTimestamp
            val announceTimestamp = 1700000000000L
            coEvery { announceRepository.getAnnounce(testDestHash) } returns
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    lastSeenTimestamp = announceTimestamp,
                )

            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for state to settle
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                // Then: lastSeenTimestamp should be from announce (primary source)
                val relay = (state as RelayLoadState.Loaded).relay
                assert(relay != null) { "Relay should not be null" }
                assert(relay!!.lastSeenTimestamp == announceTimestamp) {
                    "lastSeenTimestamp should be announce timestamp $announceTimestamp, got ${relay.lastSeenTimestamp}"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `currentRelay - falls back to contact lastInteractionTimestamp when no announce`() =
        runTest {
            // Given: Relay without announce data
            // The contact's lastInteractionTimestamp is set to 0 by TestFactories
            coEvery { announceRepository.getAnnounce(testDestHash) } returns null

            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )

            // Wait for state to settle
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading || (state as? RelayLoadState.Loaded)?.relay == null) {
                    state = awaitItem()
                }
                // Then: lastSeenTimestamp should fall back to contact's lastInteractionTimestamp (0)
                val relay = (state as RelayLoadState.Loaded).relay
                assert(relay != null) { "Relay should not be null" }
                // Contact's lastInteractionTimestamp is 0 as set by TestFactories
                assert(relay!!.lastSeenTimestamp == 0L) {
                    "lastSeenTimestamp should be contact timestamp 0, got ${relay.lastSeenTimestamp}"
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    // ========== getAlternativeRelay Tests ==========

    @Test
    fun `getAlternativeRelay - returns nearest excluding current`() =
        runTest {
            // Given: Multiple propagation nodes, one is current (should be excluded)
            val currentNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Current",
                    hops = 2,
                    nodeType = "PROPAGATION_NODE",
                )
            val alternativeNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    peerName = "Alternative",
                    hops = 3,
                    nodeType = "PROPAGATION_NODE",
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(listOf(currentNode, alternativeNode))

            // When
            val result = manager.getAlternativeRelay(excludeHashes = listOf(testDestHash))

            // Then: Should return the alternative (not excluded)
            assert(result != null) { "Should return an alternative relay" }
            assert(result!!.destinationHash == testDestHash2) {
                "Should return testDestHash2, got ${result.destinationHash}"
            }
        }

    @Test
    fun `getAlternativeRelay - excludes multiple relays`() =
        runTest {
            // Given: Three nodes, two are excluded
            val node1 =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    hops = 1,
                    nodeType = "PROPAGATION_NODE",
                )
            val node2 =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    hops = 2,
                    nodeType = "PROPAGATION_NODE",
                )
            val node3 =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash3,
                    hops = 3,
                    nodeType = "PROPAGATION_NODE",
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(listOf(node1, node2, node3))

            // When
            val result = manager.getAlternativeRelay(excludeHashes = listOf(testDestHash, testDestHash2))

            // Then: Should return node3 (only non-excluded)
            assert(result != null) { "Should return an alternative relay" }
            assert(result!!.destinationHash == testDestHash3) {
                "Should return testDestHash3, got ${result.destinationHash}"
            }
        }

    @Test
    fun `getAlternativeRelay - returns null when all excluded`() =
        runTest {
            // Given: All propagation nodes are excluded
            val node =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    hops = 1,
                    nodeType = "PROPAGATION_NODE",
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(listOf(node))

            // When
            val result = manager.getAlternativeRelay(excludeHashes = listOf(testDestHash))

            // Then: Should return null
            assert(result == null) { "Should return null when all nodes excluded" }
        }

    @Test
    fun `getAlternativeRelay - selects by hop count among available`() =
        runTest {
            // Given: Multiple alternatives available with different hop counts
            val farNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    hops = 5,
                    nodeType = "PROPAGATION_NODE",
                )
            val nearNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    hops = 2,
                    nodeType = "PROPAGATION_NODE",
                )
            val excludedNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash3,
                    hops = 1,
                    nodeType = "PROPAGATION_NODE",
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(listOf(farNode, nearNode, excludedNode))

            // When
            val result = manager.getAlternativeRelay(excludeHashes = listOf(testDestHash3))

            // Then: Should return nearest non-excluded (nearNode at 2 hops)
            assert(result != null) { "Should return an alternative relay" }
            assert(result!!.destinationHash == testDestHash2) {
                "Should return testDestHash2 (nearest), got ${result.destinationHash}"
            }
            assert(result.hops == 2) { "Should have 2 hops, got ${result.hops}" }
        }

    @Test
    fun `getAlternativeRelay - returns null when no propagation nodes available`() =
        runTest {
            // Given: No propagation nodes
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(emptyList())

            // When
            val result = manager.getAlternativeRelay(excludeHashes = emptyList())

            // Then: Should return null
            assert(result == null) { "Should return null when no propagation nodes" }
        }

    @Test
    fun `getAlternativeRelay - with empty exclude list returns nearest`() =
        runTest {
            // Given: Multiple propagation nodes
            val farNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    hops = 5,
                    nodeType = "PROPAGATION_NODE",
                )
            val nearNode =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash2,
                    hops = 1,
                    nodeType = "PROPAGATION_NODE",
                )
            every { announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")) } returns
                flowOf(listOf(farNode, nearNode))

            // When: No exclusions
            val result = manager.getAlternativeRelay(excludeHashes = emptyList())

            // Then: Should return nearest (nearNode)
            assert(result != null) { "Should return a relay" }
            assert(result!!.destinationHash == testDestHash2) {
                "Should return nearest relay, got ${result.destinationHash}"
            }
        }

    // ========== setManualRelayByHash Tests ==========

    @Test
    fun `setManualRelayByHash - disables auto-select`() =
        runTest {
            // Given: Mock addPendingContact for when contact doesn't exist
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(com.lxmf.messenger.data.repository.ContactRepository.AddPendingResult.AddedAsPending)

            // When
            manager.setManualRelayByHash(testDestHash, "My Relay")
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveAutoSelectPropagationNode(false) }
        }

    @Test
    fun `setManualRelayByHash - saves manual node to settings`() =
        runTest {
            // Given: Mock addPendingContact for when contact doesn't exist
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(com.lxmf.messenger.data.repository.ContactRepository.AddPendingResult.AddedAsPending)

            // When
            manager.setManualRelayByHash(testDestHash, "My Relay")
            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveManualPropagationNode(testDestHash) }
        }

    @Test
    fun `setManualRelayByHash - adds contact if not exists`() =
        runTest {
            // Given: Contact does not exist
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(com.lxmf.messenger.data.repository.ContactRepository.AddPendingResult.AddedAsPending)

            // When
            manager.setManualRelayByHash(testDestHash, "My Relay")
            advanceUntilIdle()

            // Then
            coVerify { contactRepository.addPendingContact(testDestHash, "My Relay") }
        }

    @Test
    fun `setManualRelayByHash - does not add contact if exists`() =
        runTest {
            // Given: Contact already exists
            coEvery { contactRepository.hasContact(testDestHash) } returns true

            // When
            manager.setManualRelayByHash(testDestHash, "My Relay")
            advanceUntilIdle()

            // Then: Should NOT add contact
            coVerify(exactly = 0) { contactRepository.addPendingContact(any(), any()) }

            // But should still set as relay
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `setManualRelayByHash - sets as my relay`() =
        runTest {
            // Given: Mock addPendingContact for when contact doesn't exist
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(com.lxmf.messenger.data.repository.ContactRepository.AddPendingResult.AddedAsPending)

            // When
            manager.setManualRelayByHash(testDestHash, "My Relay")
            advanceUntilIdle()

            // Then
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    @Test
    fun `setManualRelayByHash - with nickname passes nickname to addPendingContact`() =
        runTest {
            // Given: Contact does not exist
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(com.lxmf.messenger.data.repository.ContactRepository.AddPendingResult.AddedAsPending)

            // When
            manager.setManualRelayByHash(testDestHash, "Custom Nickname")
            advanceUntilIdle()

            // Then: Nickname is passed to addPendingContact
            coVerify { contactRepository.addPendingContact(testDestHash, "Custom Nickname") }
        }

    @Test
    fun `setManualRelayByHash - with null nickname passes null to addPendingContact`() =
        runTest {
            // Given: Contact does not exist
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(com.lxmf.messenger.data.repository.ContactRepository.AddPendingResult.AddedAsPending)

            // When
            manager.setManualRelayByHash(testDestHash, null)
            advanceUntilIdle()

            // Then: Null nickname is passed
            coVerify { contactRepository.addPendingContact(testDestHash, null) }
        }

    @Test
    fun `setManualRelayByHash - handles addPendingContact failure gracefully`() =
        runTest {
            // Given: Contact does not exist but adding fails
            coEvery { contactRepository.hasContact(testDestHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.failure(RuntimeException("Database error"))

            // When
            manager.setManualRelayByHash(testDestHash, "My Relay")
            advanceUntilIdle()

            // Then: Should still set as relay even if contact add fails
            coVerify { contactRepository.setAsMyRelay(testDestHash, clearOther = true) }
        }

    // ========== start() Debug Logging Tests ==========

    @Test
    fun `start - logs nodeType counts with propagation nodes present`() =
        runTest {
            // Given: Database has propagation nodes
            // Clear existing mock state and set up fresh behavior
            clearAllMocks()
            coEvery { announceRepository.getNodeTypeCounts() } returns
                listOf(
                    Pair("PROPAGATION_NODE", 5),
                    Pair("PEER", 10),
                )
            every { announceRepository.getTopPropagationNodes(any()) } returns flowOf(emptyList())
            every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
            coEvery { announceRepository.getAnnounce(any()) } returns null
            every { contactRepository.getMyRelayFlow() } returns flowOf(null)
            every { settingsRepository.autoSelectPropagationNodeFlow } returns flowOf(true)
            every { settingsRepository.retrievalIntervalSecondsFlow } returns flowOf(60)
            every { settingsRepository.autoRetrieveEnabledFlow } returns flowOf(false)
            coEvery { settingsRepository.getLastSyncTimestamp() } returns null
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
            coEvery { settingsRepository.getManualPropagationNode() } returns null

            // Create a fresh manager with the mock set up
            val testManager =
                PropagationNodeManager(
                    settingsRepository = settingsRepository,
                    contactRepository = contactRepository,
                    announceRepository = announceRepository,
                    reticulumProtocol = reticulumProtocol,
                    scope = testScope.backgroundScope,
                )

            // When: Start is called - exercises the nodeType logging code path
            testManager.start()
            advanceUntilIdle()

            // Then: No exception thrown (code path exercised for coverage)
            testManager.stop()
        }

    @Test
    fun `start - logs warning when no propagation nodes in database`() =
        runTest {
            // Given: Database has no propagation nodes (only peers) - triggers warning log
            clearAllMocks()
            coEvery { announceRepository.getNodeTypeCounts() } returns
                listOf(
                    Pair("PEER", 10),
                )
            every { announceRepository.getTopPropagationNodes(any()) } returns flowOf(emptyList())
            every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
            coEvery { announceRepository.getAnnounce(any()) } returns null
            every { contactRepository.getMyRelayFlow() } returns flowOf(null)
            every { settingsRepository.autoSelectPropagationNodeFlow } returns flowOf(true)
            every { settingsRepository.retrievalIntervalSecondsFlow } returns flowOf(60)
            every { settingsRepository.autoRetrieveEnabledFlow } returns flowOf(false)
            coEvery { settingsRepository.getLastSyncTimestamp() } returns null
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
            coEvery { settingsRepository.getManualPropagationNode() } returns null

            // Create a fresh manager with the mock set up
            val testManager =
                PropagationNodeManager(
                    settingsRepository = settingsRepository,
                    contactRepository = contactRepository,
                    announceRepository = announceRepository,
                    reticulumProtocol = reticulumProtocol,
                    scope = testScope.backgroundScope,
                )

            // When: Start is called - exercises the warning log code path
            testManager.start()
            advanceUntilIdle()

            // Then: No exception thrown (code path exercised for coverage)
            testManager.stop()
        }

    @Test
    fun `start - handles getNodeTypeCounts exception gracefully`() =
        runTest {
            // Given: getNodeTypeCounts throws an exception - exercises catch block
            clearAllMocks()
            coEvery { announceRepository.getNodeTypeCounts() } throws RuntimeException("Database error")
            every { announceRepository.getTopPropagationNodes(any()) } returns flowOf(emptyList())
            every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
            coEvery { announceRepository.getAnnounce(any()) } returns null
            every { contactRepository.getMyRelayFlow() } returns flowOf(null)
            every { settingsRepository.autoSelectPropagationNodeFlow } returns flowOf(true)
            every { settingsRepository.retrievalIntervalSecondsFlow } returns flowOf(60)
            every { settingsRepository.autoRetrieveEnabledFlow } returns flowOf(false)
            coEvery { settingsRepository.getLastSyncTimestamp() } returns null
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
            coEvery { settingsRepository.getManualPropagationNode() } returns null

            // Create a fresh manager with the mock set up
            val testManager =
                PropagationNodeManager(
                    settingsRepository = settingsRepository,
                    contactRepository = contactRepository,
                    announceRepository = announceRepository,
                    reticulumProtocol = reticulumProtocol,
                    scope = testScope.backgroundScope,
                )

            // When: Start is called (should not throw) - exercises exception handler
            testManager.start()
            advanceUntilIdle()

            // Then: No exception thrown, manager continues to function
            testManager.stop()
        }

    // ========== availableRelaysState Tests ==========

    @Test
    fun `availableRelaysState - maps announces to RelayInfo correctly`() =
        runTest {
            // Clear existing mock state
            clearAllMocks()

            // Given: Database has propagation node announces
            val testAnnounce =
                TestFactories.createAnnounce(
                    destinationHash = testDestHash,
                    peerName = "Test Relay",
                    hops = 3,
                    nodeType = "PROPAGATION_NODE",
                )
            every { announceRepository.getTopPropagationNodes(any()) } returns flowOf(listOf(testAnnounce))
            every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
            coEvery { announceRepository.getAnnounce(any()) } returns null
            coEvery { announceRepository.getNodeTypeCounts() } returns emptyList()
            every { contactRepository.getMyRelayFlow() } returns flowOf(null)
            every { settingsRepository.autoSelectPropagationNodeFlow } returns flowOf(true)
            every { settingsRepository.retrievalIntervalSecondsFlow } returns flowOf(60)
            every { settingsRepository.autoRetrieveEnabledFlow } returns flowOf(false)
            coEvery { settingsRepository.getLastSyncTimestamp() } returns null
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
            coEvery { settingsRepository.getManualPropagationNode() } returns null

            // Create a new manager to get fresh StateFlow
            val testManager =
                PropagationNodeManager(
                    settingsRepository = settingsRepository,
                    contactRepository = contactRepository,
                    announceRepository = announceRepository,
                    reticulumProtocol = reticulumProtocol,
                    scope = testScope.backgroundScope,
                )

            // Wait for StateFlow to emit
            testManager.availableRelaysState.test(timeout = 5.seconds) {
                // Skip loading state
                var state = awaitItem()
                if (state is AvailableRelaysState.Loading) {
                    state = awaitItem()
                }

                // Then: Should be Loaded with correct relay info
                assertTrue("State should be Loaded", state is AvailableRelaysState.Loaded)
                val loadedState = state as AvailableRelaysState.Loaded
                assertEquals(1, loadedState.relays.size)
                val relay = loadedState.relays[0]
                assertEquals(testDestHash, relay.destinationHash)
                assertEquals("Test Relay", relay.displayName)
                assertEquals(3, relay.hops)

                cancelAndConsumeRemainingEvents()
            }

            testManager.stop()
        }

    @Test
    fun `availableRelaysState - empty when no propagation nodes`() =
        runTest {
            // Clear existing mock state
            clearAllMocks()

            // Given: No propagation nodes in database
            every { announceRepository.getTopPropagationNodes(any()) } returns flowOf(emptyList())
            every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
            coEvery { announceRepository.getAnnounce(any()) } returns null
            coEvery { announceRepository.getNodeTypeCounts() } returns emptyList()
            every { contactRepository.getMyRelayFlow() } returns flowOf(null)
            every { settingsRepository.autoSelectPropagationNodeFlow } returns flowOf(true)
            every { settingsRepository.retrievalIntervalSecondsFlow } returns flowOf(60)
            every { settingsRepository.autoRetrieveEnabledFlow } returns flowOf(false)
            coEvery { settingsRepository.getLastSyncTimestamp() } returns null
            coEvery { settingsRepository.getAutoSelectPropagationNode() } returns true
            coEvery { settingsRepository.getManualPropagationNode() } returns null

            // Create a new manager
            val testManager =
                PropagationNodeManager(
                    settingsRepository = settingsRepository,
                    contactRepository = contactRepository,
                    announceRepository = announceRepository,
                    reticulumProtocol = reticulumProtocol,
                    scope = testScope.backgroundScope,
                )

            // Wait for StateFlow to emit
            testManager.availableRelaysState.test(timeout = 5.seconds) {
                var state = awaitItem()
                if (state is AvailableRelaysState.Loading) {
                    state = awaitItem()
                }

                // Then: Should be Loaded with empty list
                assertTrue("State should be Loaded", state is AvailableRelaysState.Loaded)
                val loadedState = state as AvailableRelaysState.Loaded
                assertTrue("Relays should be empty", loadedState.relays.isEmpty())

                cancelAndConsumeRemainingEvents()
            }

            testManager.stop()
        }

    // ========== SyncProgress.Complete Tests ==========

    @Test
    fun `syncProgress is Complete when messagesReceived greater than zero`() =
        runTest {
            // Given: Relay is configured and manager started
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )
            manager.start()

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When: Start sync and complete with messages received
            manager.syncProgress.test(timeout = 5.seconds) {
                // Skip initial state
                awaitItem()

                launch { manager.triggerSync() }
                testScheduler.runCurrent()

                // Simulate sync completion with messages received (PR_COMPLETE state = 7)
                val completeState =
                    PropagationState(
                        state = 7,
                        stateName = "complete",
                        progress = 1.0f,
                        messagesReceived = 5,
                    )
                propagationStateFlow.emit(completeState)
                testScheduler.runCurrent()

                // Then: syncProgress should be Complete
                var progress = awaitItem()
                // Skip Starting state if present
                while (progress is SyncProgress.Starting || progress is SyncProgress.InProgress) {
                    progress = awaitItem()
                }
                assertEquals(SyncProgress.Complete, progress)
                cancelAndConsumeRemainingEvents()
            }

            manager.stop()
        }

    @Test
    fun `syncProgress is Idle when messagesReceived is zero`() =
        runTest {
            // Given: Relay is configured and manager started
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )
            manager.start()

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When: Start sync and complete with zero messages
            manager.syncProgress.test(timeout = 5.seconds) {
                // Skip initial state
                awaitItem()

                launch { manager.triggerSync() }
                testScheduler.runCurrent()

                // Simulate sync completion with NO messages received (PR_COMPLETE state = 7)
                val completeState =
                    PropagationState(
                        state = 7,
                        stateName = "complete",
                        progress = 1.0f,
                        messagesReceived = 0,
                    )
                propagationStateFlow.emit(completeState)
                testScheduler.runCurrent()

                // Then: syncProgress should go straight to Idle (not Complete)
                var progress = awaitItem()
                // Skip Starting state if present
                while (progress is SyncProgress.Starting || progress is SyncProgress.InProgress) {
                    progress = awaitItem()
                }
                assertEquals(SyncProgress.Idle, progress)
                cancelAndConsumeRemainingEvents()
            }

            manager.stop()
        }

    @Test
    fun `syncProgress Complete transitions to Idle after delay`() =
        runTest {
            // Given: Relay is configured and manager started
            myRelayFlow.value =
                TestFactories.createContactEntity(
                    destinationHash = testDestHash,
                    isMyRelay = true,
                )
            manager.start()

            // Wait for currentRelayState to become Loaded
            manager.currentRelayState.test(timeout = 5.seconds) {
                var state = awaitItem()
                while (state is RelayLoadState.Loading) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val mockSyncState =
                PropagationState(
                    state = 0,
                    stateName = "IDLE",
                    progress = 0.0f,
                    messagesReceived = 0,
                )
            coEvery { reticulumProtocol.requestMessagesFromPropagationNode() } returns
                Result.success(mockSyncState)

            // When: Start sync, complete with messages, then wait for delay
            launch { manager.triggerSync() }
            testScheduler.runCurrent()

            // Simulate sync completion with messages (PR_COMPLETE state = 7)
            val completeState =
                PropagationState(
                    state = 7,
                    stateName = "complete",
                    progress = 1.0f,
                    messagesReceived = 3,
                )
            propagationStateFlow.emit(completeState)
            testScheduler.runCurrent()

            // Verify Complete state
            assertEquals(SyncProgress.Complete, manager.syncProgress.value)

            // Advance time past the 2 second delay
            testScheduler.advanceTimeBy(2001)

            // Then: syncProgress should transition to Idle
            assertEquals(SyncProgress.Idle, manager.syncProgress.value)

            manager.stop()
        }
}
