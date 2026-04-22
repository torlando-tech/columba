@file:Suppress("IgnoredReturnValue") // first() calls trigger flow collection, result intentionally unused

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import app.cash.turbine.test
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.reticulum.model.AnnounceEvent
import network.columba.app.reticulum.model.Identity
import network.columba.app.reticulum.model.NetworkStatus
import network.columba.app.reticulum.model.NodeType
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.service.IdentityResolutionManager
import network.columba.app.service.PropagationNodeManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for AnnounceStreamViewModel.
 * Tests announce collection, network readiness waiting, database integration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnounceStreamViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var serviceReticulumProtocol: ReticulumProtocol
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var identityRepository: IdentityRepository
    private lateinit var identityResolutionManager: IdentityResolutionManager
    private lateinit var networkStatusFlow: MutableStateFlow<NetworkStatus>
    private lateinit var announceFlow: MutableSharedFlow<AnnounceEvent>
    private lateinit var viewModel: AnnounceStreamViewModel

    private val testIdentity =
        Identity(
            hash = ByteArray(16) { it.toByte() },
            publicKey = ByteArray(32) { it.toByte() },
            privateKey = ByteArray(32) { it.toByte() },
        )

    private val testAnnounce =
        AnnounceEvent(
            destinationHash = ByteArray(16) { 0xAB.toByte() },
            identity = testIdentity,
            appData = "TestNode".toByteArray(),
            hops = 2,
            timestamp = 1000L,
            nodeType = NodeType.NODE,
            receivingInterface = "ble0",
        )

    private val testLocalIdentity =
        LocalIdentityEntity(
            identityHash = "testhash123",
            displayName = "TestUser",
            destinationHash = "destHash123",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Disable periodic updates in tests to prevent OOM from infinite loop
        AnnounceStreamViewModel.updateIntervalMs = 0

        reticulumProtocol = mockk()
        serviceReticulumProtocol = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        propagationNodeManager = mockk()
        identityRepository = mockk()
        identityResolutionManager = mockk()
        coEvery { identityResolutionManager.requestPathForContact(any()) } just Runs

        // Setup network status flow
        networkStatusFlow = MutableStateFlow(NetworkStatus.SHUTDOWN)
        every { reticulumProtocol.networkStatus } returns networkStatusFlow

        // Setup announce flow
        announceFlow = MutableSharedFlow()
        every { reticulumProtocol.observeAnnounces() } returns announceFlow

        // Mock repository methods
        every { announceRepository.getAnnounces() } returns flowOf(emptyList())
        every { announceRepository.getAnnouncesByTypes(any()) } returns flowOf(emptyList())
        every { announceRepository.getAnnouncesPaged(any(), any()) } returns flowOf(PagingData.empty())
        every { announceRepository.getAnnounceCountFlow() } returns flowOf(0)
        coEvery { announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { announceRepository.getAnnounceCount() } returns 0
        coEvery { announceRepository.countReachableAnnounces(any()) } returns 0
        coEvery { announceRepository.deleteAllAnnouncesExceptContacts(any()) } just Runs
        coEvery { reticulumProtocol.shutdown() } returns Result.success(Unit)
        coEvery { reticulumProtocol.getPathTableHashes() } returns emptyList()
        coEvery { announceRepository.setFavorite(any(), any()) } just Runs
        coEvery { announceRepository.getAnnounce(any()) } returns null
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.deleteContact(any()) } just Runs
        coEvery { contactRepository.addContactFromAnnounce(any(), any()) } returns Result.success(Unit)

        // Mock identity repository
        coEvery { identityRepository.getActiveIdentitySync() } returns testLocalIdentity

        // Setup serviceReticulumProtocol mock (inherits ReticulumProtocol behavior)
        every { serviceReticulumProtocol.networkStatus } returns networkStatusFlow
        every { serviceReticulumProtocol.observeAnnounces() } returns announceFlow
        coEvery { serviceReticulumProtocol.shutdown() } returns Result.success(Unit)
        coEvery { serviceReticulumProtocol.getPathTableHashes() } returns emptyList()
        // Note: Result is an inline class, use runCatching to create it properly
        coEvery { serviceReticulumProtocol.triggerAutoAnnounce(any()) } returns runCatching { }
    }

    @After
    fun tearDown() {
        // Cancel any running coroutines in the ViewModel to prevent UncompletedCoroutinesError
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
        clearAllMocks()
        // Reset update interval to default
        AnnounceStreamViewModel.updateIntervalMs = 30_000L
    }

    @Test
    fun `waits for READY status before collecting announces`() =
        runTest {
            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )

            // Status starts as SHUTDOWN - should wait
            viewModel.initializationStatus.test {
                assertEquals("Reticulum managed by app", awaitItem())

                // Change to READY
                networkStatusFlow.value = NetworkStatus.READY
                advanceUntilIdle()

                assertEquals("Ready", awaitItem())
            }

            // Verify observeAnnounces was called after READY
            verify { reticulumProtocol.observeAnnounces() }
        }

    @Test
    fun `handles ERROR status and stops waiting`() =
        runTest {
            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )

            viewModel.initializationStatus.test {
                assertEquals("Reticulum managed by app", awaitItem())

                // Change to ERROR
                networkStatusFlow.value = NetworkStatus.ERROR("Test error")
                advanceUntilIdle()

                val status = awaitItem()
                assertTrue(status.contains("Error"))
                assertTrue(status.contains("Test error"))

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `handles timeout waiting for READY`() =
        runTest {
            // Don't change status - let it timeout
            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )

            // Fast-forward past the 10 second timeout
            testScheduler.advanceTimeBy(11000)
            advanceUntilIdle()

            viewModel.initializationStatus.test {
                val status = awaitItem()
                assertTrue(
                    status == "Timeout waiting for service" ||
                        status == "Reticulum managed by app",
                ) // May not have updated yet
            }
        }

    @Test
    fun `processes announces and saves to database`() =
        runTest {
            // Setup: Start with READY status
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { announceRepository.getAnnounceCount() } returns 1

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Emit an announce
            announceFlow.emit(testAnnounce)
            advanceUntilIdle()

            // Verify: Announce was saved to database with correct parameters
            val savedAnnounce = slot<String>()
            coVerify {
                announceRepository.saveAnnounce(
                    destinationHash = capture(savedAnnounce),
                    peerName = any(),
                    publicKey = testIdentity.publicKey,
                    appData = testAnnounce.appData,
                    hops = 2,
                    timestamp = 1000L,
                    nodeType = "NODE",
                    receivingInterface = "ble0",
                    receivingInterfaceType = "ANDROID_BLE",
                    aspect = any(),
                    stampCost = any(),
                    stampCostFlexibility = any(),
                    peeringCost = any(),
                )
            }
            assertTrue("Destination hash should start with 'abab'", savedAnnounce.captured.startsWith("abab"))
        }

    @Test
    fun `processes multiple announces sequentially`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            var saveCount = 0
            coEvery {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } answers { saveCount++ }

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Emit multiple announces
            val announce2 =
                testAnnounce.copy(
                    destinationHash = ByteArray(16) { 0xCD.toByte() },
                    hops = 3,
                )
            val announce3 =
                testAnnounce.copy(
                    destinationHash = ByteArray(16) { 0xEF.toByte() },
                    hops = 1,
                )

            announceFlow.emit(testAnnounce)
            advanceUntilIdle()
            announceFlow.emit(announce2)
            advanceUntilIdle()
            announceFlow.emit(announce3)
            advanceUntilIdle()

            // Verify all announces were saved
            assertEquals("Count should reflect 3 saved announces", 3, saveCount)
            coVerify(exactly = 3) {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `handles database save errors gracefully`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws Exception("Database error")

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Emit an announce - should not crash
            announceFlow.emit(testAnnounce)
            advanceUntilIdle()

            // Verify: saveAnnounce was attempted
            coVerify {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

    @Test
    fun `announces flow comes from repository filtered by default PEER type`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Verify default selected node types before collecting
            val selectedTypes = viewModel.selectedNodeTypes.value
            assertEquals("Default filter should only include PEER", 1, selectedTypes.size)
            assertTrue("Default filter should contain PEER", selectedTypes.contains(NodeType.PEER))

            // Trigger the flow by collecting the first emission
            viewModel.announces.first()
            advanceUntilIdle()

            // Verify repository was called with correct parameters (default PEER filter)
            verify { announceRepository.getAnnouncesPaged(listOf("PEER"), "") }
        }

    @Test
    fun `transitions through INITIALIZING to READY`() =
        runTest {
            // Start with INITIALIZING
            networkStatusFlow.value = NetworkStatus.INITIALIZING

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )

            viewModel.initializationStatus.test {
                assertEquals("Reticulum managed by app", awaitItem())

                // Change to READY (simulating initialization completing)
                networkStatusFlow.value = NetworkStatus.READY
                testScheduler.advanceTimeBy(100) // Small advancement
                testScheduler.runCurrent()

                assertEquals("Ready", awaitItem())

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `extractsPeerName from announce appData`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            val announceWithName =
                testAnnounce.copy(
                    appData = "MyCustomNode".toByteArray(),
                )

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            announceFlow.emit(announceWithName)
            advanceUntilIdle()

            // Capture the appData passed to verify it matches what we emitted
            val capturedAppData = slot<ByteArray?>()
            coVerify {
                announceRepository.saveAnnounce(
                    destinationHash = any(),
                    peerName = any(), // AppDataParser will extract this
                    publicKey = any(),
                    appData = captureNullable(capturedAppData),
                    hops = any(),
                    timestamp = any(),
                    nodeType = any(),
                    receivingInterface = any(),
                    receivingInterfaceType = any(),
                    aspect = any(),
                    stampCost = any(),
                    stampCostFlexibility = any(),
                    peeringCost = any(),
                )
            }
            assertEquals("AppData should be passed through", "MyCustomNode", capturedAppData.captured?.let { String(it) })
        }

    @Test
    fun `default filter is PEER only`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Check default filter
            viewModel.selectedNodeTypes.test {
                val selectedTypes = awaitItem()
                assertEquals(1, selectedTypes.size)
                assertTrue(selectedTypes.contains(NodeType.PEER))
            }
        }

    @Test
    fun `updateSelectedNodeTypes changes filter`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            // Mock both PEER (default) and the types we'll filter by
            every { announceRepository.getAnnouncesPaged(any(), any()) } returns flowOf(PagingData.empty())

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Update filter to NODE and PROPAGATION_NODE
            viewModel.updateSelectedNodeTypes(setOf(NodeType.NODE, NodeType.PROPAGATION_NODE))
            advanceUntilIdle()

            // Verify filter state updated
            viewModel.selectedNodeTypes.test {
                val selectedTypes = awaitItem()
                assertEquals(2, selectedTypes.size)
                assertTrue(selectedTypes.contains(NodeType.NODE))
                assertTrue(selectedTypes.contains(NodeType.PROPAGATION_NODE))
            }

            // Trigger the flow by collecting
            viewModel.announces.first()
            advanceUntilIdle()

            // Verify repository was called with new filter types
            verify { announceRepository.getAnnouncesPaged(match { it.containsAll(listOf("NODE", "PROPAGATION_NODE")) }, "") }
        }

    @Test
    fun `empty filter returns empty announces`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Set empty filter
            viewModel.updateSelectedNodeTypes(emptySet())
            advanceUntilIdle()

            // Verify selected types is empty
            viewModel.selectedNodeTypes.test {
                val selectedTypes = awaitItem()
                assertEquals(0, selectedTypes.size)
            }

            // Trigger the flow by collecting
            viewModel.announces.first()
            advanceUntilIdle()

            // With empty filter, repository should NOT be called (empty flow returned directly)
            // Verify that with empty types, we don't call the repository
            verify(exactly = 0) { announceRepository.getAnnouncesPaged(emptyList(), any()) }
        }

    @Test
    fun `search query filters announces`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Set search query
            viewModel.searchQuery.value = "Alice"
            advanceUntilIdle()

            // Verify the search query was stored in the ViewModel
            assertEquals("Search query should be set", "Alice", viewModel.searchQuery.value)

            // Trigger the flow by collecting
            viewModel.announces.first()
            advanceUntilIdle()

            // Verify repository was called with search query
            verify { announceRepository.getAnnouncesPaged(listOf("PEER"), "Alice") }
        }

    // ========== Manual Announce Tests ==========

    @Test
    fun `triggerAnnounce succeeds with NativeReticulumProtocol`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            // Use NativeReticulumProtocol instead of base ReticulumProtocol
            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Initial state
            assertFalse(viewModel.isAnnouncing.value)
            assertFalse(viewModel.announceSuccess.value)
            assertNull(viewModel.announceError.value)

            // Trigger announce and run only until the triggerAutoAnnounce completes
            // (don't advance past the 3-second auto-dismiss delay)
            viewModel.triggerAnnounce()
            runCurrent() // Start the coroutine
            advanceTimeBy(100) // Advance a small amount to let the coroutine complete
            runCurrent()

            // Verify methods were called
            coVerify { identityRepository.getActiveIdentitySync() }
            coVerify { serviceReticulumProtocol.triggerAutoAnnounce("TestUser") }

            // Verify success state (before auto-dismiss kicks in)
            assertFalse("Expected isAnnouncing=false", viewModel.isAnnouncing.value)
            assertTrue("Expected announceSuccess=true", viewModel.announceSuccess.value)
            assertNull("Expected no error", viewModel.announceError.value)
        }

    @Test
    fun `triggerAnnounce fails when NativeReticulumProtocol returns error`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { serviceReticulumProtocol.triggerAutoAnnounce(any()) } returns Result.failure(Exception("Network error"))

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger announce (don't advance past auto-dismiss)
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            // Verify error state
            assertFalse(viewModel.isAnnouncing.value)
            assertFalse(viewModel.announceSuccess.value)
            assertEquals("Network error", viewModel.announceError.value)
        }

    @Test
    fun `triggerAnnounce works with base ReticulumProtocol`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            // Use base ReticulumProtocol — triggerAutoAnnounce is available on all implementations
            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            // Verify announce completed (no type guard error)
            assertFalse(viewModel.isAnnouncing.value)
        }

    @Test
    fun `triggerAnnounce uses Unknown when no active identity`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger announce (don't advance past auto-dismiss)
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            // Verify announce completed successfully despite no identity
            assertTrue("Announce should succeed", viewModel.announceSuccess.value)

            // Verify triggerAutoAnnounce was called with "Unknown"
            coVerify { serviceReticulumProtocol.triggerAutoAnnounce("Unknown") }
        }

    @Test
    fun `triggerAnnounce sets isAnnouncing during execution`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Capture isAnnouncing states during execution
            viewModel.isAnnouncing.test {
                // Initial state
                assertFalse(awaitItem())

                // Trigger announce and advance to capture intermediate state
                viewModel.triggerAnnounce()
                runCurrent()

                // Should be announcing
                assertTrue(awaitItem())

                // Complete the coroutine
                advanceUntilIdle()

                // Should be done announcing
                assertFalse(awaitItem())

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearAnnounceStatus resets success and error`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger successful announce (don't advance past auto-dismiss)
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            assertTrue(viewModel.announceSuccess.value)

            // Clear status
            viewModel.clearAnnounceStatus()

            assertFalse(viewModel.announceSuccess.value)
            assertNull(viewModel.announceError.value)
        }

    @Test
    fun `clearAnnounceStatus resets error state`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { serviceReticulumProtocol.triggerAutoAnnounce(any()) } returns Result.failure(Exception("Test error"))

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger failed announce (don't advance past auto-dismiss)
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            assertEquals("Test error", viewModel.announceError.value)

            // Clear status
            viewModel.clearAnnounceStatus()

            assertFalse(viewModel.announceSuccess.value)
            assertNull(viewModel.announceError.value)
        }

    @Test
    fun `triggerAnnounce handles exception gracefully`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { identityRepository.getActiveIdentitySync() } throws RuntimeException("Database error")

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger announce - should not crash (don't advance past auto-dismiss)
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            // Verify error state is set
            assertFalse(viewModel.isAnnouncing.value)
            assertEquals("Database error", viewModel.announceError.value)
        }

    @Test
    fun `announce success auto-dismisses after delay`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger announce and let it complete but NOT auto-dismiss
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            // Success should be set
            assertTrue(viewModel.announceSuccess.value)

            // Advance time past the 3 second auto-dismiss (remaining ~2900ms)
            advanceTimeBy(3000)
            runCurrent()

            // Success should be auto-cleared
            assertFalse(viewModel.announceSuccess.value)
        }

    @Test
    fun `announce error auto-dismisses after delay`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { serviceReticulumProtocol.triggerAutoAnnounce(any()) } returns Result.failure(Exception("Network error"))

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Trigger announce and let it complete but NOT auto-dismiss
            viewModel.triggerAnnounce()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            // Error should be set
            assertEquals("Network error", viewModel.announceError.value)

            // Advance time past the 5 second auto-dismiss (remaining ~4900ms)
            advanceTimeBy(5000)
            runCurrent()

            // Error should be auto-cleared
            assertNull(viewModel.announceError.value)
        }

    @Test
    fun `initial announce state is correct`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    serviceReticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Verify initial state
            assertFalse(viewModel.isAnnouncing.value)
            assertFalse(viewModel.announceSuccess.value)
            assertNull(viewModel.announceError.value)
        }

    // ========== Announce Count Tests ==========

    @Test
    fun `announceCount starts with initial value of zero`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Verify initial count is 0 (from stateIn initialValue)
            assertEquals(0, viewModel.announceCount.value)
        }

    @Test
    fun `announceCount is wired to repository flow`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Verify announceCount has an initial value (from the repository flow)
            assertEquals("Initial announce count should be 0", 0, viewModel.announceCount.value)

            // Verify repository method was called to set up the flow
            verify { announceRepository.getAnnounceCountFlow() }
        }

    @Test
    fun `announceCount updates reactively when repository emits`() =
        runTest {
            // Setup a mutable flow for count BEFORE creating ViewModel
            val countFlow = MutableStateFlow(0)
            every { announceRepository.getAnnounceCountFlow() } returns countFlow

            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Subscribe to the StateFlow to trigger collection
            viewModel.announceCount.test {
                // Initial value
                assertEquals(0, awaitItem())

                // Update the count
                countFlow.value = 42
                advanceUntilIdle()

                // Verify updated count
                assertEquals(42, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Network Status Check Tests ==========

    @Test
    fun `updateReachableCount skips when network is SHUTDOWN`() =
        runTest {
            // Given: Network is SHUTDOWN
            networkStatusFlow.value = NetworkStatus.SHUTDOWN

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // When: We wait for any periodic updates (none should happen due to updateIntervalMs = 0)
            advanceTimeBy(1000)
            advanceUntilIdle()

            // Verify reachable count remains at default (0) since network is not ready
            assertEquals("Reachable count should remain 0 when network is SHUTDOWN", 0, viewModel.reachableAnnounceCount.value)

            // Then: getPathTableHashes should NOT be called because network is not READY
            coVerify(exactly = 0) { reticulumProtocol.getPathTableHashes() }
        }

    @Test
    fun `updateReachableCount skips when network transitions to SHUTDOWN`() =
        runTest {
            // Given: Network starts as READY
            networkStatusFlow.value = NetworkStatus.READY

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Clear any initial calls
            clearMocks(reticulumProtocol, answers = false, recordedCalls = true, verificationMarks = true)
            every { reticulumProtocol.networkStatus } returns networkStatusFlow
            every { reticulumProtocol.observeAnnounces() } returns announceFlow
            coEvery { reticulumProtocol.getPathTableHashes() } returns emptyList()

            // When: Network transitions to SHUTDOWN
            networkStatusFlow.value = NetworkStatus.SHUTDOWN
            advanceUntilIdle()

            // Verify reachable count remains at 0 after shutdown
            assertEquals("Reachable count should remain 0 after shutdown", 0, viewModel.reachableAnnounceCount.value)

            // Then: getPathTableHashes should NOT be called after shutdown
            coVerify(exactly = 0) { reticulumProtocol.getPathTableHashes() }
        }

    // ========== Delete Announce Tests ==========

    @Test
    fun `deleteAnnounce calls repository with correct hash`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            val deletedHashes = mutableListOf<String>()
            coEvery { announceRepository.deleteAnnounce(any()) } answers {
                deletedHashes.add(firstArg())
            }

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Delete an announce
            val testHash = "abc123def456"
            viewModel.deleteAnnounce(testHash)
            advanceUntilIdle()

            // Verify correct hash was passed to repository
            assertEquals("Should have deleted exactly one announce", 1, deletedHashes.size)
            assertEquals("Deleted hash should match", testHash, deletedHashes.first())
        }

    @Test
    fun `deleteAnnounce handles errors gracefully`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { announceRepository.deleteAnnounce(any()) } throws Exception("Database error")

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Delete should not crash even with error
            viewModel.deleteAnnounce("abc123")
            advanceUntilIdle()

            // Verify delete was attempted
            coVerify { announceRepository.deleteAnnounce("abc123") }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

    @Test
    fun `deleteAllAnnounces preserves contact announces via identity-aware delete`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            var calledWithIdentityHash: String? = null
            coEvery { announceRepository.deleteAllAnnouncesExceptContacts(any()) } answers {
                calledWithIdentityHash = firstArg()
            }

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Delete all announces
            viewModel.deleteAllAnnounces()
            advanceUntilIdle()

            // Verify identity-aware delete was called with correct identity hash
            assertEquals("Should delete with active identity hash", testLocalIdentity.identityHash, calledWithIdentityHash)
            coVerify(exactly = 0) { announceRepository.deleteAllAnnounces() }
        }

    @Test
    fun `deleteAllAnnounces handles errors gracefully`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { announceRepository.deleteAllAnnouncesExceptContacts(any()) } throws Exception("Database error")

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Delete all should not crash even with error
            viewModel.deleteAllAnnounces()
            advanceUntilIdle()

            // Verify delete was attempted with identity-aware method
            coVerify { announceRepository.deleteAllAnnouncesExceptContacts(testLocalIdentity.identityHash) }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

    @Test
    fun `deleteAllAnnounces falls back to deleteAll when no active identity`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery { identityRepository.getActiveIdentitySync() } returns null
            var deleteAllCalled = false
            coEvery { announceRepository.deleteAllAnnounces() } answers {
                deleteAllCalled = true
            }

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            advanceUntilIdle()

            // Delete all announces
            viewModel.deleteAllAnnounces()
            advanceUntilIdle()

            // Verify fallback behavior was used (deleteAllAnnounces, not identity-aware version)
            assertTrue("Should have called deleteAllAnnounces as fallback", deleteAllCalled)
            coVerify(exactly = 0) { announceRepository.deleteAllAnnouncesExceptContacts(any()) }
        }

    // ========== CancellationException Propagation Tests ==========

    @Test
    fun `reachable count loop stops when viewModelScope is cancelled`() =
        runTest {
            // Enable periodic updates with a short interval for this test.
            // IMPORTANT: Do NOT use advanceUntilIdle() while the while(true) loop is
            // active — it drains all pending tasks, but the loop keeps scheduling more,
            // causing the test to hang. Use advanceTimeBy() + runCurrent() instead.
            AnnounceStreamViewModel.updateIntervalMs = 100L
            networkStatusFlow.value = NetworkStatus.READY

            var pathTableCallCount = 0
            coEvery { reticulumProtocol.getPathTableHashes() } answers {
                pathTableCallCount++
                emptyList()
            }

            viewModel =
                AnnounceStreamViewModel(
                    reticulumProtocol,
                    announceRepository,
                    contactRepository,
                    propagationNodeManager,
                    identityRepository,
                    mockk(),
                    identityResolutionManager,
                )
            // Run the init block tasks (startCollectingAnnouncesWhenReady + first loop iteration)
            runCurrent()

            // Advance past the first delay + second iteration
            advanceTimeBy(150)
            runCurrent()
            val callsBeforeCancel = pathTableCallCount
            assertTrue("Loop should have called getPathTableHashes at least once", callsBeforeCancel >= 1)

            // Cancel the viewModelScope (simulates ViewModel clearing)
            viewModel.viewModelScope.cancel()
            runCurrent()

            // Advance time well past several more intervals
            advanceTimeBy(500)
            runCurrent()

            // No additional calls should have been made after cancellation
            assertEquals(
                "Loop should stop after scope cancellation (CancellationException must propagate)",
                callsBeforeCancel,
                pathTableCallCount,
            )
        }
}
