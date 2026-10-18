package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.MigrationUiState
import network.columba.app.viewmodel.MigrationViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for MigrationScreen export section.
 * Tests the include attachments checkbox behavior and helper text display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private lateinit var mockViewModel: MigrationViewModel
    private val includeAttachmentsFlow = MutableStateFlow(true)

    private fun resText(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Suppress("NoRelaxedMocks") // MigrationViewModel is a complex ViewModel with many properties; explicit stubbing for all would be excessive
    @Before
    fun setup() {
        mockViewModel = mockk(relaxed = true)

        // Setup default mock returns
        every { mockViewModel.uiState } returns MutableStateFlow(MigrationUiState.Idle)
        every { mockViewModel.exportProgress } returns MutableStateFlow(0f)
        every { mockViewModel.importProgress } returns MutableStateFlow(0f)
        every { mockViewModel.exportPreview } returns MutableStateFlow(null)
        every { mockViewModel.includeAttachments } returns includeAttachmentsFlow
    }

    // ========== Include Attachments Checkbox Tests ==========

    @Test
    fun `checkbox displays Include file attachments label`() {
        includeAttachmentsFlow.value = true

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.migration_include_attachments)).assertIsDisplayed()
    }

    @Test
    fun `helper text not shown when checkbox is checked`() {
        includeAttachmentsFlow.value = true

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        // Helper text should NOT be displayed when checked
        composeTestRule
            .onNodeWithText(resText(R.string.migration_no_attachments_note))
            .assertDoesNotExist()
    }

    @Test
    fun `helper text appears when checkbox is unchecked`() {
        includeAttachmentsFlow.value = false

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule
            .onNodeWithText(resText(R.string.migration_no_attachments_note))
            .assertIsDisplayed()
    }

    @Test
    fun `clicking label text triggers setIncludeAttachments on viewmodel`() {
        includeAttachmentsFlow.value = true

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        // Then - checkbox label is displayed
        composeTestRule.onNodeWithText(resText(R.string.migration_include_attachments)).assertIsDisplayed()

        // Click on the label text (entire row is clickable)
        composeTestRule.onNodeWithText(resText(R.string.migration_include_attachments)).performClick()

        // Verify the ViewModel method was called with toggled value (true -> false)
        verify { mockViewModel.setIncludeAttachments(false) }
    }

    @Test
    fun `clicking label when unchecked triggers setIncludeAttachments with true`() {
        includeAttachmentsFlow.value = false

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        // Then - checkbox label and helper text are displayed
        composeTestRule.onNodeWithText(resText(R.string.migration_include_attachments)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(resText(R.string.migration_no_attachments_note))
            .assertIsDisplayed()

        // Click on the label text
        composeTestRule.onNodeWithText(resText(R.string.migration_include_attachments)).performClick()

        // Verify the ViewModel method was called with toggled value (false -> true)
        verify { mockViewModel.setIncludeAttachments(true) }
    }

    @Test
    fun `export button is displayed`() {
        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.migration_export_all_data)).assertIsDisplayed()
    }

    @Test
    fun `export section header is displayed`() {
        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.migration_export_data_title)).assertIsDisplayed()
    }

    // ========== Notification Permission Dialog Tests ==========

    @Test
    fun `notification permission dialog not shown initially`() {
        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        // Dialog should not be visible on initial screen load
        composeTestRule.onNodeWithText(resText(R.string.migration_enable_notifications_title)).assertDoesNotExist()
    }
}
