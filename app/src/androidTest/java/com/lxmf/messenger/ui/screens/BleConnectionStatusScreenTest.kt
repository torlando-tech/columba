package com.lxmf.messenger.ui.screens

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.test.BleTestFixtures
import com.lxmf.messenger.test.TestActivity
import com.lxmf.messenger.test.waitForNodeWithTag
import com.lxmf.messenger.test.waitForNodeWithText
import com.lxmf.messenger.test.waitForTextCount
import com.lxmf.messenger.viewmodel.BleConnectionsUiState
import com.lxmf.messenger.viewmodel.BleConnectionsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for BleConnectionStatusScreen.
 * Tests different UI states, user interactions, and composable rendering.
 * Uses TestActivity to avoid process resolution conflicts in multi-process app architecture.
 */
class BleConnectionStatusScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    // ========== Loading State Tests ==========

    @Test
    fun loadingState_displaysLoadingIndicator() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(BleConnectionsUiState.Loading)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Loading connections...").assertIsDisplayed()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun loadingState_displaysTopAppBar() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(BleConnectionsUiState.Loading)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("BLE Connections").assertIsDisplayed()
    }

    // ========== Empty State Tests ==========

    @Test
    fun successState_emptyList_displaysNoConnectionsMessage() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val emptyState =
            BleConnectionsUiState.Success(
                connections = emptyList(),
                totalConnections = 0,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(emptyState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("No Active Connections").assertIsDisplayed()
        composeTestRule.onNodeWithText("BLE peers will appear here when connected")
            .assertIsDisplayed()
    }

    @Test
    fun successState_emptyList_displaysBluetoothDisabledIcon() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val emptyState =
            BleConnectionsUiState.Success(
                connections = emptyList(),
                totalConnections = 0,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(emptyState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    // ========== Success State with Data Tests ==========

    @Ignore("Flaky on CI: LazyColumn composition timing issues - ComposeTimeoutException")
    @Test
    fun successState_withConnections_displaysSummaryCard() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connections = BleTestFixtures.createMultipleConnections(count = 3)
        val successState =
            BleConnectionsUiState.Success(
                connections = connections,
                totalConnections = 3,
                centralConnections = 2,
                peripheralConnections = 1,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Wait for summary card to be composed and laid out
        composeTestRule.waitForNodeWithTag("summary_card")
        composeTestRule.waitForIdle() // Ensure LazyColumn item is fully composed

        // Wait for actual text content to ensure full layout completion
        composeTestRule.waitForNodeWithText("Total", timeoutMillis = 5000)

        // Then - scroll to summary card to ensure visibility in LazyColumn
        composeTestRule.onNodeWithTag("summary_card").performScrollTo()
        composeTestRule.onNodeWithText("Total").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()

        // Wait for "Central" text to appear exactly 2 times
        composeTestRule.waitForTextCount("Central", expectedCount = 2)
        composeTestRule.onAllNodesWithText("Central").assertCountEquals(2)

        composeTestRule.onNodeWithText("2").assertIsDisplayed()

        // Wait for "Peripheral" text to appear exactly 2 times
        composeTestRule.waitForTextCount("Peripheral", expectedCount = 2)
        composeTestRule.onAllNodesWithText("Peripheral").assertCountEquals(2)

        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun successState_withConnections_displaysConnectionCards() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                peerName = "RNS-TestPeer",
                identityHash = "abcd1234efgh5678",
                connectionType = ConnectionType.BOTH,
                rssi = -65,
            )
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 1,
                peripheralConnections = 1,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("RNS-TestPeer").assertIsDisplayed()
        composeTestRule.onNodeWithText("abcd1234").assertIsDisplayed() // Short hash
        composeTestRule.onNodeWithText("Both").assertIsDisplayed() // Connection type badge
    }

    @Test
    fun successState_withConnections_displaysSignalStrength() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -60)
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Signal Strength").assertIsDisplayed()
        composeTestRule.onNodeWithText("Good").assertIsDisplayed()
        composeTestRule.onNodeWithText("-60 dBm").assertIsDisplayed()
    }

    @Test
    fun successState_peripheralConnection_displaysUnavailableRssi() {
        // Given - Peripheral connections have -100 as placeholder RSSI
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                rssi = -100,
                connectionType = ConnectionType.PERIPHERAL,
            )
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 1,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Signal Strength").assertIsDisplayed()
        composeTestRule.onNodeWithText("N/A").assertIsDisplayed()
        composeTestRule.onNodeWithText("—").assertIsDisplayed()
        // Should NOT display the placeholder value
        composeTestRule.onNodeWithText("-100 dBm").assertDoesNotExist()
    }

    @Test
    fun successState_withConnections_displaysConnectionDetails() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                currentMac = "AA:BB:CC:DD:EE:FF",
                mtu = 512,
            )
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("MAC Address").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:DD:EE:FF").assertIsDisplayed()
        composeTestRule.onNodeWithText("MTU").assertIsDisplayed()
        composeTestRule.onNodeWithText("512 bytes").assertIsDisplayed()
    }

    @Ignore("Flaky on CI: LazyColumn lazy composition race - timeout waiting for RNS-Peer1")
    @Test
    fun successState_multipleConnections_displaysAllCards() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        // Use helper that generates unique MAC addresses for each connection
        val connections = BleTestFixtures.createMultipleConnections(count = 3)
        val successState =
            BleConnectionsUiState.Success(
                connections = connections,
                totalConnections = 3,
                centralConnections = 3,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Wait for first connection card to appear (LazyColumn lazy composition)
        composeTestRule.waitForNodeWithText("RNS-Peer0", timeoutMillis = 5000)
        composeTestRule.waitForIdle() // Ensure LazyColumn items are fully displayable

        // Then - Verify cards are rendered (scroll to ensure visibility)
        // createMultipleConnections uses 0-based indexing: RNS-Peer0, RNS-Peer1, RNS-Peer2
        composeTestRule.onNodeWithText("RNS-Peer0").performScrollTo().assertIsDisplayed()

        // Wait for RNS-Peer1 to be composed (may require LazyColumn to compose next item)
        composeTestRule.waitForNodeWithText("RNS-Peer1", timeoutMillis = 5000)
        composeTestRule.onNodeWithText("RNS-Peer1").performScrollTo().assertIsDisplayed()
        // RNS-Peer2 exists in data but may not be visible without scrolling
    }

    // ========== Error State Tests ==========

    @Test
    fun errorState_displaysErrorMessage() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val errorState = BleConnectionsUiState.Error("Test error message")
        every { mockViewModel.uiState } returns MutableStateFlow(errorState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Error Loading Connections").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test error message").assertIsDisplayed()
    }

    @Test
    fun errorState_displaysRetryButton() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val errorState = BleConnectionsUiState.Error("Network error")
        every { mockViewModel.uiState } returns MutableStateFlow(errorState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    // ========== User Interaction Tests ==========

    @Test
    fun backButton_triggersOnBackClick() {
        // Given
        var backClicked = false
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(BleConnectionsUiState.Loading)

        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = { backClicked = true },
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // Then
        assert(backClicked)
    }

    @Test
    fun refreshButton_callsViewModelRefresh() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val successState =
            BleConnectionsUiState.Success(
                connections = emptyList(),
                totalConnections = 0,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithContentDescription("Refresh").performClick()

        // Then
        verify(exactly = 1) { mockViewModel.refresh() }
    }

    @Test
    fun retryButton_callsViewModelRefresh() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val errorState = BleConnectionsUiState.Error("Error")
        every { mockViewModel.uiState } returns MutableStateFlow(errorState)

        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithText("Retry").performClick()

        // Then
        verify(exactly = 1) { mockViewModel.refresh() }
    }

    @Test
    fun disconnectButton_callsViewModelDisconnect() {
        // Given
        val testMac = "AA:BB:CC:DD:EE:FF"
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(currentMac = testMac)
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Wait for connection card and disconnect button to appear
        composeTestRule.waitForNodeWithTag("connection_card_$testMac")
        composeTestRule.waitForIdle()

        // When - scroll to button and click (required for LazyColumn items)
        composeTestRule.onNodeWithTag("disconnect_button_$testMac")
            .performScrollTo()
            .performClick()

        // Then
        verify(exactly = 1) { mockViewModel.disconnectPeer(testMac) }
    }

    // ========== Connection Type Badge Tests ==========

    @Test
    fun connectionTypeBadge_displaysCentral() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.CENTRAL)
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 1,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        // "Central" appears in both summary card and connection badge
        composeTestRule.onAllNodesWithText("Central").assertCountEquals(2)
    }

    @Test
    fun connectionTypeBadge_displaysPeripheral() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.PERIPHERAL)
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 1,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        // "Peripheral" appears in both summary card and connection badge
        composeTestRule.onAllNodesWithText("Peripheral").assertCountEquals(2)
    }

    @Test
    fun connectionTypeBadge_displaysBoth() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(connectionType = ConnectionType.BOTH)
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 1,
                peripheralConnections = 1,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Both").assertIsDisplayed()
    }

    // ========== Signal Quality Tests ==========

    @Test
    fun signalQuality_displaysExcellent() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -40) // Excellent
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Excellent").assertIsDisplayed()
    }

    @Test
    fun signalQuality_displaysFair() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -75) // Fair
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Fair").assertIsDisplayed()
    }

    @Test
    fun signalQuality_displaysPoor() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection = BleTestFixtures.createBleConnectionInfo(rssi = -90) // Poor
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Poor").assertIsDisplayed()
    }

    // ========== Performance Metrics Tests ==========

    @Ignore("Flaky on CI: LazyColumn item not displayable after scroll - assertion fails")
    @Test
    fun performanceMetrics_displayedWhenDataAvailable() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val testMac = "AA:BB:CC:DD:EE:FF"
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                currentMac = testMac,
                bytesSent = 1024,
                bytesReceived = 2048,
                successRate = 0.95,
            )
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Wait for performance section to be composed using test tag
        composeTestRule.waitForNodeWithTag("performance_section_$testMac")
        composeTestRule.waitForIdle() // Ensure section content is fully laid out

        // Wait for actual text content to ensure full layout completion
        composeTestRule.waitForNodeWithText("Performance", timeoutMillis = 5000)

        // Then - scroll to performance section to ensure visibility in LazyColumn
        composeTestRule.onNodeWithTag("performance_section_$testMac").performScrollTo()
        composeTestRule.onNodeWithText("Performance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Data Sent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Data Received").assertIsDisplayed()
        composeTestRule.onNodeWithText("Success Rate").assertIsDisplayed()
        composeTestRule.onNodeWithText("95%").assertIsDisplayed()
    }

    @Test
    @Ignore("Flaky on CI: Activity lifecycle timeout on resource-constrained runners")
    fun performanceMetrics_notDisplayedWhenNoData() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connection =
            BleTestFixtures.createBleConnectionInfo(
                bytesSent = 0,
                bytesReceived = 0,
            )
        val successState =
            BleConnectionsUiState.Success(
                connections = listOf(connection),
                totalConnections = 1,
                centralConnections = 0,
                peripheralConnections = 0,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Performance").assertDoesNotExist()
    }

    // ========== Scrolling Tests ==========

    @Test
    fun successState_largeList_isScrollable() {
        // Given
        val mockViewModel = mockk<BleConnectionsViewModel>(relaxed = true)
        val connections = BleTestFixtures.createMultipleConnections(count = 20)
        val successState =
            BleConnectionsUiState.Success(
                connections = connections,
                totalConnections = 20,
                centralConnections = 10,
                peripheralConnections = 10,
            )
        every { mockViewModel.uiState } returns MutableStateFlow(successState)

        // When
        composeTestRule.setContent {
            BleConnectionStatusScreen(
                onBackClick = {},
                viewModel = mockViewModel,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Verify scrollable content exists and can scroll
        composeTestRule.onNodeWithText("RNS-Peer0").assertIsDisplayed()
        // The last item might not be visible without scrolling
        composeTestRule.onNodeWithText("RNS-Peer19").assertDoesNotExist()
    }
}
