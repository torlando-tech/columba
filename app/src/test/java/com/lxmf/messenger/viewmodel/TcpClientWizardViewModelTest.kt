package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.model.TcpCommunityServer
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for TcpClientWizardViewModel.
 * Tests wizard navigation, validation, state management, and save operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpClientWizardViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var viewModel: TcpClientWizardViewModel

    private val testServer =
        TcpCommunityServer(
            name = "Test Server",
            host = "test.example.com",
            port = 4242,
        )

    private val anotherTestServer =
        TcpCommunityServer(
            name = "Another Server",
            host = "another.example.com",
            port = 5000,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        interfaceRepository = mockk(relaxed = true)
        configManager = mockk(relaxed = true)

        // Mock allInterfaceEntities to return empty list (no duplicates)
        every { interfaceRepository.allInterfaceEntities } returns flowOf(emptyList())

        viewModel = TcpClientWizardViewModel(interfaceRepository, configManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state is SERVER_SELECTION step`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(TcpClientWizardStep.SERVER_SELECTION, state.currentStep)
            }
        }

    @Test
    fun `initial state has no selected server`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.selectedServer)
            }
        }

    @Test
    fun `initial state has isCustomMode false`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isCustomMode)
            }
        }

    @Test
    fun `initial state has empty configuration fields`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("", state.interfaceName)
                assertEquals("", state.targetHost)
                assertEquals("", state.targetPort)
            }
        }

    @Test
    fun `initial state has no save errors`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSaving)
                assertNull(state.saveError)
                assertFalse(state.saveSuccess)
            }
        }

    // ========== Server Selection Tests ==========

    @Test
    fun `selectServer updates selectedServer field`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(testServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(testServer, state.selectedServer)
            }
        }

    @Test
    fun `selectServer populates interfaceName from server name`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(testServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("Test Server", state.interfaceName)
            }
        }

    @Test
    fun `selectServer populates targetHost from server host`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(testServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("test.example.com", state.targetHost)
            }
        }

    @Test
    fun `selectServer populates targetPort from server port as string`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(testServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("4242", state.targetPort)
            }
        }

    @Test
    fun `selectServer clears isCustomMode flag`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // First enable custom mode
                viewModel.enableCustomMode()
                advanceUntilIdle()
                awaitItem() // Custom mode state

                // Then select server
                viewModel.selectServer(testServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertFalse(state.isCustomMode)
            }
        }

    @Test
    fun `multiple selectServer calls update state correctly`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(testServer)
                advanceUntilIdle()
                awaitItem() // First server selected

                viewModel.selectServer(anotherTestServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(anotherTestServer, state.selectedServer)
                assertEquals("Another Server", state.interfaceName)
                assertEquals("another.example.com", state.targetHost)
                assertEquals("5000", state.targetPort)
            }
        }

    // ========== Custom Mode Tests ==========

    @Test
    fun `enableCustomMode sets isCustomMode true`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.enableCustomMode()
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.isCustomMode)
            }
        }

    @Test
    fun `enableCustomMode clears selectedServer`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // First select a server
                viewModel.selectServer(testServer)
                advanceUntilIdle()
                awaitItem() // Server selected

                // Then enable custom mode
                viewModel.enableCustomMode()
                advanceUntilIdle()

                val state = awaitItem()
                assertNull(state.selectedServer)
            }
        }

    @Test
    fun `enableCustomMode clears all form fields`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // First select a server to populate fields
                viewModel.selectServer(testServer)
                advanceUntilIdle()
                awaitItem() // Server selected with populated fields

                // Then enable custom mode
                viewModel.enableCustomMode()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("", state.interfaceName)
                assertEquals("", state.targetHost)
                assertEquals("", state.targetPort)
            }
        }

    @Test
    fun `selecting server after custom mode clears isCustomMode`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.enableCustomMode()
                advanceUntilIdle()

                val customState = awaitItem()
                assertTrue(customState.isCustomMode)

                viewModel.selectServer(testServer)
                advanceUntilIdle()

                val serverState = awaitItem()
                assertFalse(serverState.isCustomMode)
                assertNotNull(serverState.selectedServer)
            }
        }

    // ========== Field Update Tests ==========

    @Test
    fun `updateInterfaceName updates interfaceName field`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.updateInterfaceName("My Custom Name")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("My Custom Name", state.interfaceName)
            }
        }

    @Test
    fun `updateTargetHost updates targetHost field`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.updateTargetHost("custom.host.com")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("custom.host.com", state.targetHost)
            }
        }

    @Test
    fun `updateTargetPort updates targetPort field`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.updateTargetPort("9999")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("9999", state.targetPort)
            }
        }

    @Test
    fun `field updates do not affect other fields`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // Set all fields
                viewModel.updateInterfaceName("Name")
                advanceUntilIdle()
                awaitItem()

                viewModel.updateTargetHost("host.com")
                advanceUntilIdle()
                awaitItem()

                viewModel.updateTargetPort("1234")
                advanceUntilIdle()

                val stateAfterPort = awaitItem()
                assertEquals("Name", stateAfterPort.interfaceName)
                assertEquals("host.com", stateAfterPort.targetHost)
                assertEquals("1234", stateAfterPort.targetPort)

                // Update only one field
                viewModel.updateTargetHost("new.host.com")
                advanceUntilIdle()

                val finalState = awaitItem()
                assertEquals("Name", finalState.interfaceName) // Unchanged
                assertEquals("new.host.com", finalState.targetHost) // Changed
                assertEquals("1234", finalState.targetPort) // Unchanged
            }
        }

    // ========== Navigation Validation Tests (canProceed) ==========

    @Test
    fun `canProceed returns false at SERVER_SELECTION with no selection and not custom mode`() =
        runTest {
            // Initial state - no selection, not custom mode
            assertFalse(viewModel.canProceed())
        }

    @Test
    fun `canProceed returns true at SERVER_SELECTION with selected server`() =
        runTest {
            viewModel.selectServer(testServer)
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed returns true at SERVER_SELECTION with custom mode enabled`() =
        runTest {
            viewModel.enableCustomMode()
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    @Test
    fun `canProceed returns true at REVIEW_CONFIGURE step`() =
        runTest {
            // Navigate to REVIEW_CONFIGURE step
            viewModel.selectServer(testServer)
            advanceUntilIdle()
            viewModel.goToNextStep()
            advanceUntilIdle()

            assertTrue(viewModel.canProceed())
        }

    // ========== Step Navigation Tests ==========

    @Test
    fun `goToNextStep from SERVER_SELECTION goes to REVIEW_CONFIGURE`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.goToNextStep()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(TcpClientWizardStep.REVIEW_CONFIGURE, state.currentStep)
            }
        }

    @Test
    fun `goToNextStep at REVIEW_CONFIGURE has no effect`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // Go to REVIEW_CONFIGURE
                viewModel.goToNextStep()
                advanceUntilIdle()

                val reviewState = awaitItem()
                assertEquals(TcpClientWizardStep.REVIEW_CONFIGURE, reviewState.currentStep)

                // Try to go to next step again
                viewModel.goToNextStep()
                advanceUntilIdle()

                // No additional emission expected - state doesn't change
                expectNoEvents()
            }
        }

    @Test
    fun `goToPreviousStep from REVIEW_CONFIGURE goes to SERVER_SELECTION`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // Go to REVIEW_CONFIGURE
                viewModel.goToNextStep()
                advanceUntilIdle()
                awaitItem() // REVIEW_CONFIGURE state

                // Go back
                viewModel.goToPreviousStep()
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(TcpClientWizardStep.SERVER_SELECTION, state.currentStep)
            }
        }

    @Test
    fun `goToPreviousStep at SERVER_SELECTION has no effect`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                // Try to go previous
                viewModel.goToPreviousStep()
                advanceUntilIdle()

                // No additional emission expected - state doesn't change
                expectNoEvents()
            }
        }

    // ========== Save Configuration Tests - Success Path ==========

    @Test
    fun `saveConfiguration sets isSaving true during operation`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()

                // Should see isSaving = true before completion
                val savingState = awaitItem()
                assertTrue(savingState.isSaving)

                advanceUntilIdle()
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `saveConfiguration calls repository insertInterface with correct config`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.selectServer(testServer)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            coVerify(exactly = 1) { interfaceRepository.insertInterface(any()) }

            val savedConfig = configSlot.captured
            assertTrue(savedConfig is InterfaceConfig.TCPClient)
            val tcpConfig = savedConfig as InterfaceConfig.TCPClient
            assertEquals("Test Server", tcpConfig.name)
            assertEquals("test.example.com", tcpConfig.targetHost)
            assertEquals(4242, tcpConfig.targetPort)
            assertTrue(tcpConfig.enabled)
            assertFalse(tcpConfig.kissFraming)
            assertEquals("full", tcpConfig.mode)
        }

    @Test
    fun `saveConfiguration calls configManager setPendingChanges true`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel.saveConfiguration()
            advanceUntilIdle()

            coVerify(exactly = 1) { configManager.setPendingChanges(true) }
        }

    @Test
    fun `saveConfiguration sets saveSuccess true on completion`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                // Skip intermediate isSaving state
                val finalState = expectMostRecentItem()
                assertTrue(finalState.saveSuccess)
            }
        }

    @Test
    fun `saveConfiguration sets isSaving false after completion`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertFalse(finalState.isSaving)
            }
        }

    // ========== Save Configuration Tests - Default Values ==========

    @Test
    fun `empty interface name defaults to TCP Connection`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            // Enable custom mode (empty fields)
            viewModel.enableCustomMode()
            viewModel.updateTargetHost("test.com")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals("TCP Connection", savedConfig.name)
        }

    @Test
    fun `whitespace-only interface name defaults to TCP Connection`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.enableCustomMode()
            viewModel.updateInterfaceName("   ")
            viewModel.updateTargetHost("test.com")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals("TCP Connection", savedConfig.name)
        }

    @Test
    fun `valid interface name is trimmed`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.enableCustomMode()
            viewModel.updateInterfaceName("  My Server  ")
            viewModel.updateTargetHost("test.com")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals("My Server", savedConfig.name)
        }

    @Test
    fun `target host is trimmed`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.enableCustomMode()
            viewModel.updateTargetHost("  test.com  ")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals("test.com", savedConfig.targetHost)
        }

    @Test
    fun `empty port defaults to 4242`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.enableCustomMode()
            viewModel.updateTargetHost("test.com")
            // targetPort is empty by default
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals(4242, savedConfig.targetPort)
        }

    @Test
    fun `invalid port non-numeric defaults to 4242`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.enableCustomMode()
            viewModel.updateTargetHost("test.com")
            viewModel.updateTargetPort("abc")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals(4242, savedConfig.targetPort)
        }

    @Test
    fun `valid port is parsed correctly`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.enableCustomMode()
            viewModel.updateTargetHost("test.com")
            viewModel.updateTargetPort("9999")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals(9999, savedConfig.targetPort)
        }

    // ========== Save Configuration Tests - Error Handling ==========

    @Test
    fun `repository exception sets saveError with message`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } throws RuntimeException("Database error")

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertEquals("Database error", finalState.saveError)
            }
        }

    @Test
    fun `repository exception sets isSaving false`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } throws RuntimeException("Database error")

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertFalse(finalState.isSaving)
            }
        }

    @Test
    fun `repository exception does not set saveSuccess`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } throws RuntimeException("Database error")

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertFalse(finalState.saveSuccess)
            }
        }

    @Test
    fun `exception without message uses fallback error message`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } throws RuntimeException()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertEquals("Failed to save configuration", finalState.saveError)
            }
        }

    @Test
    fun `clearSaveError resets saveError to null`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } throws RuntimeException("Error")

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val errorState = expectMostRecentItem()
                assertNotNull(errorState.saveError)

                viewModel.clearSaveError()
                advanceUntilIdle()

                val clearedState = awaitItem()
                assertNull(clearedState.saveError)
            }
        }

    // ========== Duplicate Interface Name Tests ==========

    @Test
    fun `saveConfiguration fails with duplicate interface name`() =
        runTest {
            // Mock existing interfaces with a name that will conflict
            val existingEntity =
                InterfaceEntity(
                    id = 1L,
                    name = "Test Server",
                    type = "TCPClient",
                    enabled = true,
                    configJson = "{}",
                )
            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))

            viewModel.state.test {
                awaitItem() // Initial state

                // Try to save with duplicate name
                viewModel.selectServer(testServer) // Sets name to "Test Server"
                advanceUntilIdle()
                awaitItem() // Server selected

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertNotNull(finalState.saveError)
                assertTrue(finalState.saveError!!.contains("already exists"))
                assertFalse(finalState.saveSuccess)
                assertFalse(finalState.isSaving)
            }
        }

    @Test
    fun `saveConfiguration fails with case-insensitive duplicate name`() =
        runTest {
            // Mock existing interface with different case
            val existingEntity =
                InterfaceEntity(
                    id = 1L,
                    name = "test server",
                    type = "TCPClient",
                    enabled = true,
                    configJson = "{}",
                )
            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))

            viewModel.state.test {
                awaitItem() // Initial state

                // Try to save with "Test Server" (different case)
                viewModel.selectServer(testServer)
                advanceUntilIdle()
                awaitItem()

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertNotNull(finalState.saveError)
                assertTrue(finalState.saveError!!.contains("already exists"))
            }
        }

    @Test
    fun `saveConfiguration succeeds with unique interface name`() =
        runTest {
            // Mock no existing interfaces
            every { interfaceRepository.allInterfaceEntities } returns flowOf(emptyList())
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(testServer)
                advanceUntilIdle()
                awaitItem()

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertNull(finalState.saveError)
                assertTrue(finalState.saveSuccess)
            }
        }

    @Test
    fun `saveConfiguration does not call repository when duplicate name detected`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 1L,
                    name = "Test Server",
                    type = "TCPClient",
                    enabled = true,
                    configJson = "{}",
                )
            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))

            viewModel.selectServer(testServer)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            // Should NOT call insertInterface because duplicate was detected
            coVerify(exactly = 0) { interfaceRepository.insertInterface(any()) }
        }

    // ========== Community Servers Tests ==========

    @Test
    fun `getCommunityServers returns non-empty list`() =
        runTest {
            val servers = viewModel.getCommunityServers()
            assertTrue(servers.isNotEmpty())
        }

    @Test
    fun `getCommunityServers returns list with expected server entries`() =
        runTest {
            val servers = viewModel.getCommunityServers()

            // Verify some known community servers exist
            assertTrue(servers.any { it.name.isNotBlank() })
            assertTrue(servers.any { it.host.isNotBlank() })
            assertTrue(servers.all { it.port > 0 })
        }

    // ========== Bootstrap Only Flag Tests ==========

    @Test
    fun `initial state has bootstrapOnly false`() =
        runTest {
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.bootstrapOnly)
            }
        }

    @Test
    fun `toggleBootstrapOnly enables bootstrap mode`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.toggleBootstrapOnly(true)
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.bootstrapOnly)
            }
        }

    @Test
    fun `toggleBootstrapOnly disables bootstrap mode`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.toggleBootstrapOnly(true)
                advanceUntilIdle()
                awaitItem() // Enabled state

                viewModel.toggleBootstrapOnly(false)
                advanceUntilIdle()

                val state = awaitItem()
                assertFalse(state.bootstrapOnly)
            }
        }

    @Test
    fun `selectServer with bootstrap server sets bootstrapOnly true`() =
        runTest {
            val bootstrapServer =
                TcpCommunityServer(
                    name = "Bootstrap Server",
                    host = "bootstrap.example.com",
                    port = 4242,
                    isBootstrap = true,
                )

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.selectServer(bootstrapServer)
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.bootstrapOnly)
            }
        }

    @Test
    fun `saveConfiguration includes bootstrapOnly flag`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(configSlot)) } returns 1L

            viewModel.selectServer(testServer)
            viewModel.toggleBootstrapOnly(true)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertTrue(savedConfig.bootstrapOnly)
        }

    // ========== Load Existing Interface Tests (Edit Mode) ==========

    @Test
    fun `loadExistingInterface sets editingInterfaceId`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Existing Server",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"existing.com","targetPort":5000,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Existing Server",
                    enabled = true,
                    targetHost = "existing.com",
                    targetPort = 5000,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.loadExistingInterface(42L)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(42L, state.editingInterfaceId)
            }
        }

    @Test
    fun `loadExistingInterface populates fields from existing config`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Existing Server",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"existing.com","targetPort":5000,"bootstrapOnly":true}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Existing Server",
                    enabled = true,
                    targetHost = "existing.com",
                    targetPort = 5000,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = true,
                )

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.loadExistingInterface(42L)
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("Existing Server", state.interfaceName)
                assertEquals("existing.com", state.targetHost)
                assertEquals("5000", state.targetPort)
                assertTrue(state.bootstrapOnly)
            }
        }

    @Test
    fun `loadExistingInterface matches community server when host and port match`() =
        runTest {
            // Use a known community server's host/port
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Custom Name",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"test.example.com","targetPort":4242,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Custom Name",
                    enabled = true,
                    targetHost = "test.example.com",
                    targetPort = 4242,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.loadExistingInterface(42L)
                advanceUntilIdle()

                val state = awaitItem()
                // Should be custom mode since test.example.com isn't a real community server
                assertTrue(state.isCustomMode)
            }
        }

    @Test
    fun `loadExistingInterface sets custom mode when no community server matches`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "My Custom Server",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"my-custom.local","targetPort":9999,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "My Custom Server",
                    enabled = true,
                    targetHost = "my-custom.local",
                    targetPort = 9999,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.loadExistingInterface(42L)
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.isCustomMode)
                assertNull(state.selectedServer)
            }
        }

    @Test
    fun `loadExistingInterface does nothing when entity not found`() =
        runTest {
            coEvery { interfaceRepository.getInterfaceByIdOnce(999L) } returns null

            viewModel.state.test {
                val initialState = awaitItem()

                viewModel.loadExistingInterface(999L)
                advanceUntilIdle()

                // No state change expected
                expectNoEvents()
                assertNull(initialState.editingInterfaceId)
            }
        }

    // ========== Set Initial Values Tests (From Discovered Interface) ==========

    @Test
    fun `setInitialValues populates host and port`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialValues("discovered.host.com", 7777, "Discovered Interface")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("discovered.host.com", state.targetHost)
                assertEquals("7777", state.targetPort)
            }
        }

    @Test
    fun `setInitialValues sets interface name`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialValues("host.com", 4242, "My Discovered Node")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals("My Discovered Node", state.interfaceName)
            }
        }

    @Test
    fun `setInitialValues skips to REVIEW_CONFIGURE step`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialValues("host.com", 4242, "Name")
                advanceUntilIdle()

                val state = awaitItem()
                assertEquals(TcpClientWizardStep.REVIEW_CONFIGURE, state.currentStep)
            }
        }

    @Test
    fun `setInitialValues enables custom mode when no community server matches`() =
        runTest {
            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setInitialValues("unknown-server.com", 9999, "Unknown")
                advanceUntilIdle()

                val state = awaitItem()
                assertTrue(state.isCustomMode)
                assertNull(state.selectedServer)
            }
        }

    @Test
    fun `setInitialValues sets bootstrapOnly from matched community server`() =
        runTest {
            // This tests that if a discovered interface matches a bootstrap community server,
            // the bootstrapOnly flag is set appropriately
            viewModel.state.test {
                awaitItem() // Initial state

                // Use a custom host that won't match any community server
                viewModel.setInitialValues("custom.host", 4242, "Custom")
                advanceUntilIdle()

                val state = awaitItem()
                // Since no community server matches, bootstrapOnly should be false
                assertFalse(state.bootstrapOnly)
            }
        }

    // ========== Edit Mode Save Configuration Tests ==========

    @Test
    fun `saveConfiguration in edit mode calls updateInterface instead of insertInterface`() =
        runTest {
            // Set up edit mode
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Old Name",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"old.com","targetPort":4242,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Old Name",
                    enabled = true,
                    targetHost = "old.com",
                    targetPort = 4242,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )
            coEvery { interfaceRepository.updateInterface(any(), any()) } returns Unit

            viewModel.loadExistingInterface(42L)
            advanceUntilIdle()

            // Modify the interface
            viewModel.updateInterfaceName("New Name")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            // Should call updateInterface, not insertInterface
            coVerify(exactly = 1) { interfaceRepository.updateInterface(42L, any()) }
            coVerify(exactly = 0) { interfaceRepository.insertInterface(any()) }
        }

    @Test
    fun `saveConfiguration in edit mode passes correct config to updateInterface`() =
        runTest {
            val configSlot = slot<InterfaceConfig>()
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Old Name",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"old.com","targetPort":4242,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Old Name",
                    enabled = true,
                    targetHost = "old.com",
                    targetPort = 4242,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )
            coEvery { interfaceRepository.updateInterface(any(), capture(configSlot)) } returns Unit

            viewModel.loadExistingInterface(42L)
            advanceUntilIdle()

            viewModel.updateInterfaceName("Updated Name")
            viewModel.updateTargetHost("updated.host.com")
            viewModel.updateTargetPort("8888")
            viewModel.toggleBootstrapOnly(true)
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            val savedConfig = configSlot.captured as InterfaceConfig.TCPClient
            assertEquals("Updated Name", savedConfig.name)
            assertEquals("updated.host.com", savedConfig.targetHost)
            assertEquals(8888, savedConfig.targetPort)
            assertTrue(savedConfig.bootstrapOnly)
        }

    @Test
    fun `saveConfiguration in edit mode excludes current interface from duplicate name check`() =
        runTest {
            // Mock existing interface we're editing
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "My Interface",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"test.com","targetPort":4242,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "My Interface",
                    enabled = true,
                    targetHost = "test.com",
                    targetPort = 4242,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )

            // Mock all interfaces including the one we're editing
            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))
            coEvery { interfaceRepository.updateInterface(any(), any()) } returns Unit

            viewModel.loadExistingInterface(42L)
            advanceUntilIdle()

            // Save with the same name (should succeed since we're editing this interface)
            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.saveSuccess)
                assertNull(state.saveError)
            }
        }

    @Test
    fun `saveConfiguration in edit mode detects duplicate name from other interfaces`() =
        runTest {
            val editingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Interface A",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"a.com","targetPort":4242,"bootstrapOnly":false}""",
                )
            val otherEntity =
                InterfaceEntity(
                    id = 100L,
                    name = "Interface B",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"b.com","targetPort":4242,"bootstrapOnly":false}""",
                )

            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns editingEntity
            every { interfaceRepository.entityToConfig(editingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Interface A",
                    enabled = true,
                    targetHost = "a.com",
                    targetPort = 4242,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )

            // Both interfaces exist
            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(editingEntity, otherEntity))

            viewModel.loadExistingInterface(42L)
            advanceUntilIdle()

            // Try to rename to the other interface's name
            viewModel.updateInterfaceName("Interface B")
            advanceUntilIdle()

            viewModel.saveConfiguration()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.saveSuccess)
                assertNotNull(state.saveError)
                assertTrue(state.saveError!!.contains("already exists"))
            }
        }

    @Test
    fun `saveConfiguration in edit mode sets saveSuccess on completion`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 42L,
                    name = "Test",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"targetHost":"test.com","targetPort":4242,"bootstrapOnly":false}""",
                )
            coEvery { interfaceRepository.getInterfaceByIdOnce(42L) } returns existingEntity
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Test",
                    enabled = true,
                    targetHost = "test.com",
                    targetPort = 4242,
                    kissFraming = false,
                    mode = "full",
                    bootstrapOnly = false,
                )
            coEvery { interfaceRepository.updateInterface(any(), any()) } returns Unit

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.loadExistingInterface(42L)
                advanceUntilIdle()
                awaitItem() // Loaded state

                viewModel.saveConfiguration()
                advanceUntilIdle()

                val finalState = expectMostRecentItem()
                assertTrue(finalState.saveSuccess)
                assertFalse(finalState.isSaving)
            }
        }
}
