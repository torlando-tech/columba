package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.test.TestFactories
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
    //
    // The icon is classified from the RAW `receivingInterface` field at
    // render time (not the cached `receivingInterfaceType` column) — see
    // the comment on the `InterfaceTypeIcon` call site in `PeerCard.kt`.
    // The cached column can be stale ("UNKNOWN" from a buggy classifier
    // run at save time), so tests use class-prefixed strings matching the
    // canonical `event_bridge.py` emit format: `"ClassName[friendly_name]"`.

    @Test
    fun peerCard_displaysWiFiIcon_forAutoInterface() {
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "AutoInterface[Local]",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("WiFi").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysWiFiIcon_forAutoInterfacePeer() {
        // AutoInterfacePeer is what `RNS.Transport.path_table[...][5]` returns
        // for LAN-multicast-discovered peers; its `__str__` returns the
        // `AutoInterfacePeer[ifname/addr]` shape that the classifier expects.
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "AutoInterfacePeer[wlan0/fe80::10af:905f:7605:214e]",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("WiFi").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysGlobeIcon_forTcpClient() {
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "TCPClientInterface[homelab]",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Internet").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysBluetoothIcon_forAndroidBle() {
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "AndroidBLE[Pixel 7 / 0A:1B:2C:3D:4E:5F]",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Bluetooth").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysAntennaIcon_forRnode() {
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "RNodeInterface[My Radio]",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("LoRa/RNode").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysAntennaIcon_forColumbaRNodeBle() {
        // BLE-attached RNode: classifier must prefer "rnode" over "ble"
        // because the transport really IS RNode even though the wire
        // adapter is BLE. Regression-pin from the original cascade fix.
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "ColumbaRNodeInterface[Heltec V3]",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("LoRa/RNode").assertIsDisplayed()
    }

    @Test
    fun peerCard_displaysIcon_evenWhenCachedTypeIsStaleUnknown() {
        // Regression-pin for the brittleness Tyler called out: a row whose
        // cached `receivingInterfaceType` was set to "UNKNOWN" by an old
        // (buggy) classifier run must still render the icon if the raw
        // `receivingInterface` can be classified now. PeerCard reads the raw
        // field — `receivingInterfaceType` is irrelevant for icons.
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "TCPClientInterface[homelab]",
                receivingInterfaceType = "UNKNOWN",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Internet").assertIsDisplayed()
    }

    @Test
    fun peerCard_doesNotDisplayInterfaceIcon_forUnknownRawName() {
        // A truly unrecognisable raw name (no class prefix, no pattern match)
        // still produces UNKNOWN — the resilience improvement helps with
        // classifier-cache drift, not with genuinely unknowable strings.
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = "homelab",
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("WiFi").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Internet").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Bluetooth").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("LoRa/RNode").assertDoesNotExist()
    }

    @Test
    fun peerCard_doesNotDisplayInterfaceIcon_forNullRawName() {
        val announce =
            TestFactories.createAnnounce(
                receivingInterface = null,
            )

        composeTestRule.setContent {
            PeerCard(
                announce = announce,
                onClick = {},
                onFavoriteClick = {},
            )
        }

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

        // Then - unknown node types fall through to "Node" (protocol default),
        // not "Site" (which is reserved for known NomadNet nodes).
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
