package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.data.model.InterfaceType
import network.columba.app.test.TestFactories
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for the announce-stream peer card and its badge/icon satellites.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens]. The
 * relative-time line ("2 hours ago", "3 days ago") is rendered against the
 * live wall clock, so the announce timestamps are computed relative to
 * `System.currentTimeMillis()` at test time with half-step margins that keep
 * the formatted bucket stable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `peer card node announce`() {
        composeRule.setContent {
            MaterialTheme {
                PeerCard(
                    announce = TestFactories.createAnnounce(
                        destinationHash = TestFactories.TEST_DEST_HASH,
                        peerName = "Alice",
                        hops = 2,
                        nodeType = "NODE",
                        lastSeenTimestamp = nodeLastSeenMillis(),
                        isFavorite = false,
                        receivingInterface = "RNODE",
                        recentInterfaceTypes = setOf(InterfaceType.AUTO),
                    ),
                )
            }
        }
        TextGoldens.assertGolden("peer_card_node", composeRule)
    }

    @Test
    fun `peer card peer announce`() {
        composeRule.setContent {
            MaterialTheme {
                PeerCard(
                    announce = TestFactories.createAnnounce(
                        destinationHash = TestFactories.TEST_DEST_HASH_2,
                        peerName = "Bob",
                        hops = 0,
                        nodeType = "PEER",
                        lastSeenTimestamp = peerLastSeenMillis(),
                        isFavorite = true,
                    ),
                )
            }
        }
        TextGoldens.assertGolden("peer_card_peer", composeRule)
    }

    @Test
    fun `signal strength indicator zero hops`() {
        composeRule.setContent {
            MaterialTheme { SignalStrengthIndicator(hops = 0) }
        }
        TextGoldens.assertGolden("signal_strength_indicator_hops_zero", composeRule)
    }

    @Test
    fun `signal strength indicator three hops`() {
        composeRule.setContent {
            MaterialTheme { SignalStrengthIndicator(hops = 3) }
        }
        TextGoldens.assertGolden("signal_strength_indicator_hops_three", composeRule)
    }

    @Test
    fun `interface type icon rnode`() {
        composeRule.setContent {
            MaterialTheme { InterfaceTypeIcon(interfaceType = "RNODE") }
        }
        TextGoldens.assertGolden("interface_type_icon_rnode", composeRule)
    }

    @Test
    fun `interface type icon unknown renders nothing`() {
        composeRule.setContent {
            MaterialTheme { InterfaceTypeIcon(interfaceType = null) }
        }
        TextGoldens.assertGolden("interface_type_icon_unknown", composeRule)
    }

    @Test
    fun `node type badge node`() {
        composeRule.setContent {
            MaterialTheme { NodeTypeBadge(nodeType = "NODE") }
        }
        TextGoldens.assertGolden("node_type_badge_node", composeRule)
    }

    @Test
    fun `node type badge peer`() {
        composeRule.setContent {
            MaterialTheme { NodeTypeBadge(nodeType = "PEER") }
        }
        TextGoldens.assertGolden("node_type_badge_peer", composeRule)
    }

    @Test
    fun `node type badge propagation node`() {
        composeRule.setContent {
            MaterialTheme { NodeTypeBadge(nodeType = "PROPAGATION_NODE") }
        }
        TextGoldens.assertGolden("node_type_badge_propagation_node", composeRule)
    }

    @Test
    fun `node type badge phone`() {
        composeRule.setContent {
            MaterialTheme { NodeTypeBadge(nodeType = "PHONE") }
        }
        TextGoldens.assertGolden("node_type_badge_phone", composeRule)
    }

    @Test
    fun `node type badge other`() {
        composeRule.setContent {
            MaterialTheme { NodeTypeBadge(nodeType = "SOMETHING_NEW") }
        }
        TextGoldens.assertGolden("node_type_badge_other", composeRule)
    }

    @Test
    fun `other badge`() {
        composeRule.setContent {
            MaterialTheme { OtherBadge() }
        }
        TextGoldens.assertGolden("other_badge", composeRule)
    }

    private companion object {
        /** Now minus 2.5 hours -> stably renders "2 hours ago". */
        private const val NODE_AGE_MILLIS = 2 * 60 * 60 * 1000L + 30 * 60 * 1000L

        /** Now minus 3.5 days -> stably renders "3 days ago". */
        private const val PEER_AGE_MILLIS = 3 * 24 * 60 * 60 * 1000L + 12 * 60 * 60 * 1000L

        fun nodeLastSeenMillis(): Long = System.currentTimeMillis() - NODE_AGE_MILLIS

        fun peerLastSeenMillis(): Long = System.currentTimeMillis() - PEER_AGE_MILLIS
    }
}
