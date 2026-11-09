package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the saved-sites row menu on the NomadNet tab home: every action is
 * reachable and fires exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NomadNetHomeScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private var opened = 0
    private var pinned = 0
    private var removed = 0

    @Test
    fun menuListsOpenAddToHomeScreenAndRemove() {
        composeRule.setContent { Menu() }

        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onNodeWithText("Add to home screen").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun openingAPageInvokesOnlyTheOpenCallback() {
        composeRule.setContent { Menu() }

        composeRule.onNodeWithText("Open").performClick()

        assertEquals(1, opened)
        assertEquals(0, pinned)
        assertEquals(0, removed)
    }

    @Test
    fun pinningAPageInvokesOnlyThePinCallback() {
        composeRule.setContent { Menu() }

        composeRule.onNodeWithText("Add to home screen").performClick()

        assertEquals(0, opened)
        assertEquals(1, pinned)
        assertEquals(0, removed)
    }

    @Test
    fun removingAPageInvokesOnlyTheRemoveCallback() {
        composeRule.setContent { Menu() }

        composeRule.onNodeWithText("Remove").performClick()

        assertEquals(0, opened)
        assertEquals(0, pinned)
        assertEquals(1, removed)
    }

    @Composable
    private fun Menu() {
        val expanded = mutableStateOf(true)
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            NomadNetSavedSiteMenu(
                onOpen = { opened++ },
                onPinToHomeScreen = { pinned++ },
                onRemove = { removed++ },
            )
        }
    }
}
