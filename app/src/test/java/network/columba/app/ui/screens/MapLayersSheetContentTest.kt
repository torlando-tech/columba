package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.util.InterfaceCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapLayersSheetContentTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun rendersHeaderAndOneChipPerProvidedCategory() {
        composeRule.setContent {
            MapLayersSheetContent(
                categories = listOf(InterfaceCategory.TCP, InterfaceCategory.BLUETOOTH, InterfaceCategory.LORA),
                filterEnabled =
                    mapOf(
                        InterfaceCategory.TCP to true,
                        InterfaceCategory.BLUETOOTH to true,
                        InterfaceCategory.LORA to true,
                    ),
                onToggle = {},
            )
        }

        composeRule.onNodeWithText("Show on map").assertIsDisplayed()
        composeRule.onNodeWithText("TCP/IP").assertIsDisplayed()
        composeRule.onNodeWithText("Bluetooth").assertIsDisplayed()
        composeRule.onNodeWithText("LoRa Radio").assertIsDisplayed()
    }

    @Test
    fun chipSelectedStateReflectsFilterEnabledMap() {
        composeRule.setContent {
            MapLayersSheetContent(
                categories = listOf(InterfaceCategory.TCP, InterfaceCategory.BLUETOOTH),
                filterEnabled =
                    mapOf(
                        InterfaceCategory.TCP to true,
                        InterfaceCategory.BLUETOOTH to false,
                    ),
                onToggle = {},
            )
        }

        composeRule.onNodeWithText("TCP/IP").assertIsSelected()
        composeRule.onNodeWithText("Bluetooth").assertIsNotSelected()
    }

    @Test
    fun categoryMissingFromMapDefaultsToSelected() {
        composeRule.setContent {
            MapLayersSheetContent(
                categories = listOf(InterfaceCategory.TCP),
                // Empty map — chip should still render as selected via ?: true fallback
                filterEnabled = emptyMap(),
                onToggle = {},
            )
        }

        composeRule.onNodeWithText("TCP/IP").assertIsSelected()
    }

    @Test
    fun clickingChipInvokesOnToggleWithThatCategory() {
        val toggled = mutableListOf<InterfaceCategory>()
        composeRule.setContent {
            MapLayersSheetContent(
                categories = listOf(InterfaceCategory.TCP, InterfaceCategory.LORA),
                filterEnabled =
                    mapOf(
                        InterfaceCategory.TCP to true,
                        InterfaceCategory.LORA to true,
                    ),
                onToggle = { toggled += it },
            )
        }

        composeRule.onNodeWithText("LoRa Radio").performClick()

        assertEquals(listOf(InterfaceCategory.LORA), toggled)
    }
}
