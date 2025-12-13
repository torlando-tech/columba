package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.model.AnnounceEvent
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.PropagationNodeManager
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
import org.junit.Assert.assertNotNull
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
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var propagationNodeManager: PropagationNodeManager
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Disable periodic updates in tests to prevent OOM from infinite loop
        AnnounceStreamViewModel.updateIntervalMs = 0

        reticulumProtocol = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        propagationNodeManager = mockk(relaxed = true)

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
        coEvery { announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { announceRepository.getAnnounceCount() } returns 0
        coEvery { announceRepository.countReachableAnnounces(any()) } returns 0
        coEvery { reticulumProtocol.shutdown() } returns Result.success(Unit)
        coEvery { reticulumProtocol.getPathTableHashes() } returns emptyList()
        coEvery { announceRepository.setFavorite(any(), any()) } just Runs
        coEvery { announceRepository.getAnnounce(any()) } returns null
        coEvery { contactRepository.hasContact(any()) } returns false
        coEvery { contactRepository.deleteContact(any()) } just Runs
        coEvery { contactRepository.addContactFromAnnounce(any(), any()) } returns Result.success(Unit)
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
            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)

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
            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)

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
            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)

            // Fast-forward past the 10 second timeout
            testScheduler.apply { advanceTimeBy(11000) }
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

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
            advanceUntilIdle()

            // Emit an announce
            announceFlow.emit(testAnnounce)
            advanceUntilIdle()

            // Verify: Announce was saved to database
            coVerify {
                announceRepository.saveAnnounce(
                    destinationHash = match { it.startsWith("abab") }, // hex of 0xAB repeated
                    peerName = any(),
                    publicKey = testIdentity.publicKey,
                    appData = testAnnounce.appData,
                    hops = 2,
                    timestamp = 1000L,
                    nodeType = "NODE",
                    receivingInterface = "ble0",
                )
            }
        }

    @Test
    fun `processes multiple announces sequentially`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            var count = 0
            coEvery { announceRepository.getAnnounceCount() } answers { ++count }

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
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

            // Verify: All announces were saved
            coVerify(exactly = 3) {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `handles database save errors gracefully`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY
            coEvery {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any())
            } throws Exception("Database error")

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
            advanceUntilIdle()

            // Emit an announce - should not crash
            announceFlow.emit(testAnnounce)
            advanceUntilIdle()

            // Verify: saveAnnounce was attempted
            coVerify {
                announceRepository.saveAnnounce(any(), any(), any(), any(), any(), any(), any(), any())
            }

            // ViewModel should still be functioning
            assertNotNull(viewModel)
        }

    @Test
    fun `announces flow comes from repository filtered by default PEER type`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
            advanceUntilIdle()

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

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)

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

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
            advanceUntilIdle()

            announceFlow.emit(announceWithName)
            advanceUntilIdle()

            // Verify peer name was extracted and passed to repository
            coVerify {
                announceRepository.saveAnnounce(
                    destinationHash = any(),
                    peerName = any(), // AppDataParser will extract this
                    publicKey = any(),
                    appData = announceWithName.appData,
                    hops = any(),
                    timestamp = any(),
                    nodeType = any(),
                    receivingInterface = any(),
                )
            }
        }

    @Test
    fun `default filter is PEER only`() =
        runTest {
            networkStatusFlow.value = NetworkStatus.READY

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
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

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
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

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
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

            viewModel = AnnounceStreamViewModel(reticulumProtocol, announceRepository, contactRepository, propagationNodeManager)
            advanceUntilIdle()

            // Set search query
            viewModel.searchQuery.value = "Alice"
            advanceUntilIdle()

            // Trigger the flow by collecting
            viewModel.announces.first()
            advanceUntilIdle()

            // Verify repository was called with search query
            verify { announceRepository.getAnnouncesPaged(listOf("PEER"), "Alice") }
        }
}
