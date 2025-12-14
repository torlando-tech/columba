package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.test.TestFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for PeerCard composable.
 * Tests the peer card component used on announces and saved peers screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun peerCard_displaysPeerName() {
        // Given
        val announce = TestFactories.createAnnounce(peerName = "Alice")

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysDestinationHash() {
        // Given
        val announce = TestFactories.createAnnounce()

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - destination hash is abbreviated to first 16 chars
        composeTestRule.onNodeWithText(announce.destinationHash.take(16)).assertIsDisplayed()
    }

    // ========== Star Button Tests ==========

    @Test
    fun peerCard_starButton_showsSaveToContacts_whenNotFavorite() {
        // Given
        val announce = TestFactories.createAnnounce(isFavorite = false)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
                showFavoriteToggle = true,
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Save to contacts").assertIsDisplayed()
    }

    @Test
    fun peerCard_starButton_showsRemoveFromContacts_whenFavorite() {
        // Given
        val announce = TestFactories.createAnnounce(isFavorite = true)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
                showFavoriteToggle = true,
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Remove from contacts").assertIsDisplayed()
    }

    @Test
    fun peerCard_starButton_showsRemoveFromContacts_whenShowFavoriteToggleFalse() {
        // Given - on SavedPeersScreen, showFavoriteToggle is false and all items show as starred
        val announce = TestFactories.createAnnounce(isFavorite = false)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
                showFavoriteToggle = false,
            )
        }

        // Then - should show as starred even when isFavorite is false
        composeTestRule.onNodeWithContentDescription("Remove from contacts").assertIsDisplayed()
    }

    @Test
    fun peerCard_starButton_clickCallsOnFavoriteClick() {
        // Given
        var favoriteClicked = false
        val announce = TestFactories.createAnnounce(isFavorite = false)

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = { favoriteClicked = true },
                showFavoriteToggle = true,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Save to contacts").performClick()

        // Then
        assertTrue(favoriteClicked)
    }

    @Test
    fun peerCard_starButton_clickWhenFavorite_callsOnFavoriteClick() {
        // Given
        var favoriteClicked = false
        val announce = TestFactories.createAnnounce(isFavorite = true)

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = { favoriteClicked = true },
                showFavoriteToggle = true,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Remove from contacts").performClick()

        // Then
        assertTrue(favoriteClicked)
    }

    // ========== Click Interaction Tests ==========

    @Test
    fun peerCard_click_callsOnClick() {
        // Given
        var clicked = false
        val announce = TestFactories.createAnnounce(peerName = "Bob")

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = { clicked = true },
                onFavoriteClick = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Bob").performClick()

        // Then
        assertTrue(clicked)
    }

    @Test
    fun peerCard_multipleStarClicks_callsOnFavoriteClickEachTime() {
        // Given
        var clickCount = 0
        val announce = TestFactories.createAnnounce(isFavorite = false)

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = { clickCount++ },
                showFavoriteToggle = true,
            )
        }

        // When
        repeat(3) {
            composeTestRule.onNodeWithContentDescription("Save to contacts").performClick()
        }

        // Then
        assertEquals(3, clickCount)
    }
}
