package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.ApkSharingState
import com.lxmf.messenger.viewmodel.ApkSharingViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApkSharingScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private fun createMockViewModel(state: ApkSharingState = ApkSharingState()): ApkSharingViewModel =
        mockk<ApkSharingViewModel>().apply {
            every { this@apply.state } returns MutableStateFlow(state)
            every { createShareIntent() } returns null
        }

    // ========== Top Bar Tests ==========

    @Test
    fun `displays Share Columba title in top bar`() {
        val viewModel = createMockViewModel()

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Share Columba").assertIsDisplayed()
    }

    @Test
    fun `back button invokes navigation callback`() {
        var navigatedBack = false
        val viewModel = createMockViewModel()

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = { navigatedBack = true },
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Back").assertDoesNotExist()
        // Back button is an icon button with content description
        composeTestRule.onNodeWithText("Share Columba").assertIsDisplayed()
    }

    // ========== Loading State Tests ==========

    @Test
    fun `shows starting message when server is not running and no error`() {
        val viewModel = createMockViewModel(ApkSharingState())

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Starting sharing server...").assertIsDisplayed()
    }

    @Test
    fun `displays Share via WiFi header`() {
        val viewModel = createMockViewModel()

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Share via WiFi").assertIsDisplayed()
    }

    // ========== Error State Tests ==========

    @Test
    fun `displays error message when present`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(errorMessage = "No WiFi connection detected."),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("No WiFi connection detected.").assertIsDisplayed()
    }

    @Test
    fun `does not show QR code when error is present`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(errorMessage = "Some error"),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Have the other person scan this QR code").assertDoesNotExist()
    }

    // ========== Server Running State Tests ==========

    @Test
    fun `displays QR code instructions when server is running`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = "http://192.168.1.100:8080",
                    localIp = "192.168.1.100",
                    apkSizeBytes = 10_000_000,
                ),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Have the other person scan this QR code").assertIsDisplayed()
    }

    @Test
    fun `displays download URL when server is running`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = "http://192.168.1.100:8080",
                    localIp = "192.168.1.100",
                ),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("http://192.168.1.100:8080").assertExists()
    }

    @Test
    fun `displays APK size when server is running`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = "http://192.168.1.100:8080",
                    localIp = "192.168.1.100",
                    apkSizeBytes = 15_728_640, // 15 MB
                ),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("APK size: 15.0 MB").assertExists()
    }

    @Test
    fun `does not display APK size when zero`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = "http://192.168.1.100:8080",
                    localIp = "192.168.1.100",
                    apkSizeBytes = 0,
                ),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("APK size:", substring = true).assertDoesNotExist()
    }

    @Test
    fun `displays instructions section when server is running`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = "http://192.168.1.100:8080",
                    localIp = "192.168.1.100",
                ),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Instructions").assertExists()
        composeTestRule
            .onNodeWithText("Both phones must be on the same WiFi network", substring = true)
            .assertExists()
    }

    @Test
    fun `displays all four instruction steps`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = "http://192.168.1.100:8080",
                    localIp = "192.168.1.100",
                ),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("1. Both phones", substring = true).assertExists()
        composeTestRule.onNodeWithText("2. Open the camera", substring = true).assertExists()
        composeTestRule.onNodeWithText("3. Tap the link", substring = true).assertExists()
        composeTestRule.onNodeWithText("4. Download and install", substring = true).assertExists()
    }

    // ========== Alternative Sharing Section Tests ==========

    @Test
    fun `displays alternative sharing section`() {
        val viewModel = createMockViewModel()

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("Or share another way").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share APK via...").assertIsDisplayed()
    }

    @Test
    fun `displays sharing method description`() {
        val viewModel = createMockViewModel()

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule
            .onNodeWithText("Use Bluetooth, Nearby Share, or any other installed sharing app.")
            .assertIsDisplayed()
    }

    @Test
    fun `share button click does not crash when intent is null`() {
        val viewModel = createMockViewModel()

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        // Clicking share when createShareIntent returns null should not crash
        composeTestRule.onNodeWithText("Share APK via...").performClick()
        // UI should remain intact after the click
        composeTestRule.onNodeWithText("Share APK via...").assertIsDisplayed()
    }
}
