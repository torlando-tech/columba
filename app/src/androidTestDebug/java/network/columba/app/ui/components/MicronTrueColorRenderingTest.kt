package network.columba.app.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import network.columba.app.micron.MicronParser
import network.columba.app.test.TestHostActivity
import network.columba.app.viewmodel.NomadNetBrowserViewModel.RenderingMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MicronTrueColorRenderingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun rngitCodeBlockRendersTrueColorsWithoutVisibleControlPayloads() {
        val markup =
            ">Quick Start\n" +
                "`BT282828`Fddd`FT8b949e# Add tasks`f\n" +
                "`FTc9d1d9nt add `FTa5d6ff\"Buy groceries\"`f`b"
        val document = MicronParser.parse(markup)

        composeTestRule.setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    MicronPageContent(
                        document = document,
                        formFields = emptyMap(),
                        renderingMode = RenderingMode.MONOSPACE_SCROLL,
                        onLinkClick = { _, _ -> },
                        onFieldUpdate = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Quick Start").assertIsDisplayed()
        composeTestRule.onNodeWithText("# Add tasks").assertIsDisplayed()
        composeTestRule.onNodeWithText("nt add \"Buy groceries\"").assertIsDisplayed()
        composeTestRule.onNodeWithText("T282828", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("T8b949e", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Tc9d1d9", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Ta5d6ff", substring = true).assertDoesNotExist()

        val pixels =
            composeTestRule
                .onNodeWithTag("micron-selection-container")
                .captureToImage()
                .toPixelMap()
        val expectedCodeBackground = Color(0xFF282828).toArgb()
        var closestColor = 0
        var closestDistance = Int.MAX_VALUE
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y].toArgb()
                val distance = colorDistance(color, expectedCodeBackground)
                if (distance < closestDistance) {
                    closestColor = color
                    closestDistance = distance
                }
            }
        }
        assertTrue(
            "Rendered code block should contain the rngit #282828 background; " +
                "closest captured color was #${closestColor.toUInt().toString(16).padStart(8, '0')} " +
                "at RGB distance $closestDistance",
            // Android's display/capture pipeline can shift each channel by one value.
            closestDistance <= 3,
        )
    }

    private fun colorDistance(
        actual: Int,
        expected: Int,
    ): Int {
        val red = ((actual shr 16) and 0xFF) - ((expected shr 16) and 0xFF)
        val green = ((actual shr 8) and 0xFF) - ((expected shr 8) and 0xFF)
        val blue = (actual and 0xFF) - (expected and 0xFF)
        return red * red + green * green + blue * blue
    }
}
