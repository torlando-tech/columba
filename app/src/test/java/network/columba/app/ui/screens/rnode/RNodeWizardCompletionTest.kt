package network.columba.app.ui.screens.rnode

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*

import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.RNodeWizardState
import network.columba.app.viewmodel.RNodeWizardViewModel
import network.columba.app.viewmodel.WizardStep
import io.mockk.every
import io.mockk.mockk
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("NoRelaxedMocks") // Screen orchestration tests isolate navigation callbacks from unrelated wizard UI methods.
class RNodeWizardCompletionTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun repairModePendingConfig_doesNotExposeOrdinaryWizard() {
        val viewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(RNodeWizardState())

        composeRule.setContent {
            RNodeWizardScreen(
                editingInterfaceId = 3L,
                repairPairing = true,
                onNavigateBack = {},
                onComplete = {},
                viewModel = viewModel,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Repair RNode Pairing").assertIsDisplayed()
        composeRule.onNodeWithText("Select RNode Device").assertDoesNotExist()
        composeRule.onNodeWithText("Pair via USB").assertDoesNotExist()
        verify(exactly = 1) { viewModel.loadExistingConfig(3L, repairPairing = true) }
    }

    @Test
    fun saveSuccess_isConsumedBeforeCompletionNavigation() {
        val state = MutableStateFlow(RNodeWizardState(saveSuccess = true))
        val viewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        var completionCount = 0
        var completionWasConsumed = false
        every { viewModel.state } returns state
        every { viewModel.consumeSaveSuccess() } answers {
            completionWasConsumed = true
            state.value = state.value.copy(saveSuccess = false)
        }

        composeRule.setContent {
            RNodeWizardScreen(
                onNavigateBack = {},
                onComplete = {
                    assertTrue(completionWasConsumed)
                    completionCount++
                },
                viewModel = viewModel,
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, completionCount)
        verify(exactly = 1) { viewModel.consumeSaveSuccess() }
    }

    @Test
    fun toolbarAndSystemBack_onFirstStep_bothExitWizard() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        var backCount = 0
        val viewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(RNodeWizardState())

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RNodeWizardScreen(
                onNavigateBack = { backCount++ },
                onComplete = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(2, backCount)
    }

    @Test
    fun toolbarAndSystemBack_onLaterStep_bothMoveToPreviousStep() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        val viewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state = MutableStateFlow(RNodeWizardState(currentStep = WizardStep.REVIEW_CONFIGURE))
        every { viewModel.state } returns state
        every { viewModel.goToPreviousStep() } answers {
            state.value = state.value.copy(currentStep = WizardStep.FREQUENCY_SLOT)
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RNodeWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(WizardStep.FREQUENCY_SLOT, state.value.currentStep)
        verify(exactly = 2) { viewModel.goToPreviousStep() }
    }
}
