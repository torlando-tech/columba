package com.lxmf.messenger.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.data.repository.BleStatusRepository
import com.lxmf.messenger.test.BleTestFixtures
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Instrumented tests for BleConnectionsViewModel.
 * Tests state management, Flow observation, statistics calculation, and user actions.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: BleStatusRepository
    private lateinit var viewModel: BleConnectionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mockk<BleStatusRepository>(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Initial State Tests ==========

    @Test
    fun initial_state_is_Loading() =
        runTest {
            // Given
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(emptyList()))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val initialState = awaitItem()
                assertTrue(initialState is BleConnectionsUiState.Loading)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun ViewModel_starts_observing_connections_on_init() =
        runTest {
            // Given
            val testConnections = BleTestFixtures.createMultipleConnections(count = 2)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(testConnections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem()
                assertTrue(state is BleConnectionsUiState.Success)
                assertEquals(2, (state as BleConnectionsUiState.Success).connections.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Flow Observation Tests ==========

    @Test
    fun uiState_transitions_from_Loading_to_Success_when_connections_received() =
        runTest {
            // Given
            val testConnections = BleTestFixtures.createMultipleConnections(count = 3)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(testConnections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem()
                assertTrue(state is BleConnectionsUiState.Success)
                assertEquals(3, (state as BleConnectionsUiState.Success).totalConnections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun uiState_transitions_to_Error_when_flow_throws_exception() =
        runTest {
            // Given
            every { mockRepository.getConnectedPeersFlow() } returns
                flow {
                    throw RuntimeException("Test error")
                }

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem()
                assertTrue(state is BleConnectionsUiState.Error)
                assertEquals("Test error", (state as BleConnectionsUiState.Error).message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun uiState_emits_multiple_times_as_flow_emits_new_data() =
        runTest {
            // Given
            val firstBatch = BleTestFixtures.createMultipleConnections(count = 1)
            val secondBatch = BleTestFixtures.createMultipleConnections(count = 3)
            every { mockRepository.getConnectedPeersFlow() } returns
                flow {
                    emit(BleConnectionsState.Success(firstBatch))
                    emit(BleConnectionsState.Success(secondBatch))
                }

            // When
            viewModel = BleConnectionsViewModel(mockRepository)

            // Then - start collecting before advancing time to catch all emissions
            viewModel.uiState.test(timeout = 5.seconds) {
                // Skip initial Loading state
                skipItems(1) // Loading state

                // Advance time to trigger flow emissions
                advanceUntilIdle()

                val firstState = awaitItem()
                assertTrue(firstState is BleConnectionsUiState.Success)
                assertEquals(1, (firstState as BleConnectionsUiState.Success).totalConnections)

                val secondState = awaitItem()
                assertTrue(secondState is BleConnectionsUiState.Success)
                assertEquals(3, (secondState as BleConnectionsUiState.Success).totalConnections)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Statistics Calculation Tests ==========

    @Test
    fun Success_state_calculates_correct_total_connections() =
        runTest {
            // Given
            val testConnections = BleTestFixtures.createMultipleConnections(count = 5)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(testConnections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(5, state.totalConnections)
                assertEquals(5, state.connections.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Success_state_calculates_correct_central_connections_count() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.PERIPHERAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH),
                )
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                // CENTRAL (2) + BOTH (1) = 3
                assertEquals(3, state.centralConnections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Success_state_calculates_correct_peripheral_connections_count() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.PERIPHERAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.PERIPHERAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH),
                )
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                // PERIPHERAL (2) + BOTH (1) = 3
                assertEquals(3, state.peripheralConnections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Success_state_counts_BOTH_connections_in_both_central_and_peripheral() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH),
                )
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(2, state.centralConnections)
                assertEquals(2, state.peripheralConnections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== RSSI Sorting Tests ==========

    @Test
    fun connections_are_sorted_by_RSSI_in_descending_order() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(rssi = -90, peerName = "Weak"),
                    BleTestFixtures.createBleConnectionInfo(rssi = -50, peerName = "Strong"),
                    BleTestFixtures.createBleConnectionInfo(rssi = -70, peerName = "Medium"),
                )
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                val sortedConnections = state.connections
                assertEquals(-50, sortedConnections[0].rssi) // Strongest first
                assertEquals(-70, sortedConnections[1].rssi)
                assertEquals(-90, sortedConnections[2].rssi) // Weakest last
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun sorting_handles_connections_with_equal_RSSI() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(rssi = -70, peerName = "Peer1"),
                    BleTestFixtures.createBleConnectionInfo(rssi = -70, peerName = "Peer2"),
                    BleTestFixtures.createBleConnectionInfo(rssi = -70, peerName = "Peer3"),
                )
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(3, state.connections.size)
                assertTrue(state.connections.all { it.rssi == -70 })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== refresh() Function Tests ==========

    @Test
    fun refresh_calls_repository_and_updates_state() =
        runTest {
            // Given
            val initialConnections = BleTestFixtures.createMultipleConnections(count = 2)
            val refreshedConnections = BleTestFixtures.createMultipleConnections(count = 4)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(initialConnections))
            coEvery { mockRepository.getConnectedPeers() } returns refreshedConnections

            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(4, state.totalConnections)
                coVerify(exactly = 1) { mockRepository.getConnectedPeers() }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun refresh_handles_exceptions_and_updates_to_Error_state() =
        runTest {
            // Given
            val initialConnections = BleTestFixtures.createMultipleConnections(count = 2)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(initialConnections))
            coEvery { mockRepository.getConnectedPeers() } throws RuntimeException("Refresh failed")

            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem()
                assertTrue(state is BleConnectionsUiState.Error)
                assertEquals("Refresh failed", (state as BleConnectionsUiState.Error).message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun refresh_can_be_called_multiple_times() =
        runTest {
            // Given
            val connections = BleTestFixtures.createMultipleConnections(count = 1)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))
            coEvery { mockRepository.getConnectedPeers() } returns connections

            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()

            // Then
            coVerify(exactly = 3) { mockRepository.getConnectedPeers() }
        }

    // ========== disconnectPeer() Function Tests ==========

    @Test
    fun disconnectPeer_calls_repository_with_correct_address() =
        runTest {
            // Given
            val testMac = "AA:BB:CC:DD:EE:FF"
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(emptyList()))
            coEvery { mockRepository.disconnectPeer(testMac) } returns Unit

            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When
            viewModel.disconnectPeer(testMac)
            advanceUntilIdle()

            // Then
            coVerify(exactly = 1) { mockRepository.disconnectPeer(testMac) }
        }

    @Test
    fun disconnectPeer_handles_exceptions_silently() =
        runTest {
            // Given
            val testMac = "AA:BB:CC:DD:EE:FF"
            val connections = BleTestFixtures.createMultipleConnections(count = 2)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))
            coEvery { mockRepository.disconnectPeer(testMac) } throws RuntimeException("Disconnect failed")

            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When
            viewModel.disconnectPeer(testMac)
            advanceUntilIdle()

            // Then - No error state change, exception is logged but not propagated
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem()
                assertTrue(state is BleConnectionsUiState.Success)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun disconnectPeer_can_be_called_on_multiple_peers() =
        runTest {
            // Given
            val mac1 = "AA:BB:CC:DD:EE:01"
            val mac2 = "AA:BB:CC:DD:EE:02"
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(emptyList()))
            coEvery { mockRepository.disconnectPeer(any()) } returns Unit

            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When
            viewModel.disconnectPeer(mac1)
            viewModel.disconnectPeer(mac2)
            advanceUntilIdle()

            // Then
            coVerify(exactly = 1) { mockRepository.disconnectPeer(mac1) }
            coVerify(exactly = 1) { mockRepository.disconnectPeer(mac2) }
        }

    // ========== Edge Case Tests ==========

    @Test
    fun Success_state_handles_empty_connections_list() =
        runTest {
            // Given
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(emptyList()))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(0, state.totalConnections)
                assertEquals(0, state.centralConnections)
                assertEquals(0, state.peripheralConnections)
                assertTrue(state.connections.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Success_state_handles_single_connection() =
        runTest {
            // Given
            val connection = listOf(BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL))
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connection))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(1, state.totalConnections)
                assertEquals(1, state.centralConnections)
                assertEquals(0, state.peripheralConnections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Success_state_handles_large_number_of_connections() =
        runTest {
            // Given
            val largeConnectionList = BleTestFixtures.createMultipleConnections(count = 100)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(largeConnectionList))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(100, state.totalConnections)
                assertEquals(100, state.connections.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Success_state_handles_mixed_connection_types() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.PERIPHERAL),
                    BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH),
                )
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Success
                assertEquals(3, state.totalConnections)
                // CENTRAL (1) + BOTH (1) = 2
                assertEquals(2, state.centralConnections)
                // PERIPHERAL (1) + BOTH (1) = 2
                assertEquals(2, state.peripheralConnections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Error_state_preserves_error_message_from_exception() =
        runTest {
            // Given
            val errorMessage = "Custom network error"
            every { mockRepository.getConnectedPeersFlow() } returns
                flow {
                    throw RuntimeException(errorMessage)
                }

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Error
                assertEquals(errorMessage, state.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun Error_state_handles_exception_with_null_message() =
        runTest {
            // Given
            every { mockRepository.getConnectedPeersFlow() } returns
                flow {
                    throw RuntimeException(null as String?)
                }

            // When
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // Then
            viewModel.uiState.test(timeout = 5.seconds) {
                val state = awaitItem() as BleConnectionsUiState.Error
                assertEquals("Unknown error", state.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== StateFlow Behavior Tests ==========

    @Test
    fun uiState_is_exposed_as_immutable_StateFlow() =
        runTest {
            // Given
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(emptyList()))
            viewModel = BleConnectionsViewModel(mockRepository)

            // Then - Should be StateFlow, not MutableStateFlow
            assertTrue(viewModel.uiState is StateFlow)
        }

    @Test
    fun multiple_collectors_receive_same_state_updates() =
        runTest {
            // Given
            val connections = BleTestFixtures.createMultipleConnections(count = 2)
            every { mockRepository.getConnectedPeersFlow() } returns flowOf(BleConnectionsState.Success(connections))
            viewModel = BleConnectionsViewModel(mockRepository)
            advanceUntilIdle()

            // When/Then - Multiple collectors should see the same state
            viewModel.uiState.test(timeout = 5.seconds) {
                val state1 = awaitItem()
                assertTrue(state1 is BleConnectionsUiState.Success)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.uiState.test(timeout = 5.seconds) {
                val state2 = awaitItem()
                assertTrue(state2 is BleConnectionsUiState.Success)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
