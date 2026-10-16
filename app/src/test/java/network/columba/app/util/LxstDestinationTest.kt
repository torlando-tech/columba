package network.columba.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ground truth for [LxstDestination] computed with the Reticulum reference
 * implementation (RNS.Destination.hash(identity, "lxst", "telephony")) from
 * the offline aleph mirror, for identity hash "ab" * 16.
 */
class LxstDestinationTest {
    @Test
    fun `derives telephony destination hash matching RNS reference`() {
        val identityHash = "ab".repeat(16)
        // RNS: RNS.Destination.hash(bytes.fromhex('ab'*16), 'lxst', 'telephony').hex()
        assertEquals("8137b7aa68cce05c931a4ea1634ea1c7", LxstDestination.telephonyDestinationHashFromIdentity(identityHash))
    }

    @Test
    fun `normalizes uppercase and spaces`() {
        val identityHash = "ab".repeat(16)
        val messy = identityHash.uppercase().chunked(8).joinToString(" ")
        assertEquals("8137b7aa68cce05c931a4ea1634ea1c7", LxstDestination.telephonyDestinationHashFromIdentity(messy))
    }

    @Test
    fun `rejects invalid hash lengths and characters`() {
        assertNull(LxstDestination.normalizeHash(""))
        assertNull(LxstDestination.normalizeHash("abc123"))
        assertNull(LxstDestination.normalizeHash("zz".repeat(16)))
        assertNull(LxstDestination.normalizeHash("ab".repeat(15) + "abc"))
        assertNull(LxstDestination.telephonyDestinationHashFromIdentity("not-a-hash"))
    }

    @Test
    fun `all-zero identity derives deterministically`() {
        // RNS: RNS.Destination.hash(bytes.fromhex('0'*32), 'lxst', 'telephony').hex()
        assertEquals("ed81b75105c5421c2b18ec88f2b00bb1", LxstDestination.telephonyDestinationHashFromIdentity("0".repeat(32)))
        // RNS: RNS.Destination.hash(bytes.fromhex('ff'*16), 'lxst', 'telephony').hex()
        assertEquals("99b0e8ec4a87701ea0d714c2649bde03", LxstDestination.telephonyDestinationHashFromIdentity("f".repeat(32)))
    }
}
