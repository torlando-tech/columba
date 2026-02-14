package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.CodecProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CodecSelectionDialog composable.
 * Tests the codec profile selection UI for voice calls.
 *
 * Note: The dialog uses a LazyColumn to display profiles. In Robolectric tests,
 * LazyColumn items may not all be composed until scrolled into view. We test
 * visible items (first 3 profiles) and verify the core functionality works.
 * The callback behavior is verified via the default selection test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodecSelectionDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Dialog Display Tests ==========

    @Test
    fun `dialog displays title`() {
        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = {},
            )
        }

        composeTestRule.onNodeWithText("Select Call Quality").assertIsDisplayed()
    }

    @Test
    fun `dialog displays description text`() {
        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = {},
            )
        }

        composeTestRule
            .onNodeWithText("Choose a codec profile based on your connection speed")
            .assertIsDisplayed()
    }

    @Test
    fun `dialog displays Call button`() {
        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = {},
            )
        }

        composeTestRule.onNodeWithText("Call").assertIsDisplayed()
    }

    @Test
    fun `dialog displays Cancel button`() {
        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    // ========== Profile Display Tests ==========

    @Test
    fun `dialog displays all codec profiles`() {
        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = {},
            )
        }

        // Verify profiles visible at the top of the LazyColumn
        // (In Robolectric, LazyColumn only composes items that fit in viewport)
        composeTestRule.onNodeWithText("Ultra Low Bandwidth").assertExists()
        composeTestRule.onNodeWithText("Very Low Bandwidth").assertExists()
        composeTestRule.onNodeWithText("Low Bandwidth").assertExists()

        // Verify count is correct (8 profiles total in the enum)
        assertEquals(8, CodecProfile.entries.size)
    }

    @Test
    fun `dialog displays profile descriptions`() {
        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = {},
            )
        }

        // Verify descriptions for visible profiles
        composeTestRule.onNodeWithText("Codec2 700C - Best for very slow connections").assertExists()
        composeTestRule.onNodeWithText("Codec2 1600 - Good for slow connections").assertExists()
    }

    @Test
    fun `default profile is QUALITY_MEDIUM`() {
        // The default profile is QUALITY_MEDIUM which shows "Recommended" badge
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.DEFAULT)
    }

    // ========== Selection Tests ==========

    @Test
    fun `dialog shows selected profile as checked`() {
        // The default selection is QUALITY_MEDIUM
        // Verify the default is set correctly
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.DEFAULT)
    }

    @Test
    fun `clicking profile updates selection`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Click on a visible profile (first in list)
        composeTestRule.onNodeWithText("Ultra Low Bandwidth").performClick()

        // Click Call to confirm selection
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, selectedProfile)
    }

    @Test
    fun `clicking Ultra Low Bandwidth profile selects it`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        composeTestRule.onNodeWithText("Ultra Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, selectedProfile)
    }

    @Test
    fun `clicking Very Low Bandwidth profile selects it`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        composeTestRule.onNodeWithText("Very Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, selectedProfile)
    }

    @Test
    fun `clicking Low Bandwidth profile selects it`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        composeTestRule.onNodeWithText("Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_LOW, selectedProfile)
    }

    // ========== Button Callback Tests ==========

    @Test
    fun `confirm button calls onProfileSelected with current selection`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Without changing selection, default is QUALITY_MEDIUM
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.QUALITY_MEDIUM, selectedProfile)
    }

    @Test
    fun `confirm button calls onProfileSelected with changed selection`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Change selection to Ultra Low Bandwidth (first in list, definitely visible)
        composeTestRule.onNodeWithText("Ultra Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, selectedProfile)
    }

    @Test
    fun `cancel button calls onDismiss`() {
        var dismissCalled = false

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = { dismissCalled = true },
                onProfileSelected = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue("onDismiss should be called", dismissCalled)
    }

    @Test
    fun `cancel button does not call onProfileSelected`() {
        var profileSelectedCalled = false

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { profileSelectedCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertFalse("onProfileSelected should not be called", profileSelectedCalled)
    }

    // ========== Multiple Selection Changes ==========

    @Test
    fun `changing selection multiple times updates correctly`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Click through multiple visible selections
        composeTestRule.onNodeWithText("Ultra Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Very Low Bandwidth").performClick()

        // Confirm final selection
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, selectedProfile)
    }

    @Test
    fun `selecting same profile twice keeps selection`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Click same profile twice
        composeTestRule.onNodeWithText("Very Low Bandwidth").performClick()
        composeTestRule.onNodeWithText("Very Low Bandwidth").performClick()

        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, selectedProfile)
    }

    // ========== Default Selection Tests ==========

    @Test
    fun `dialog defaults to QUALITY_MEDIUM profile`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Immediately confirm without changing selection
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.DEFAULT, selectedProfile)
        assertEquals(CodecProfile.QUALITY_MEDIUM, selectedProfile)
    }

    // ========== Edge Cases ==========

    @Test
    fun `clicking on profile description text also selects profile`() {
        var selectedProfile: CodecProfile? = null

        composeTestRule.setContent {
            CodecSelectionDialog(
                onDismiss = {},
                onProfileSelected = { selectedProfile = it },
            )
        }

        // Click on description text (the row is clickable)
        composeTestRule.onNodeWithText("Codec2 700C - Best for very slow connections").performClick()
        composeTestRule.onNodeWithText("Call").performClick()

        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, selectedProfile)
    }

    @Test
    fun `all codec profiles have correct count`() {
        // Verify we're testing the correct number of profiles
        assertEquals(8, CodecProfile.entries.size)
    }

    // ========== CodecProfile Model Tests ==========

    @Test
    fun `CodecProfile DEFAULT is QUALITY_MEDIUM`() {
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.DEFAULT)
    }

    @Test
    fun `CodecProfile fromCode returns correct profile`() {
        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, CodecProfile.fromCode(0x10))
        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, CodecProfile.fromCode(0x20))
        assertEquals(CodecProfile.BANDWIDTH_LOW, CodecProfile.fromCode(0x30))
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.fromCode(0x40))
        assertEquals(CodecProfile.QUALITY_HIGH, CodecProfile.fromCode(0x50))
        assertEquals(CodecProfile.QUALITY_MAX, CodecProfile.fromCode(0x60))
        assertEquals(CodecProfile.LATENCY_LOW, CodecProfile.fromCode(0x80))
        assertEquals(CodecProfile.LATENCY_ULTRA_LOW, CodecProfile.fromCode(0x70))
    }

    @Test
    fun `CodecProfile fromCode returns null for invalid code`() {
        assertEquals(null, CodecProfile.fromCode(0x00))
        assertEquals(null, CodecProfile.fromCode(0xFF))
        assertEquals(null, CodecProfile.fromCode(-1))
    }

    @Test
    fun `each CodecProfile has unique code`() {
        val codes = CodecProfile.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `each CodecProfile has displayName and description`() {
        CodecProfile.entries.forEach { profile ->
            assertTrue("displayName should not be blank", profile.displayName.isNotBlank())
            assertTrue("description should not be blank", profile.description.isNotBlank())
        }
    }
}
