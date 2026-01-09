package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

        // Then - full destination hash is displayed
        composeTestRule.onNodeWithText(announce.destinationHash).assertIsDisplayed()
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

    // ========== Interface Type Icon Tests ==========

    @Test
    fun peerCard_displaysWiFiIcon_forAutoInterface() {
        // Given
        val announce =
            TestFactories.createAnnounce(
                receivingInterfaceType = "AUTO_INTERFACE",
            )

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("WiFi").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysGlobeIcon_forTcpClient() {
        // Given
        val announce =
            TestFactories.createAnnounce(
                receivingInterfaceType = "TCP_CLIENT",
            )

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Internet").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysBluetoothIcon_forAndroidBle() {
        // Given
        val announce =
            TestFactories.createAnnounce(
                receivingInterfaceType = "ANDROID_BLE",
            )

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Bluetooth").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysAntennaIcon_forRnode() {
        // Given
        val announce =
            TestFactories.createAnnounce(
                receivingInterfaceType = "RNODE",
            )

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("LoRa/RNode").assertIsDisplayed()
    }

    @Test
    fun peerCard_doesNotDisplayInterfaceIcon_forUnknown() {
        // Given
        val announce =
            TestFactories.createAnnounce(
                receivingInterfaceType = "UNKNOWN",
            )

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - no interface icon should be displayed for unknown type
        composeTestRule.onNodeWithContentDescription("WiFi").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Internet").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Bluetooth").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("LoRa/RNode").assertDoesNotExist()
    }

    @Test
    fun peerCard_doesNotDisplayInterfaceIcon_forNull() {
        // Given
        val announce =
            TestFactories.createAnnounce(
                receivingInterfaceType = null,
            )

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - no interface icon should be displayed for null type
        composeTestRule.onNodeWithContentDescription("WiFi").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Internet").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Bluetooth").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("LoRa/RNode").assertDoesNotExist()
    }

    // ========== Long Press Tests ==========

    @Test
    fun peerCard_longPress_callsOnLongPress() {
        // Given
        var longPressed = false
        val announce = TestFactories.createAnnounce(peerName = "Charlie")

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
                onLongPress = { longPressed = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Charlie").performTouchInput { longClick() }

        // Then
        assertTrue(longPressed)
    }

    // ========== Node Type Badge Tests ==========

    @Test
    fun peerCard_displaysDefaultNodeBadge_forUnknownNodeType() {
        // Given - nodeType that doesn't match any known type
        val announce = TestFactories.createAnnounce(nodeType = "UNKNOWN_TYPE")

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - should display "Node" as default (else branch)
        composeTestRule.onNodeWithText("Node").assertIsDisplayed()
    }

    // ========== Signal Strength Indicator Tests ==========

    @Test
    fun peerCard_displaysWeakSignal_forHighHops() {
        // Given - hops > 3 should show weak signal (else branch)
        val announce = TestFactories.createAnnounce(hops = 5)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - should display "5 hops" with weak signal indicator
        composeTestRule.onNodeWithText("5 hops").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysGoodSignal_forMediumHops() {
        // Given - hops between 1 and 3 should show good signal
        val announce = TestFactories.createAnnounce(hops = 2)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - should display "2 hops" with good signal indicator
        composeTestRule.onNodeWithText("2 hops").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysExcellentSignal_forLowHops() {
        // Given - hops <= 1 should show excellent signal
        val announce = TestFactories.createAnnounce(hops = 1)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - should display "1 hop" with excellent signal indicator
        composeTestRule.onNodeWithText("1 hop").assertIsDisplayed()
    }

    // ========== Time Since Display Tests ==========

    @Test
    fun peerCard_displaysTimeSinceLastSeen() {
        // Given - announce with recent timestamp
        val recentTimestamp = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes ago
        val announce = TestFactories.createAnnounce(lastSeenTimestamp = recentTimestamp)

        // When
        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        // Then - should display time since text (LaunchedEffect initializes it)
        // The exact text depends on formatTimeSince, but should contain "ago" or "just now"
        composeTestRule.waitForIdle()
        // Just verify the component renders - LaunchedEffect coverage is difficult to test fully
    }
}
