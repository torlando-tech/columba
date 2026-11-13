package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttachmentPanelTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var photosClicked = false
    private var galleryClicked = false
    private var fileClicked = false
    private var voiceClicked = false

    @Before
    fun setUp() {
        photosClicked = false
        galleryClicked = false
        fileClicked = false
        voiceClicked = false
    }

    private fun setPanel(
        onPhotosClick: () -> Unit = {},
        onGalleryClick: () -> Unit = {},
        onFileClick: () -> Unit = {},
        onVoiceClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                onPhotosClick = onPhotosClick,
                onGalleryClick = onGalleryClick,
                onFileClick = onFileClick,
                onVoiceClick = onVoiceClick,
            )
        }
    }

    // ========== Photo Picker Entry Point ==========

    @Test
    fun `shows photo picker entry point`() {
        setPanel()

        composeTestRule.onNodeWithText("Choose photos").assertIsDisplayed()
    }

    @Test
    fun `tapping photo picker entry point calls onPhotosClick`() {
        setPanel(onPhotosClick = { photosClicked = true })

        composeTestRule.onNodeWithText("Choose photos").performClick()
        assertTrue(photosClicked)
    }

    // ========== Action Button Tests ==========

    @Test
    fun `gallery button is displayed`() {
        setPanel()

        composeTestRule.onNodeWithText("Gallery").assertIsDisplayed()
    }

    @Test
    fun `file button is displayed`() {
        setPanel()

        composeTestRule.onNodeWithText("File").assertIsDisplayed()
    }

    @Test
    fun `tapping gallery button calls onGalleryClick`() {
        setPanel(onGalleryClick = { galleryClicked = true })

        composeTestRule.onNodeWithContentDescription("Gallery").performClick()
        assertTrue(galleryClicked)
    }

    @Test
    fun `tapping file button calls onFileClick`() {
        setPanel(onFileClick = { fileClicked = true })

        composeTestRule.onNodeWithContentDescription("File").performClick()
        assertTrue(fileClicked)
    }

    @Test
    fun `tapping voice button calls onVoiceClick`() {
        setPanel(onVoiceClick = { voiceClicked = true })

        composeTestRule.onNodeWithContentDescription("Record a voice message").performClick()
        assertTrue(voiceClicked)
    }
}
