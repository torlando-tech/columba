package network.columba.app.rns.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RNode companion presence events are allowed to be repeated (BLE appeared +
 * BT connected). The service must not re-arm an interface that is already
 * online, but must still try when the configured interface never registered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReticulumServiceRNodePresenceTest {
    private val service = ReticulumService()

    @Test
    fun offlineRNodeTriggersReconnect() {
        val interfaces =
            listOf(
                mapOf("type" to "ColumbaRNodeInterface", "name" to "RNode", "online" to false),
            )
        assertTrue(service.shouldReconnectRNode(interfaces))
    }

    @Test
    fun onlineRNodeSkipsReconnect() {
        val interfaces =
            listOf(
                mapOf("type" to "ColumbaRNodeInterface", "name" to "RNode", "online" to true),
            )
        assertFalse(service.shouldReconnectRNode(interfaces))
    }

    @Test
    fun absentRNodeStillTriesBecauseFailedStartsHaveNoLiveRow() {
        assertTrue(service.shouldReconnectRNode(emptyList<Any>()))
        assertTrue(
            service.shouldReconnectRNode(
                listOf(mapOf("type" to "AutoInterface", "name" to "Auto", "online" to true)),
            ),
        )
    }

    @Test
    fun oneOfflineRNodeAmongOnlineOnesTriggers() {
        val interfaces =
            listOf(
                mapOf("type" to "ColumbaRNodeInterface", "name" to "BLE", "online" to true),
                mapOf("type" to "RNodeInterface", "name" to "USB", "online" to false),
            )
        assertTrue(service.shouldReconnectRNode(interfaces))
    }
}
