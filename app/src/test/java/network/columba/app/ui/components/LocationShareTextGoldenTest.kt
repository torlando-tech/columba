package network.columba.app.ui.components

import android.app.Application
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.data.model.EnrichedContact
import network.columba.app.test.TestFactories
import network.columba.app.viewmodel.ContactMarker
import network.columba.app.viewmodel.MarkerState
import network.columba.app.viewmodel.UiMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for location-sharing components: [QuickShareLocationBottomSheet],
 * [ShareLocationBottomSheet] (empty / populated / pre-selected) and
 * [ContactLocationBottomSheet] (fresh / stale / expired, with and without user location).
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class LocationShareTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `quick share location bottom sheet`() {
        composeRule.setContent {
            MaterialTheme {
                QuickShareLocationBottomSheet(
                    contactName = CONTACT_NAME,
                    onDismiss = {},
                    onStartSharing = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("quick_share_location_bottom_sheet", composeRule)
    }

    @Test
    fun `share location bottom sheet empty contacts`() {
        composeRule.setContent {
            MaterialTheme {
                ShareLocationBottomSheet(
                    contacts = emptyList(),
                    onDismiss = {},
                    onStartSharing = { _, _ -> },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("share_location_bottom_sheet_empty", composeRule)
    }

    @Test
    fun `share location bottom sheet with contacts`() {
        composeRule.setContent {
            MaterialTheme {
                ShareLocationBottomSheet(
                    contacts = CONTACTS,
                    onDismiss = {},
                    onStartSharing = { _, _ -> },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("share_location_bottom_sheet_contacts", composeRule)
    }

    @Test
    fun `share location bottom sheet with preselected contacts`() {
        composeRule.setContent {
            MaterialTheme {
                ShareLocationBottomSheet(
                    contacts = CONTACTS,
                    onDismiss = {},
                    onStartSharing = { _, _ -> },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    initialSelectedHashes = setOf("hash_alice"),
                )
            }
        }
        TextGoldens.assertGolden("share_location_bottom_sheet_preselected", composeRule)
    }

    @Test
    fun `contact location bottom sheet fresh with user location`() {
        composeRule.setContent {
            MaterialTheme {
                ContactLocationBottomSheet(
                    marker = createMarker(MarkerState.FRESH),
                    userLocation = createUserLocation(),
                    onDismiss = {},
                    onSendMessage = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("contact_location_bottom_sheet_fresh", composeRule)
    }

    @Test
    fun `contact location bottom sheet fresh without user location`() {
        composeRule.setContent {
            MaterialTheme {
                ContactLocationBottomSheet(
                    marker = createMarker(MarkerState.FRESH),
                    userLocation = null,
                    onDismiss = {},
                    onSendMessage = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("contact_location_bottom_sheet_no_user_location", composeRule)
    }

    @Test
    fun `contact location bottom sheet stale`() {
        composeRule.setContent {
            MaterialTheme {
                ContactLocationBottomSheet(
                    marker = createMarker(MarkerState.STALE),
                    userLocation = createUserLocation(),
                    onDismiss = {},
                    onSendMessage = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("contact_location_bottom_sheet_stale", composeRule)
    }

    @Test
    fun `contact location bottom sheet expired grace period`() {
        composeRule.setContent {
            MaterialTheme {
                ContactLocationBottomSheet(
                    marker = createMarker(MarkerState.EXPIRED_GRACE_PERIOD),
                    userLocation = createUserLocation(),
                    onDismiss = {},
                    onSendMessage = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("contact_location_bottom_sheet_expired", composeRule)
    }

    private fun createUserLocation(): Location =
        Location("golden").apply {
            latitude = USER_LAT
            longitude = USER_LNG
        }

    private fun createMarker(state: MarkerState): ContactMarker =
        ContactMarker(
            destinationHash = "aabbccddeeff0011",
            displayName = UiMessage.Raw(CONTACT_NAME),
            latitude = MARKER_LAT,
            longitude = MARKER_LNG,
            timestamp = System.currentTimeMillis() - MARKER_AGE_MILLIS,
            state = state,
            approximateRadius = 0,
        )

    private companion object {
        const val CONTACT_NAME = "Alice"
        const val USER_LAT = 37.7749
        const val USER_LNG = -122.4194
        const val MARKER_LAT = 37.7849
        const val MARKER_LNG = -122.4094
        /** Now minus 2.5 hours -> stably renders "Updated 2h ago". */
        const val MARKER_AGE_MILLIS = 150 * 60_000L

        /**
         * Pinned equal addedTimestamp: the sheet sorts by recency
         * (lastMessageTimestamp, then addedTimestamp desc) and the factory's
         * live-clock value made the Alice/Bob order non-deterministic.
         */
        const val FIXED_ADDED_TIMESTAMP = 1_700_000_000_000L

        val CONTACTS: List<EnrichedContact> =
            listOf(
                TestFactories
                    .createEnrichedContact(destinationHash = "hash_alice", displayName = "Alice")
                    .copy(addedTimestamp = FIXED_ADDED_TIMESTAMP),
                TestFactories
                    .createEnrichedContact(destinationHash = "hash_bob", displayName = "Bob")
                    .copy(addedTimestamp = FIXED_ADDED_TIMESTAMP),
            )
    }
}
