package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.service.SyncProgress
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.PendingFileInfo
import com.lxmf.messenger.ui.screens.PendingFileNotificationBubble
import org.junit.Assert.assertEquals
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
 * UI tests for PendingFileNotificationBubble composable.
 * Tests display of different sync states (Idle, Starting, InProgress, Complete)
 * and verifies correct text, icons, and click behavior for each state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingFileNotificationBubbleTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var clickCount = 0

    private val testPendingFileInfo = PendingFileInfo(
        originalMessageId = "msg123",
        filename = "document.pdf",
        fileCount = 1,
        totalSize = 1024 * 1024L, // 1 MB
    )

    private val testPeerName = "Alice"

    @Before
    fun setUp() {
        clickCount = 0
    }

    // ========== Idle State Tests ==========

    @Test
    fun `idle state shows tap to fetch text`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Tap to fetch from relay").assertIsDisplayed()
    }

    @Test
    fun `idle state shows peer name sent message`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("$testPeerName sent a large file").assertIsDisplayed()
    }

    @Test
    fun `idle state shows filename and size`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("document.pdf (1.0 MB)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `idle state is clickable`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Tap to fetch from relay").performClick()

        assertEquals("Should register click", 1, clickCount)
    }

    // ========== Starting State Tests ==========

    @Test
    fun `starting state shows connecting text`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Starting,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Connecting to relay...").assertIsDisplayed()
    }

    @Test
    fun `starting state shows fetching file text`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Starting,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Fetching file...").assertIsDisplayed()
    }

    @Test
    fun `starting state does not register clicks`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Starting,
                onClick = { clickCount++ },
            )
        }

        // Try to click - should not register because sync is in progress
        composeTestRule.onNodeWithText("Fetching file...").performClick()

        assertEquals("Should not register click during sync", 0, clickCount)
    }

    // ========== InProgress State Tests ==========

    @Test
    fun `inProgress with path_requested shows discovering network path`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("path_requested", 0f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Discovering network path...").assertIsDisplayed()
    }

    @Test
    fun `inProgress with link_establishing shows establishing connection`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("link_establishing", 0f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Establishing connection...").assertIsDisplayed()
    }

    @Test
    fun `inProgress with link_established shows connected preparing`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("link_established", 0f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Connected, preparing...").assertIsDisplayed()
    }

    @Test
    fun `inProgress with request_sent shows requesting messages`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("request_sent", 0f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Requesting messages...").assertIsDisplayed()
    }

    @Test
    fun `inProgress with receiving and progress shows downloading percentage`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("receiving", 0.75f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Downloading: 75%").assertIsDisplayed()
    }

    @Test
    fun `inProgress with receiving and zero progress shows downloading without percentage`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("receiving", 0f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Downloading...").assertIsDisplayed()
    }

    @Test
    fun `inProgress with downloading state name shows percentage`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("downloading", 0.50f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Downloading: 50%").assertIsDisplayed()
    }

    @Test
    fun `inProgress with unknown state shows processing`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("unknown_state", 0.5f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Processing...").assertIsDisplayed()
    }

    @Test
    fun `inProgress does not register clicks`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("receiving", 0.5f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Fetching file...").performClick()

        assertEquals("Should not register click during sync", 0, clickCount)
    }

    @Test
    fun `inProgress handles mixed case state names`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("PATH_REQUESTED", 0f),
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Discovering network path...").assertIsDisplayed()
    }

    // ========== Complete State Tests ==========

    @Test
    fun `complete state shows download complete text`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Complete,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Download complete").assertIsDisplayed()
    }

    @Test
    fun `complete state does not register clicks`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Complete,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("Fetching file...").performClick()

        assertEquals("Should not register click during complete state", 0, clickCount)
    }

    // ========== Multi-file Tests ==========

    @Test
    fun `shows additional files count when fileCount greater than 1`() {
        val multiFileInfo = testPendingFileInfo.copy(fileCount = 3)

        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = multiFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("+2 more files").assertIsDisplayed()
    }

    @Test
    fun `shows singular file text when fileCount is 2`() {
        val twoFileInfo = testPendingFileInfo.copy(fileCount = 2)

        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = twoFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("+1 more file").assertIsDisplayed()
    }

    @Test
    fun `hides additional files text when fileCount is 1`() {
        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = testPendingFileInfo, // fileCount = 1
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        composeTestRule.onNodeWithText("+", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `hides additional files text during sync even with multiple files`() {
        val multiFileInfo = testPendingFileInfo.copy(fileCount = 3)

        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = multiFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.InProgress("receiving", 0.5f),
                onClick = { clickCount++ },
            )
        }

        // During sync, the additional files text should be hidden
        composeTestRule.onNodeWithText("+2 more files").assertDoesNotExist()
    }

    // ========== File Size Display Tests ==========

    @Test
    fun `displays KB for small files`() {
        val smallFileInfo = testPendingFileInfo.copy(totalSize = 512 * 1024L) // 512 KB

        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = smallFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        // formatFileSize uses integer division for KB
        composeTestRule.onNodeWithText("512 KB", substring = true).assertIsDisplayed()
    }

    @Test
    fun `displays GB for very large files`() {
        val largeFileInfo = testPendingFileInfo.copy(totalSize = 2L * 1024 * 1024 * 1024) // 2 GB

        composeTestRule.setContent {
            PendingFileNotificationBubble(
                pendingFileInfo = largeFileInfo,
                peerName = testPeerName,
                syncProgress = SyncProgress.Idle,
                onClick = { clickCount++ },
            )
        }

        // formatFileSize uses 2 decimal places for GB
        composeTestRule.onNodeWithText("2.00 GB", substring = true).assertIsDisplayed()
    }
}
