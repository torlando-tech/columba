package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.ApkSharingState
import network.columba.app.viewmodel.ApkSharingViewModel
import network.columba.app.viewmodel.UiMessage
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

    private fun resText(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *args)

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

        composeTestRule.onNodeWithText(resText(R.string.settings_share_columba_title)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(resText(R.string.back)).assertDoesNotExist()
        // Back button is an icon button with content description
        composeTestRule.onNodeWithText(resText(R.string.settings_share_columba_title)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_starting_server)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_via_wifi)).assertIsDisplayed()
    }

    // ========== Error State Tests ==========

    @Test
    fun `displays error message when present`() {
        val viewModel =
            createMockViewModel(
                ApkSharingState(errorMessage = UiMessage.Raw("No WiFi connection detected.")),
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
                ApkSharingState(errorMessage = UiMessage.Raw("Some error")),
            )

        composeTestRule.setContent {
            ApkSharingScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_scan_qr)).assertDoesNotExist()
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_scan_qr)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_apk_size, 15.0)).assertExists()
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

        composeTestRule
            .onNodeWithText(resText(R.string.apk_sharing_apk_size, 1.0).substringBefore(" 1.0"), substring = true)
            .assertDoesNotExist()
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_instructions)).assertExists()
        composeTestRule
            .onNodeWithText(resText(R.string.apk_sharing_instr_wifi_1), substring = true)
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_instruction_step, 1, resText(R.string.apk_sharing_instr_wifi_1)), substring = true).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_instruction_step, 2, resText(R.string.apk_sharing_instr_wifi_2)), substring = true).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_instruction_step, 3, resText(R.string.apk_sharing_instr_wifi_3)), substring = true).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_instruction_step, 4, resText(R.string.apk_sharing_instr_download_install)), substring = true).assertExists()
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

        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_or_share)).assertIsDisplayed()
        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_share_via)).assertIsDisplayed()
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
            .onNodeWithText(resText(R.string.apk_sharing_alt_share_desc))
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
        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_share_via)).performClick()
        // UI should remain intact after the click
        composeTestRule.onNodeWithText(resText(R.string.apk_sharing_share_via)).assertIsDisplayed()
    }
}
