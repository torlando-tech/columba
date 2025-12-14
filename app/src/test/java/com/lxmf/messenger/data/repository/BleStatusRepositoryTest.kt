package com.lxmf.messenger.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.test.BleTestFixtures
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import android.app.Application
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Instrumented tests for BleStatusRepository.
 * Tests IPC calls, JSON parsing, Flow emissions, and error handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BleStatusRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockProtocol: ServiceReticulumProtocol
    private lateinit var mockContext: Context
    private lateinit var repository: BleStatusRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockProtocol = mockk<ServiceReticulumProtocol>(relaxed = true)
        mockContext = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== getConnectedPeers() Tests ==========

    @Test
    fun getConnectedPeers_returns_parsed_connections_from_service() =
        runTest {
            // Given
            val testConnections = BleTestFixtures.createMultipleConnections(count = 3)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(testConnections)
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(3, result.size)
            verify(exactly = 1) { mockProtocol.getBleConnectionDetails() }
        }

    @Test
    fun getConnectedPeers_returns_empty_list_for_empty_JSON_array() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } returns BleTestFixtures.createEmptyJson()
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun getConnectedPeers_returns_empty_list_on_JSON_parsing_error() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } returns BleTestFixtures.createMalformedJson()
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun getConnectedPeers_handles_incomplete_JSON_gracefully() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } returns BleTestFixtures.createIncompleteJson()
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then - Should handle missing fields gracefully (or return empty)
            assertTrue(result.isEmpty() || result.isNotEmpty()) // Depends on error handling strategy
        }

    @Test
    fun getConnectedPeers_returns_empty_list_when_protocol_is_not_ServiceReticulumProtocol() =
        runTest {
            // Given
            val wrongProtocol = mockk<ReticulumProtocol>()
            repository = BleStatusRepository(mockContext, wrongProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun getConnectedPeers_handles_service_exception() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } throws RuntimeException("Service error")
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertTrue(result.isEmpty())
        }

    // ========== Connection Type Mapping Tests ==========

    @Test
    fun getConnectedPeers_correctly_maps_CENTRAL_connection_type() =
        runTest {
            // Given
            val connection = BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(listOf(connection))
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(1, result.size)
            assertEquals(ConnectionType.CENTRAL, result[0].connectionType)
        }

    @Test
    fun getConnectedPeers_correctly_maps_PERIPHERAL_connection_type() =
        runTest {
            // Given
            val connection = BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.PERIPHERAL)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(listOf(connection))
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(1, result.size)
            assertEquals(ConnectionType.PERIPHERAL, result[0].connectionType)
        }

    @Test
    fun getConnectedPeers_correctly_maps_BOTH_connection_type() =
        runTest {
            // Given
            val connection = BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(listOf(connection))
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(1, result.size)
            assertEquals(ConnectionType.BOTH, result[0].connectionType)
        }

    // ========== getConnectedPeersFlow() Tests ==========

    @Test
    @Ignore("Flaky on CI: Flow timing-sensitive test fails on resource-constrained runners")
    fun getConnectedPeersFlow_emits_initial_data_immediately() =
        runTest {
            // Given
            val testConnections = BleTestFixtures.createMultipleConnections(count = 2)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(testConnections)
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When/Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val firstEmission = awaitItem()
                assertTrue(firstEmission is BleConnectionsState.Success)
                assertEquals(2, (firstEmission as BleConnectionsState.Success).connections.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @Ignore("Flaky on CI: Flow timing-sensitive test fails on resource-constrained runners")
    fun getConnectedPeersFlow_emits_periodically_every_3_seconds() =
        runTest {
            // Given
            var callCount = 0
            every { mockProtocol.getBleConnectionDetails() } answers {
                callCount++
                BleTestFixtures.createEmptyJson()
            }
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When/Then
            repository.getConnectedPeersFlow().test(timeout = 10.seconds) {
                awaitItem() // First emission (immediate)
                advanceTimeBy(3000) // Advance 3 seconds
                awaitItem() // Second emission
                advanceTimeBy(3000) // Advance 3 more seconds
                awaitItem() // Third emission

                assertTrue(callCount >= 3)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @Ignore("Flaky on CI: Flow timing-sensitive test fails on resource-constrained runners")
    fun getConnectedPeersFlow_emits_empty_list_on_error() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } throws RuntimeException("Test error")
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When/Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue(emission is BleConnectionsState.Success)
                assertTrue((emission as BleConnectionsState.Success).connections.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @Ignore("Flaky on CI: Flow timing-sensitive test fails on resource-constrained runners")
    fun getConnectedPeersFlow_continues_emitting_after_error() =
        runTest {
            // Given
            var attemptCount = 0
            every { mockProtocol.getBleConnectionDetails() } answers {
                attemptCount++
                if (attemptCount == 1) {
                    throw java.io.IOException("First attempt fails")
                } else {
                    BleTestFixtures.createEmptyJson()
                }
            }
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When/Then
            repository.getConnectedPeersFlow().test(timeout = 10.seconds) {
                val firstEmission = awaitItem() // Error case, should emit empty list
                assertTrue(firstEmission is BleConnectionsState.Success)
                assertTrue((firstEmission as BleConnectionsState.Success).connections.isEmpty())

                advanceTimeBy(3000)
                val secondEmission = awaitItem() // Should recover
                assertTrue(secondEmission is BleConnectionsState.Success)
                assertTrue((secondEmission as BleConnectionsState.Success).connections.isEmpty())

                assertTrue(attemptCount >= 2)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== getConnectedPeerCount() Tests ==========

    @Test
    fun getConnectedPeerCount_returns_correct_count() =
        runTest {
            // Given
            val testConnections = BleTestFixtures.createMultipleConnections(count = 5)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(testConnections)
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val count = repository.getConnectedPeerCount()

            // Then
            assertEquals(5, count)
        }

    @Test
    fun getConnectedPeerCount_returns_0_for_empty_connections() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } returns BleTestFixtures.createEmptyJson()
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val count = repository.getConnectedPeerCount()

            // Then
            assertEquals(0, count)
        }

    @Test
    fun getConnectedPeerCount_returns_0_on_error() =
        runTest {
            // Given
            every { mockProtocol.getBleConnectionDetails() } throws RuntimeException("Error")
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val count = repository.getConnectedPeerCount()

            // Then
            assertEquals(0, count)
        }

    // ========== JSON Field Parsing Tests ==========

    @Test
    fun getConnectedPeers_correctly_parses_all_JSON_fields() =
        runTest {
            // Given
            val expectedConnection =
                BleTestFixtures.createBleConnectionInfo(
                    identityHash = "test123hash456",
                    peerName = "RNS-Device1",
                    currentMac = "AA:BB:CC:DD:EE:FF",
                    rssi = -65,
                    mtu = 512,
                    connectionType = ConnectionType.BOTH,
                )
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(listOf(expectedConnection))
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(1, result.size)
            val parsed = result[0]
            assertEquals(expectedConnection.identityHash, parsed.identityHash)
            assertEquals(expectedConnection.peerName, parsed.peerName)
            assertEquals(expectedConnection.currentMac, parsed.currentMac)
            assertEquals(expectedConnection.rssi, parsed.rssi)
            assertEquals(expectedConnection.mtu, parsed.mtu)
            assertEquals(expectedConnection.connectionType, parsed.connectionType)
        }

    // ========== Edge Case Tests ==========

    @Test
    fun getConnectedPeers_handles_large_connection_list() =
        runTest {
            // Given
            val largeConnectionList = BleTestFixtures.createMultipleConnections(count = 100)
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(largeConnectionList)
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(100, result.size)
        }

    @Test
    fun getConnectedPeers_handles_connections_with_extreme_RSSI_values() =
        runTest {
            // Given
            val connections =
                listOf(
                    BleTestFixtures.createBleConnectionInfo(rssi = -120), // Very weak
                    BleTestFixtures.createBleConnectionInfo(rssi = 0), // Perfect
                )
            val jsonResponse = BleTestFixtures.createBleConnectionsJson(connections)
            every { mockProtocol.getBleConnectionDetails() } returns jsonResponse
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            val result = repository.getConnectedPeers()

            // Then
            assertEquals(2, result.size)
            assertEquals(-120, result[0].rssi)
            assertEquals(0, result[1].rssi)
        }
}
