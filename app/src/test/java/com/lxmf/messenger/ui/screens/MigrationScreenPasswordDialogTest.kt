package com.lxmf.messenger.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.MigrationUiState
import com.lxmf.messenger.viewmodel.MigrationViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for PasswordDialog and encryption-related UI in MigrationScreen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationScreenPasswordDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== PasswordDialog Direct Tests (export mode) ==========

    @Test
    fun `export dialog displays title and description`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Encrypt Export",
                description = "Choose a password to protect your export file.",
                isConfirmMode = true,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Encrypt Export").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose a password to protect your export file.").assertIsDisplayed()
    }

    @Test
    fun `export dialog shows password and confirm fields`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Encrypt Export",
                description = "Test",
                isConfirmMode = true,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm password").assertIsDisplayed()
    }

    @Test
    fun `export dialog shows Export button`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = true,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Export").assertIsDisplayed()
    }

    @Test
    fun `import dialog shows Unlock button`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Unlock").assertIsDisplayed()
    }

    @Test
    fun `import dialog does not show confirm field`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm password").assertDoesNotExist()
    }

    @Test
    fun `wrong password shows error message`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = true,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Incorrect password").assertIsDisplayed()
    }

    @Test
    fun `cancel button calls onDismiss`() {
        var dismissed = false
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `export button disabled when password empty`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = true,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Export").assertIsNotEnabled()
    }

    @Test
    fun `short password shows validation error on submit`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Password").performTextInput("short")
        composeTestRule.onNodeWithText("Unlock").performClick()

        composeTestRule.onNodeWithText("Password must be at least 8 characters").assertIsDisplayed()
    }

    @Test
    fun `mismatched passwords show validation error on submit`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = true,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Password").performTextInput("validpassword1")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("differentpass")
        composeTestRule.onNodeWithText("Export").performClick()

        composeTestRule.onNodeWithText("Passwords do not match").assertIsDisplayed()
    }

    @Test
    fun `valid password calls onConfirm`() {
        var confirmedPassword = ""
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = false,
                onConfirm = { confirmedPassword = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Password").performTextInput("validpassword1")
        composeTestRule.onNodeWithText("Unlock").performClick()

        assertEquals("validpassword1", confirmedPassword)
    }

    @Test
    fun `matching passwords in confirm mode calls onConfirm`() {
        var confirmedPassword = ""
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = true,
                isWrongPassword = false,
                onConfirm = { confirmedPassword = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Password").performTextInput("validpassword1")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("validpassword1")
        composeTestRule.onNodeWithText("Export").performClick()

        assertEquals("validpassword1", confirmedPassword)
    }

    @Test
    fun `show hide toggle is displayed`() {
        composeTestRule.setContent {
            PasswordDialog(
                title = "Test",
                description = "Test",
                isConfirmMode = false,
                isWrongPassword = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Show").assertIsDisplayed()
    }

    // ========== MigrationScreen integration: Export button triggers password dialog ==========

    @Suppress("NoRelaxedMocks")
    @Test
    fun `clicking Export All Data shows password dialog`() {
        val mockViewModel = mockk<MigrationViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(MigrationUiState.Idle)
        every { mockViewModel.exportProgress } returns MutableStateFlow(0f)
        every { mockViewModel.importProgress } returns MutableStateFlow(0f)
        every { mockViewModel.exportPreview } returns MutableStateFlow(null)
        every { mockViewModel.includeAttachments } returns MutableStateFlow(true)

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        // Click export button
        composeTestRule.onNodeWithText("Export All Data").performClick()

        // Password dialog should appear
        composeTestRule.onNodeWithText("Encrypt Export").assertIsDisplayed()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `PasswordRequired state shows import password dialog`() {
        val mockUri = mockk<Uri>()
        val mockViewModel = mockk<MigrationViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(MigrationUiState.PasswordRequired(mockUri))
        every { mockViewModel.exportProgress } returns MutableStateFlow(0f)
        every { mockViewModel.importProgress } returns MutableStateFlow(0f)
        every { mockViewModel.exportPreview } returns MutableStateFlow(null)
        every { mockViewModel.includeAttachments } returns MutableStateFlow(true)

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Encrypted Backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unlock").assertIsDisplayed()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `WrongPassword state shows error in import password dialog`() {
        val mockUri = mockk<Uri>()
        val mockViewModel = mockk<MigrationViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns MutableStateFlow(MigrationUiState.WrongPassword(mockUri))
        every { mockViewModel.exportProgress } returns MutableStateFlow(0f)
        every { mockViewModel.importProgress } returns MutableStateFlow(0f)
        every { mockViewModel.exportPreview } returns MutableStateFlow(null)
        every { mockViewModel.includeAttachments } returns MutableStateFlow(true)

        composeTestRule.setContent {
            MigrationScreen(
                onNavigateBack = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Encrypted Backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Incorrect password").assertIsDisplayed()
    }
}
