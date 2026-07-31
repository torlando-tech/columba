package network.columba.app.rns.host.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerActivityPolicyTest {
    @Test
    fun `only delivered status qualifies as returned proof`() {
        assertTrue(PeerActivityPolicy.isVerifiedDeliveryProof("delivered"))
        assertTrue(PeerActivityPolicy.isVerifiedDeliveryProof("DELIVERED"))
        listOf("pending", "sent", "failed", "propagated", "retrying_propagated", "")
            .forEach { assertFalse("$it must not count as peer activity", PeerActivityPolicy.isVerifiedDeliveryProof(it)) }
    }
}
