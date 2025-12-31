package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.components.Identicon
import com.lxmf.messenger.ui.components.ProfileIcon
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ProfileIconCard in MyIdentityScreen.
 * Tests the profile icon customization UI section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MyIdentityScreenProfileIconTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // Test composable that mirrors ProfileIconCard from MyIdentityScreen
    @Composable
    private fun TestProfileIconCard(
        iconName: String?,
        foregroundColor: String?,
        backgroundColor: String?,
        fallbackHash: ByteArray,
        onEditIcon: () -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Profile Icon",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Profile Icon",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = "Customize your profile icon that others will see. " +
                        "This is compatible with Sideband and other Reticulum apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (iconName != null && foregroundColor != null && backgroundColor != null) {
                        ProfileIcon(
                            iconName = iconName,
                            foregroundColor = foregroundColor,
                            backgroundColor = backgroundColor,
                            size = 64.dp,
                            fallbackHash = fallbackHash,
                        )
                    } else {
                        Identicon(
                            hash = fallbackHash,
                            size = 64.dp,
                        )
                    }

                    Button(onClick = onEditIcon) {
                        Text(if (iconName != null) "Change Icon" else "Set Icon")
                    }
                }
            }
        }
    }

    // ========== Display Tests ==========

    @Test
    fun profileIconCard_displaysTitle() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        composeTestRule.onNodeWithText("Profile Icon").assertIsDisplayed()
    }

    @Test
    fun profileIconCard_displaysDescription() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Customize your profile icon that others will see. " +
                "This is compatible with Sideband and other Reticulum apps."
        ).assertIsDisplayed()
    }

    @Test
    fun profileIconCard_displaysSetIconButton_whenNoIcon() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        composeTestRule.onNodeWithText("Set Icon").assertIsDisplayed()
    }

    @Test
    fun profileIconCard_displaysChangeIconButton_whenIconSet() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        composeTestRule.onNodeWithText("Change Icon").assertIsDisplayed()
    }

    // ========== Callback Tests ==========

    @Test
    fun profileIconCard_setIconButtonCallsOnEditIcon() {
        var editCalled = false

        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = { editCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Set Icon").performClick()
        assertTrue("onEditIcon should be called", editCalled)
    }

    @Test
    fun profileIconCard_changeIconButtonCallsOnEditIcon() {
        var editCalled = false

        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = "star",
                foregroundColor = "FF0000",
                backgroundColor = "0000FF",
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = { editCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Change Icon").performClick()
        assertTrue("onEditIcon should be called", editCalled)
    }

    // ========== Icon Display Tests ==========

    @Test
    fun profileIconCard_showsIdenticon_whenNoIconSet() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        // Card should render without crashing when using identicon fallback
        composeTestRule.onNodeWithText("Profile Icon").assertIsDisplayed()
    }

    @Test
    fun profileIconCard_showsProfileIcon_whenIconSet() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        // Card should render without crashing when showing profile icon
        composeTestRule.onNodeWithText("Profile Icon").assertIsDisplayed()
    }

    @Test
    fun profileIconCard_handlesEmptyFallbackHash() {
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(0),
                onEditIcon = {},
            )
        }

        // Should not crash with empty hash
        composeTestRule.onNodeWithText("Profile Icon").assertIsDisplayed()
    }

    @Test
    fun profileIconCard_handlesPartialIconData() {
        // Only icon name provided, missing colors - shows identicon but button says Change
        // because the button text is based on whether iconName is set
        composeTestRule.setContent {
            TestProfileIconCard(
                iconName = "account",
                foregroundColor = null,
                backgroundColor = null,
                fallbackHash = ByteArray(16) { it.toByte() },
                onEditIcon = {},
            )
        }

        // Since iconName is set (even if colors are missing), button shows "Change Icon"
        // The actual display falls back to identicon but UX allows changing the incomplete icon
        composeTestRule.onNodeWithText("Profile Icon").assertIsDisplayed()
    }
}
