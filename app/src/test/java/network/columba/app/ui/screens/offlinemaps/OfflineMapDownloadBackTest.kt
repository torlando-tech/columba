package network.columba.app.ui.screens.offlinemaps

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.DownloadProgress
import network.columba.app.viewmodel.DownloadWizardStep
import network.columba.app.viewmodel.OfflineMapDownloadState
import network.columba.app.viewmodel.OfflineMapDownloadViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun resText(id: Int): String =
    ApplicationProvider.getApplicationContext<Application>().getString(id)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapDownloadBackTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun toolbarAndSystemBack_onLocation_bothExitWizard() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        val viewModel = mockk<OfflineMapDownloadViewModel>()
        every { viewModel.state } returns MutableStateFlow(OfflineMapDownloadState())
        var exitCount = 0

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            OfflineMapDownloadScreen(
                onNavigateBack = { exitCount++ },
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithContentDescription(resText(R.string.offline_map_download_cd_back)).performClick()
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(2, exitCount)
    }

    @Test
    fun toolbarAndSystemBack_onSetupStep_bothMoveToPreviousStep() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        val viewModel = mockk<OfflineMapDownloadViewModel>()
        val radiusState =
            OfflineMapDownloadState(
                step = DownloadWizardStep.RADIUS,
                centerLatitude = 45.5,
                centerLongitude = -122.6,
            )
        val state = MutableStateFlow(radiusState)
        every { viewModel.state } returns state
        every { viewModel.previousStep() } answers {
            state.value = state.value.copy(step = DownloadWizardStep.LOCATION)
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            OfflineMapDownloadScreen(viewModel = viewModel)
        }

        composeRule.onNodeWithContentDescription(resText(R.string.offline_map_download_cd_back)).performClick()
        composeRule.waitForIdle()
        assertEquals(DownloadWizardStep.LOCATION, state.value.step)

        state.value = radiusState
        composeRule.waitForIdle()
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(DownloadWizardStep.LOCATION, state.value.step)
        verify(exactly = 2) { viewModel.previousStep() }
    }

    @Test
    fun toolbarAndSystemBack_duringDownload_bothRequireCancellationConfirmation() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        val viewModel = mockk<OfflineMapDownloadViewModel>()
        every { viewModel.state } returns
            MutableStateFlow(
                OfflineMapDownloadState(
                    step = DownloadWizardStep.DOWNLOADING,
                    downloadProgress = DownloadProgress(progress = 0.5f),
                ),
            )

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            OfflineMapDownloadScreen(viewModel = viewModel)
        }

        composeRule.onNodeWithContentDescription(resText(R.string.offline_map_download_cd_cancel)).performClick()
        composeRule.onNodeWithText(resText(R.string.offline_map_download_cancel_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText(resText(R.string.offline_map_download_cancel_dialog_continue)).performClick()

        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(resText(R.string.offline_map_download_cancel_dialog_title)).assertIsDisplayed()
    }
}
