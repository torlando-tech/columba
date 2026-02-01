package com.lxmf.messenger.ui.screens.tcpclient

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.TcpCommunityServer
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.test.TcpClientWizardTestFixtures
import com.lxmf.messenger.viewmodel.TcpClientWizardViewModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for ServerSelectionStep.kt.
 * Tests the server selection screen with community servers and custom option.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerSelectionStepTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Header Tests ==========

    @Test
    fun serverSelectionStep_displaysHeader() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Choose Server").assertIsDisplayed()
    }

    @Test
    fun serverSelectionStep_displaysDescription() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule
            .onNodeWithText("Select a community server or enter custom connection details.")
            .assertIsDisplayed()
    }

    // ========== Server List Tests ==========

    @Test
    fun serverSelectionStep_displaysCommunityServers() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Test Server").assertIsDisplayed()
        composeTestRule.onNodeWithText("test.example.com:4242").assertIsDisplayed()
        composeTestRule.onNodeWithText("Another Server").assertIsDisplayed()
        composeTestRule.onNodeWithText("another.example.com:5000").assertIsDisplayed()
    }

    @Test
    fun serverSelectionStep_displaysCustomOption() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Custom").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter server details manually").assertIsDisplayed()
    }

    // ========== Selection Tests ==========

    @Test
    fun serverCard_selected_showsCheckIcon() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(
                    selectedServer = TcpClientWizardTestFixtures.testServer,
                ),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Selected").assertIsDisplayed()
    }

    @Test
    fun serverCard_onClick_callsSelectServer() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers
        val serverSlot = slot<TcpCommunityServer>()
        every { mockViewModel.selectServer(capture(serverSlot)) } just Runs

        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // When
        composeTestRule.onNodeWithText("Test Server").performClick()

        // Then
        verify { mockViewModel.selectServer(any()) }
        assertTrue("selectServer should have been called", serverSlot.isCaptured)
        assertEquals(TcpClientWizardTestFixtures.testServer, serverSlot.captured)
    }

    @Test
    fun customSettingsCard_onClick_callsEnableCustomMode() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers
        var enableCustomModeCalled = false
        every { mockViewModel.enableCustomMode() } answers { enableCustomModeCalled = true }

        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // When
        composeTestRule.onNodeWithText("Custom").performClick()

        // Then
        verify { mockViewModel.enableCustomMode() }
        assertTrue("enableCustomMode should have been called", enableCustomModeCalled)
    }

    @Test
    fun customMode_selected_showsCheckIcon() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(isCustomMode = true),
            )
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            ServerSelectionStep(viewModel = mockViewModel)
        }

        // Then - Check icon on Custom card
        composeTestRule.onNodeWithContentDescription("Selected").assertIsDisplayed()
    }
}
