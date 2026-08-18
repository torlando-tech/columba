package network.columba.app.rns.host.persistence

import network.columba.app.rns.api.model.DeliveryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerActivityPolicyTest {
    @Test
    fun `only delivered status qualifies as returned proof`() {
        assertTrue(PeerActivityPolicy.isVerifiedDeliveryProof(DeliveryStatus.DELIVERED))
        DeliveryStatus.entries
            .filterNot { it == DeliveryStatus.DELIVERED }
            .forEach { assertFalse("$it must not count as peer activity", PeerActivityPolicy.isVerifiedDeliveryProof(it)) }
    }
}
