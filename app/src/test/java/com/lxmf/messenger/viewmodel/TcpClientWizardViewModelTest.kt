package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.model.TcpCommunityServer
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
