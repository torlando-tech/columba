package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.CodecProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for the in-call audio profile picker ([CallProfileSheetContent]).
 *
 * Covers the behaviour the call screen relies on: every LXST profile is
 * offered with its label and description, the profile the backend reports is
 * the selected row, tapping a row reports that exact profile, and the
 * experimental profiles are marked as such.
 *
 * The modal wrapper ([CallProfileSheet]) is not exercised here: its only job
 * is `ModalBottomSheet { content }`, and dialog windows are not part of the
 * Compose test root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallProfileSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    private fun resText(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Test
    fun `picker lists every codec profile with label and description`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(activeProfileCode = null, onProfileSelected = {})
            }
        }

        composeTestRule.onNodeWithText(resText(R.string.call_profile_title)).assertIsDisplayed()

        CodecProfile.entries.forEach { profile ->
            composeTestRule.onNodeWithText(profile.displayName).assertExists()
            composeTestRule.onNodeWithText(profile.description).assertExists()
        }
    }

    @Test
    fun `picker marks the active profile as selected and the rest as not selected`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(
                    activeProfileCode = CodecProfile.QUALITY_HIGH.code,
                    onProfileSelected = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("profileOption_${CodecProfile.QUALITY_HIGH.abbreviation}")
            .assertIsSelected()

        CodecProfile.entries
            .filter { it != CodecProfile.QUALITY_HIGH }
            .forEach { profile ->
                composeTestRule.onNodeWithTag("profileOption_${profile.abbreviation}")
                    .assertIsNotSelected()
            }
    }

    @Test
    fun `picker has no selection when the backend reports no profile`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(activeProfileCode = null, onProfileSelected = {})
            }
        }

        CodecProfile.entries.forEach { profile ->
            composeTestRule.onNodeWithTag("profileOption_${profile.abbreviation}")
                .assertIsNotSelected()
        }
    }

    @Test
    fun `tapping a row reports that exact profile`() {
        var selected: CodecProfile? = null
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(
                    activeProfileCode = CodecProfile.QUALITY_MEDIUM.code,
                    onProfileSelected = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("profileOption_${CodecProfile.BANDWIDTH_ULTRA_LOW.abbreviation}")
            .performClick()

        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, selected)
    }

    @Test
    fun `tapping the row of the active profile still reports it`() {
        var selected: CodecProfile? = null
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(
                    activeProfileCode = CodecProfile.QUALITY_MEDIUM.code,
                    onProfileSelected = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("profileOption_${CodecProfile.QUALITY_MEDIUM.abbreviation}")
            .performClick()

        assertEquals(CodecProfile.QUALITY_MEDIUM, selected)
    }

    @Test
    fun `selection follows the active profile when it changes mid-call`() {
        var activeProfileCode by mutableStateOf(CodecProfile.QUALITY_MEDIUM.code)
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(
                    activeProfileCode = activeProfileCode,
                    onProfileSelected = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("profileOption_MQ").assertIsSelected()
        composeTestRule.onNodeWithTag("profileOption_HQ").assertIsNotSelected()

        activeProfileCode = CodecProfile.QUALITY_HIGH.code
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profileOption_HQ").assertIsSelected()
        composeTestRule.onNodeWithTag("profileOption_MQ").assertIsNotSelected()
    }

    @Test
    fun `experimental profiles are the only rows marked experimental`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(activeProfileCode = null, onProfileSelected = {})
            }
        }

        val experimental = CodecProfile.entries.count { it.isExperimental }

        composeTestRule
            .onAllNodesWithText(resText(R.string.call_profile_experimental))
            .assertCountEquals(experimental)
    }

    @Test
    fun `picker does not apply anything until a row is tapped`() {
        var selected: CodecProfile? = null
        composeTestRule.setContent {
            MaterialTheme {
                CallProfileSheetContent(
                    activeProfileCode = CodecProfile.QUALITY_MEDIUM.code,
                    onProfileSelected = { selected = it },
                )
            }
        }

        composeTestRule.waitForIdle()

        assertNull(selected)
    }
}
