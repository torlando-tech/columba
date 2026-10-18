package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.data.model.InterfaceType
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.NodeType
import network.columba.app.viewmodel.DiscoveredInterfacesSortMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for small chrome components: chips, toggles, banners, badges.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmallChromeTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `sharing status chip singular`() {
        composeRule.setContent {
            MaterialTheme { SharingStatusChip(sharingWithCount = 1, onStopAllClick = {}) }
        }
        TextGoldens.assertGolden("sharing_status_chip_one", composeRule)
    }

    @Test
    fun `sharing status chip plural`() {
        composeRule.setContent {
            MaterialTheme { SharingStatusChip(sharingWithCount = 3, onStopAllClick = {}) }
        }
        TextGoldens.assertGolden("sharing_status_chip_plural", composeRule)
    }

    @Test
    fun `star toggle button unstarred`() {
        composeRule.setContent {
            MaterialTheme { StarToggleButton(isStarred = false, onClick = {}) }
        }
        TextGoldens.assertGolden("star_toggle_button_unstarred", composeRule)
    }

    @Test
    fun `star toggle button starred`() {
        composeRule.setContent {
            MaterialTheme { StarToggleButton(isStarred = true, onClick = {}) }
        }
        TextGoldens.assertGolden("star_toggle_button_starred", composeRule)
    }

    @Test
    fun `sort mode selector quality selected with location`() {
        composeRule.setContent {
            MaterialTheme {
                SortModeSelector(
                    currentMode = DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY,
                    hasUserLocation = true,
                    onModeSelected = {},
                )
            }
        }
        TextGoldens.assertGolden("sort_mode_selector_quality", composeRule)
    }

    @Test
    fun `sort mode selector proximity selected`() {
        composeRule.setContent {
            MaterialTheme {
                SortModeSelector(
                    currentMode = DiscoveredInterfacesSortMode.PROXIMITY,
                    hasUserLocation = true,
                    onModeSelected = {},
                )
            }
        }
        TextGoldens.assertGolden("sort_mode_selector_proximity", composeRule)
    }

    @Test
    fun `sort mode selector proximity disabled`() {
        composeRule.setContent {
            MaterialTheme {
                SortModeSelector(
                    currentMode = DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY,
                    hasUserLocation = false,
                    onModeSelected = {},
                )
            }
        }
        TextGoldens.assertGolden("sort_mode_selector_no_location", composeRule)
    }

    @Test
    fun `rnode battery indicator high`() {
        composeRule.setContent {
            MaterialTheme { RNodeBatteryIndicator(percent = 85) }
        }
        TextGoldens.assertGolden("rnode_battery_indicator_high", composeRule)
    }

    @Test
    fun `rnode battery indicator low`() {
        composeRule.setContent {
            MaterialTheme { RNodeBatteryIndicator(percent = 12) }
        }
        TextGoldens.assertGolden("rnode_battery_indicator_low", composeRule)
    }

    @Test
    fun `service restart banner`() {
        composeRule.setContent {
            MaterialTheme { ServiceRestartBanner() }
        }
        TextGoldens.assertGolden("service_restart_banner", composeRule)
    }

    @Test
    fun `offline mode banner shutdown`() {
        composeRule.setContent {
            MaterialTheme {
                OfflineModeBanner(
                    networkStatus = NetworkStatus.SHUTDOWN,
                    isRestarting = false,
                    onReconnect = {},
                )
            }
        }
        TextGoldens.assertGolden("offline_mode_banner_shutdown", composeRule)
    }

    @Test
    fun `offline mode banner error`() {
        composeRule.setContent {
            MaterialTheme {
                OfflineModeBanner(
                    networkStatus = NetworkStatus.ERROR("radio init failed"),
                    isRestarting = false,
                    onReconnect = {},
                )
            }
        }
        TextGoldens.assertGolden("offline_mode_banner_error", composeRule)
    }

    @Test
    fun `offline mode banner reconnecting`() {
        composeRule.setContent {
            MaterialTheme {
                OfflineModeBanner(
                    networkStatus = NetworkStatus.SHUTDOWN,
                    isRestarting = true,
                    onReconnect = {},
                )
            }
        }
        TextGoldens.assertGolden("offline_mode_banner_reconnecting", composeRule)
    }

    @Test
    fun `searchable top app bar idle`() {
        composeRule.setContent {
            MaterialTheme {
                SearchableTopAppBar(
                    title = "Contacts",
                    subtitle = "12 peers",
                    isSearching = false,
                    searchQuery = "",
                    onSearchQueryChange = {},
                    onSearchToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("searchable_top_app_bar_idle", composeRule)
    }

    @Test
    fun `searchable top app bar searching`() {
        composeRule.setContent {
            MaterialTheme {
                SearchableTopAppBar(
                    title = "Contacts",
                    subtitle = null,
                    isSearching = true,
                    searchQuery = "ali",
                    onSearchQueryChange = {},
                    onSearchToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("searchable_top_app_bar_searching", composeRule)
    }

    @Test
    fun `announce filter chips expanded unfiltered`() {
        composeRule.setContent {
            MaterialTheme {
                AnnounceFilterChips(
                    selectedNodeTypes = setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
                    showAudio = true,
                    selectedInterfaceTypes = emptySet(),
                    onNodeTypesChange = {},
                    onShowAudioChange = {},
                    onInterfaceTypesChange = {},
                )
            }
        }
        TextGoldens.assertGolden("announce_filter_chips_expanded", composeRule)
    }

    @Test
    fun `announce filter chips expanded filtered`() {
        composeRule.setContent {
            MaterialTheme {
                AnnounceFilterChips(
                    selectedNodeTypes = setOf(NodeType.NODE),
                    showAudio = false,
                    selectedInterfaceTypes = setOf(InterfaceType.RNODE),
                    onNodeTypesChange = {},
                    onShowAudioChange = {},
                    onInterfaceTypesChange = {},
                )
            }
        }
        TextGoldens.assertGolden("announce_filter_chips_expanded_filtered", composeRule)
    }

    @Test
    fun `announce filter chips collapsed pill`() {
        composeRule.setContent {
            MaterialTheme {
                AnnounceFilterChips(
                    selectedNodeTypes = setOf(NodeType.PEER),
                    showAudio = false,
                    selectedInterfaceTypes = setOf(InterfaceType.TCP_CLIENT),
                    onNodeTypesChange = {},
                    onShowAudioChange = {},
                    onInterfaceTypesChange = {},
                    expanded = false,
                )
            }
        }
        TextGoldens.assertGolden("announce_filter_chips_collapsed", composeRule)
    }
}
