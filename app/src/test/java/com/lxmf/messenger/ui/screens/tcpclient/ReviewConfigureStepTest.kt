package com.lxmf.messenger.ui.screens.tcpclient

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.test.TcpClientWizardTestFixtures
import com.lxmf.messenger.viewmodel.TcpClientWizardViewModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
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
 * UI tests for ReviewConfigureStep.kt.
 * Tests the review/configure screen with form fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReviewConfigureStepTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Server Summary Card Tests ==========

    @Test
    fun reviewStep_displaysServerLabel() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Server").assertIsDisplayed()
    }

    @Test
    fun reviewStep_selectedServer_displaysServerName() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(
                    selectedServer = TcpClientWizardTestFixtures.testServer,
                ),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then - Server name appears in summary card and may also appear in interface name field
        composeTestRule.onAllNodesWithText("Test Server").assertCountEquals(2)
    }

    @Test
    fun reviewStep_customMode_displaysCustomServerText() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.customModeReviewState(),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Custom Server").assertIsDisplayed()
    }

    // ========== Form Field Tests ==========

    @Test
    fun reviewStep_displaysInterfaceNameField() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Interface Name").assertIsDisplayed()
    }

    @Test
    fun reviewStep_displaysTargetHostField() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Target Host").assertIsDisplayed()
    }

    @Test
    fun reviewStep_displaysTargetPortField() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Target Port").assertIsDisplayed()
    }

    // ========== Input Callback Tests ==========

    @Test
    fun reviewStep_onInterfaceNameChange_callsViewModel() {
        // Given
        val capturedName = slot<String>()
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(interfaceName = ""),
            )
        every { mockViewModel.updateInterfaceName(capture(capturedName)) } just Runs

        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // When
        composeTestRule.onNodeWithText("Interface Name").performTextInput("New Name")

        // Then
        assertTrue(capturedName.isCaptured)
        assertEquals("New Name", capturedName.captured)
    }

    @Test
    fun reviewStep_onTargetHostChange_callsViewModel() {
        // Given
        val capturedHost = slot<String>()
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.customModeReviewState(targetHost = ""),
            )
        every { mockViewModel.updateTargetHost(capture(capturedHost)) } just Runs

        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // When
        composeTestRule.onNodeWithText("Target Host").performTextInput("new.host.com")

        // Then
        assertTrue(capturedHost.isCaptured)
        assertEquals("new.host.com", capturedHost.captured)
    }

    @Test
    fun reviewStep_onTargetPortChange_callsViewModel() {
        // Given
        val capturedPort = slot<String>()
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.customModeReviewState(targetPort = ""),
            )
        every { mockViewModel.updateTargetPort(capture(capturedPort)) } just Runs

        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // When
        composeTestRule.onNodeWithText("Target Port").performTextInput("5000")

        // Then
        assertTrue(capturedPort.isCaptured)
        assertEquals("5000", capturedPort.captured)
    }

    @Test
    fun reviewStep_displaysCurrentFieldValues() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>()
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(
                    interfaceName = "My Interface",
                    targetHost = "my.host.com",
                    targetPort = "9999",
                ),
            )

        // When
        composeTestRule.setContent {
            ReviewConfigureStep(viewModel = mockViewModel)
        }

        // Then - Values are displayed in the text fields
        composeTestRule.onNodeWithText("My Interface").assertIsDisplayed()
        composeTestRule.onNodeWithText("my.host.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("9999").assertIsDisplayed()
    }
}
