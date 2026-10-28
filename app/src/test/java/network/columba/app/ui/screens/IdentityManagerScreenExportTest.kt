package network.columba.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.IdentityManagerUiState
import network.columba.app.viewmodel.IdentityManagerViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression test for Sentry COLUMBA-EG.
 *
 * On devices without a document picker (e.g. Amlogic TV boxes shipping no
 * DocumentsUI), nothing resolves ACTION_CREATE_DOCUMENT and the export
 * launcher's `launch()` threw ActivityNotFoundException, crashing the app.
 * The screen must instead surface a user-visible fallback message.
 *
 * With ShadowApplication.checkActivities(true), Robolectric mirrors the
 * framework: ShadowPackageManager resolves no activity for
 * ACTION_CREATE_DOCUMENT unless one is explicitly registered, so launch()
 * throws ActivityNotFoundException exactly like the no-DocumentsUI device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityManagerScreenExportTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val identities = MutableStateFlow<List<LocalIdentityEntity>>(emptyList())
    private val activeIdentity = MutableStateFlow<LocalIdentityEntity?>(null)
    private val uiState = MutableStateFlow<IdentityManagerUiState>(IdentityManagerUiState.Idle)

    private fun createViewModelMock(): IdentityManagerViewModel =
        mockk<IdentityManagerViewModel> {
            every { this@mockk.identities } returns identities
            every { this@mockk.activeIdentity } returns activeIdentity
            every { this@mockk.uiState } returns uiState
            every { this@mockk.resetUiState() } returns Unit
        }

    @Before
    fun makeIntentResolutionStrict() {
        // Real devices throw ActivityNotFoundException when nothing resolves
        // the picker intent; make Robolectric do the same (COLUMBA-EG repro).
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
    }

    @Test
    fun `binary export with no document picker available shows fallback instead of crashing`() {
        val viewModel = createViewModelMock()

        composeRule.setContent {
            IdentityManagerScreen(onNavigateBack = {}, viewModel = viewModel)
        }

        // Export finished in the ViewModel; the screen now tries to open the
        // SAF save dialog, which no activity on this device can handle.
        uiState.value =
            IdentityManagerUiState.ExportReady(Uri.parse("content://exported/identity.rnsidentity"))

        composeRule.waitForIdle()

        // No crash: the fallback message is surfaced to the user instead.
        composeRule
            .onNodeWithText("No file manager app found on this device")
            .assertIsDisplayed()
    }

    @Test
    fun `identity file import with no document picker available shows fallback instead of crashing`() {
        val viewModel = createViewModelMock()

        composeRule.setContent {
            IdentityManagerScreen(onNavigateBack = {}, viewModel = viewModel)
        }

        // Open the import-type dialog and pick "raw identity file", which
        // launches the ACTION_GET_CONTENT picker.
        composeRule.onNodeWithContentDescription("Import identity").performClick()
        composeRule.onNodeWithText("From Identity File").performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("No file manager app found on this device")
            .assertIsDisplayed()
    }
}
