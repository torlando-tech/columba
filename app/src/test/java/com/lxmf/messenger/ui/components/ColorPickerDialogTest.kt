package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ColorPickerDialog composable.
 * Tests the color picker functionality including HSL sliders, hex input,
 * and ROYGBIV preset colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ColorPickerDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Dialog Display Tests ==========

    @Test
    fun colorPickerDialog_displaysTitle() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                title = "Pick a Color",
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Pick a Color").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_displaysCustomTitle() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                title = "Choose Background Color",
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Choose Background Color").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_displaysHexInputField() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hex Color").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_displaysHueSliderLabel() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_displaysSaturationSliderLabel() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Saturation label should exist in the node tree (may not be visible due to scrolling)
        composeTestRule.onNodeWithText("Saturation").assertExists()
    }

    @Test
    fun colorPickerDialog_displaysLightnessSliderLabel() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Lightness label should exist in the node tree (may not be visible due to scrolling)
        composeTestRule.onNodeWithText("Lightness").assertExists()
    }

    // ========== Button Tests ==========

    @Test
    fun colorPickerDialog_displaysConfirmButton() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_displaysCancelButton() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_cancelButtonCallsOnDismiss() {
        var dismissCalled = false

        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = {},
                onDismiss = { dismissCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue("onDismiss should be called when Cancel is clicked", dismissCalled)
    }

    @Test
    fun colorPickerDialog_confirmButtonCallsBothCallbacks() {
        var confirmCalled = false
        var dismissCalled = false

        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = { confirmCalled = true },
                onDismiss = { dismissCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Confirm").performClick()
        assertTrue("onConfirm should be called when Confirm is clicked", confirmCalled)
        assertTrue("onDismiss should be called when Confirm is clicked", dismissCalled)
    }

    // ========== Initial Color Tests ==========

    @Test
    fun colorPickerDialog_initializesWithProvidedColor() {
        // We can't easily test the visual color, but we can verify the dialog loads
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Red,
                onConfirm = {},
                onDismiss = {},
            )
        }

        // If no exception is thrown, the initial color was parsed successfully
        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_handlesWhiteColor() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.White,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_handlesBlackColor() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Black,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_handlesTransparentColor() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Transparent,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    // ========== Color Confirmation Tests ==========

    @Test
    fun colorPickerDialog_confirmReturnsColor() {
        var confirmedColor: Color? = null

        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color.Blue,
                onConfirm = { confirmedColor = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Confirm").performClick()
        assertTrue("Confirmed color should not be null", confirmedColor != null)
    }

    // ========== Preset Display Tests ==========
    // Note: We can't directly test preset colors visually in unit tests,
    // but we verify the dialog functions correctly with various initial colors

    @Test
    fun colorPickerDialog_rendersWithMaterialBlue() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color(0xFF2196F3),
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_rendersWithMaterialGreen() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color(0xFF4CAF50),
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }

    @Test
    fun colorPickerDialog_rendersWithMaterialRed() {
        composeTestRule.setContent {
            ColorPickerDialog(
                initialColor = Color(0xFFF44336),
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Hue").assertIsDisplayed()
    }
}
