package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for ImageOptionsSheet composable.
 * Tests display and interaction behavior for image save/share options.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageOptionsSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var saveCallbackInvoked = false
    private var shareCallbackInvoked = false
    private var dismissCallbackInvoked = false

    @Before
    fun setUp() {
        saveCallbackInvoked = false
        shareCallbackInvoked = false
        dismissCallbackInvoked = false
    }

    // ========== Display Tests ==========

    @Test
    fun `displays Image title`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Image").assertIsDisplayed()
    }

    @Test
    fun `displays Save to device option`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Save to device").assertIsDisplayed()
    }

    @Test
    fun `displays Save to device description`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Save to a folder on your device").assertIsDisplayed()
    }

    @Test
    fun `displays Share option`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Share").assertIsDisplayed()
    }

    @Test
    fun `displays Share description`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Share via another app").assertIsDisplayed()
    }

    // ========== Interaction Tests ==========

    @Test
    fun `tap Save to device invokes onSaveToDevice callback`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = { saveCallbackInvoked = true },
                onShare = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Save to device").performClick()

        assertTrue("onSaveToDevice callback should be invoked", saveCallbackInvoked)
        assertFalse("onShare callback should not be invoked", shareCallbackInvoked)
    }

    @Test
    fun `tap Share invokes onShare callback`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = { shareCallbackInvoked = true },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Share").performClick()

        assertTrue("onShare callback should be invoked", shareCallbackInvoked)
        assertFalse("onSaveToDevice callback should not be invoked", saveCallbackInvoked)
    }

    @Test
    fun `tap Save description also invokes onSaveToDevice callback`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = { saveCallbackInvoked = true },
                onShare = {},
                onDismiss = {},
            )
        }

        // Click on the description text
        composeTestRule.onNodeWithText("Save to a folder on your device").performClick()

        assertTrue("onSaveToDevice callback should be invoked", saveCallbackInvoked)
    }

    @Test
    fun `tap Share description also invokes onShare callback`() {
        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = {},
                onShare = { shareCallbackInvoked = true },
                onDismiss = {},
            )
        }

        // Click on the description text
        composeTestRule.onNodeWithText("Share via another app").performClick()

        assertTrue("onShare callback should be invoked", shareCallbackInvoked)
    }

    // ========== Multiple Interactions ==========

    @Test
    fun `both options are independently clickable`() {
        var saveCount = 0
        var shareCount = 0

        composeTestRule.setContent {
            ImageOptionsSheet(
                onSaveToDevice = { saveCount++ },
                onShare = { shareCount++ },
                onDismiss = {},
            )
        }

        // Click save
        composeTestRule.onNodeWithText("Save to device").performClick()
        // Click share
        composeTestRule.onNodeWithText("Share").performClick()

        // Both should have been invoked once
        assertTrue("Save should be invoked once", saveCount == 1)
        assertTrue("Share should be invoked once", shareCount == 1)
    }
}
