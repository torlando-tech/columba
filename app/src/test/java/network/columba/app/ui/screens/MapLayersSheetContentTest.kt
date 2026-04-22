package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.data.model.MapStylePreference
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

    private fun render(
        categories: List<InterfaceCategory> = listOf(InterfaceCategory.TCP),
        filterEnabled: Map<InterfaceCategory, Boolean> = mapOf(InterfaceCategory.TCP to true),
        onToggle: (InterfaceCategory) -> Unit = {},
        stylePreference: MapStylePreference = MapStylePreference.AUTO,
        onStylePreferenceChange: (MapStylePreference) -> Unit = {},
    ) {
        composeRule.setContent {
            MapLayersSheetContent(
                categories = categories,
                filterEnabled = filterEnabled,
                onToggle = onToggle,
                stylePreference = stylePreference,
                onStylePreferenceChange = onStylePreferenceChange,
            )
        }
    }

    @Test
    fun rendersStylePickerAndOneChipPerProvidedCategory() {
        render(
            categories = listOf(InterfaceCategory.TCP, InterfaceCategory.BLUETOOTH, InterfaceCategory.LORA),
            filterEnabled =
                mapOf(
                    InterfaceCategory.TCP to true,
                    InterfaceCategory.BLUETOOTH to true,
                    InterfaceCategory.LORA to true,
                ),
        )

        composeRule.onNodeWithText("Map style").assertIsDisplayed()
        composeRule.onNodeWithText("Auto").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("Show on map").assertIsDisplayed()
        composeRule.onNodeWithText("TCP/IP").assertIsDisplayed()
        composeRule.onNodeWithText("Bluetooth").assertIsDisplayed()
        composeRule.onNodeWithText("LoRa Radio").assertIsDisplayed()
    }

    @Test
    fun hidesInterfaceChipsSectionWhenNoCategories() {
        render(categories = emptyList(), filterEnabled = emptyMap())

        // Style picker still shown
        composeRule.onNodeWithText("Map style").assertIsDisplayed()
        composeRule.onNodeWithText("Auto").assertIsDisplayed()
        // "Show on map" header should NOT be displayed when there are no categories
        composeRule
            .onAllNodesWithText("Show on map")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }
    }

    @Test
    fun stylePickerSelectedReflectsPreference() {
        render(stylePreference = MapStylePreference.DARK)

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("Auto").assertIsNotSelected()
        composeRule.onNodeWithText("Light").assertIsNotSelected()
    }

    @Test
    fun clickingStylePickerInvokesCallback() {
        val changes = mutableListOf<MapStylePreference>()
        render(
            stylePreference = MapStylePreference.AUTO,
            onStylePreferenceChange = { changes += it },
        )

        composeRule.onNodeWithText("Dark").performClick()

        assertEquals(listOf(MapStylePreference.DARK), changes)
    }

    @Test
    fun chipSelectedStateReflectsFilterEnabledMap() {
        render(
            categories = listOf(InterfaceCategory.TCP, InterfaceCategory.BLUETOOTH),
            filterEnabled =
                mapOf(
                    InterfaceCategory.TCP to true,
                    InterfaceCategory.BLUETOOTH to false,
                ),
        )

        composeRule.onNodeWithText("TCP/IP").assertIsSelected()
        composeRule.onNodeWithText("Bluetooth").assertIsNotSelected()
    }

    @Test
    fun categoryMissingFromMapDefaultsToSelected() {
        render(
            categories = listOf(InterfaceCategory.TCP),
            filterEnabled = emptyMap(),
        )

        composeRule.onNodeWithText("TCP/IP").assertIsSelected()
    }

    @Test
    fun clickingChipInvokesOnToggleWithThatCategory() {
        val toggled = mutableListOf<InterfaceCategory>()
        render(
            categories = listOf(InterfaceCategory.TCP, InterfaceCategory.LORA),
            filterEnabled =
                mapOf(
                    InterfaceCategory.TCP to true,
                    InterfaceCategory.LORA to true,
                ),
            onToggle = { toggled += it },
        )

        composeRule.onNodeWithText("LoRa Radio").performClick()

        assertEquals(listOf(InterfaceCategory.LORA), toggled)
    }
}
