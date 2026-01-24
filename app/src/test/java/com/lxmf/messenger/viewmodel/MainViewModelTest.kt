package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.PacketReceipt
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for MainViewModel.
 * Tests the interaction between the ViewModel and the Reticulum protocol.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var viewModel: MainViewModel
    private lateinit var mockNetworkStatus: MutableStateFlow<NetworkStatus>

    /**
     * Runs a test with the ViewModel created inside the test's coroutine scope.
     * This ensures coroutines launched during ViewModel init are properly tracked.
     */
    private fun runViewModelTest(testBody: suspend TestScope.() -> Unit) =
        runTest {
            viewModel = MainViewModel(reticulumProtocol)
            advanceUntilIdle()
            testBody()
        }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Create mock StateFlow for network status
        mockNetworkStatus = MutableStateFlow(NetworkStatus.SHUTDOWN)

        reticulumProtocol = mockk()
        every { reticulumProtocol.networkStatus } returns mockNetworkStatus
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `initial state is Initial`() =
        runViewModelTest {
            viewModel.uiState.test {
                assertEquals(UiState.Initial, awaitItem())
            }
        }

    @Test
    fun `initial network status is SHUTDOWN`() =
        runViewModelTest {
            viewModel.networkStatus.test {
                assertEquals(NetworkStatus.SHUTDOWN, awaitItem())
            }
        }

    @Test
    fun `initializeReticulum success updates state and status`() =
        runViewModelTest {
            // Mock successful initialization
            coEvery { reticulumProtocol.initialize(any()) } returns Result.success(Unit)

            // Simulate status change to READY via StateFlow
            coEvery { reticulumProtocol.initialize(any()) } coAnswers {
                mockNetworkStatus.value = NetworkStatus.READY
                Result.success(Unit)
            }

            viewModel.initializeReticulum()
            advanceUntilIdle()

            // Verify state transitions
            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Success)
                assertTrue((state as UiState.Success).message.contains("initialized successfully"))
            }

            // Verify network status updated via StateFlow
            viewModel.networkStatus.test {
                assertEquals(NetworkStatus.READY, awaitItem())
            }

            // Verify protocol was called correctly
            coVerify { reticulumProtocol.initialize(any()) }
        }

    @Test
    fun `initializeReticulum failure updates state with error`() =
        runViewModelTest {
            // Mock failed initialization
            val errorMessage = "Initialization failed"
            coEvery { reticulumProtocol.initialize(any()) } returns Result.failure(Exception(errorMessage))

            viewModel.initializeReticulum()
            advanceUntilIdle()

            // Verify error state
            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Error)
                assertTrue((state as UiState.Error).message.contains(errorMessage))
            }
        }

    @Test
    fun `createIdentity success updates state with hash`() =
        runViewModelTest {
            val mockIdentity =
                Identity(
                    hash = ByteArray(16) { it.toByte() },
                    publicKey = ByteArray(32),
                    privateKey = ByteArray(32),
                )
            coEvery { reticulumProtocol.createIdentity() } returns Result.success(mockIdentity)

            viewModel.createIdentity()
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Success)
                val message = (state as UiState.Success).message
                assertTrue(message.contains("Identity created"))
                assertTrue(message.contains("Hash"))
            }

            coVerify { reticulumProtocol.createIdentity() }
        }

    @Test
    fun `createIdentity failure updates state with error`() =
        runViewModelTest {
            val errorMessage = "Failed to create identity"
            coEvery { reticulumProtocol.createIdentity() } returns Result.failure(Exception(errorMessage))

            viewModel.createIdentity()
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Error)
                assertTrue((state as UiState.Error).message.contains(errorMessage))
            }
        }

    @Test
    fun `testSendPacket without identity shows error`() =
        runViewModelTest {
            viewModel.testSendPacket()
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Error)
                assertEquals("Please create an identity first", (state as UiState.Error).message)
            }
        }

    @Test
    fun `testSendPacket with identity sends successfully`() =
        runViewModelTest {
            // First create an identity
            val mockIdentity =
                Identity(
                    hash = ByteArray(16),
                    publicKey = ByteArray(32),
                    privateKey = ByteArray(32),
                )
            coEvery { reticulumProtocol.createIdentity() } returns Result.success(mockIdentity)
            viewModel.createIdentity()
            advanceUntilIdle()

            // Mock destination creation
            val mockDestination =
                Destination(
                    hash = ByteArray(16),
                    hexHash = "abcd1234",
                    identity = mockIdentity,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "columba.test",
                    aspects = listOf("test"),
                )
            coEvery {
                reticulumProtocol.createDestination(
                    any(), any(), any(), any(), any(),
                )
            } returns Result.success(mockDestination)

            // Mock packet sending
            val mockReceipt =
                PacketReceipt(
                    hash = ByteArray(32) { it.toByte() },
                    delivered = true,
                    timestamp = System.currentTimeMillis(),
                )
            coEvery { reticulumProtocol.sendPacket(any(), any(), any()) } returns Result.success(mockReceipt)

            // Test sending packet
            viewModel.testSendPacket()
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Success)
                val message = (state as UiState.Success).message
                assertTrue(message.contains("Packet sent"))
                assertTrue(message.contains("Delivered: true"))
            }

            coVerify { reticulumProtocol.createDestination(any(), any(), any(), any(), any()) }
            coVerify { reticulumProtocol.sendPacket(any(), any(), any()) }
        }

    @Test
    fun `testSendPacket handles destination creation failure`() =
        runViewModelTest {
            // Create identity first
            val mockIdentity =
                Identity(
                    hash = ByteArray(16),
                    publicKey = ByteArray(32),
                    privateKey = ByteArray(32),
                )
            coEvery { reticulumProtocol.createIdentity() } returns Result.success(mockIdentity)
            viewModel.createIdentity()
            advanceUntilIdle()

            // Mock destination creation failure
            coEvery {
                reticulumProtocol.createDestination(any(), any(), any(), any(), any())
            } returns Result.failure(Exception("Destination creation failed"))

            viewModel.testSendPacket()
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state is UiState.Error)
                assertTrue((state as UiState.Error).message.contains("Failed to create destination"))
            }
        }

    @Test
    fun `getNetworkStatusColor returns correct colors`() =
        runViewModelTest {
            // Test initial SHUTDOWN status (gray)
            assertEquals(0xFF9E9E9EL, viewModel.getNetworkStatusColor()) // Gray

            // Test READY status - trigger via initialization
            coEvery { reticulumProtocol.initialize(any()) } coAnswers {
                mockNetworkStatus.value = NetworkStatus.READY
                Result.success(Unit)
            }
            viewModel.initializeReticulum()
            advanceUntilIdle()
            assertEquals(0xFF4CAF50L, viewModel.getNetworkStatusColor()) // Green
        }

    @Test
    fun `multiple operations can be performed sequentially`() =
        runViewModelTest {
            // Mock all operations
            coEvery { reticulumProtocol.initialize(any()) } coAnswers {
                mockNetworkStatus.value = NetworkStatus.READY
                Result.success(Unit)
            }

            val mockIdentity =
                Identity(
                    hash = ByteArray(16),
                    publicKey = ByteArray(32),
                    privateKey = ByteArray(32),
                )
            coEvery { reticulumProtocol.createIdentity() } returns Result.success(mockIdentity)

            // Perform operations
            viewModel.initializeReticulum()
            advanceUntilIdle()
            viewModel.createIdentity()
            advanceUntilIdle()

            // Verify all operations succeeded
            coVerify { reticulumProtocol.initialize(any()) }
            coVerify { reticulumProtocol.createIdentity() }
        }

    @Test
    fun `networkStatus updates from protocol StateFlow during initialization`() =
        runViewModelTest {
            // Verify initial state
            viewModel.networkStatus.test {
                assertEquals(NetworkStatus.SHUTDOWN, awaitItem())
                cancelAndConsumeRemainingEvents()
            }

            // Mock initialization to update protocol StateFlow
            coEvery { reticulumProtocol.initialize(any()) } coAnswers {
                mockNetworkStatus.value = NetworkStatus.READY
                Result.success(Unit)
            }

            // Initialize and verify ViewModel copies the status
            viewModel.initializeReticulum()
            advanceUntilIdle()

            viewModel.networkStatus.test {
                assertEquals(NetworkStatus.READY, awaitItem())
                cancelAndConsumeRemainingEvents()
            }
        }
}
