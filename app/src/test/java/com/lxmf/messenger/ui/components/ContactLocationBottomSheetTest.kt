package com.lxmf.messenger.ui.components

import android.app.Application
import android.location.Location
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.ContactMarker
import com.lxmf.messenger.viewmodel.MarkerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ContactLocationBottomSheet.
 *
 * Tests:
 * - Pure utility functions (bearingToDirection, formatDistanceAndDirection, formatUpdatedTime)
 * - UI display and interactions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class ContactLocationBottomSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== bearingToDirection Tests ==========

    @Test
    fun `bearingToDirection returns north for 0 degrees`() {
        assertEquals("north", bearingToDirection(0f))
    }

    @Test
    fun `bearingToDirection returns north for 360 degrees`() {
        // 360 is same as 0
        assertEquals("north", bearingToDirection(360f))
    }

    @Test
    fun `bearingToDirection returns north for small positive angles`() {
        assertEquals("north", bearingToDirection(10f))
        assertEquals("north", bearingToDirection(22f))
    }

    @Test
    fun `bearingToDirection returns north for angles near 360`() {
        assertEquals("north", bearingToDirection(350f))
        assertEquals("north", bearingToDirection(338f))
    }

    @Test
    fun `bearingToDirection returns northeast for angles 22_5 to 67_5`() {
        assertEquals("northeast", bearingToDirection(23f))
        assertEquals("northeast", bearingToDirection(45f))
        assertEquals("northeast", bearingToDirection(67f))
    }

    @Test
    fun `bearingToDirection returns east for angles 67_5 to 112_5`() {
        assertEquals("east", bearingToDirection(68f))
        assertEquals("east", bearingToDirection(90f))
        assertEquals("east", bearingToDirection(112f))
    }

    @Test
    fun `bearingToDirection returns southeast for angles 112_5 to 157_5`() {
        assertEquals("southeast", bearingToDirection(113f))
        assertEquals("southeast", bearingToDirection(135f))
        assertEquals("southeast", bearingToDirection(157f))
    }

    @Test
    fun `bearingToDirection returns south for angles 157_5 to 202_5`() {
        assertEquals("south", bearingToDirection(158f))
        assertEquals("south", bearingToDirection(180f))
        assertEquals("south", bearingToDirection(202f))
    }

    @Test
    fun `bearingToDirection returns southwest for angles 202_5 to 247_5`() {
        assertEquals("southwest", bearingToDirection(203f))
        assertEquals("southwest", bearingToDirection(225f))
        assertEquals("southwest", bearingToDirection(247f))
    }

    @Test
    fun `bearingToDirection returns west for angles 247_5 to 292_5`() {
        assertEquals("west", bearingToDirection(248f))
        assertEquals("west", bearingToDirection(270f))
        assertEquals("west", bearingToDirection(292f))
    }

    @Test
    fun `bearingToDirection returns northwest for angles 292_5 to 337_5`() {
        assertEquals("northwest", bearingToDirection(293f))
        assertEquals("northwest", bearingToDirection(315f))
        assertEquals("northwest", bearingToDirection(337f))
    }

    @Test
    fun `bearingToDirection handles negative angles`() {
        // -90 should normalize to 270 (west)
        assertEquals("west", bearingToDirection(-90f))
        // -180 should normalize to 180 (south)
        assertEquals("south", bearingToDirection(-180f))
    }

    @Test
    fun `bearingToDirection handles angles greater than 360`() {
        // 450 should normalize to 90 (east)
        assertEquals("east", bearingToDirection(450f))
        // 720 should normalize to 0 (north)
        assertEquals("north", bearingToDirection(720f))
    }

    // ========== formatDistanceAndDirection Tests ==========

    @Test
    fun `formatDistanceAndDirection returns unknown when userLocation is null`() {
        val result = formatDistanceAndDirection(null, 37.7749, -122.4194)
        assertEquals("Location unknown", result)
    }

    @Test
    fun `formatDistanceAndDirection formats meters for short distances`() {
        // San Francisco coordinates
        val userLocation = createTestLocation(37.7749, -122.4194)

        // Location very close (~500m away)
        val result = formatDistanceAndDirection(userLocation, 37.7799, -122.4194)

        // Should contain "m" for meters
        assertTrue("Result should contain meters: $result", result.contains("m"))
        // Should contain a direction
        assertTrue(
            "Result should contain a direction: $result",
            result.contains("north") ||
                result.contains("south") ||
                result.contains("east") ||
                result.contains("west"),
        )
    }

    @Test
    fun `formatDistanceAndDirection formats kilometers for long distances`() {
        val userLocation = createTestLocation(37.7749, -122.4194) // San Francisco

        // Location ~10km away
        val result = formatDistanceAndDirection(userLocation, 37.8749, -122.4194)

        // Should contain "km" for kilometers
        assertTrue("Result should contain kilometers: $result", result.contains("km"))
    }

    @Test
    fun `formatDistanceAndDirection includes direction`() {
        val userLocation = createTestLocation(37.7749, -122.4194)

        // Location to the east
        val result = formatDistanceAndDirection(userLocation, 37.7749, -122.3194)

        // Should contain a direction word
        val directions = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        assertTrue(
            "Result should contain a direction: $result",
            directions.any { result.contains(it) },
        )
    }

    // ========== formatUpdatedTime Tests ==========

    @Test
    fun `formatUpdatedTime returns just now for recent timestamps`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 5_000) // 5 seconds ago

        assertEquals("Updated just now", result)
    }

    @Test
    fun `formatUpdatedTime returns seconds for timestamps under 1 minute`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 30_000) // 30 seconds ago

        assertTrue("Result should contain seconds: $result", result.contains("s ago"))
        assertTrue("Result should start with Updated: $result", result.startsWith("Updated"))
    }

    @Test
    fun `formatUpdatedTime returns minutes for timestamps under 1 hour`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 5 * 60_000) // 5 minutes ago

        assertTrue("Result should contain minutes: $result", result.contains("m ago"))
    }

    @Test
    fun `formatUpdatedTime returns hours for timestamps under 1 day`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 3 * 3600_000) // 3 hours ago

        assertTrue("Result should contain hours: $result", result.contains("h ago"))
    }

    @Test
    fun `formatUpdatedTime returns days for old timestamps`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 2L * 86400_000L) // 2 days ago

        assertTrue("Result should contain days: $result", result.contains("d ago"))
    }

    @Test
    fun `formatUpdatedTime handles very old timestamps`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 30L * 86400_000L) // 30 days ago

        assertTrue("Result should contain days: $result", result.contains("d ago"))
    }

    // ========== ContactLocationBottomSheet UI Tests ==========

    @Test
    fun `contactLocationBottomSheet displays contact name`() {
        val marker = createTestMarker("Alice", MarkerState.FRESH)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet displays directions button`() {
        val marker = createTestMarker("Bob", MarkerState.FRESH)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Directions").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet displays message button`() {
        val marker = createTestMarker("Carol", MarkerState.FRESH)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Message").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet message button invokes callback`() {
        var messageCalled = false
        val marker = createTestMarker("Dave", MarkerState.FRESH)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = { messageCalled = true },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Message").performClick()

        assertTrue(messageCalled)
    }

    @Test
    fun `contactLocationBottomSheet displays location unknown when no user location`() {
        val marker = createTestMarker("Eve", MarkerState.FRESH)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Location unknown").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet displays updated time`() {
        val marker = createTestMarker("Frank", MarkerState.FRESH, timestamp = System.currentTimeMillis())

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Updated just now").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet stale marker shows stale badge`() {
        val marker = createTestMarker("Grace", MarkerState.STALE)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Stale").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet expired marker shows last known badge`() {
        val marker = createTestMarker("Henry", MarkerState.EXPIRED_GRACE_PERIOD)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Last known").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet fresh marker does not show remove button`() {
        val marker = createTestMarker("Iris", MarkerState.FRESH)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                onRemoveMarker = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Remove from map").assertDoesNotExist()
    }

    @Test
    fun `contactLocationBottomSheet stale marker shows remove button`() {
        val marker = createTestMarker("Jack", MarkerState.STALE)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                onRemoveMarker = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Remove from map").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet expired marker shows remove button`() {
        val marker = createTestMarker("Kate", MarkerState.EXPIRED_GRACE_PERIOD)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                onRemoveMarker = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Remove from map").assertIsDisplayed()
    }

    @Test
    fun `contactLocationBottomSheet remove button invokes callback`() {
        var removeCalled = false
        val marker = createTestMarker("Leo", MarkerState.STALE)

        composeTestRule.setContent {
            ContactLocationBottomSheet(
                marker = marker,
                userLocation = null,
                onDismiss = {},
                onSendMessage = {},
                onRemoveMarker = { removeCalled = true },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Remove from map").performClick()

        assertTrue(removeCalled)
    }

    // ========== Helper Functions ==========

    private fun createTestLocation(
        lat: Double,
        lng: Double,
    ): Location =
        Location("test").apply {
            latitude = lat
            longitude = lng
        }

    private fun createTestMarker(
        name: String,
        state: MarkerState,
        timestamp: Long = System.currentTimeMillis(),
    ): ContactMarker =
        ContactMarker(
            destinationHash = "hash_$name",
            displayName = name,
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = timestamp,
            state = state,
            approximateRadius = 0,
        )
}
