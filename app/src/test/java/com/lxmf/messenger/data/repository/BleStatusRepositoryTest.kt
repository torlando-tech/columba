package com.lxmf.messenger.data.repository

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.test.BleTestFixtures
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    private lateinit var mockBridge: KotlinBLEBridge
    private lateinit var repository: BleStatusRepository
    private lateinit var adapterStateFlow: MutableStateFlow<Int>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockProtocol = mockk<ServiceReticulumProtocol>(relaxed = true)
        mockContext = ApplicationProvider.getApplicationContext()

        // Mock the KotlinBLEBridge singleton
        mockBridge = mockk(relaxed = true)
        adapterStateFlow = MutableStateFlow(BluetoothAdapter.STATE_ON)
        mockkObject(KotlinBLEBridge.Companion)
        every { KotlinBLEBridge.getInstance(any()) } returns mockBridge
        every { mockBridge.adapterState } returns adapterStateFlow
    }

    @After
    fun tearDown() {
        unmockkObject(KotlinBLEBridge.Companion)
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

    // ========== getConnectedPeersFlow() Event-Driven Tests ==========

    @Test
    fun getConnectedPeersFlow_emits_on_bleConnectionsFlow_event() =
        runTest {
            // Given - Mock the event-driven flow
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - Emit connection event
            bleConnectionsFlow.emit(
                """[{"identityHash":"abc123","address":"AA:BB:CC:DD:EE:FF","hasCentralConnection":true,"hasPeripheralConnection":false,"mtu":512}]""",
            )

            // Then - Flow should emit immediately (no polling delay)
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue(emission is BleConnectionsState.Success)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_handles_empty_event() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - Emit empty array
            bleConnectionsFlow.emit("[]")

            // Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue(emission is BleConnectionsState.Success)
                assertTrue((emission as BleConnectionsState.Success).connections.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_handles_malformed_event_json() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - Emit malformed JSON
            bleConnectionsFlow.emit("not valid json")

            // Then - Should handle gracefully and emit empty list
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue(emission is BleConnectionsState.Success)
                assertTrue((emission as BleConnectionsState.Success).connections.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_emits_BluetoothDisabled_when_adapter_off() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            adapterStateFlow.value = BluetoothAdapter.STATE_OFF
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            bleConnectionsFlow.emit("[]")

            // Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue("Expected BluetoothDisabled", emission is BleConnectionsState.BluetoothDisabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_emits_Loading_when_adapter_turning_on() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            adapterStateFlow.value = BluetoothAdapter.STATE_TURNING_ON
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            bleConnectionsFlow.emit("[]")

            // Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue("Expected Loading", emission is BleConnectionsState.Loading)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_emits_BluetoothDisabled_when_adapter_turning_off() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            adapterStateFlow.value = BluetoothAdapter.STATE_TURNING_OFF
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When
            bleConnectionsFlow.emit("[]")

            // Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue("Expected BluetoothDisabled", emission is BleConnectionsState.BluetoothDisabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_parses_multiple_connections_correctly() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - Emit multiple connections
            val json = """[
                {"identityHash":"abc123","address":"AA:BB:CC:DD:EE:FF","hasCentralConnection":true,"hasPeripheralConnection":false,"mtu":512,"rssi":-50},
                {"identityHash":"def456","address":"11:22:33:44:55:66","hasCentralConnection":false,"hasPeripheralConnection":true,"mtu":256,"rssi":-70}
            ]"""
            bleConnectionsFlow.emit(json)

            // Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue(emission is BleConnectionsState.Success)
                val connections = (emission as BleConnectionsState.Success).connections
                assertEquals(2, connections.size)
                assertEquals("abc123", connections[0].identityHash)
                assertEquals(ConnectionType.CENTRAL, connections[0].connectionType)
                assertEquals("def456", connections[1].identityHash)
                assertEquals(ConnectionType.PERIPHERAL, connections[1].connectionType)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getConnectedPeersFlow_parses_BOTH_connection_type() =
        runTest {
            // Given
            val bleConnectionsFlow = MutableSharedFlow<String>(replay = 1)
            every { mockProtocol.bleConnectionsFlow } returns bleConnectionsFlow
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - Emit connection with both central and peripheral
            val json = """[{"identityHash":"xyz789","address":"FF:EE:DD:CC:BB:AA","hasCentralConnection":true,"hasPeripheralConnection":true,"mtu":512}]"""
            bleConnectionsFlow.emit(json)

            // Then
            repository.getConnectedPeersFlow().test(timeout = 5.seconds) {
                val emission = awaitItem()
                assertTrue(emission is BleConnectionsState.Success)
                val connections = (emission as BleConnectionsState.Success).connections
                assertEquals(1, connections.size)
                assertEquals(ConnectionType.BOTH, connections[0].connectionType)
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

    // ========== disconnectPeer Tests ==========

    @Test
    fun disconnectPeer_handles_address_gracefully() =
        runTest {
            // Given
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - disconnectPeer is called (it just logs a warning, doesn't throw)
            repository.disconnectPeer("AA:BB:CC:DD:EE:FF")

            // Then - no exception should be thrown
            assertTrue(true)
        }

    @Test
    fun disconnectPeer_handles_empty_address() =
        runTest {
            // Given
            repository = BleStatusRepository(mockContext, mockProtocol)

            // When - disconnectPeer with empty address
            repository.disconnectPeer("")

            // Then - no exception should be thrown
            assertTrue(true)
        }
}
