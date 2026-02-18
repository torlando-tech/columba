package com.lxmf.messenger.ui.components

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.test.RegisterComponentActivityRule
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

    private var permissionRequested = false
    private var galleryClicked = false
    private var fileClicked = false
    private var selectedPhoto: Uri? = null

    @Before
    fun setUp() {
        permissionRequested = false
        galleryClicked = false
        fileClicked = false
        selectedPhoto = null
    }

    // ========== Permission Prompt Tests ==========

    @Test
    fun `shows permission prompt when no media permission`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = false,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithText("Allow access to show recent photos").assertIsDisplayed()
        composeTestRule.onNodeWithText("Allow access").assertIsDisplayed()
    }

    @Test
    fun `tapping allow access button requests permission`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = false,
                onRequestMediaPermission = { permissionRequested = true },
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithText("Allow access").performClick()
        assertTrue(permissionRequested)
    }

    // ========== Empty State Tests ==========

    @Test
    fun `shows no photos found when permission granted but no photos`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = true,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithText("No photos found").assertIsDisplayed()
    }

    // ========== Action Button Tests ==========

    @Test
    fun `gallery button is displayed`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = true,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithText("Gallery").assertIsDisplayed()
    }

    @Test
    fun `file button is displayed`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = true,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithText("File").assertIsDisplayed()
    }

    @Test
    fun `tapping gallery button calls onGalleryClick`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = true,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = { galleryClicked = true },
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Gallery").performClick()
        assertTrue(galleryClicked)
    }

    @Test
    fun `tapping file button calls onFileClick`() {
        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = emptyList(),
                hasMediaPermission = true,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = { fileClicked = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("File").performClick()
        assertTrue(fileClicked)
    }

    // ========== Photo Grid Tests ==========

    @Test
    fun `does not show permission prompt when photos are available`() {
        val photos =
            listOf(
                Uri.parse("content://media/external/images/media/1"),
                Uri.parse("content://media/external/images/media/2"),
            )

        composeTestRule.setContent {
            AttachmentPanel(
                panelHeight = 300.dp,
                recentPhotos = photos,
                hasMediaPermission = true,
                onRequestMediaPermission = {},
                onPhotoSelected = {},
                onGalleryClick = {},
                onFileClick = {},
            )
        }

        composeTestRule.onNodeWithText("Allow access to show recent photos").assertDoesNotExist()
        composeTestRule.onNodeWithText("No photos found").assertDoesNotExist()
    }
}
